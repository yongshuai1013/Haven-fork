package sh.haven.core.tunnel

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * javax.net.SocketFactory that dials through a [Tunnel] and returns the
 * resulting connection wrapped as a [TunneledSocket].
 *
 * Used where an upstream library takes a SocketFactory rather than a
 * pre-dialled Socket — currently smbj via `SmbConfig.builder()
 * .withSocketFactory(...)`.
 *
 * @param dialTimeoutMs timeout passed to [Tunnel.dial]. Default 30 s
 *   matches [TunnelProxy]'s floor: WireGuard / Tailscale handshakes
 *   take 1–2 s before application traffic, and many upstream consumers
 *   pass shorter SO_TIMEOUT-style values that would prematurely fail.
 */
class TunnelSocketFactory(
    private val tunnel: Tunnel,
    private val dialTimeoutMs: Int = 30_000,
) : SocketFactory() {

    /**
     * Unconnected socket whose dial is deferred to [Socket.connect]. JavaMail's
     * SocketFetcher creates the base socket via the no-arg `createSocket()` and
     * then calls `connect(addr)`, so a connected-only factory can't serve it
     * (it hits SocketFactory's "Unconnected sockets not implemented" default).
     * [DeferredTunneledSocket] bridges that, dialing through the tunnel on
     * connect — so a dead tunnel fails the connect (no clearnet fallback).
     */
    override fun createSocket(): Socket = DeferredTunneledSocket(tunnel, dialTimeoutMs)

    override fun createSocket(host: String, port: Int): Socket {
        val conn = tunnel.dial(host, port, dialTimeoutMs)
        return TunneledSocket(conn, host, port)
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.toString(), port)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = createSocket(address, port)
}
