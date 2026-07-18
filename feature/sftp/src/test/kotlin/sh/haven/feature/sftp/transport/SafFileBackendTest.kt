package sh.haven.feature.sftp.transport

import android.database.Cursor
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #415: SAF-backed "local folder" location. These pin the pure path→document-id
 * resolution (and its memoisation) that the browser leans on — the framework
 * `DocumentsContract`/`ContentResolver` glue in [SafFileBackend] itself needs a
 * device, but the walk logic is the part with real branches, so it's isolated
 * here (cursor source injected, mirroring `ScanDocumentTreeTest`).
 */
class SafFileBackendTest {

    private val DIR = DocumentsContract.Document.MIME_TYPE_DIR

    /** Cursor over rows of (docId, displayName, mime). Only cols 0/1 are read by the walk. */
    private fun cursorOf(rows: List<Array<String>>): Cursor {
        var pos = -1
        val c = io.mockk.mockk<Cursor>(relaxed = true)
        io.mockk.every { c.moveToNext() } answers { ++pos < rows.size }
        io.mockk.every { c.getString(0) } answers { rows[pos][0] }
        io.mockk.every { c.getString(1) } answers { rows[pos][1] }
        io.mockk.every { c.getString(2) } answers { rows[pos][2] }
        io.mockk.every { c.close() } returns Unit
        return c
    }

    /** tree: root/{a.txt, sub/{b.bin, deep/{c.txt}}} */
    private val tree: Map<String, List<Array<String>>> = mapOf(
        "root" to listOf(
            arrayOf("id-a", "a.txt", "text/plain"),
            arrayOf("id-sub", "sub", DIR),
        ),
        "id-sub" to listOf(
            arrayOf("id-b", "b.bin", "application/octet-stream"),
            arrayOf("id-deep", "deep", DIR),
        ),
        "id-deep" to listOf(arrayOf("id-c", "c.txt", "text/plain")),
    )

    private fun freshCache() = mutableMapOf("/" to "root")

    @Test
    fun `root path resolves without any query`() {
        val queried = mutableListOf<String>()
        val id = resolveSafDocId("/", "root", freshCache()) { queried.add(it); cursorOf(tree[it]!!) }
        assertEquals("root", id)
        assertEquals(0, queried.size)
    }

    @Test
    fun `resolves a nested path querying one directory per level`() {
        val queried = mutableListOf<String>()
        val id = resolveSafDocId("/sub/deep", "root", freshCache()) { queried.add(it); cursorOf(tree[it]!!) }
        assertEquals("id-deep", id)
        // Walked root (find "sub") then id-sub (find "deep") — two levels, two queries.
        assertEquals(listOf("root", "id-sub"), queried)
    }

    @Test
    fun `memoises ids seen while walking so a deeper sibling costs one more query`() {
        val cache = freshCache()
        val queried = mutableListOf<String>()
        val q: (String) -> Cursor = { queried.add(it); cursorOf(tree[it]!!) }

        resolveSafDocId("/sub", "root", cache, q)             // queries: root
        queried.clear()
        val id = resolveSafDocId("/sub/deep/c.txt", "root", cache, q)

        assertEquals("id-c", id)
        // "/sub" already cached from the first call; only id-sub and id-deep get queried now.
        assertEquals(listOf("id-sub", "id-deep"), queried)
    }

    @Test
    fun `returns null for a missing segment`() {
        val id = resolveSafDocId("/sub/nope", "root", freshCache()) { cursorOf(tree[it] ?: emptyList()) }
        assertNull(id)
    }

    @Test
    fun `caches leaf ids for reuse`() {
        val cache = freshCache()
        resolveSafDocId("/sub/deep/c.txt", "root", cache) { cursorOf(tree[it]!!) }
        assertEquals("id-c", cache["/sub/deep/c.txt"])
        assertEquals("id-sub", cache["/sub"])
        assertEquals("id-a", cache["/a.txt"])
    }

    @Test
    fun `normalizeSafPath canonicalises empty, root and trailing slashes`() {
        assertEquals("/", normalizeSafPath(""))
        assertEquals("/", normalizeSafPath("/"))
        assertEquals("/foo", normalizeSafPath("/foo/"))
        assertEquals("/foo/bar", normalizeSafPath("foo/bar"))
        assertEquals("/foo/bar", normalizeSafPath("/foo/bar"))
    }
}
