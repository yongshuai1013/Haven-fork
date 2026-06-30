package sh.haven.app.usb

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.QemuManager
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.usb.UsbBroker
import sh.haven.core.usb.UsbDeviceInfo
import sh.haven.core.usb.UsbIpServer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the user-facing "Open USB drive" flow (#287): export the
 * attached USB mass-storage device over the shipped [UsbIpServer], boot the
 * [QemuManager] appliance VM (which imports + mounts it), and surface the
 * drive's files through an ordinary loopback SSH/SFTP [ConnectionProfile] — so
 * the existing file browser, terminal, and MCP file verbs all work unchanged.
 *
 * The VM boot is slow (TCG, no KVM unrooted), so [open] does the fast checks
 * (pref gate, USB permission, mass-storage class) synchronously and then boots
 * in the background; callers poll [status]. One drive at a time.
 */
@Singleton
class UsbDriveVmManager @Inject constructor(
    private val qemuManager: QemuManager,
    private val usbIpServer: UsbIpServer,
    private val usbBroker: UsbBroker,
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val preferences: UserPreferencesRepository,
) {
    enum class Phase { IDLE, OPENING, READY, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        val productName: String? = null,
        val busid: String? = null,
        val profileId: String? = null,
        val keyId: String? = null,
        val mounts: List<String> = emptyList(),
        val sshPort: Int = 0,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class UsbVmException(message: String) : Exception(message)

    /** USB mass-storage devices attached to the phone (the "open in a VM" candidates). */
    fun massStorageDevices(): List<UsbDeviceInfo> =
        usbBroker.listDevices().filter { it.interfaces.any { i -> i.interfaceClass == USB_CLASS_MASS_STORAGE } }

    /**
     * Validate + open the device + start the VM boot in the background. Returns
     * the resolved deviceName immediately once the (fast) checks pass; the VM is
     * still booting — poll [status] until phase READY (profileId set) or ERROR.
     */
    suspend fun open(deviceName: String?): String {
        if (!preferences.usbVmEnabled.first()) {
            throw UsbVmException(
                "Opening USB drives in a VM is disabled. Enable Settings → " +
                    "\"Open USB drives in a VM\" (or set usb_vm_enabled) first.",
            )
        }
        if (_status.value.phase == Phase.OPENING || _status.value.phase == Phase.READY) {
            throw UsbVmException("A USB drive is already open in a VM; close it first.")
        }
        val target = resolveDrive(deviceName)
        val info = try {
            usbBroker.openDevice(target)
        } catch (e: Exception) {
            throw UsbVmException("USB open failed: ${e.message}")
        }
        if (info.interfaces.none { it.interfaceClass == USB_CLASS_MASS_STORAGE }) {
            throw UsbVmException("$target is not a USB mass-storage device (class 8). Use usb_attach_to_guest for HID/serial devices.")
        }
        val busid = busidOf(target)
        _status.value = Status(Phase.OPENING, target, info.productName, busid)
        scope.launch { boot(target, info, busid) }
        return target
    }

    private suspend fun boot(deviceName: String, info: UsbDeviceInfo, busid: String) {
        var keyId: String? = null
        var profileId: String? = null
        try {
            usbIpServer.start(deviceName) // export on :3240 (binds all interfaces)
            val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "Haven USB drive")
            val keyEntity = SshKey(
                label = "USB drive VM (ephemeral)",
                keyType = key.type.sshName,
                privateKeyBytes = key.privateKeyBytes,
                publicKeyOpenSsh = key.publicKeyOpenSsh,
                fingerprintSha256 = key.fingerprintSha256,
            )
            sshKeyRepository.save(keyEntity); keyId = keyEntity.id

            val session = qemuManager.openDrive(busid, key.publicKeyOpenSsh)

            val profile = ConnectionProfile(
                label = driveLabel(info),
                host = "127.0.0.1",
                port = session.sshPort,
                username = "root",
                authType = ConnectionProfile.AuthType.KEY,
                keyId = keyId,
                connectionType = "SSH",
            )
            connectionRepository.save(profile); profileId = profile.id

            _status.value = Status(
                phase = Phase.READY, deviceName = deviceName, productName = info.productName,
                busid = busid, profileId = profile.id, keyId = keyId,
                mounts = session.mounts, sshPort = session.sshPort,
            )
            Log.i(TAG, "USB drive VM ready: $deviceName → profile ${profile.id}, mounts ${session.mounts}")
        } catch (e: Exception) {
            Log.w(TAG, "USB drive VM boot failed: ${e.message}")
            runCatching { qemuManager.closeDrive() }
            runCatching { usbIpServer.stop() }
            profileId?.let { id -> runCatching { connectionRepository.delete(id) } }
            keyId?.let { id -> runCatching { sshKeyRepository.delete(id) } }
            _status.value = Status(Phase.ERROR, deviceName, info.productName, busid, error = e.message)
        }
    }

    /** Tear down: power off the VM, stop the export, drop the transient profile + key. */
    suspend fun close() {
        val s = _status.value
        if (s.phase == Phase.IDLE) return
        runCatching { qemuManager.closeDrive() }
        runCatching { usbIpServer.stop() }
        s.profileId?.let { runCatching { connectionRepository.delete(it) } }
        s.keyId?.let { runCatching { sshKeyRepository.delete(it) } }
        _status.value = Status(Phase.IDLE)
    }

    private fun resolveDrive(deviceName: String?): String {
        if (!deviceName.isNullOrBlank()) return deviceName
        val drives = massStorageDevices()
        return when (drives.size) {
            0 -> throw UsbVmException("No USB mass-storage drive attached.")
            1 -> drives.single().deviceName
            else -> throw UsbVmException(
                "Multiple USB drives attached — pass deviceName. Found: ${drives.joinToString { it.deviceName }}",
            )
        }
    }

    private fun driveLabel(info: UsbDeviceInfo): String =
        info.productName?.takeIf { it.isNotBlank() }?.let { "USB: $it" } ?: "USB drive"

    // /dev/bus/usb/BBB/DDD → "B-D" (matches usbip + start_usbip_export).
    private fun busidOf(deviceName: String): String {
        val parts = deviceName.trimEnd('/').split('/')
        val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
        val dev = parts.lastOrNull()?.toIntOrNull() ?: 1
        return "$bus-$dev"
    }

    companion object {
        private const val TAG = "UsbDriveVmManager"
        const val USB_CLASS_MASS_STORAGE = 8
    }
}
