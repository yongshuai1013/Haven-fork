package sh.haven.core.security

/**
 * POSIX single-quote escape: wraps [s] in `'...'` and rewrites every `'`
 * inside as `'\''`. Safe for any byte sequence — including spaces, `$`,
 * backticks, backslashes and newlines — when pasted into a POSIX shell.
 */
fun posixShellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
