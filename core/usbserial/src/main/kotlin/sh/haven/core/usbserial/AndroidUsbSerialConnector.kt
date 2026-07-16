package sh.haven.core.usbserial

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * Opens a USB-serial link to an attached adapter using usb-serial-for-android's
 * driver probe (CDC-ACM, CH34x, FTDI, CP21xx, Prolific). The USB runtime
 * permission must already be granted for [device] — the caller (UI) owns that
 * request, exactly as the BT-serial path owns BLUETOOTH_CONNECT.
 *
 * `connect` does blocking USB IO (open + control transfers for line coding) and
 * must run off the main thread.
 */
class AndroidUsbSerialConnector(private val usbManager: UsbManager) {

    fun connect(device: UsbDevice, params: UsbSerialParams): UsbSerialLink {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IllegalStateException(
                "No serial driver for ${device.productName ?: device.deviceName} " +
                    "(${vidPid(device)}) — not a recognised USB-serial adapter",
            )
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: throw IllegalStateException(
                "Could not open ${device.productName ?: device.deviceName} — USB permission not granted",
            )
        val port = driver.ports.firstOrNull()
            ?: run {
                connection.close()
                throw IllegalStateException("Serial adapter exposes no ports")
            }
        try {
            port.open(connection)
            port.setParameters(
                params.baudRate,
                params.dataBits,
                stopBitsConst(params.stopBits),
                parityConst(params.parity),
            )
            // Flow control (best-effort — not every chipset implements it).
            runCatching { port.setFlowControl(flowControlConst(params.flowControl)) }
            // Assert DTR — and RTS unless RTS/CTS flow control owns it — on open,
            // exactly as a hardware terminal (PuTTY, screen, minicom) does. Many
            // CDC-ACM devices gate ALL data flow on DTR: the nRF USB-CDC stack
            // returns NRF_ERROR_INVALID_STATE from its write until DTR is set,
            // and Arduino `while (!Serial)` sketches never start. Without this
            // the port opens but no bytes move. Best-effort — some drivers do
            // not implement modem-control lines and throw here.
            runCatching { port.setDTR(true) }
            if (params.flowControl != UsbSerialParams.FlowControl.RTS_CTS) {
                runCatching { port.setRTS(true) }
            }
        } catch (e: Exception) {
            runCatching { port.close() }
            runCatching { connection.close() }
            throw e
        }
        return AndroidUsbSerialLink(port, connection, device.productName)
    }

    private companion object {
        fun vidPid(d: UsbDevice) =
            "%04x:%04x".format(d.vendorId, d.productId)

        fun stopBitsConst(s: UsbSerialParams.StopBits) = when (s) {
            UsbSerialParams.StopBits.ONE -> UsbSerialPort.STOPBITS_1
            UsbSerialParams.StopBits.ONE_POINT_FIVE -> UsbSerialPort.STOPBITS_1_5
            UsbSerialParams.StopBits.TWO -> UsbSerialPort.STOPBITS_2
        }

        fun parityConst(p: UsbSerialParams.Parity) = when (p) {
            UsbSerialParams.Parity.NONE -> UsbSerialPort.PARITY_NONE
            UsbSerialParams.Parity.ODD -> UsbSerialPort.PARITY_ODD
            UsbSerialParams.Parity.EVEN -> UsbSerialPort.PARITY_EVEN
            UsbSerialParams.Parity.MARK -> UsbSerialPort.PARITY_MARK
            UsbSerialParams.Parity.SPACE -> UsbSerialPort.PARITY_SPACE
        }

        fun flowControlConst(f: UsbSerialParams.FlowControl) = when (f) {
            UsbSerialParams.FlowControl.NONE -> UsbSerialPort.FlowControl.NONE
            UsbSerialParams.FlowControl.RTS_CTS -> UsbSerialPort.FlowControl.RTS_CTS
            UsbSerialParams.FlowControl.XON_XOFF -> UsbSerialPort.FlowControl.XON_XOFF
        }
    }
}

/** [UsbSerialLink] backed by an open usb-serial-for-android port + IO manager. */
private class AndroidUsbSerialLink(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    override val displayName: String?,
) : UsbSerialLink {

    @Volatile
    private var io: SerialInputOutputManager? = null

    override fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        val manager = SerialInputOutputManager(port).apply {
            readTimeout = READ_TIMEOUT_MS
            listener = object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray?) {
                    if (data != null && data.isNotEmpty()) onData(data)
                }

                override fun onRunError(e: Exception?) {
                    onError(e ?: RuntimeException("USB serial read error"))
                }
            }
        }
        io = manager
        manager.start()
    }

    override fun write(bytes: ByteArray) {
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        runCatching { io?.stop() }
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 2000
    }
}
