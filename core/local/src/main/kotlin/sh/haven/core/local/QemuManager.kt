package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.haven.core.local.proot.PackageFamily
import sh.haven.core.local.proot.PackageOps
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Runs one on-device QEMU VM that gives a phone-attached USB drive a **real
 * Linux kernel** — so mass-storage / block / ext4 / GPT, which proot has no
 * kernel for, work (#287). The VM is the USB/IP *client* the proot guest can't
 * be (Android ships no vhci-hcd): it imports the device the shipped
 * [sh.haven.core.usb.UsbIpServer] exports, mounts it, and serves the files over
 * sshd. Haven points an ordinary loopback SSH/SFTP profile at it — no new
 * transport, no new UI; the drive's files appear in the normal file browser.
 *
 * Delivery = **serial-console auto-drive** (not an Alpine apkovl, which didn't
 * auto-apply from a separate disk reliably): qemu runs `-serial stdio`, so the
 * proot launcher Process's streams *are* the VM serial. We wait for `login:`,
 * send `root`, then one setup line (dhcp → apk add usbip+openssh → inject the
 * caller's pubkey → sshd → `usbip attach` → mount -o ro), then poll the
 * forwarded sshd port. This mirrors exactly what was proven by hand in the
 * #287 spike.
 *
 * Unrooted Android = no /dev/kvm, so qemu runs TCG (slow but correct) — fine
 * for pulling files off a drive. Isochronous USB still can't pass (the broker
 * has no isochronous API). One drive at a time.
 *
 * Orchestration note: the export (UsbIpServer) lives in `core:usb`, which this
 * module doesn't depend on, so the app layer starts the export and passes us
 * the `busid`; we only own the VM.
 */
@Singleton
class QemuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class DriveSession(
        val busid: String,
        val sshPort: Int,
        val mounts: List<String>,
        val state: State,
        val error: String? = null,
    )

    private val _session = MutableStateFlow<DriveSession?>(null)
    val session: StateFlow<DriveSession?> = _session.asStateFlow()

    @Volatile private var vmProcess: Process? = null
    @Volatile private var serialReader: Thread? = null
    private val serial = StringBuilder()
    private val serialLock = Any()

    val isRunning: Boolean get() = vmProcess?.isAlive == true

    /**
     * Boot the appliance VM, import [busid] over USB/IP, mount it read-only, and
     * bring up sshd authorised for [authorizedPubKey]. Returns the forwarded
     * loopback ssh port + the mounted paths. Suspends through the (TCG) boot +
     * one-time package install; throws on failure (caller stops the export).
     */
    suspend fun openDrive(
        busid: String,
        authorizedPubKey: String,
        onStage: (String) -> Unit = {},
    ): DriveSession = withContext(Dispatchers.IO) {
        check(vmProcess?.isAlive != true) { "A USB-drive VM is already running; close it first." }
        _session.value = DriveSession(busid, 0, emptyList(), State.STARTING)
        try {
            ensureQemu(onStage)
            val disk = ensureProvisionedAppliance(onStage)
            val port = freeLoopbackPort()

            onStage("Starting the virtual machine…")
            startVm(qemuRuntimeCommand(disk, port))

            // 1) login — nudge getty until the prompt shows, then send root. The
            // appliance disk boots the already-installed system (no apk, no
            // re-download), so this is just a kernel boot — still TCG-slow and
            // load-dependent, so report live elapsed + a milestone.
            onStage("Booting the USB helper…")
            val bootOk = awaitSerial("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                val hint = synchronized(serialLock) {
                    when {
                        serial.contains("Welcome to Alpine") || serial.contains("Alpine Linux") -> "kernel up, reaching login"
                        serial.contains("SeaBIOS") || serial.contains("ISOLINUX") -> "loading the kernel"
                        else -> "starting"
                    }
                }
                onStage("Booting Linux — $hint (${sec}s)…")
            }
            if (!bootOk) fail("VM didn't reach a login prompt within ${BOOT_TIMEOUT_MS / 1000}s — the emulated boot is slow and varies with phone load; try again, ideally with less else running.")
            send("root\n")
            Thread.sleep(1500)
            // 2) one-shot setup; wait for the done marker.
            onStage("Setting up the VM and mounting your drive…")
            send(runtimeSetupScript(busid, authorizedPubKey) + "\n")
            val setupOk = awaitSerial("HAVEN_SETUP_DONE", SETUP_TIMEOUT_MS) { sec ->
                onStage("Setting up the VM (installing drivers, mounting; ${sec}s)…")
            }
            if (!setupOk) fail("VM setup (attach/mount/sshd) didn't finish within ${SETUP_TIMEOUT_MS / 1000}s")
            // 3) confirm sshd actually answers on the forward.
            onStage("Almost ready — connecting…")
            if (!awaitSshBanner(port, SSH_TIMEOUT_MS)) fail("sshd never answered on 127.0.0.1:$port")

            val mounts = parseMounts()
            DriveSession(busid, port, mounts, State.RUNNING).also { _session.value = it }
        } catch (e: Exception) {
            Log.w(TAG, "openDrive failed: ${e.message}")
            _session.value = DriveSession(busid, 0, emptyList(), State.ERROR, e.message)
            stopVm()
            throw e
        }
    }

    /** Power off + reap the VM. Idempotent. The caller stops the USB/IP export. */
    fun closeDrive() {
        // Best-effort graceful poweroff first so mounts flush.
        runCatching { send("\npoweroff\n") }
        runCatching { vmProcess?.waitFor(4, TimeUnit.SECONDS) }
        stopVm()
        _session.value = null
    }

    // --- internals -------------------------------------------------------

    private fun stopVm() {
        val proc = vmProcess ?: return
        vmProcess = null
        val launcher = pidOf(proc) ?: -1
        val tracees = if (launcher > 0) descendantPids(launcher) else emptyList()
        proc.destroy()
        if (runCatching { !proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(true)) proc.destroyForcibly()
        // proot's --kill-on-exit doesn't reap the ptrace tracee when the launcher
        // is force-destroyed; signal the snapshotted qemu tree directly.
        if (tracees.isNotEmpty()) {
            tracees.forEach { runCatching { android.os.Process.sendSignal(it, 15) } }
            runCatching { Thread.sleep(300) }
            tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
            Log.d(TAG, "reaped ${tracees.size} qemu tracee(s): $tracees")
        }
        serialReader?.interrupt(); serialReader = null
    }

    // Provisioning boot: ISO + the blank appliance disk (virtio = /dev/vda).
    // No hostfwd — provisioning is serial-only (install Alpine to /dev/vda).
    private fun qemuProvisionCommand(isoGuestPath: String, diskGuestPath: String): String =
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot d -cdrom $isoGuestPath " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0 -device virtio-net-pci,netdev=n0"

    // Runtime boot: the provisioned appliance disk ONLY (no ISO, no re-install).
    // -boot c boots the installed system from /dev/vda; hostfwd exposes its sshd.
    private fun qemuRuntimeCommand(diskGuestPath: String, port: Int): String =
        // exec → the launcher process *is* qemu (clean to signal). Serial on
        // stdio = our Process streams; headless; -no-reboot so poweroff exits.
        "exec qemu-system-x86_64 -M pc -m $VM_MEM_MB -display none -monitor none " +
            "-serial stdio -no-reboot -boot c " +
            "-drive file=$diskGuestPath,if=virtio,format=raw " +
            "-netdev user,id=n0,hostfwd=tcp:127.0.0.1:$port-:22 -device virtio-net-pci,netdev=n0"

    /** Start a VM Process for [command] and begin draining its serial. */
    private fun startVm(command: String) {
        synchronized(serialLock) { serial.setLength(0) }
        val proc = prootManager.startCommandInProot(command)
        vmProcess = proc
        serialReader = thread(name = "haven-qemu-serial", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(1024)
                    while (true) {
                        val n = r.read(chunk); if (n < 0) break
                        synchronized(serialLock) {
                            serial.append(chunk, 0, n)
                            if (serial.length > SERIAL_CAP) serial.delete(0, serial.length - SERIAL_CAP)
                        }
                    }
                }
            }
        }
    }

    /**
     * The single setup line typed at the appliance's root shell each open. The
     * appliance already has linux-tools-usbip + openssh installed (provisioned
     * once), so this only does the per-open work: network up, authorise the
     * caller's ephemeral key, start sshd, attach the drive over USB/IP, mount RO.
     */
    private fun runtimeSetupScript(busid: String, pubKey: String): String {
        // pubKey is a single "ssh-ed25519 AAAA... comment" line (no single quotes).
        return "ip link set eth0 up; udhcpc -i eth0 -q -n; " +
            "mkdir -p /root/.ssh; echo '$pubKey' > /root/.ssh/authorized_keys; " +
            "chmod 700 /root/.ssh; chmod 600 /root/.ssh/authorized_keys; " +
            "ssh-keygen -A >/dev/null 2>&1; " +
            "sed -i 's/^#*PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config; " +
            "rc-service sshd restart >/dev/null 2>&1 || /usr/sbin/sshd; " +
            "modprobe vhci_hcd; " +
            // busybox `mount` auto-detect only works for filesystems already in
            // /proc/filesystems — it doesn't modprobe — so load the common ones
            // up front or an ext4 stick mounts as an empty dir.
            "for m in ext4 vfat exfat ntfs3; do modprobe \$m 2>/dev/null; done; " +
            // Attach + wait for the SCSI node to enumerate. usbip occasionally
            // imports the device at the wrong speed and it never enumerates; one
            // detach/re-attach clears that, so retry once before giving up.
            "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; ok=0; " +
            "for i in \$(seq 1 15); do ls /dev/sd[a-z][0-9]* >/dev/null 2>&1 && { ok=1; break; }; sleep 1; done; " +
            "[ \$ok = 1 ] || { usbip detach -p 00 2>/dev/null; usbip detach -p 0 2>/dev/null; sleep 2; " +
            "usbip attach -r 10.0.2.2 -b $busid 2>/dev/null; " +
            "for i in \$(seq 1 15); do ls /dev/sd[a-z][0-9]* >/dev/null 2>&1 && break; sleep 1; done; }; " +
            "mkdir -p /mnt; for p in /dev/sd[a-z][0-9]*; do [ -b \"\$p\" ] || continue; " +
            "l=\$(basename \"\$p\"); mkdir -p /mnt/\$l; " +
            "(mount -o ro \"\$p\" /mnt/\$l 2>/dev/null || mount -o ro,noload \"\$p\" /mnt/\$l 2>/dev/null) " +
            "&& echo HVNMOUNT:/mnt/\$l; done; " +
            "echo HAVEN_SETUP_DONE"
    }

    private fun parseMounts(): List<String> {
        val text = synchronized(serialLock) { serial.toString() }
        // Match only the loop's own output lines (HVNMOUNT:/mnt/<part> at line
        // start), not the echoed setup command which also contains the literal.
        val re = Regex("^HVNMOUNT:(/mnt/\\S+)$")
        return text.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .mapNotNull { re.find(it)?.groupValues?.get(1) }
            .distinct().toList()
    }

    private fun send(s: String) {
        runCatching { vmProcess?.outputStream?.apply { write(s.toByteArray()); flush() } }
    }

    private fun awaitSerial(
        marker: String,
        timeoutMs: Long,
        nudge: Boolean = false,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
    ): Boolean {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        var nextTick = start + PROGRESS_TICK_MS
        while (System.currentTimeMillis() < deadline) {
            if (vmProcess?.isAlive != true) return false
            synchronized(serialLock) { if (serial.contains(marker)) return true }
            if (nudge) send("\n")
            val now = System.currentTimeMillis()
            if (onProgress != null && now >= nextTick) {
                onProgress((now - start) / 1000)
                nextTick = now + PROGRESS_TICK_MS
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
        }
        return synchronized(serialLock) { serial.contains(marker) }
    }

    private fun awaitSshBanner(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    s.soTimeout = 2000
                    val b = ByteArray(4)
                    val n = s.getInputStream().read(b)
                    n >= 4 && String(b) == "SSH-"
                }
            }.getOrDefault(false)
            if (ok) return true
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private suspend fun ensureQemu(onStage: (String) -> Unit = {}) {
        onStage("Checking the VM engine…")
        val (out, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (out.contains("qemu-system-x86_64")) return
        val family = prootManager.activeDistro.family
        val pkg = qemuPackageFor(family)
        val ops = PackageOps.forFamily(family)
        onStage("Installing the VM engine (one-time)…")
        Log.i(TAG, "installing $pkg (${family})")
        val (instOut, code) = prootManager.runCommandInProot("${ops.updateCmd()} >/dev/null 2>&1 ; ${ops.installCmd(listOf(pkg))} 2>&1")
        val (check, _) = prootManager.runCommandInProot("command -v qemu-system-x86_64 || true")
        if (!check.contains("qemu-system-x86_64")) {
            throw IllegalStateException("Could not install $pkg in ${prootManager.activeDistroId} (exit $code): ${instOut.takeLast(200)}")
        }
    }

    private fun qemuPackageFor(family: PackageFamily): String = when (family) {
        PackageFamily.APK -> "qemu-system-x86_64"
        PackageFamily.APT -> "qemu-system-x86"
        PackageFamily.PACMAN -> "qemu-system-x86"
        PackageFamily.XBPS -> "qemu"
        else -> "qemu"
    }

    /** Download + verify the appliance ISO once into cacheDir (bound at /tmp in proot). */
    private fun ensureAppliance(onStage: (String) -> Unit = {}): String {
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val iso = File(dir, APPLIANCE_NAME)
        val marker = File(dir, "$APPLIANCE_NAME.ok")
        if (iso.exists() && marker.exists()) return "/tmp/haven-vm/$APPLIANCE_NAME"
        iso.delete(); marker.delete()
        onStage("Downloading the Linux image (one-time, ~270 MB)…")
        Log.i(TAG, "downloading appliance ISO …")
        val conn = URL(APPLIANCE_URL).openConnection() as java.net.HttpURLConnection
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: APPLIANCE_SIZE
        conn.inputStream.use { input ->
            iso.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var read = 0L; var lastPct = -1
                var n = input.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n); read += n
                    val pct = ((read * 100) / total).toInt()
                    if (pct >= lastPct + 5) { onStage("Downloading the Linux image (one-time)… $pct%"); lastPct = pct }
                    n = input.read(buf)
                }
            }
        }
        onStage("Verifying the download…")
        val sha = MessageDigest.getInstance("SHA-256").let { md ->
            iso.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16); var n = ins.read(buf)
                while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        if (!sha.equals(APPLIANCE_SHA256, ignoreCase = true)) {
            iso.delete(); throw SecurityException("appliance ISO checksum mismatch: $sha")
        }
        marker.writeText("ok\n")
        return "/tmp/haven-vm/$APPLIANCE_NAME"
    }

    /** True once the persistent appliance disk has been provisioned. */
    val isApplianceProvisioned: Boolean
        get() = File(File(context.cacheDir, "haven-vm"), "$APPLIANCE_DISK.ok").exists()

    /**
     * Provision the persistent appliance disk once — install Alpine +
     * linux-tools-usbip + openssh to it (via `setup-disk -m sys`) — or return
     * it immediately if already provisioned. After this, every [openDrive] boots
     * the disk directly (no ISO, no apk, no network), so repeat opens are just a
     * kernel boot. The user keeps the appliance until they [deleteAppliance].
     *
     * The whole sequence (install + serial-console + passwordless-root config)
     * was validated locally under KVM; see scratch/qemu-appliance.
     *
     * Public so callers can provision BEFORE starting the USB/IP export — the
     * one-time provision takes minutes, and holding the export open across it
     * stales the drive (it re-imports at the wrong speed and never enumerates).
     * openDrive() calls this again, but it's a no-op once provisioned.
     */
    suspend fun ensureProvisionedAppliance(onStage: (String) -> Unit): String {
        val dir = File(context.cacheDir, "haven-vm").apply { mkdirs() }
        val disk = File(dir, APPLIANCE_DISK)
        val marker = File(dir, "$APPLIANCE_DISK.ok")
        val guestDisk = "/tmp/haven-vm/$APPLIANCE_DISK"
        if (disk.exists() && marker.exists()) return guestDisk

        // (Re-)provision from scratch. Needs the ISO; drop it again afterwards.
        disk.delete(); marker.delete()
        val iso = ensureAppliance(onStage)
        onStage("Building the USB helper Linux (one-time)…")
        Log.i(TAG, "provisioning appliance disk …")
        // Raw sparse image (truncate is universal — no qemu-img dependency).
        prootManager.runCommandInProot("rm -f $guestDisk && truncate -s $APPLIANCE_DISK_SIZE $guestDisk")
        startVm(qemuProvisionCommand(iso, guestDisk))
        try {
            val bootOk = awaitSerial("login:", BOOT_TIMEOUT_MS, nudge = true) { sec ->
                onStage("Building the USB helper Linux — booting (${sec}s)…")
            }
            if (!bootOk) fail("appliance provisioning: VM didn't reach a login prompt in ${BOOT_TIMEOUT_MS / 1000}s")
            send("root\n"); Thread.sleep(1500)
            onStage("Building the USB helper Linux — installing (one-time)…")
            send(provisionScript + "\n")
            val ok = awaitSerial("HAVEN_PROVISION_DONE", PROVISION_TIMEOUT_MS) { sec ->
                onStage("Building the USB helper Linux — installing (${sec}s)…")
            }
            if (!ok) fail("appliance provisioning didn't finish in ${PROVISION_TIMEOUT_MS / 1000}s")
        } finally {
            runCatching { send("\npoweroff\n") }
            runCatching { vmProcess?.waitFor(6, TimeUnit.SECONDS) }
            stopVm()
        }
        // setup-disk grows the sparse image well past 20 MB; a tiny file means
        // the install never happened (marker can't gate that — the host file is
        // what we boot next).
        if (!disk.exists() || disk.length() < 20_000_000L) fail("appliance disk wasn't provisioned")
        marker.writeText("ok\n")
        // The appliance is self-contained now; reclaim the ~270 MB ISO. Deleting
        // the appliance re-downloads + re-provisions.
        runCatching { File(dir, APPLIANCE_NAME).delete(); File(dir, "$APPLIANCE_NAME.ok").delete() }
        Log.i(TAG, "appliance provisioned (${disk.length() / 1024 / 1024} MB)")
        return guestDisk
    }

    /** Delete the persistent appliance; the next [openDrive] re-provisions it. */
    fun deleteAppliance() {
        closeDrive()
        val dir = File(context.cacheDir, "haven-vm")
        val removed = File(dir, APPLIANCE_DISK).delete()
        File(dir, "$APPLIANCE_DISK.ok").delete()
        Log.i(TAG, "deleteAppliance: removed=$removed")
    }

    // The one-time install line typed at the ISO's root shell with a blank
    // /dev/vda attached. Validated locally under KVM (scratch/qemu-appliance):
    // installs the live system to disk (sys mode), keeps console=ttyS0 +
    // passwordless serial root, and gates the done-marker on success so a failed
    // setup-disk can't be read as provisioned.
    private val provisionScript: String =
        "ip link set eth0 up; udhcpc -i eth0 -q -n 2>/dev/null; " +
            "printf 'https://dl-cdn.alpinelinux.org/alpine/v3.21/main\\n" +
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\\n' > /etc/apk/repositories; " +
            "apk update -q && apk add -q linux-tools-usbip openssh e2fsprogs syslinux && " +
            "export ERASE_DISKS=/dev/vda BOOTLOADER=syslinux && " +
            "setup-disk -m sys -s 0 /dev/vda && " +
            "R=\$(blkid | grep /dev/vda | grep -i ext4 | head -1 | cut -d: -f1) && [ -n \"\$R\" ] && " +
            "mount \$R /mnt && chroot /mnt rc-update add sshd default 2>/dev/null; " +
            "if [ -d /mnt/etc ]; then chroot /mnt rc-update add networking default 2>/dev/null; " +
            "grep -q ttyS0 /mnt/etc/inittab || echo 'ttyS0::respawn:/sbin/getty -L 0 ttyS0 vt100' >> /mnt/etc/inittab; " +
            "for f in /mnt/boot/extlinux.conf /mnt/extlinux.conf; do [ -f \"\$f\" ] && " +
            "{ grep -q console=ttyS0 \"\$f\" || sed -i 's|\\(APPEND .*\\)|\\1 console=ttyS0,115200|' \"\$f\"; }; done; " +
            "printf 'auto eth0\\niface eth0 inet dhcp\\n' > /mnt/etc/network/interfaces; " +
            "mkdir -p /mnt/root/.ssh; chmod 700 /mnt/root/.ssh; sync; umount /mnt && echo HAVEN_PROVISION_DONE; fi"

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun fail(msg: String): Nothing = throw IllegalStateException(msg)

    // tracee reaping — same approach as GuestServiceManager (proot ptrace).
    private fun descendantPids(rootPid: Int): List<Int> {
        val childrenOf = mutableMapOf<Int, MutableList<Int>>()
        val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) } ?: return emptyList()
        for (dir in procDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val ppid = readPpid(File(dir, "stat")) ?: continue
            childrenOf.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val out = mutableListOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(rootPid) }
        while (queue.isNotEmpty()) childrenOf[queue.removeFirst()]?.forEach { out.add(it); queue.add(it) }
        return out
    }

    private fun readPpid(statFile: File): Int? = try {
        val stat = statFile.readText()
        val after = stat.substringAfterLast(')').trim().split(" ")
        after.getOrNull(1)?.toIntOrNull()
    } catch (_: Throwable) { null }

    private fun pidOf(p: Process): Int? = try {
        val v = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(p)
        when (v) { is Int -> v; is Long -> v.toInt(); else -> null }
    } catch (_: Throwable) { null }

    companion object {
        private const val TAG = "QemuManager"
        private const val VM_MEM_MB = 768
        private const val SERIAL_CAP = 64 * 1024
        // Generous: TCG boot (no KVM) is slow and varies a lot with phone load —
        // 4 min was marginal and timed out under load before reaching login.
        private const val BOOT_TIMEOUT_MS = 420_000L
        private const val SETUP_TIMEOUT_MS = 360_000L
        // One-time: apk download + setup-disk install over TCG can take a while.
        private const val PROVISION_TIMEOUT_MS = 720_000L
        // Persistent installed appliance (raw sparse image; usbip+ssh baked in).
        private const val APPLIANCE_DISK = "usb_vm_appliance.img"
        private const val APPLIANCE_DISK_SIZE = "2G"
        private const val SSH_TIMEOUT_MS = 30_000L
        private const val PROGRESS_TICK_MS = 15_000L
        private const val APPLIANCE_NAME = "alpine-standard-3.21.7-x86_64.iso"
        private const val APPLIANCE_SIZE = 278_921_216L // fallback when no Content-Length
        private const val APPLIANCE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/x86_64/alpine-standard-3.21.7-x86_64.iso"
        // Pinned point release; rotates when Alpine supersedes it (re-pin then).
        private const val APPLIANCE_SHA256 =
            "f1a3a93628927b382d31e7b173b12801342641f711d8c591b88582be1b29954a"
    }
}
