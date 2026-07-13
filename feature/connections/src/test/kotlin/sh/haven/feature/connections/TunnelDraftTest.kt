package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SSH-tunnel editor behaviour shared by the VNC / RDP / SPICE /
 * SMB rows in ConnectionEditDialog — extracted verbatim from the four
 * previously-duplicated inline blocks.
 */
class TunnelDraftTest {

    // --- initial toggle state (strict guard used by VNC and SPICE) ---

    @Test fun strictInitRequiresMatchingTypeFlagAndCarrier() {
        assertTrue(strictTunnelInitialEnabled("VNC", "VNC", true, "ssh-1"))
    }

    @Test fun strictInitFalseWhenStoredTypeDiffers() {
        // A profile switched from RDP can carry a stale flag for the old type.
        assertFalse(strictTunnelInitialEnabled("RDP", "VNC", true, "ssh-1"))
    }

    @Test fun strictInitFalseWithoutCarrier() {
        // vncSshForward historically defaulted true; without a carrier the
        // toggle must start off rather than blocking Save on a hidden field.
        assertFalse(strictTunnelInitialEnabled("VNC", "VNC", true, null))
    }

    @Test fun strictInitFalseWhenFlagCleared() {
        assertFalse(strictTunnelInitialEnabled("VNC", "VNC", false, "ssh-1"))
    }

    // --- host transitions on toggle ---

    @Test fun enablingDefaultsBlankHostToIpv4Loopback() {
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, ""))
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, "  "))
    }

    @Test fun enablingRewritesLocalhostToIpv4Loopback() {
        // Not "localhost": remote sshd may resolve it to ::1 while the
        // server listens on IPv4 only (v5.24.14).
        assertEquals("127.0.0.1", tunnelHostOnToggle(true, "localhost"))
    }

    @Test fun enablingKeepsCustomHost() {
        assertEquals("192.168.1.100", tunnelHostOnToggle(true, "192.168.1.100"))
    }

    @Test fun disablingClearsOnlyLoopbackHosts() {
        assertEquals("", tunnelHostOnToggle(false, "127.0.0.1"))
        assertEquals("", tunnelHostOnToggle(false, "localhost"))
        assertEquals("192.168.1.100", tunnelHostOnToggle(false, "192.168.1.100"))
    }

    // --- save gating and normalisation ---

    @Test fun enabledTunnelWithoutCarrierBlocksSave() {
        // This is what keeps the RDP/SMB raw-flag initialisation safe: a
        // stored flag with a missing carrier forces a dropdown choice.
        assertFalse(tunnelComplete(enabled = true, carrierId = null))
        assertTrue(tunnelComplete(enabled = true, carrierId = "ssh-1"))
        assertTrue(tunnelComplete(enabled = false, carrierId = null))
    }

    @Test fun disabledTunnelNeverPersistsACarrier() {
        assertNull(tunnelCarrierForSave(enabled = false, carrierId = "ssh-1"))
        assertEquals("ssh-1", tunnelCarrierForSave(enabled = true, carrierId = "ssh-1"))
        assertNull(tunnelCarrierForSave(enabled = true, carrierId = null))
    }
}
