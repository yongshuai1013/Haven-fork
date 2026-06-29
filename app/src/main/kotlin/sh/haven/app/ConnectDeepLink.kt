package sh.haven.app

import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.db.entities.ConnectionProfile

/**
 * Pure parsing + saved-profile matching for the `haven://connect` deep link
 * (#305). Kept free of `android.net.Uri` (the caller adapts it via the
 * [parse] query getter) so the parameter handling and host matching are
 * unit-testable on the JVM.
 *
 * Behaviour: a link identifies a host (plus optional user/port/transport).
 * If exactly one saved profile matches, we connect it (via a confirm step);
 * otherwise — no match or ambiguous — we open the New-Connection editor
 * pre-filled, since a deep link can't carry credentials and shouldn't
 * silently create a profile.
 */
object ConnectDeepLink {

    data class Params(
        val host: String,
        val username: String?,
        val port: Int?,
        /** `ssh` / `mosh` / `et`, or null when the link didn't specify one. */
        val transport: String?,
        val session: String?,
    )

    /**
     * Build [Params] from a query-parameter getter (e.g. `uri::getQueryParameter`).
     * Returns null when no `host` is present — a connect link without a host
     * is a no-op rather than an error.
     */
    fun parse(query: (String) -> String?): Params? {
        val host = query("host")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Params(
            host = host,
            username = query("user")?.trim()?.takeIf { it.isNotEmpty() },
            port = query("port")?.trim()?.toIntOrNull(),
            transport = query("transport")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            session = query("session")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** Whether [profile] is the SSH-family transport named by [transport]. */
    fun matchesTransport(profile: ConnectionProfile, transport: String): Boolean =
        when (transport) {
            "mosh" -> profile.connectionType == "SSH" && profile.useMosh
            "et", "eternal", "eternalterminal" -> profile.connectionType == "SSH" && profile.useEternalTerminal
            // Plain "ssh" matches any SSH-family profile for the host (incl.
            // mosh/ET-enabled ones) rather than excluding them on a technicality.
            "ssh" -> profile.connectionType == "SSH"
            else -> true
        }

    /** Saved profiles whose host (and any supplied user/port/transport) match [p]. */
    fun matches(profiles: List<ConnectionProfile>, p: Params): List<ConnectionProfile> =
        profiles.filter { prof ->
            prof.host.equals(p.host, ignoreCase = true) &&
                (p.username == null || prof.username.equals(p.username, ignoreCase = true)) &&
                (p.port == null || prof.port == p.port) &&
                (p.transport == null || matchesTransport(prof, p.transport))
        }

    /**
     * The command to emit for [p]: connect a single matched profile, otherwise
     * (zero or ambiguous matches) open the pre-filled New-Connection editor.
     */
    fun resolve(profiles: List<ConnectionProfile>, p: Params): AgentUiCommand {
        val match = matches(profiles, p).singleOrNull()
        return if (match != null) {
            AgentUiCommand.ConnectFromDeepLink(match.id, p.session)
        } else {
            AgentUiCommand.PrefillNewConnection(p.host, p.username, p.port, p.transport ?: "ssh", p.session)
        }
    }
}
