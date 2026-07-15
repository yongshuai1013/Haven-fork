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
) {
    enum class StopBits { ONE, ONE_POINT_FIVE, TWO }
    enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
}
