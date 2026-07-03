package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #328: a tar hard-link entry's link target is an archive-relative path that
 * carries the same leading components as entry names, so `--strip-components`
 * must apply to it too. Before [resolveTarLinkTarget] the target of every hard
 * link in a wrapped tarball (stripComponents=1 — the shape of a proot-distro
 * import) pointed at a nonexistent path and the linked file went silently
 * missing from the rootfs.
 */
class TarLinkTargetTest {

    @Test
    fun `strip 0 leaves the target untouched`() {
        assertEquals("usr/bin/gunzip", resolveTarLinkTarget("usr/bin/gunzip", 0))
        assertEquals("./usr/bin/gunzip", resolveTarLinkTarget("./usr/bin/gunzip", 0))
    }

    @Test
    fun `strip 1 removes the wrapper directory (proot-distro import shape)`() {
        assertEquals(
            "usr/bin/gunzip",
            resolveTarLinkTarget("debian-bookworm/usr/bin/gunzip", 1),
        )
    }

    @Test
    fun `strip 1 on a dot-prefixed target matches dot-prefixed entry stripping`() {
        // tar -C dir -czf out . produces "./"-prefixed names; entry names and
        // link targets are stripped by the same rule, so both lose the ".".
        assertEquals("usr/bin/gunzip", resolveTarLinkTarget("./usr/bin/gunzip", 1))
    }

    @Test
    fun `stripping more components than the target has yields empty`() {
        // Degenerate wrapper-only path — mirrors the entry-name behaviour
        // (empty → entry skipped) rather than throwing.
        assertEquals("", resolveTarLinkTarget("wrapper", 1))
    }
}
