package sh.haven.core.usb

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

private const val TAG = "UsbAccessGate"

/**
 * Coordinates exclusive access to a phone-attached USB device between Haven
 * consumers that do **not** share a [UsbDeviceConnection].
 *
 * The USB/IP export (via [UsbBroker]) and `core:fido`'s `FidoAuthenticator`
 * each open their *own* connection to the same physical key and claim the same
 * HID interface. Their CTAPHID frames then interleave on the shared interrupt
 * endpoints and corrupt each other — the `0xbb` keepalive storm seen when the
 * same YubiKey is both the SSH-auth credential and the usbip-export target on a
 * flaky link.
 *
 * While FIDO auth runs it takes a per-device lease ([acquire]); the broker's
 * transfer path waits on [awaitClear] so the export isn't transferring on that
 * device concurrently. Keyed by `UsbDevice.deviceName` so an export of an
 * *unrelated* device is never paused by a FIDO auth on the key.
 */
@Singleton
class UsbAccessGate @Inject constructor() {

    private val lock = ReentrantLock()
    private val cleared = lock.newCondition()
    /** Lease counts by device name; >0 ⇒ a priority consumer holds the device. Guarded by [lock]. */
    private val holders = HashMap<String, Int>()

    /** Take an exclusive lease on [deviceName]. Re-entrant; balance with [release]. */
    fun acquire(deviceName: String) = lock.withLock {
        holders[deviceName] = (holders[deviceName] ?: 0) + 1
    }

    /** Release one lease on [deviceName] and wake any waiting broker transfers. */
    fun release(deviceName: String) = lock.withLock {
        val n = (holders[deviceName] ?: 0) - 1
        if (n <= 0) holders.remove(deviceName) else holders[deviceName] = n
        cleared.signalAll()
    }

    fun isHeld(deviceName: String): Boolean = lock.withLock { (holders[deviceName] ?: 0) > 0 }

    /**
     * Block until no lease is held on [deviceName], up to [timeoutMs]. Returns
     * true if the device is clear, false on timeout — the caller then proceeds
     * anyway, since a brief contended transfer is better than wedging the export
     * if a lease is never released (e.g. the holder's thread died).
     */
    fun awaitClear(deviceName: String, timeoutMs: Long): Boolean = lock.withLock {
        if ((holders[deviceName] ?: 0) == 0) return@withLock true
        var remaining = timeoutMs * 1_000_000 // nanos
        while ((holders[deviceName] ?: 0) > 0) {
            if (remaining <= 0) {
                Log.w(TAG, "export on $deviceName proceeding despite a FIDO lease (waited ${timeoutMs}ms)")
                return@withLock false
            }
            remaining = cleared.awaitNanos(remaining)
        }
        true
    }
}
