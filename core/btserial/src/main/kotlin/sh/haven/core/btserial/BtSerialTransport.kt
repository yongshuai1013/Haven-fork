package sh.haven.core.btserial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * An open Bluetooth-serial (RFCOMM / SPP) byte pipe to a paired device.
 *
 * Deliberately just streams + close, so the terminal-facing code
 * ([BtSerialSession]) is transport-agnostic and unit-testable with plain piped
 * streams — no Bluetooth hardware in the tests.
 */
interface BtSerialTransport {
    val input: InputStream
    val output: OutputStream
    val remoteName: String?
    fun close()
}

/**
 * Opens a Bluetooth Classic RFCOMM connection to a **bonded** device using the
 * standard Serial Port Profile UUID — the same mechanism Reticulum's shipping
 * [network.reticulum.android.spp.AndroidSppDriver] uses, and what a BT-to-serial
 * console adapter presents (#406).
 *
 * `connect` blocks (1–12 s) and must run off the main thread. BLUETOOTH_CONNECT
 * must be granted before calling; the caller (UI) owns that runtime request.
 */
class AndroidBtSerialConnector(private val context: Context) {

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT gated at the call site
    fun connect(address: String, secure: Boolean = true): BtSerialTransport {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: throw IllegalStateException("This device has no Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        val socket = if (secure) {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        }
        // An in-progress discovery scan slows every connect and makes RFCOMM
        // flaky — SPP callers are expected to cancel it first.
        runCatching { adapter.cancelDiscovery() }
        socket.connect()
        return object : BtSerialTransport {
            override val input: InputStream get() = socket.inputStream
            override val output: OutputStream get() = socket.outputStream
            override val remoteName: String? = device.name
            override fun close() {
                runCatching { socket.close() }
            }
        }
    }

    companion object {
        /** Standard Serial Port Profile UUID — matches AndroidSppDriver.SPP_UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
