package sh.haven.core.usbserial

/**
 * A live USB-serial byte pipe to an attached adapter (CDC-ACM, CH34x, FTDI,
 * CP21xx, …). Callback-shaped rather than stream-shaped because
 * usb-serial-for-android delivers reads through a [com.hoho.android.usbserial.util.SerialInputOutputManager]
 * listener, not a blocking InputStream — so [UsbSerialSession] stays
 * transport-agnostic and unit-testable with a fake link, no USB hardware.
 */
interface UsbSerialLink {
    /** Human-facing adapter name (product string), if the device exposed one. */
    val displayName: String?

    /**
     * Begin pumping incoming bytes to [onData]. [onError] fires once if the read
     * loop dies on its own (cable pulled, adapter error); it does NOT fire on an
     * explicit [close].
     */
    fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit)

    /** Write keystrokes / commands to the device. */
    fun write(bytes: ByteArray)

    /** Tear down the read loop and release the USB device. Idempotent. */
    fun close()
}

/**
 * Serial line configuration. Defaults (115200 8N1) suit a Duet3D G-code console
 * and most Arduino sketches; the profile editor overrides per device.
 */
data class UsbSerialParams(
    val baudRate: Int = 115200,
    /** Data bits 5..8. */
    val dataBits: Int = 8,
    val stopBits: StopBits = StopBits.ONE,
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE,
) {
    enum class StopBits { ONE, ONE_POINT_FIVE, TWO }
    enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
    enum class FlowControl { NONE, RTS_CTS, XON_XOFF }

    /**
     * Serialise the line format (everything but the baud rate) to the compact
     * `"<dataBits>,<parity>,<stopBits>,<flow>"` string stored in a profile —
     * baud lives in its own column, so it is deliberately excluded here.
     */
    fun toConfigString(): String =
        "$dataBits,${parity.code},${stopBits.code},${flowControl.code}"

    companion object {
        /**
         * Rebuild params from [baudRate] plus a [config] string produced by
         * [toConfigString]. Missing / blank / unrecognised tokens fall back to
         * 8N1, no flow control — so a legacy profile that only stored a baud
         * rate (config == null) still opens with sane defaults.
         */
        fun fromConfigString(baudRate: Int, config: String?): UsbSerialParams {
            val t = config?.split(",").orEmpty()
            fun tok(i: Int) = t.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            return UsbSerialParams(
                baudRate = baudRate,
                dataBits = tok(0)?.toIntOrNull()?.coerceIn(5, 8) ?: 8,
                parity = parityFromCode(tok(1)),
                stopBits = stopBitsFromCode(tok(2)),
                flowControl = flowControlFromCode(tok(3)),
            )
        }
    }
}

private val UsbSerialParams.Parity.code: String
    get() = when (this) {
        UsbSerialParams.Parity.NONE -> "N"
        UsbSerialParams.Parity.ODD -> "O"
        UsbSerialParams.Parity.EVEN -> "E"
        UsbSerialParams.Parity.MARK -> "M"
        UsbSerialParams.Parity.SPACE -> "S"
    }

private fun parityFromCode(c: String?) = when (c?.uppercase()) {
    "O" -> UsbSerialParams.Parity.ODD
    "E" -> UsbSerialParams.Parity.EVEN
    "M" -> UsbSerialParams.Parity.MARK
    "S" -> UsbSerialParams.Parity.SPACE
    else -> UsbSerialParams.Parity.NONE
}

private val UsbSerialParams.StopBits.code: String
    get() = when (this) {
        UsbSerialParams.StopBits.ONE -> "1"
        UsbSerialParams.StopBits.ONE_POINT_FIVE -> "1.5"
        UsbSerialParams.StopBits.TWO -> "2"
    }

private fun stopBitsFromCode(c: String?) = when (c) {
    "1.5" -> UsbSerialParams.StopBits.ONE_POINT_FIVE
    "2" -> UsbSerialParams.StopBits.TWO
    else -> UsbSerialParams.StopBits.ONE
}

private val UsbSerialParams.FlowControl.code: String
    get() = when (this) {
        UsbSerialParams.FlowControl.NONE -> "none"
        UsbSerialParams.FlowControl.RTS_CTS -> "rtscts"
        UsbSerialParams.FlowControl.XON_XOFF -> "xonxoff"
    }

private fun flowControlFromCode(c: String?) = when (c?.lowercase()) {
    "rtscts" -> UsbSerialParams.FlowControl.RTS_CTS
    "xonxoff" -> UsbSerialParams.FlowControl.XON_XOFF
    else -> UsbSerialParams.FlowControl.NONE
}
