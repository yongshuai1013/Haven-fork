package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Test

class PosixShellQuoteTest {

    @Test fun emptyString() {
        assertEquals("''", posixShellQuote(""))
    }

    @Test fun plainPath() {
        assertEquals("'/tmp/file.txt'", posixShellQuote("/tmp/file.txt"))
    }

    @Test fun pathWithSpaces() {
        assertEquals("'/tmp/my file.txt'", posixShellQuote("/tmp/my file.txt"))
    }

    @Test fun singleQuoteInside() {
        assertEquals("'it'\\''s.txt'", posixShellQuote("it's.txt"))
    }

    @Test fun multipleSingleQuotes() {
        assertEquals("''\\'''\\'''", posixShellQuote("''"))
    }

    @Test fun shellMetacharsArePreserved() {
        assertEquals("'\$HOME/`id`/\\n'", posixShellQuote("\$HOME/`id`/\\n"))
    }

    @Test fun rcloneRemoteReference() {
        assertEquals("'gdrive:Photos/holiday 2025/IMG_001.jpg'",
            posixShellQuote("gdrive:Photos/holiday 2025/IMG_001.jpg"))
    }
}
