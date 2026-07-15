package sh.haven.core.usbserial

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UsbSerialSessionManager"

/**
 * Manages USB-serial terminal sessions, mirroring [sh.haven.core.btserial]:
 * register → connect (open the USB serial port) → create terminal →
 * detach/reattach/remove. Only the connect step touches hardware and runs on IO.
 *
 * A device is identified by its `vendorId:productId` hex key (from the saved
 * profile); at connect time we resolve it against the currently-attached
 * devices, since USB device names/paths are not stable across re-plugs.
 */
@Singleton
class UsbSerialSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        /** `vendorId:productId` hex, e.g. `1a86:7523`. */
        val deviceKey: String = "",
        val params: UsbSerialParams = UsbSerialParams(),
        val deviceName: String? = null,
        /** Open link, set by [connectSession], consumed by [createTerminalSession]. */
        val link: UsbSerialLink? = null,
        val session: UsbSerialSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Built on first real connect only (tests override openLink before that, so
    // no USB is touched under test).
    private val connector by lazy { AndroidUsbSerialConnector(usbManager) }

    /** Resolve a saved `vid:pid` key to a currently-attached device. Test seam. */
    internal var resolveDevice: (deviceKey: String) -> UsbDevice? = ::defaultResolve

    /** Open the serial link for a resolved device. Test seam (real path = USB IO). */
    internal var openLink: (device: UsbDevice, params: UsbSerialParams) -> UsbSerialLink =
        { device, params -> connector.connect(device, params) }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbserial-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED || it.status == SessionState.Status.CONNECTING
        }

    /** Register a new session (status CONNECTING). Returns the generated sessionId. */
    fun registerSession(
        profileId: String,
        label: String,
        deviceKey: String,
        params: UsbSerialParams = UsbSerialParams(),
    ): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                deviceKey = deviceKey,
                params = params,
            ))
        }
        return sessionId
    }

    /**
     * Open the USB serial link (blocking, on IO). Sets status CONNECTED and
     * stashes the link for [createTerminalSession] to wrap. Throws on failure
     * (status → ERROR). Mirrors BtSerialSessionManager.connectSession.
     */
    suspend fun connectSession(sessionId: String) {
        val state = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        if (state.link != null || state.session != null) return
        if (state.deviceKey.isEmpty()) throw IllegalStateException("No device key for $sessionId")

        val link = try {
            withContext(Dispatchers.IO) {
                val device = resolveDevice(state.deviceKey)
                    ?: throw IllegalStateException("USB device ${state.deviceKey} is not attached")
                openLink(device, state.params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB serial connect failed for ${state.label}: ${e.message}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            throw e
        }
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                deviceName = link.displayName,
                link = link,
            ))
        }
    }

    /**
     * Wrap the already-open link ([connectSession] first) in a terminal session,
     * wiring device output to [onDataReceived]. Non-suspend so the terminal
     * syncSessions loop can call it. Idempotent.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): UsbSerialSession? {
        val state = _sessions.value[sessionId] ?: return null
        state.session?.let { return it }
        val link = state.link ?: return null

        val session = UsbSerialSession(
            sessionId = sessionId,
            link = link,
            onDataReceived = onDataReceived,
            onDisconnected = { updateStatus(it, SessionState.Status.DISCONNECTED) },
        )
        session.start()

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = session))
        }
        return session
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val s = _sessions.value[sessionId] ?: return false
        return s.status == SessionState.Status.CONNECTED && s.session == null && s.link != null
    }

    /** Detach the terminal without dropping the link. */
    fun detachTerminalSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        state.session?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = null))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    /** Send [text] as UTF-8 to the session (sendInput contract, #366). */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]?.session
            ?: throw IllegalStateException("No live USB-serial session: $sessionId")
        session.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    // Close the terminal session if one exists (it owns the link), else the bare
    // link opened by connectSession before a terminal was created.
    private fun tearDown(state: SessionState) {
        runCatching { state.session?.close() ?: state.link?.close() }
            .onFailure { Log.e(TAG, "tearDown failed for ${state.sessionId}", it) }
    }

    fun removeSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute { tearDown(state) }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute { toRemove.forEach { tearDown(it) } }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute { snapshot.forEach { tearDown(it) } }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    private fun defaultResolve(deviceKey: String): UsbDevice? {
        val (vid, pid) = parseKey(deviceKey) ?: return null
        return usbManager.deviceList.values.firstOrNull {
            it.vendorId == vid && it.productId == pid
        }
    }

    companion object {
        /** `1a86:7523` → (0x1a86, 0x7523); null if malformed. */
        fun parseKey(deviceKey: String): Pair<Int, Int>? {
            val parts = deviceKey.split(":")
            if (parts.size < 2) return null
            val vid = parts[0].toIntOrNull(16) ?: return null
            val pid = parts[1].toIntOrNull(16) ?: return null
            return vid to pid
        }

        /** Build the stable key stored in the profile. */
        fun deviceKey(vendorId: Int, productId: Int): String =
            "%04x:%04x".format(vendorId, productId)
    }
}
