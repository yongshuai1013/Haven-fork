package sh.haven.core.tunnel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class TunnelResolverTest {

    @Test
    fun dialReturnsNullWhenProfileHasNoTunnelConfigId() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.dial(profile(tunnelConfigId = null), "h", 80, 30_000))
    }

    @Test
    fun dialReturnsNullWhenTunnelConfigDeleted() = runTest {
        val mgr = mockk<TunnelManager> {
            coEvery { getTunnel("missing") } returns null
        }
        val resolver = TunnelResolver(mgr)
        assertNull(resolver.dial(profile(tunnelConfigId = "missing"), "h", 80, 30_000))
    }

    @Test
    fun dialDelegatesToTunnelWhenConfigPresent() = runTest {
        val conn = stubConn()
        val tunnel = mockk<Tunnel> {
            every { dial("example.com", 443, 5_000) } returns conn
        }
        val mgr = mockk<TunnelManager> {
            coEvery { getTunnel("tid") } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val result = resolver.dial(profile(tunnelConfigId = "tid"), "example.com", 443, 5_000)

        assertSame(conn, result)
    }

    @Test
    fun socketFactoryReturnsNullWhenNoTunnel() = runTest {
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socketFactory(profile(tunnelConfigId = null)))
    }

    @Test
    fun socketFactoryReturnsTunnelSocketFactoryWhenConfigPresent() = runTest {
        val tunnel = mockk<Tunnel>(relaxed = true)
        val mgr = mockk<TunnelManager> {
            coEvery { getTunnel("tid") } returns tunnel
        }
        val resolver = TunnelResolver(mgr)

        val factory = resolver.socketFactory(profile(tunnelConfigId = "tid"))

        assertNotNull(factory)
        assertTrue(factory is TunnelSocketFactory)
    }

    @Test
    fun socksEndpointReturnsNullPendingStep4() = runTest {
        // Documented behaviour until the wgbridge / tsnet SOCKS5 listener
        // (step 4 of #149) lands. This test pins the contract so callers
        // know FFI-bound transports fall through to direct dialling.
        val resolver = TunnelResolver(mockk(relaxed = true))
        assertNull(resolver.socksEndpoint(profile(tunnelConfigId = "tid")))
    }

    private fun profile(tunnelConfigId: String?): ConnectionProfile = ConnectionProfile(
        label = "test",
        host = "example.com",
        username = "user",
        tunnelConfigId = tunnelConfigId,
    )

    private fun stubConn(): TunneledConnection = object : TunneledConnection {
        override val inputStream: InputStream = ByteArrayInputStream(ByteArray(0))
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
