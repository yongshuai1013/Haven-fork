package sh.haven.core.ssh

/**
 * Which SSH protocol implementation a connection uses (#58).
 *
 * Selected per-connection via the `HavenSshEngine` directive in the
 * profile's SSH Options (`HavenSshEngine sshlib`) — a Haven-internal
 * directive that [SshOptionsApplier] filters out before JSch config is
 * applied. Absent/unknown values fall back to [JSCH], so a hand-mangled
 * directive can never change engines silently.
 */
enum class SshEngine {
    /** mwiede JSch fork — the default engine. */
    JSCH,

    /** org.connectbot.sshlib (ssh-proto) — experimental, SFTP only for now. */
    SSHLIB,
}

private const val ENGINE_DIRECTIVE = "havensshengine"

/** Engine choice carried by this config's SSH Options; [SshEngine.JSCH] unless valid. */
val ConnectionConfig.sshEngine: SshEngine
    get() {
        val value = sshOptions.entries
            .firstOrNull { it.key.trim().lowercase() == ENGINE_DIRECTIVE }
            ?.value?.trim() ?: return SshEngine.JSCH
        return if (value.equals("sshlib", ignoreCase = true)) SshEngine.SSHLIB else SshEngine.JSCH
    }
