package sh.haven.app.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.desktop.DesktopInputHandle
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.TunnelConfigType
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.SnippetOps
import sh.haven.core.data.preferences.ToolbarItem
import sh.haven.core.data.preferences.ToolbarLayout
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.ffmpeg.TranscodeCommand
import sh.haven.core.local.WaylandSocketHelper
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.RcloneConfigParseResult
import sh.haven.core.rclone.RcloneConfigParser
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tool implementations for the MCP agent transport.
 *
 * Every tool:
 * 1. Has a name, a human description, and a JSON Schema for its
 *    arguments, discoverable via `tools/list`.
 * 2. Runs its work on the same repositories and session managers
 *    the UI uses, so "what the agent sees" and "what the user sees"
 *    come from the same source of truth.
 * 3. Returns a JSON object that both serialises to text (for MCP's
 *    `content[].text` field) and is structured (for
 *    `structuredContent`).
 *
 * v1 is read-only. Mutating tools (convert, upload, disconnect,
 * delete, add-port-forward, ...) will ship in v2 behind a per-action
 * consent mechanism that lives in the UI.
 */
internal class McpTools(
    private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val reticulumSessionManager: sh.haven.core.reticulum.ReticulumSessionManager,
    private val reticulumForwardServer: sh.haven.core.reticulum.ReticulumForwardServer,
    private val rcloneClient: RcloneClient,
    private val mailSessionManager: sh.haven.core.mail.MailSessionManager,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
    private val workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val portKnocker: sh.haven.core.knock.PortKnocker,
    private val spaSender: sh.haven.core.spa.SpaSender,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
    private val mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
    private val agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
    private val usbIpServer: sh.haven.core.usb.UsbIpServer,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
    // Capture + drive Haven's OWN rendered UI for the self-hosting loop (§1a).
    private val havenUiBridge: HavenUiBridge,
    // Tier-3 standing policies: the create/list/revoke tools persist here;
    // enforcement happens in McpServer via StandingPolicyEnforcer.
    private val standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
    private val mcpTunnelManager: McpTunnelManager,
    private val mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
    // Pending password/passphrase fallback prompt, mirrored from
    // ConnectionsViewModel so get_pending_auth_prompt / answer_auth_prompt can
    // observe and answer it without a human tap. Defaulted so tests that don't
    // exercise those verbs construct McpTools without supplying it.
    private val pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder =
        sh.haven.core.data.agent.PendingAuthPromptHolder(),
    // Pending session-manager picker, mirrored from ConnectionsViewModel so
    // get_pending_session_picker / answer_session_picker can observe and answer
    // it without a human tap. Defaulted like the auth-prompt holder above.
    private val sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder =
        sh.haven.core.data.agent.SessionSelectionHolder(),
    /**
     * The MCP HTTP server's live bound port. Evaluated lazily so the
     * reverse-tunnel auto-detect follows an 8731+ fallback instead of a
     * hardcoded 8730. Defaults to the conventional port for tests.
     */
    private val mcpPortProvider: () -> Int = { MCP_REVERSE_TUNNEL_PORT },
) {

    /**
     * Look up a profile's user-facing label for use in consent prompts.
     * Falls back to the profileId if the lookup fails — better to show
     * an opaque ID than to crash the prompt builder.
     *
     * Called from the synchronous summarise lambdas, so wrapping the
     * repository's `suspend fun getById` in [runBlocking] is required.
     * Profile lookups hit a small Room table and always return in <1 ms,
     * so the blocking is harmless.
     */
    private fun profileLabel(profileId: String): String =
        runBlocking {
            connectionRepository.getById(profileId)?.label
        } ?: profileId

    /**
     * Same shape as [profileLabel], for workspace ids. Used by the
     * compose_workspace consent prompt so the user sees the workspace
     * name rather than a UUID.
     */
    private fun workspaceLabel(workspaceId: String): String =
        runBlocking {
            workspaceRepository.getWorkspace(workspaceId)?.profile?.name
        } ?: workspaceId

    /**
     * USB proxy server (Slice 2). Constructed from the injected broker rather
     * than DI-wired separately, so it stays out of the McpTools/McpServer
     * constructor signature (and the test fakes). Only the usb_attach_to_guest
     * tool drives it, so a single instance here is sufficient.
     */
    private val usbProxyServer by lazy { sh.haven.core.usb.UsbProxyServer(usbBroker) }
    // usbIpServer is the DI singleton (constructor param) — shared with the
    // connection auto-forward so the two never fight over the listen port.

    /**
     * Fire-and-forget scope for agent→user *presentation* staging. present_media
     * / present_web stage the referenced file (up to [MAX_PRESENT_BYTES]) off
     * this scope so the MCP call acks immediately rather than blocking on the
     * read — a slow backend read used to outlast the agent's call window, so a
     * delivered image read back to the agent as a timeout. SupervisorJob so one
     * failed staging never cancels the next; the overlay is the real user-facing
     * artifact, so a staging failure is logged here, not surfaced to the agent.
     */
    private val presentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Auto-incrementing notification ID for raise_notification. Starts at
     * 40_000 to stay clear of the foreground-service notification IDs the
     * SSH / desktop / mcp services hand out (those use small, single-digit
     * constants). AtomicInteger so concurrent agent calls don't collide.
     */
    private val nextAgentNotificationId = AtomicInteger(40_000)

    /**
     * True once the dedicated agent.test.notifications channel has been
     * created on this process. Channel creation is idempotent, but skipping
     * the system call on subsequent posts keeps raise_notification cheap.
     */
    @Volatile
    private var agentNotificationChannelEnsured = false

    @Volatile
    private var installNotificationChannelEnsured = false

    /** Tool registry: name → handler. */
    private val tools: Map<String, ToolHandler> = linkedMapOf(
        "get_app_info" to ToolHandler(
            description = "Return Haven version, which optional features are available in this build, and mcpCarriers — which MCP transports are actually open right now (a WireGuard-collision warning if the WG carrier is shadowed by a system VPN, and whether the near/SSH carrier is currently riding a connected interactive session — see McpNearCarrier).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getAppInfo() },

        "list_paired_clients" to ToolHandler(
            description = "List the MCP clients paired with Haven — the clientInfo.name values that passed the first-connect pairing prompt and may call tools. For each: `name`; `autoApprove` (true when the user has enabled 'Skip approval prompts' for it under Settings → Agent endpoint → Paired MCP clients, so its calls bypass per-call consent); and `isCaller` (true for the client making this request). Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listPairedClients() },

        "unpair_mcp_client" to ToolHandler(
            description = "Remove a paired MCP client from Haven's allowlist. It must be approved again via a fresh pairing prompt the next time it connects, and any persistent auto-approval for it is revoked. Use list_paired_clients for exact names. Note: this gates *new* connections — a client with an already-established session may keep working until Haven restarts. The pairing allowlist is the trust boundary, so there is intentionally no MCP tool to *add* a client (that only happens through the on-device pairing prompt) or to grant a client auto-approval (that's UI-only). Gated by consent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Exact clientInfo.name to un-pair, as shown by list_paired_clients.")
                    })
                })
                put("required", JSONArray().put("name"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Un-pair MCP client '${args.optString("name", "?")}' from Haven?" },
        ) { args -> unpairMcpClient(args) },

        "list_connections" to ToolHandler(
            description = "List saved connection profiles (SSH, Mosh, VNC, RDP, SMB, rclone, local, Reticulum). Secrets like passwords and keys are redacted.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listConnections() },

        "list_sessions" to ToolHandler(
            description = "List currently registered sessions across all transports (ssh, mosh, et, reticulum, rdp, smb, local, mail) with sessionId, profileId, label, status (connecting, connected, reconnecting, disconnected, error), and transport. SSH sessions additionally include sessionManager, channel state, jump-session linkage, and active port forwards.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSessions() },

        "list_desktop_sessions" to ToolHandler(
            description = "List open remote-desktop tabs (VNC/RDP/SPICE) by connection profile, with their live status (connecting, connected, error). These are Desktop-screen tabs, not transport sessions — a VNC/RDP/SPICE-over-SSH desktop has its SSH tunnel in list_sessions and its own connect state here. Use after connect_profile to confirm a desktop reached 'connected', and after disconnect_profile to confirm the tab is gone (profile absent from the list).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listDesktopSessions() },

        "list_rclone_remotes" to ToolHandler(
            description = "List rclone cloud storage remotes configured in Haven.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listRcloneRemotes() },

        "list_mail_rules" to ToolHandler(
            description = "List inbound-email automation rules (Mail Rules). Returns each rule's id, name, enabled, orderIndex, accountProfileId (null=any), folderId, criteria, actions, lastFiredAt. Read-only.",
            inputSchema = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
            consentLevel = ConsentLevel.NEVER,
        ) { listMailRules() },

        "create_mail_rule" to ToolHandler(
            description = "Create an inbound-email automation rule: when a message in folderId (default INBOX) of accountProfileId (omit = any connected email account) matches `criteria`, run the ordered `actions`. criteria = {combinator:\"ALL\"|\"ANY\", conditions:[{type, op, value}]} where type is from|to|subject|is_unread|body|has_attachment|attachment_name|attachment_mime|header and op is CONTAINS|EQUALS|REGEX|GLOB. actions = an ordered array of {type, …}: save_attachments{destProfileId,destDir,nameGlob?,mimeGlob?} | run_command{template,background?} | send_to_agent{messageTemplate,targetSessionId?} | notify{titleTemplate,bodyTemplate} | imap_filter{op: MARK_READ|MARK_UNREAD|SET_FLAGGED|UNSET_FLAGGED|MOVE|DELETE, destFolderId?} | forward{to[],template?} | invoke_mcp_tool{toolName,argsTemplateJson}. Templates may use {from} {fromName} {subject} {to} {uid}. Creating + enabling a rule is your standing authorization for its actions (they fire without a per-call prompt); destructive actions (move/delete/forward/run-command, or a non-NEVER MCP tool) are queued for foreground approval when Haven is backgrounded. Turn the master switch on with set_preference mail_automation_enabled=true.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply { put("type", "string"); put("description", "Human label for the rule.") })
                    put("accountProfileId", JSONObject().apply { put("type", "string"); put("description", "EMAIL profile id to watch; omit for any connected email account.") })
                    put("folderId", JSONObject().apply { put("type", "string"); put("description", "Folder to watch (default INBOX).") })
                    put("criteria", JSONObject().apply { put("type", "object"); put("description", "{combinator, conditions:[…]} — see the tool description.") })
                    put("actions", JSONObject().apply { put("type", "array"); put("description", "Ordered actions — see the tool description.") })
                    put("enabled", JSONObject().apply { put("type", "boolean"); put("description", "Default true.") })
                    put("orderIndex", JSONObject().apply { put("type", "integer"); put("description", "Evaluation order; lower runs first.") })
                    put("stopOnMatch", JSONObject().apply { put("type", "boolean"); put("description", "Stop evaluating later rules when this one matches.") })
                    put("notifyOnFire", JSONObject().apply { put("type", "boolean"); put("description", "Raise a notification each time the rule fires.") })
                })
                put("required", JSONArray().put("name").put("criteria").put("actions"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Create mail rule \"${args.optString("name")}\" (grants standing authorization for its actions)?" },
        ) { args -> createMailRule(args) },

        "delete_mail_rule" to ToolHandler(
            description = "Delete a Mail Rule by id (see list_mail_rules).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Rule id from list_mail_rules.") })
                })
                put("required", JSONArray().put("id"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Delete mail rule ${args.optString("id")}?" },
        ) { args -> deleteMailRule(args) },

        "get_mail_automation_status" to ToolHandler(
            description = "Mail-Rules automation status: master switch, rule counts, recent firings (the audit log), and destructive actions queued for foreground approval. Read-only.",
            inputSchema = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
            consentLevel = ConsentLevel.NEVER,
        ) { getMailAutomationStatus() },

        "poke_mail_watch" to ToolHandler(
            description = "Force a Mail-Rules poll cycle now instead of waiting for the periodic timer (for testing/immediacy). No-op when the master switch is off. Returns { poked }.",
            inputSchema = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) },
            consentLevel = ConsentLevel.NEVER,
        ) {
            mailWatchManager.pokeNow()
            JSONObject().put("poked", true)
        },

        "list_mail_folders" to ToolHandler(
            description = "List folders/labels for a connected EMAIL profile (IMAP/Gmail or Proton). Pass profileId (from list_connections). The profile must already be connected (connect_profile first). Returns each folder's id, name, type, and role (inbox/sent/trash/…). Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "EMAIL connection profile id.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listMailFolders(args) },

        "list_mail_messages" to ToolHandler(
            description = "List message envelopes in a folder of a connected EMAIL profile. Pass profileId and folderId (default '0'/INBOX; see list_mail_folders). Returns id, subject, from, unread, time, numAttachments per message, newest first. Page with limit (default 100) + offset (skip from the newest end; offset = page*limit walks older). Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "EMAIL connection profile id.")
                    })
                    put("folderId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Folder/label id (default '0'/INBOX).")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max envelopes to return (default 100, 1..500).")
                    })
                    put("offset", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Skip this many from the newest end (default 0) — page older with offset = page*limit.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listMailMessages(args) },

        "read_mail_message" to ToolHandler(
            description = "Fetch one message from a connected EMAIL profile (IMAP/Gmail or Proton; Proton messages are decrypted), returning parsed headers (from, to[], cc[] — cc enables reply-all) and plain-text body (HTML is stripped; remote content is never loaded). Pass profileId and messageId (from list_mail_messages). Each attachment carries an { index, filename, mimeType, sizeBytes, isInline } — pass the index to save_mail_attachment to write its bytes to any connected filesystem. Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "EMAIL connection profile id.")
                    })
                    put("messageId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Message id from list_mail_messages.")
                    })
                })
                put("required", JSONArray().put("profileId").put("messageId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readMailMessage(args) },

        "send_mail" to ToolHandler(
            description = "Send a plain-text email from a connected EMAIL profile. Pass profileId (from list_connections; connect_profile first), to (array of recipient addresses, at least one), subject, and body (plain text). Optional cc/bcc arrays. Optional attachments: an array of { profileId, path } files on any connected backend (\"local\" or a connected profile id) to attach. To reply in-thread, pass inReplyToMessageId (a messageId from list_mail_messages) — the engine sets In-Reply-To/References from that message so the reply threads (set your own \"Re: …\" subject). Returns { sent, messageId, appendedToSent }. IMAP/SMTP only — Proton send is not yet implemented and returns an error. Side-effectful: prompts for consent on every call and is recorded in the connection log.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "EMAIL connection profile id.")
                    })
                    put("to", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Recipient email addresses (at least one).")
                    })
                    put("subject", JSONObject().apply {
                        put("type", "string")
                        put("description", "Subject line.")
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "Plain-text message body.")
                    })
                    put("cc", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional Cc addresses.")
                    })
                    put("bcc", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional Bcc addresses.")
                    })
                    put("inReplyToMessageId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional: messageId (from list_mail_messages) this is a reply to — threads via In-Reply-To/References.")
                    })
                    put("attachments", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("profileId", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Backend profile id holding the file, or \"local\".")
                                })
                                put("path", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "Absolute path of the file to attach on that backend.")
                                })
                            })
                            put("required", JSONArray().put("profileId").put("path"))
                        })
                        put("description", "Optional files to attach, each { profileId, path } on a connected backend.")
                    })
                })
                put("required", JSONArray().put("profileId").put("to").put("subject").put("body"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("to")?.length() ?: 0
                val na = args.optJSONArray("attachments")?.length() ?: 0
                "Send email to $n recipient(s) — \"${args.optString("subject")}\" — " +
                    "from \"${profileLabel(args.optString("profileId"))}\"" +
                    (if (na > 0) " with $na attachment(s)" else "") + "?"
            },
        ) { args -> sendMail(args) },

        "save_mail_attachment" to ToolHandler(
            description = "Save one attachment from a message on a connected EMAIL profile to any connected filesystem (local, SFTP, SMB, rclone, Reticulum). Pass profileId + messageId + attachmentIndex (the index from read_mail_message), and the destination as destProfileId (\"local\" or any connected profile id) + destPath (a directory). Optional destFilename overrides the saved name. The file is named after the attachment (sanitised); a collision gets \" (1)\", \" (2)\", … Returns { saved, destProfileId, backend, destPath, filename, bytes }. Works for both IMAP and Proton. Writes a file — prompts for consent on every call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Source EMAIL connection profile id.")
                    })
                    put("messageId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Message id from list_mail_messages.")
                    })
                    put("attachmentIndex", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Attachment index from read_mail_message.")
                    })
                    put("destProfileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Destination backend profile id, or \"local\" for the device filesystem.")
                    })
                    put("destPath", JSONObject().apply {
                        put("type", "string")
                        put("description", "Destination directory on the chosen backend.")
                    })
                    put("destFilename", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional name to save as (defaults to the attachment's own filename).")
                    })
                })
                put("required", JSONArray().put("profileId").put("messageId").put("attachmentIndex").put("destProfileId").put("destPath"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Save attachment #${args.optInt("attachmentIndex")} from \"${profileLabel(args.optString("profileId"))}\" " +
                    "to ${args.optString("destPath")} on \"${profileLabel(args.optString("destProfileId"))}\"?"
            },
        ) { args -> saveMailAttachment(args) },

        "modify_mail_message" to ToolHandler(
            description = "Mutate one message on a connected EMAIL profile: mark read/unread, flag/unflag (star), move to another folder, copy/apply-a-label, or delete. Pass profileId + messageId (from list_mail_messages) + op (mark_read | mark_unread | flag | unflag | move | copy | delete). op=move and op=copy also require destFolderId (a folder id from list_mail_folders). IMAP/Gmail only — the Proton engine returns 501. On Gmail: move relabels (removes the source label, adds dest); copy is additive — it applies the dest label and KEEPS the message in its current folders (use copy to label without archiving from Inbox); delete moves to Trash. Returns { ok, op, messageId }. Side-effectful — prompts for consent on every call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "EMAIL connection profile id.")
                    })
                    put("messageId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Message id from list_mail_messages.")
                    })
                    put("op", JSONObject().apply {
                        put("type", "string")
                        put("description", "mark_read | mark_unread | flag | unflag | move | copy | delete")
                    })
                    put("destFolderId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Destination folder/label id (from list_mail_folders) — required when op=move or op=copy.")
                    })
                })
                put("required", JSONArray().put("profileId").put("messageId").put("op"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val op = args.optString("op")
                val dest = args.optString("destFolderId").ifBlank { null }
                "Mail: $op message on \"${profileLabel(args.optString("profileId"))}\"" +
                    (if ((op == "move" || op == "copy") && dest != null) " → $dest" else "") + "?"
            },
        ) { args -> modifyMailMessage(args) },

        "search_mail" to ToolHandler(
            description = "Server-side search of a folder on a connected EMAIL profile. Pass profileId, optional folderId (default INBOX; see list_mail_folders), and one or more criteria: from, to, subject, body (substring matches), unreadOnly (bool), sinceEpochSec / beforeEpochSec (Unix seconds, day granularity). Criteria are ANDed; at least one is required. Optional limit (default 100, 1..500). Returns the same envelope shape as list_mail_messages (newest first) — feed ids into read_mail_message / modify_mail_message. IMAP/Gmail only — Proton returns 501. Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "EMAIL connection profile id.") })
                    put("folderId", JSONObject().apply { put("type", "string"); put("description", "Folder/label id to search (default INBOX).") })
                    put("from", JSONObject().apply { put("type", "string"); put("description", "Match sender address/name (substring).") })
                    put("to", JSONObject().apply { put("type", "string"); put("description", "Match a To recipient (substring).") })
                    put("subject", JSONObject().apply { put("type", "string"); put("description", "Match subject (substring).") })
                    put("body", JSONObject().apply { put("type", "string"); put("description", "Match body text (substring).") })
                    put("unreadOnly", JSONObject().apply { put("type", "boolean"); put("description", "Only unread (no \\Seen) messages.") })
                    put("sinceEpochSec", JSONObject().apply { put("type", "integer"); put("description", "On/after this Unix-seconds date (day granularity).") })
                    put("beforeEpochSec", JSONObject().apply { put("type", "integer"); put("description", "On/before this Unix-seconds date (day granularity).") })
                    put("limit", JSONObject().apply { put("type", "integer"); put("description", "Max results (default 100, 1..500).") })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> searchMail(args) },

        "save_mail_draft" to ToolHandler(
            description = "Save a draft (NOT sent) to the account's Drafts folder on a connected EMAIL profile — use to compose a message for the user to review/send later. Same fields as send_mail (to/cc/bcc/subject/body, optional attachments, optional inReplyToMessageId to thread) but all are optional — a draft may be incomplete. Returns { saved, draftFolderId }. IMAP/Gmail only — Proton returns 501. Writes to the mailbox — prompts for consent on every call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "EMAIL connection profile id.") })
                    put("to", JSONObject().apply { put("type", "array"); put("items", JSONObject().put("type", "string")); put("description", "Recipient addresses (optional for a draft).") })
                    put("subject", JSONObject().apply { put("type", "string"); put("description", "Subject line.") })
                    put("body", JSONObject().apply { put("type", "string"); put("description", "Plain-text body.") })
                    put("cc", JSONObject().apply { put("type", "array"); put("items", JSONObject().put("type", "string")); put("description", "Optional Cc addresses.") })
                    put("bcc", JSONObject().apply { put("type", "array"); put("items", JSONObject().put("type", "string")); put("description", "Optional Bcc addresses.") })
                    put("inReplyToMessageId", JSONObject().apply { put("type", "string"); put("description", "Optional: messageId this draft replies to — threads via In-Reply-To/References.") })
                    put("attachments", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("profileId", JSONObject().apply { put("type", "string"); put("description", "Backend profile id holding the file, or \"local\".") })
                                put("path", JSONObject().apply { put("type", "string"); put("description", "Absolute path of the file on that backend.") })
                            })
                            put("required", JSONArray().put("profileId").put("path"))
                        })
                        put("description", "Optional files to attach, each { profileId, path }.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Save a draft to \"${profileLabel(args.optString("profileId"))}\" — \"${args.optString("subject")}\"?" },
        ) { args -> saveMailDraft(args) },

        "create_mail_folder" to ToolHandler(
            description = "Create a new folder/label on a connected EMAIL profile (IMAP CREATE; on Gmail this is a new label). Pass profileId + name (use the server's hierarchy separator for nesting, e.g. \"Work/2026\"). Returns { created, folderId } — use folderId as a destination for modify_mail_message move/copy. Fails if it already exists. IMAP/Gmail only — Proton returns 501. Changes the mailbox — prompts for consent on every call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "EMAIL connection profile id.") })
                    put("name", JSONObject().apply { put("type", "string"); put("description", "New folder/label name (e.g. \"Receipts\" or \"Work/2026\").") })
                })
                put("required", JSONArray().put("profileId").put("name"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Create mail folder/label \"${args.optString("name")}\" on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> createMailFolder(args) },

        "delete_mail_folder" to ToolHandler(
            description = "Delete a folder/label on a connected EMAIL profile (IMAP DELETE). Pass profileId + folderId (from list_mail_folders). On Gmail this removes the LABEL — messages survive in All Mail; on a plain IMAP server it deletes the mailbox AND its messages (destructive). System folders (Inbox/Sent/Drafts/Trash/Spam/All Mail/…) are refused. Returns { deleted, folderId }. IMAP/Gmail only — Proton returns 501. Destructive — prompts for consent on every call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "EMAIL connection profile id.") })
                    put("folderId", JSONObject().apply { put("type", "string"); put("description", "Folder/label id to delete (from list_mail_folders). System folders are refused.") })
                })
                put("required", JSONArray().put("profileId").put("folderId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "DELETE mail folder/label \"${args.optString("folderId")}\" on \"${profileLabel(args.optString("profileId"))}\"? (On Gmail removes the label; on other IMAP deletes the folder + its messages.)" },
        ) { args -> deleteMailFolder(args) },

        "list_rclone_provider_options" to ToolHandler(
            description = "List a credentials-based rclone provider's basic config fields — the non-advanced options needed to configure a non-OAuth remote (ftp, sftp, webdav, s3, b2, mega, filen, …). Each entry has name, help, required, isPassword, default, type. Feed the collected values into configure_rclone_remote's `parameters`. OAuth providers (drive, dropbox, onedrive, box, pcloud) are configured via the in-app browser sign-in, not this. (#181)",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("provider", JSONObject().apply {
                        put("type", "string")
                        put("description", "rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.")
                    })
                })
                put("required", JSONArray().put("provider"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> listRcloneProviderOptions(args) },

        "configure_rclone_remote" to ToolHandler(
            description = "Create (or replace) a credentials-based rclone remote and verify it by listing the root. Pass remoteName, provider (ftp/sftp/webdav/s3/b2/mega/filen/…), and parameters — an option→value map (see list_rclone_provider_options; rclone obscures password fields server-side). For OAuth providers (drive/dropbox/…) use the in-app browser sign-in instead. Returns { created, verified, entryCount } or an error. Makes the remote usable by the rclone list/sync tools. (#181)",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("remoteName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name for the remote in rclone.conf, e.g. 'myftp'. Replaces an existing remote of the same name.")
                    })
                    put("provider", JSONObject().apply {
                        put("type", "string")
                        put("description", "rclone provider/type, e.g. 'ftp', 'sftp', 's3', 'filen'.")
                    })
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("description", "Option→value map for the provider (from list_rclone_provider_options), e.g. {\"host\":\"…\",\"user\":\"…\",\"pass\":\"…\",\"port\":\"2121\"}. Password fields are obscured by rclone.")
                    })
                })
                put("required", JSONArray().put("remoteName").put("provider").put("parameters"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Configure rclone remote \"${args.optString("remoteName")}\" (${args.optString("provider")})?"
            },
        ) { args -> configureRcloneRemote(args) },

        "delete_rclone_remote" to ToolHandler(
            description = "Delete an rclone remote from rclone.conf by name, and any RCLONE connection profile that references it. The inverse of configure_rclone_remote / the rclone-config import — use it to clean up a remote (e.g. a test or a failed/ghost import that left a remote with no usable profile). Returns { deleted, remoteName, removedProfiles }. deleted is false if no such remote existed (matching profiles are still removed).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("remoteName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Remote name in rclone.conf (see list_rclone_remotes).")
                    })
                })
                put("required", JSONArray().put("remoteName"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete rclone remote \"${args.optString("remoteName")}\" and any profile using it?" },
        ) { args -> deleteRcloneRemote(args) },

        "import_rclone_config" to ToolHandler(
            description = "Import remotes from a Linux rclone.conf (the headless equivalent of the in-app Import rclone config dialog, #269). Pass configText (the file contents). Each chosen remote becomes an rclone remote (token/creds copied verbatim, non-interactively — OAuth remotes don't block on the browser flow) plus a matching RCLONE connection profile; a half-created remote is rolled back on failure so a failed import leaves no ghost. Skips typeless sections and names already configured. Optional `names` limits the import to those remote names. Returns { created, skipped, failed }. Returns an error if the config is password-encrypted.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("configText", JSONObject().apply {
                        put("type", "string")
                        put("description", "Contents of an rclone.conf to import.")
                    })
                    put("names", JSONObject().apply {
                        put("type", "array")
                        put("description", "Optional: only import remotes with these names. Omit to import all importable remotes.")
                    })
                })
                put("required", JSONArray().put("configText"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Import rclone remotes from a pasted rclone.conf?" },
        ) { args -> importRcloneConfig(args) },

        "update_rclone_remote" to ToolHandler(
            description = "Update an existing rclone remote's config in place (rclone config/update) — unlike configure_rclone_remote, which replaces the whole remote. Pass remoteName and a parameters option→value map of just the fields to change (rclone obscures password fields). Returns { updated, remoteName }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("remoteName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Existing remote name (see list_rclone_remotes).")
                    })
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("description", "Option→value map of fields to change, e.g. {\"host\":\"…\"}.")
                    })
                })
                put("required", JSONArray().put("remoteName").put("parameters"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Update rclone remote \"${args.optString("remoteName")}\"?" },
        ) { args -> updateRcloneRemote(args) },

        "list_directory" to ToolHandler(
            description = "List entries at a path on any connected backend (local, SSH/SFTP, SMB, rclone). Resolves the right driver from profileId — pass the literal string 'local' for the device filesystem, otherwise a profile ID from list_connections. Returns name, path, isDir, size, modTime, permissions, and mimeType for each entry. Replaces list_sftp_directory and list_rclone_directory; those still work as deprecated aliases.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path to list. Default '/' (POSIX backends), '' (rclone root), '/' for local synthetic-roots view.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> listDirectory(args) },

        "list_rclone_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files and subdirectories at a given path on an rclone remote. Returns name, isDir, size, mimeType, and modTime for each entry.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("remote", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name of the rclone remote, e.g. 'gdrive'.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Path within the remote, relative to the remote root. Use '' for the root.")
                    })
                })
                put("required", JSONArray().put("remote"))
            },
        ) { args -> listRcloneDirectory(args) },

        "start_rclone_sync" to ToolHandler(
            description = "Start an async rclone transfer between two remote paths. mode=copy adds new/updated files (no deletes); mode=sync (a.k.a. \"Mirror\" in the UI) makes destination identical to source and deletes extras; mode=move copies then removes source files. srcFs/dstFs use rclone's remote-prefixed notation, e.g. \"gdrive:Backup/Photos\" or \"gdrive:\" for the remote root. Returns { jobId, mode } — poll get_rclone_sync_status to read finished/success and the transfer/delete counters. Honours the same optional filter and dryRun fields the SFTP sync dialog exposes.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("srcFs", JSONObject().apply { put("type", "string"); put("description", "Source remote path, e.g. \"gdrive:Backup/Photos\".") })
                    put("dstFs", JSONObject().apply { put("type", "string"); put("description", "Destination remote path, e.g. \"gdrive:Mirror/Photos\".") })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("copy").put("sync").put("move"))
                        put("description", "copy = add/update only, sync = mirror (deletes extras in dst), move = copy then delete from src.")
                    })
                    put("dryRun", JSONObject().apply { put("type", "boolean"); put("description", "If true, simulate without writing.") })
                    put("includePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional include globs, e.g. [\"*.jpg\"].")
                    })
                    put("excludePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("description", "Optional exclude globs.")
                    })
                    put("minSize", JSONObject().apply { put("type", "string"); put("description", "Optional minimum file size, e.g. \"1K\", \"5M\".") })
                    put("maxSize", JSONObject().apply { put("type", "string"); put("description", "Optional maximum file size.") })
                    put("bandwidthLimit", JSONObject().apply { put("type", "string"); put("description", "Optional bandwidth limit, e.g. \"10M\".") })
                })
                put("required", JSONArray().put("srcFs").put("dstFs").put("mode"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val mode = args.optString("mode", "?")
                val src = args.optString("srcFs", "?")
                val dst = args.optString("dstFs", "?")
                val dry = if (args.optBoolean("dryRun")) " (dry run)" else ""
                "rclone $mode$dry: $src → $dst?"
            },
        ) { args -> startRcloneSync(args) },

        "get_rclone_sync_status" to ToolHandler(
            description = "Poll the status of an async rclone job started by start_rclone_sync. Returns finished/success/error plus live transfer stats: bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs. If jobId is omitted, reports on the most recently started job (or returns active=false if none).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("jobId", JSONObject().apply { put("type", "integer"); put("description", "Job id returned by start_rclone_sync. Optional — defaults to the active job.") })
                })
            },
        ) { args -> getRcloneSyncStatus(args) },

        "cancel_rclone_sync" to ToolHandler(
            description = "Cancel a running rclone job started by start_rclone_sync. If jobId is omitted, cancels the active job. No-op if the job is already finished or never existed.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("jobId", JSONObject().apply { put("type", "integer"); put("description", "Job id to cancel. Optional — defaults to the active job.") })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Cancel the running rclone sync job?" },
        ) { args -> cancelRcloneSync(args) },

        "get_rclone_stats" to ToolHandler(
            description = "Return rclone's global transfer counters (bytes, totalBytes, speed, transfers, totalTransfers, errors, deletes, deletedDirs). These reset only when reset_rclone_stats is called or a new sync resets them at start.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getRcloneStats() },

        "reset_rclone_stats" to ToolHandler(
            description = "Reset rclone's global transfer counters to zero. Useful when running ad-hoc operations outside start_rclone_sync (which already resets on start).",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> resetRcloneStats() },

        "list_saved_sync_profiles" to ToolHandler(
            description = "List the user's saved rclone sync configurations (#159) — the named src/dst/mode/filters bundles surfaced in the SFTP folder-sync dialog's dropdown. Returns id, name, srcFs, dstFs, mode (copy/sync/move), include/exclude patterns, optional minSize/maxSize/bandwidthLimit, createdAt, lastRunAt. Sorted most-recently-run first.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSavedSyncProfiles() },

        "save_sync_profile" to ToolHandler(
            description = "Create or update a named rclone sync configuration (#159). Pass an `id` to overwrite an existing one; omit it to create. mode accepts copy/sync/move (or \"mirror\" as a sync alias). includePatterns/excludePatterns are arrays of glob strings. Returns the saved profile's id and the full resolved fields. Mutates Haven state, gated by EVERY_CALL consent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Existing profile id to overwrite. Omit to create a new one.") })
                    put("name", JSONObject().apply { put("type", "string"); put("description", "Display name shown in the dropdown.") })
                    put("srcFs", JSONObject().apply { put("type", "string"); put("description", "Source remote path, e.g. \"gdrive:Backup/Photos\".") })
                    put("dstFs", JSONObject().apply { put("type", "string"); put("description", "Destination remote path.") })
                    put("mode", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().put("copy").put("sync").put("move"))
                        put("description", "copy/sync/move; \"mirror\" is accepted as a sync alias.")
                    })
                    put("includePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    })
                    put("excludePatterns", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    })
                    put("minSize", JSONObject().apply { put("type", "string") })
                    put("maxSize", JSONObject().apply { put("type", "string") })
                    put("bandwidthLimit", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().put("name").put("srcFs").put("dstFs").put("mode"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val name = args.optString("name", "?")
                val mode = args.optString("mode", "?")
                val verb = if (args.has("id") && !args.isNull("id")) "Update" else "Save"
                "$verb \"$name\" ($mode: ${args.optString("srcFs", "?")} → ${args.optString("dstFs", "?")})?"
            },
        ) { args -> saveSyncProfile(args) },

        "queue_terminal_input" to ToolHandler(
            description = "Power-user: queue text to be typed into any connected SSH session at the next matching prompt. Haven polls the session's stdout, matches `promptPattern` against the tail (ANSI escapes stripped, regex MULTILINE), then types `text + submitKey` via the session's tty when the pattern appears. Use cases include responding to interactive prompts in install scripts (`Continue? [y/N]` → `y`, or `Path:` → `/usr/local`), driving REPLs (Python, psql, Claude Code, etc.), and chaining steps where the agent waits for one prompt then types into the next. Defaults are tuned for the common \"drive an interactive shell or REPL\" case; supply explicit `promptPattern` / `submitKey` / `sessionId` when targeting something specific. Returns immediately with a queueId; delivery happens out-of-turn whenever the prompt appears (or the queue times out). Caveat: within one SSH session, only the *foreground* tmux pane receives the typed text — for multi-pane setups, pass `sessionId` to the right session and ensure the target pane is foreground. Gated by Settings → Agent endpoint → \"Allow agents to queue terminal input\" *and* per-call consent (with an \"Allow for N min\" option for collaborative windows).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "string"); put("description", "Text to type. `submitKey` is appended automatically.") })
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "SSH session id from list_sessions. Optional — defaults to the SSH session carrying the MCP reverse tunnel on port 8730 (which, in the agent-drives-its-own-conversation case, is the session running the agent's REPL). For unrelated sessions, pass this explicitly.") })
                    put("promptPattern", JSONObject().apply { put("type", "string"); put("description", "Regex matched against the tail of the SSH scrollback to trigger delivery. Default `[\\\$#%>❯]\\s*\$` matches the trailing prompt glyph of common interactive shells (bash `\$`, root `#`, csh `%`, traditional `>`, fish/Claude Code/starship `❯`) at end-of-line. For specific programs, supply a pattern that matches their input prompt — e.g. `\\[y/N\\]\\s*\$` or `Password:\\s*\$` or `(?:postgres|mydb)=#\\s*\$`. ANSI escapes are stripped before matching; regex is MULTILINE.") })
                    put("submitKey", JSONObject().apply { put("type", "string"); put("description", "Key bytes sent after the text. Default `\\r` — what TTYs in cooked mode translate to NL, and what programs in raw mode (Claude Code, vim, less, fzf, readline-based shells) read as Enter. Use `\\n` for line-buffered programs reading stdin directly without a tty. Use `\"\"` (empty) to leave the text in the input buffer without submitting (e.g. pre-fill a prompt the user will edit).") })
                    put("timeoutSeconds", JSONObject().apply { put("type", "integer"); put("description", "Give up if the prompt hasn't appeared in this many seconds. Default 60.") })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        // Deprecated alias for queue_terminal_input — keeps clients that
        // bound against the old name working for one release. Same
        // handler, same gate, same consent shape; just routes through
        // queueTerminalInput so the implementation only lives in one
        // place. Drop after the next final release. (#161)
        "queue_self_message" to ToolHandler(
            description = "DEPRECATED: alias for `queue_terminal_input` kept for one release. Use queue_terminal_input — same arguments, same behaviour, plus a `submitKey` parameter you didn't have here.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply { put("type", "string") })
                    put("sessionId", JSONObject().apply { put("type", "string") })
                    put("promptPattern", JSONObject().apply { put("type", "string") })
                    put("timeoutSeconds", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "").take(80)
                "Queue \"$text\" to be typed into the SSH session at the next prompt?"
            },
        ) { args -> queueTerminalInput(args) },

        "delete_sync_profile" to ToolHandler(
            description = "Delete a saved rclone sync configuration by id. The dialog's dropdown updates immediately. EVERY_CALL consent because it's destructive (the user's saved config is gone after).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_saved_sync_profiles.") })
                })
                put("required", JSONArray().put("id"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete saved sync configuration ${args.optString("id")}?" },
        ) { args -> deleteSyncProfile(args) },

        "list_sftp_directory" to ToolHandler(
            description = "DEPRECATED: prefer list_directory(profileId=..., path=...). List files at a path on a connected SFTP profile. Requires an already-connected SSH/SFTP session for the profile.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connected SSH/SFTP profile.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute directory path to list. Defaults to '.'")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> listSftpDirectory(args) },

        "stream_sftp_file" to ToolHandler(
            description = "Start an HLS stream for an SFTP file and return the playlist URL. Reads via a loopback HTTP bridge so no bulk download is needed. Requires a connected SSH/SFTP session. Stops any prior HLS stream.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connected SSH/SFTP profile.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the media file on the SFTP server.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
        ) { args -> streamSftpFile(args) },

        "serve_file" to ToolHandler(
            description = "Publish a single file from any connected backend (local, SFTP, SMB, rclone) as a short-lived loopback HTTP URL the caller can curl to its own filesystem. Returns { url, size, mimeType }. Bytes are streamed over HTTP rather than returned inline through JSON-RPC. Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call by a consent prompt.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the file on the chosen backend.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val p = args.optString("path")
                "Let the agent download '$p' from '${profileLabel(pid)}' to its workstation"
            },
        ) { args -> serveFile(args) },

        "stop_stream" to ToolHandler(
            description = "Stop any currently running HLS stream started by stream_sftp_file or the UI.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> stopStream() },

        "play_file" to ToolHandler(
            description = "Open a media URL in the system player (VLC, MX Player, Chrome, etc.) via Android's ACTION_VIEW intent. Typically the playerUrl/playlistUrl returned by stream_sftp_file, or any http/https/content URL the agent already knows. The user's preferred app picker (or default app) decides what handles it — Haven only kicks off the intent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "URL or content URI to open. http://, https://, file:// (rare; prefer FileProvider URIs) and content:// are accepted.")
                    })
                    put("mimeType", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional MIME hint, e.g. 'video/mp4', 'application/vnd.apple.mpegurl' for HLS. Auto-detected from URL extension when omitted.")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> playFile(args) },

        "present_media" to ToolHandler(
            description = "Show the user an image — or play a short sound — inline in Haven. A bottom sheet floats over whatever screen the user is on, rendering the image (or an audio card with a play button) plus an optional caption. The \"here, look at / listen to this\" channel: use it when you have something visual or audible you want the user to perceive directly. Reference the media by a file Haven can reach — `profileId` (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path` — or by a ready `url` (e.g. a serve_file loopback URL). Haven streams the file into a local handle; the bytes never pass through the agent context. `mimeType` is inferred from the file (extension, else content sniff) when omitted; set it for audio. Only image/* and audio/* are supported. Returns immediately ({ presented }) as soon as the request is accepted — Haven fetches/stages the file and shows the sheet in the background, so a slow transfer can't turn a delivered image into a timeout; a staging failure is logged (not returned). The user dismisses the sheet at their leisure.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Backend holding the file: \"local\" for the device / proot-guest cache (default), or an SSH/SMB/rclone profile id. Used with `path`.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the image/audio file on that backend.")
                    })
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "Alternative to profileId+path: an http(s) URL to fetch the media from (e.g. a serve_file loopback URL).")
                    })
                    put("mimeType", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional MIME, e.g. 'image/png' or 'audio/mpeg'. Inferred from the file otherwise; set it for audio.")
                    })
                    put("caption", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional one-line caption shown above the media.")
                    })
                    put("autoPlay", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Audio only: start playback as soon as the sheet appears. Defaults to false.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> presentMedia(args) },

        "present_web" to ToolHandler(
            description = "Show the user HTML, an SVG, or a PDF inline in an in-app WebView — the interactive rung between present_media (a static image) and present_app (a full live VNC app). Pass a `url` (e.g. a serve_file loopback URL or any web page), or reference a file with `profileId` (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone profile id) + `path`, which Haven serves over a loopback URL. A PDF is paged; HTML/SVG render live (pinch-zoom + pan). Floats in a bottom sheet over whatever screen the user is on; bytes never pass through the agent context. Returns immediately: a `url` acks with { presented, id, url }; a file reference acks with { presented } and is staged/shown in the background (a staging failure is logged, not returned). The user dismisses it at their leisure.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "An http(s) URL to load (e.g. a serve_file loopback URL or any web page). Alternative to profileId+path.")
                    })
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Backend holding the file: \"local\" (device / proot-guest cache, default) or an SSH/SMB/rclone profile id. Used with `path`.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the .html/.svg/.pdf file on that backend.")
                    })
                    put("caption", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional one-line caption shown above the view.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> presentWeb(args) },

        "present_app" to ToolHandler(
            description = "Show the user a LIVE, interactive single application window inline in Haven. Launches `command` as a Wayland app under a `cage` kiosk inside the active proot guest, exposes it over VNC, and embeds the live view in a bottom sheet over whatever screen the user is on (pinch-zoom, pan, drag and fullscreen all work; the user can interact). Use this to collaborate in a real GUI app — an image viewer, a media/audio player, a PDF/whiteboard tool — rather than pushing a static image with present_media. `command` is the guest shell command cage runs (e.g. 'imv /root/board.png', 'mpv /root/clip.mp4'); the app and any Wayland deps must already be installed in the guest. Returns { presented, sessionId, vncPort, state } once the window is up. Multiple app windows can run at once: each call launches another cage; the newest is shown full-overlay and any previous one is backgrounded to a draggable edge icon (tap to bring it back). The user backgrounds a window by tapping outside it (keeps it running) and tears it down with the Dismiss button or the edge-icon close. If the window comes up grey/blank or vanishes, read the app's own stdout/stderr with read_app_window_log (works even after it crashed) instead of wrapping the command in a logging script.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Guest shell command for the GUI app cage runs, e.g. 'imv /root/x.png'.")
                    })
                    put("caption", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional one-line caption shown above the window.")
                    })
                    put("fullscreen", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Open the window filling the whole screen (immersive) instead of the bottom sheet. Default false.")
                    })
                    put("resolution", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cage display resolution: 'auto' (portrait, fills the screen — default) or a 'WxH' token like '1280x720'. Lower resolution = bigger fonts.")
                    })
                    put("scale", JSONObject().apply {
                        put("type", "number")
                        put("description", "Output scale factor (wlroots HiDPI; foot/GTK honour it). 1.0 default; 1.5/2 enlarge fonts + UI.")
                    })
                    put("runAsRoot", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Run the app as root via fakeroot-tcp (the cage compositor itself runs non-root, so system tools like package managers go read-only otherwise). Installs fakeroot if missing. APT distros only today. Default false.")
                    })
                })
                put("required", JSONArray().put("command"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Launch a GUI app window for the agent: ${args.optString("command")}"
            },
        ) { args -> presentApp(args) },

        "read_app_window_log" to ToolHandler(
            description = "Read the captured output log of a present_app cage window. The cage redirects BOTH the sway compositor AND the GUI app it runs (stdout+stderr merged) into one log, so this is how the agent SEES a present_app app's own output — startup errors, GL/Mesa diagnostics, a crash trace — without wrapping the command in a logging script. Pass the sessionId returned by present_app for a live window; OMIT it to read the most-recent app-window log, which still works after the app crashed or exited (the session is gone but the log survives on disk). Returns { sessionId?, display, bytes, truncated, log }. For a GUI app that came up then died (a grey/blank or vanished window), this is the first thing to read.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "present_app sessionId for a live window. Omit to read the newest app-window log (survives a crashed/exited app).")
                    })
                    put("maxBytes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Return at most the last N bytes of the log. Default 16384, clamped 256..262144.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
            summarise = { _ -> "Read a present_app window's output log" },
        ) { args -> readAppWindowLog(args) },

        "list_guest_apps" to ToolHandler(
            description = "List the GUI applications installed in the active proot guest, discovered from its `.desktop` files (the same source an xfce4 application menu reads). Use this to find an app to launch with `present_app` without knowing its exact command. Returns { count, iconsResolved, apps:[{ name, exec, hasIcon, categories }] } sorted by name; `exec` is the runnable guest command (field codes stripped) you pass straight to present_app's `command`. `hasIcon` indicates whether a decodable icon was resolved (icons themselves stay on-device for the launcher UI). Skips NoDisplay/Terminal/non-application entries.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { listGuestApps() },

        "raise_notification" to ToolHandler(
            description = "Post a real Android system notification on Haven's behalf so the agent can drive notification-listener / wake / DND / silencer apps during F-Droid tester reviews without needing a second device. Always posts to the dedicated 'agent.test.notifications' channel (created on first use) so the user can mute agent notifications cleanly without affecting Haven's own connection / renewal notifications. Returns { posted, id, channel } — keep the id around if you want to dismiss or replace the notification later (a future tool). Notifications use Haven's app identity, so notification-listener apps see package=sh.haven.app. Requires the POST_NOTIFICATIONS runtime grant (declared in the manifest, granted by the user on first Haven launch); the call fails with a clear error if notifications have been disabled in system settings.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "Notification title (mandatory). Shown on the lockscreen / shade row.")
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "Notification body (mandatory). Expanded into a BigTextStyle so multi-line content stays readable.")
                    })
                    put("priority", JSONObject().apply {
                        put("type", "string")
                        put("description", "One of 'min', 'low', 'default', 'high', 'max'. Maps to NotificationCompat.PRIORITY_*. Defaults to 'default'. Note: from Android 8 the channel's importance is what actually drives heads-up behaviour; priority only matters on pre-O devices and as a hint to ranking.")
                    })
                    put("ongoing", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, post as ongoing (non-dismissable) so the agent can test foreground-service-like notifications. Defaults to false (dismissable, auto-cancels on tap).")
                    })
                })
                put("required", JSONArray().put("title").put("body"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> raiseNotification(args) },

        "open_convert_dialog_with_args" to ToolHandler(
            description = "Stage a conversion in the SFTP screen's convert dialog with the given container / codec defaults. Switches to the SFTP tab and opens the dialog; the user reviews and taps Convert to actually run ffmpeg. Tap-equivalent — the agent suggests, the user confirms. Use convert_file (EVERY_CALL consent) to skip the dialog and run the conversion directly.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID. Use list_connections to find IDs.")
                    })
                    put("sourcePath", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path of the source file.")
                    })
                    put("container", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected container, e.g. 'mp4', 'mkv', 'webm', 'mp3'. Optional — defaults to source extension.")
                    })
                    put("videoEncoder", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected video codec, e.g. 'libx264', 'libx265', 'copy'. Optional — defaults to 'copy'.")
                    })
                    put("audioEncoder", JSONObject().apply {
                        put("type", "string")
                        put("description", "Pre-selected audio codec, e.g. 'aac', 'libopus', 'copy'. Optional — defaults to 'copy'.")
                    })
                })
                put("required", JSONArray().put("profileId").put("sourcePath"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openConvertDialogWithArgs(args) },

        "focus_terminal_session" to ToolHandler(
            description = "Switch to the Terminal tab and bring the session with this sessionId to the front. Tap-equivalent — same effect as the user tapping the Terminal tab and tapping the session header. Use list_sessions to discover live sessionIds; stale IDs drop silently without error.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions). Must be a session attached to a Terminal tab.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> focusTerminalSession(args) },

        "navigate_sftp_browser" to ToolHandler(
            description = "Switch to the Files tab and open the file browser at the given path on the given profile. Tap-equivalent — same effect as the user tapping into the SFTP screen and entering the path. The path is interpreted by whichever backend the profile resolves to (POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path to open. Default '/' for SSH/Local, '' for rclone (treated as remote root).")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> navigateSftpBrowser(args) },

        "open_file_in_editor" to ToolHandler(
            description = "Open a text file in Haven's built-in editor (TextMate-syntax-highlighted, with Save). Routes to the SFTP/Files tab and loads the file from the given profile's backend (SSH, SMB, rclone, or the literal \"local\" for the device filesystem). The file is read on demand by the active backend; binary files render as garbled UTF-8 — use this for source code, config, logs, etc.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID (or the literal string \"local\" for the device filesystem). Use list_connections to find IDs.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file to open. POSIX absolute for SSH/Local, share-relative for SMB, remote-relative for rclone.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openFileInEditor(args) },

        "read_terminal_scrollback" to ToolHandler(
            description = "Return the most recent bytes of raw SSH stdout for an active terminal session, exactly as the user sees them (ANSI escapes, OSC markers, control bytes preserved). Use list_sessions to discover sessionIds. The buffer is capped at 256 KiB per session and rolls older bytes off; the human terminal still keeps its own visual scrollback separately.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open.")
                    })
                    put("maxBytes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum bytes to return. Default 16384, hard-capped at 262144.")
                    })
                })
                put("required", JSONArray())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalScrollback(args) },

        "read_terminal_snapshot" to ToolHandler(
            description = "Return a structured snapshot of an active terminal session: dimensions, cursor row/col, terminal title, scrollback line count + current scrollbackPosition, the remote-driven terminal modes (mouseMode / activeMouseMode 1000|1002|1003 / bracketPasteMode), an oscEvents object with the last-seen OSC 52 clipboard-set / OSC 7 cwd / OSC 8 hyperlink / OSC 9|777 notification, and the visible-screen lines as plain text (with `softWrapped` flag per line, and optional OSC 133 semantic segments). Use list_sessions to discover sessionIds. Distinct from read_terminal_scrollback, which returns raw bytes; this is the parsed view useful for cursor-aware tooling, prompt detection, and asserting OSC/mouse-mode round-trips.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal tab.")
                    })
                    put("includeSemanticSegments", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, include each line's OSC 133 prompt-marker segments (PROMPT / COMMAND_INPUT / COMMAND_OUTPUT / COMMAND_FINISHED / ANNOTATION). Default false.")
                    })
                    put("maxLines", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of visible-screen lines to include from the top. Default returns all visible rows. Cursor and dimensions are always present regardless.")
                    })
                })
                put("required", JSONArray())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readTerminalSnapshot(args) },

        "get_selection" to ToolHandler(
            description = "Return the current text-selection state for an active terminal session: { active, mode (NONE/CHARACTER/WORD/LINE), range: { startRow, startCol, endRow, endCol } | null }. Reads termlib's SelectionController; valid only while the session has an attached terminal tab.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Active session ID with an attached terminal tab.")
                    })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getSelection(args) },

        "set_compose_mode" to ToolHandler(
            description = "Toggle or set termlib's local compose mode for a terminal session — the on-screen buffer used for CJK / accented / voice-friendly text entry. While compose mode is on, typed text (including IME-composed CJK candidates) buffers in an overlay at the cursor and the terminal hands the IME a composition-friendly InputConnection; the buffer commits to the shell on Enter, after which compose mode clears. Pass enabled=true/false to set explicitly, or omit enabled to toggle. Requires an attached terminal tab (errors for headless agent shells). Returns { sessionId, composeModeActive, composedText }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("enabled", JSONObject().apply { put("type", "boolean"); put("description", "true = start compose mode, false = stop. Omit to toggle the current state.") })
                })
                put("required", JSONArray().put("sessionId"))
            },
            // ONCE_PER_SESSION: only flips an in-memory IME input mode +
            // local compose buffer; nothing is sent to the remote until the
            // user/agent presses Enter (a separate input call).
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                if (args.has("enabled")) "Set terminal compose mode = ${args.optBoolean("enabled")}?"
                else "Toggle terminal compose mode?"
            },
        ) { args -> setComposeMode(args) },

        "read_clipboard" to ToolHandler(
            description = "Return the system clipboard's primary plain-text content. Returns { text } where text is null when the clipboard is empty or non-text (image, intent, etc.). On Android 10+ the system enforces foreground/IME restrictions on clipboard reads; this call may return null even when the clipboard has content if Haven isn't currently focused.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> readClipboard() },

        "get_preference" to ToolHandler(
            description = "Read a Haven user preference by key. Whitelisted keys: terminal_scrollback_rows, terminal_tap_to_position_cursor, terminal_font_size, terminal_color_scheme, terminal_auto_switch_scheme, terminal_light_color_scheme, terminal_dark_color_scheme, mouse_input_enabled, terminal_right_click, mcp_tunnel_endpoint_profile_id, mcp_wireguard_enabled, mcp_lan_bind_enabled, mcp_wireguard_tunnel_config_id, usb_guest_exposure_enabled, connection_logging_enabled, remap_low_ports (#300 proot launch toggle), share_storage_with_guest (#301 proot launch toggle), bind_android_system (#304 proot launch toggle). Returns { key, value } where value's type follows the preference's type (int / boolean / string). Colour-scheme values are TerminalColorScheme enum names.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("description", "Preference key (see whitelist in description).")
                    })
                })
                put("required", JSONArray().put("key"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getPreference(args) },

        // --- Write tools (require consent) ------------------------------

        "disconnect_profile" to ToolHandler(
            description = "Disconnect every live session for a profile across all transports (SSH, Mosh, Eternal Terminal, RDP, VNC, SMB, Reticulum, local). Use list_connections to find profileIds.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "ID of the connection profile to disconnect.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Disconnect from \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> disconnectProfile(args) },

        "add_port_forward" to ToolHandler(
            description = "Save a port-forward rule on an SSH profile. If the profile is currently connected the rule is also activated immediately. Type LOCAL=`-L` (local→remote), REMOTE=`-R` (remote→local), DYNAMIC=`-D` (SOCKS5 proxy server). Returns the saved rule's id and (when activated) the actually-bound port.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Owning profile ID.") })
                    put("type", JSONObject().apply { put("type", "string"); put("description", "LOCAL | REMOTE | DYNAMIC.") })
                    put("bindAddress", JSONObject().apply { put("type", "string"); put("description", "Bind address. Default 127.0.0.1.") })
                    put("bindPort", JSONObject().apply { put("type", "integer"); put("description", "Bind port. 0 = OS picks (REMOTE only).") })
                    put("targetHost", JSONObject().apply { put("type", "string"); put("description", "Target host (LOCAL/REMOTE only). Ignored for DYNAMIC.") })
                    put("targetPort", JSONObject().apply { put("type", "integer"); put("description", "Target port (LOCAL/REMOTE only). Ignored for DYNAMIC.") })
                })
                put("required", JSONArray().put("profileId").put("type").put("bindPort"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val type = args.optString("type")
                val bp = args.optInt("bindPort")
                val target = if (type == "DYNAMIC") "(SOCKS)"
                    else "${args.optString("targetHost", "?")}:${args.optInt("targetPort", 0)}"
                "Add $type port forward $bp → $target on \"${profileLabel(pid)}\"?"
            },
        ) { args -> addPortForward(args) },

        "remove_port_forward" to ToolHandler(
            description = "Delete a port-forward rule by id, and deactivate it on the live session if the owning profile is currently connected.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("ruleId", JSONObject().apply { put("type", "string"); put("description", "ID of the rule to remove.") })
                })
                put("required", JSONArray().put("ruleId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Remove port-forward rule ${args.optString("ruleId").take(8)}…?" },
        ) { args -> removePortForward(args) },

        "upload_file" to ToolHandler(
            description = "Write a local file to a path on any connected backend (local, SSH, SMB, rclone). Source must live under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary device files via the upload destination. Currently uses small-file semantics (loads the source into memory); streaming variants ship in a later #126 stage. Replaces upload_file_to_sftp; that still works as a deprecated alias for the SSH streaming path.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connection profile ID, or 'local' for the device filesystem.") })
                    put("localPath", JSONObject().apply { put("type", "string"); put("description", "Absolute path to a file under context.cacheDir on the device.") })
                    put("remotePath", JSONObject().apply { put("type", "string"); put("description", "Destination path on the target backend.") })
                })
                put("required", JSONArray().put("profileId").put("localPath").put("remotePath"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFile(args) },

        "delete_file" to ToolHandler(
            description = "Delete a file (not a directory) on any connected backend (local, SSH, SMB, rclone). Replaces delete_sftp_file; that still works as a deprecated alias.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connection profile ID, or 'local' for the device filesystem.") })
                    put("path", JSONObject().apply { put("type", "string"); put("description", "Path of the file to delete.") })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteFile(args) },

        "upload_file_to_sftp" to ToolHandler(
            description = "DEPRECATED: prefer upload_file(profileId=..., localPath=..., remotePath=...). Upload a local file to a path on a connected SFTP profile. Source must be a path under Haven's app cache (context.cacheDir) — the agent has no other writable surface, so this constraint blocks reads of arbitrary files via the upload destination. Requires a connected SSH/SFTP session. Uses streaming SFTP put (no in-memory buffer); use this for files larger than ~50 MiB until upload_file gains streaming support.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connected SSH/SFTP profile ID.") })
                    put("localPath", JSONObject().apply { put("type", "string"); put("description", "Absolute path to a file under context.cacheDir on the device.") })
                    put("remotePath", JSONObject().apply { put("type", "string"); put("description", "Absolute destination path on the SFTP server.") })
                })
                put("required", JSONArray().put("profileId").put("localPath").put("remotePath"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val local = args.optString("localPath", "?")
                val size = try { File(local).length() } catch (_: Exception) { 0L }
                val sizeStr = if (size > 0) "${size / 1024} KiB" else "(unknown size)"
                "Upload $sizeStr → ${args.optString("remotePath")} on \"${profileLabel(pid)}\"?"
            },
        ) { args -> uploadFileToSftp(args) },

        "delete_sftp_file" to ToolHandler(
            description = "DEPRECATED: prefer delete_file(profileId=..., path=...). Delete a file (not directory) from a connected SFTP profile. Requires a connected SSH/SFTP session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Connected SSH/SFTP profile ID.") })
                    put("path", JSONObject().apply { put("type", "string"); put("description", "Absolute path of the file to delete.") })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete ${args.optString("path")} on \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteSftpFile(args) },

        "send_terminal_input" to ToolHandler(
            description = "Send input to an active terminal session as if the user typed it. Provide `text` (UTF-8) and/or named `keys` — real control bytes (Enter/Esc/Ctrl-C/arrows) that `text` can't express (a \"\\r\" in text arrives as literal chars; a raw-mode REPL reads \"\\n\" as newline-insert, not submit). `text` is sent first, then `keys`, so a submit key lands after the body. Set `bracketedPaste` to wrap `text` in bracketed-paste markers so a raw-mode REPL (Claude Code, readline, vim) treats multi-line input as one paste instead of interleaved keystrokes that fight submit. Set `returnSnapshot` to get the resulting screen back without a follow-up read_terminal_snapshot. Hard cap 4096 bytes total.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session; required only when several are open. Must have an attached terminal.") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "UTF-8 text to send (before keys). To submit into a raw-mode REPL, prefer keys:[\"enter\"] over a trailing \\n.") })
                    put("keys", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "Named keys sent after text, e.g. [\"enter\"], [\"ctrl-c\"], [\"up\",\"enter\"]. Supported: enter, esc, tab, space, backspace, delete, up, down, left, right, home, end, pageup, pagedown, ctrl-a/c/d/e/l/u/w/z.")
                    })
                    put("bracketedPaste", JSONObject().apply { put("type", "boolean"); put("description", "Wrap text in bracketed-paste markers (ESC[200~ … ESC[201~). Default false. Use for multi-line input into a raw-mode REPL so it isn't folded into submit.") })
                    put("returnSnapshot", JSONObject().apply { put("type", "boolean"); put("description", "Return the terminal snapshot after sending, so you see the result without a follow-up read. Default false.") })
                })
            },
            // Once the user approves the agent typing into a session, per-call
            // re-approval is friction without added safety — the session is
            // already agent-controlled, and EVERY_CALL made interactive shell
            // driving unusable (each keystroke-batch blocked the request on a
            // device tap and timed out when unattended). Matches open_local_shell.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Send to terminal: \"$visible\"?"
            },
        ) { args -> sendTerminalInput(args) },

        "send_to_agent" to ToolHandler(
            description = "Deliver one message to another agent's REPL (or any raw-mode prompt) as a single submitted turn: bracketed-paste the text, settle, then Enter — and return the resulting screen (last ~50 lines by default). A convenience wrapper over send_terminal_input tuned for agent↔agent / REPL conversation, so you don't hand-assemble the body-then-Enter sequence. Use list_sessions (chosenSessionName) to pick the target. Returns { sessionId, delivered, bytesSent, snapshot }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID (from list_sessions). Optional — defaults to the sole open terminal session.") })
                    put("message", JSONObject().apply { put("type", "string"); put("description", "The message to deliver as one submitted prompt.") })
                    put("maxLines", JSONObject().apply { put("type", "integer"); put("description", "Cap the returned snapshot to the last N lines (default 50). Keeps the ack small so a delivered message doesn't read back as a timeout behind a large scrollback over a tunnel.") })
                })
                put("required", JSONArray().put("message"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val m = args.optString("message", "")
                "Send to agent REPL: \"${if (m.length > 80) m.take(80) + "…" else m}\"?"
            },
        ) { args -> sendToAgent(args) },

        "feed_terminal_output" to ToolHandler(
            description = "Inject raw bytes into a terminal session's OUTPUT stream — as if they had arrived from the remote — running the exact pipeline the live data callback uses (OSC scan → mouse-mode scan → emulator). Distinct from send_terminal_input, which sends to the PTY input as if typed. Use this to deterministically exercise output-side parsing without a cooperating remote: e.g. feed an OSC 52 sequence to test the clipboard round-trip, a DECSET 1000/1002/1003 to flip mouseMode, an OSC 8 hyperlink, or a partial escape split across two calls (the OSC scanner keeps state between calls). Provide exactly one of `text` (UTF-8) or `bytesBase64` (for control bytes / ESC). Hard cap 65536 bytes per call. Not supported for headless agent shells (no UI tab) — returns an error. Returns { sessionId, bytesFed }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "UTF-8 text to inject as output. Mutually exclusive with bytesBase64.") })
                    put("bytesBase64", JSONObject().apply { put("type", "string"); put("description", "Base64-encoded raw bytes to inject — use this for escape sequences (ESC = \\u001b) and other control bytes. Mutually exclusive with text.") })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = if (args.has("text")) {
                    args.optString("text").toByteArray(Charsets.UTF_8).size
                } else {
                    runCatching { android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT).size }
                        .getOrDefault(0)
                }
                "Inject $n bytes into the terminal OUTPUT stream of session ${args.optString("sessionId").take(8)}…? (renders as if sent by the remote — can set the clipboard via OSC 52)"
            },
        ) { args -> feedTerminalOutput(args) },

        "write_clipboard" to ToolHandler(
            description = "Set the system clipboard's primary plain-text content. Replaces whatever's currently on the clipboard. Useful for priming the clipboard before triggering a terminal paste.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to place on the clipboard. Empty string is allowed and clears the clipboard's primary item.")
                    })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val text = args.optString("text", "")
                val preview = if (text.length > 80) text.substring(0, 80) + "…" else text
                val visible = preview.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                "Replace clipboard with: \"$visible\"?"
            },
        ) { args -> writeClipboard(args) },

        "set_preference" to ToolHandler(
            description = "Write a Haven user preference. Whitelisted keys (and their types): terminal_scrollback_rows (int 100..25000), terminal_tap_to_position_cursor (bool), terminal_font_size (int 8..32), mouse_input_enabled (bool), terminal_right_click (bool), terminal_color_scheme (string — a TerminalColorScheme enum name, e.g. HAVEN, DRACULA, NORD, GRUVBOX; case-insensitive), terminal_auto_switch_scheme (bool — when true the active scheme follows system light/dark via the light/dark keys), terminal_light_color_scheme (string scheme name), terminal_dark_color_scheme (string scheme name), terminal_background_opacity (float 0.0..1.0 — below 1.0 the terminal renders over the device wallpaper), mcp_tunnel_endpoint_profile_id (string SSH profile id, empty to clear), mcp_wireguard_enabled (bool), mcp_lan_bind_enabled (bool — also bind the device Wi-Fi/LAN address for direct same-network reach), mcp_wireguard_tunnel_config_id (string tunnel config id the MCP server keeps up as its WG carrier, empty to clear), usb_guest_exposure_enabled (bool — master gate for usb_attach_to_guest), connection_logging_enabled (bool — audit-log connection lifecycle events to Settings → View connection log; off by default; enable before reproducing a connection issue, then read get_connection_log), gpu_use_venus (bool — experimental venus+zink GPU stack for accelerated desktops; off = virgl/virpipe), remap_low_ports (bool — #300 proot launch toggle: remap guest privileged ports +2000), share_storage_with_guest (bool — #301 proot launch toggle: mount /storage + /sdcard into the local guest; default on), bind_android_system (bool — #304 proot launch toggle: bind Android's read-only /system, /vendor, /apex, /product, /system_ext, /odm into the guest so it can run Android native binaries like getprop/toybox; default off, exposes device internals). Takes effect on the next local session/command. Returns { key, value }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("key", JSONObject().apply { put("type", "string"); put("description", "Preference key (see whitelist).") })
                    put("value", JSONObject().apply { put("description", "New value. Type must match the key's type — int for the *_rows / *_size keys, bool for the rest.") })
                })
                put("required", JSONArray().put("key").put("value"))
            },
            // ONCE_PER_SESSION: settings writes are reversible and the
            // whitelist is small + non-sensitive (terminal display
            // tweaks). One approval per session avoids prompt fatigue
            // when an agent is sweeping multiple settings during a test
            // run; the user retains the destructive-action safety on
            // tools that touch session state or the clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Set Haven preference ${args.optString("key")} = ${args.opt("value")}?" },
        ) { args -> setPreference(args) },

        "start_selection" to ToolHandler(
            description = "Anchor a new text selection at (row, col) in an active terminal session. Equivalent to a long-press at that cell. Modes: CHARACTER (default), WORD (snaps to word boundaries on creation), LINE (whole-row selection). Subsequent extend_selection / copy_selection / clear_selection calls on the same session operate on this anchor. Replaces any existing selection on this session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row, 0 = top.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column, 0 = leftmost.") })
                    put("mode", JSONObject().apply { put("type", "string"); put("description", "CHARACTER | WORD | LINE. Default CHARACTER.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            // ONCE_PER_SESSION: only mutates an in-memory UI selection,
            // doesn't write to the clipboard or send anything to the
            // remote. The destructive step (copy_selection) keeps
            // EVERY_CALL because it writes to the system clipboard.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Start ${args.optString("mode", "CHARACTER")} selection at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> startSelection(args) },

        "extend_selection" to ToolHandler(
            description = "Move the selection's end anchor to (row, col) in an active terminal session. Equivalent to dragging the selection handle to that cell. Pairs with start_selection — call start_selection first to set the anchor, then extend_selection to move the far end. Does NOT scroll the viewport — use drag_selection_to to extend a selection past the top or bottom of the viewport into scrollback.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row, 0 = top.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column, 0 = leftmost.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            // ONCE_PER_SESSION for the same reason as start_selection —
            // pure UI-state move, the clipboard-touching follow-up
            // (copy_selection) gates separately.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Extend selection to row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> extendSelection(args) },

        "drag_selection_to" to ToolHandler(
            description = "Drag the selection's end anchor toward (toRow, toCol), auto-scrolling the viewport into scrollback (or back toward live) when toRow lies outside [0, rows-1]. Mirrors the Compose drag gesture that runs when the finger crosses the top or bottom edge zone: each row of out-of-viewport target is one ScrollController.scrollBy(±1) paired with one shiftSelectionStartByRows(±1) in lockstep. toRow < 0 scrolls back |toRow| rows (end anchor lands at viewport row 0). toRow >= rows scrolls forward toRow-(rows-1) rows (end anchor lands at viewport row rows-1). toRow in [0, rows-1] is equivalent to extend_selection. Clamps at the live screen and at scrollback.size; the response's `clamped` field indicates whether the full requested distance was achieved. Returns the resolved selection range, the new scrollbackPosition, the number of scroll steps actually taken (signed), the clamp flag, and the selection text via SelectionController.getSelectedText (libvterm softWrapped-aware, scrollback-aware).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("toRow", JSONObject().apply { put("type", "integer"); put("description", "Target row. May be negative (above viewport top — drag into scrollback) or >= rows (below viewport bottom — drag toward live).") })
                    put("toCol", JSONObject().apply { put("type", "integer"); put("description", "Target column.") })
                })
                put("required", JSONArray().put("sessionId").put("toRow").put("toCol"))
            },
            // Same rationale as start_selection / extend_selection — pure
            // UI-state moves, the clipboard-touching follow-up gates
            // separately on copy_selection.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Drag selection toward row=${args.optInt("toRow")} col=${args.optInt("toCol")} (scrolls viewport into scrollback as needed)?" },
        ) { args -> dragSelectionTo(args) },

        "copy_selection" to ToolHandler(
            description = "Copy the current selection to the system clipboard and return the copied text. Goes through Haven's smart-copy interceptor (TUI border-strip + soft-wrap rejoin). Clears the selection after copying. Returns { text }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with a current selection.") })
                })
                put("required", JSONArray().put("sessionId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Copy current terminal selection to clipboard?" },
        ) { args -> copySelection(args) },

        "tap_terminal" to ToolHandler(
            description = "Simulate a tap inside an active terminal session at (row, col). When the user has Settings → Terminal → Tap to move cursor on supported prompts enabled, and the tap lands on a row carrying an OSC 133 COMMAND_INPUT segment with no matching COMMAND_FINISHED, Haven dispatches arrow keys so the readline cursor lands at the tapped column. Returns { handled, deltaCols, dispatched } describing what happened. handled=false means no OSC 133 prompt at the tap row — falls through silently.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID.") })
                    put("row", JSONObject().apply { put("type", "integer"); put("description", "Viewport-relative row.") })
                    put("col", JSONObject().apply { put("type", "integer"); put("description", "Column.") })
                })
                put("required", JSONArray().put("sessionId").put("row").put("col"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Tap terminal at row=${args.optInt("row")} col=${args.optInt("col")}?" },
        ) { args -> tapTerminal(args) },

        "scroll_terminal" to ToolHandler(
            description = "Scroll an active terminal session's viewport by N lines. Positive lines = back into scrollback (older content); negative lines = toward the live screen. Clamps at 0 (live) and scrollback.size. Returns { scrollbackPosition } — the new position after clamping.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID with an attached terminal tab.") })
                    put("lines", JSONObject().apply { put("type", "integer"); put("description", "Lines to scroll. Positive = into scrollback, negative = toward live.") })
                })
                put("required", JSONArray().put("sessionId").put("lines"))
            },
            // ONCE_PER_SESSION: pure view-state change — no PTY input,
            // no clipboard write, fully reversible by the user.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optInt("lines")
                if (n >= 0) "Scroll terminal $n lines into scrollback?"
                else "Scroll terminal ${-n} lines toward live screen?"
            },
        ) { args -> scrollTerminal(args) },

        "drag_terminal" to ToolHandler(
            description = "Drive a synthetic touch-drag through the terminal's REAL pointer pipeline — the same awaitEachGesture handler a physical finger feeds. Unlike start_selection / extend_selection / drag_selection_to, which mutate the selection model directly, this exercises the actual gesture code: long-press classification, drag-extend, edge-zone detection, and the held-still auto-repeat edge-scroll ticker. The gesture is: touch-down at path[0]; hold still for pressMs (long enough for the long-press selection timeout, ~500ms, to fire); move through path[1..] one stepMs apart; hold still at path.last() for holdMs (the window the edge-scroll ticker runs in); lift. Rows are viewport-relative; out-of-viewport rows (e.g. -3, or rows beyond the bottom) still map to valid pixels so a path can target the top/bottom edge zone. Blocks until the gesture completes (~pressMs + path*stepMs + holdMs). Requires the session's terminal tab to be the foreground tab (the gesture injector mounts with the Composable). Returns { sessionId, cells, approxDurationMs }. Verify the effect with get_selection + read_terminal_snapshot afterwards.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionId", JSONObject().apply { put("type", "string"); put("description", "Active session ID; its terminal tab must be the foreground tab.") })
                    put("path", JSONObject().apply {
                        put("type", "array")
                        put("description", "Ordered cells to traverse. Each element is { row, col } (viewport-relative; row may be negative or >= rows to reach the edge zones). path[0] = touch-down, last = lift. Must be non-empty; a single cell is a long-press with no drag.")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("row", JSONObject().apply { put("type", "integer") })
                                put("col", JSONObject().apply { put("type", "integer") })
                            })
                            put("required", JSONArray().put("row").put("col"))
                        })
                    })
                    put("pressMs", JSONObject().apply { put("type", "integer"); put("description", "Initial still-hold before the first move. Default 900. Must clear the long-press timeout (~500ms).") })
                    put("stepMs", JSONObject().apply { put("type", "integer"); put("description", "Delay between successive move events. Default 30.") })
                    put("holdMs", JSONObject().apply { put("type", "integer"); put("description", "Still-hold at the final cell before lifting — the edge-scroll ticker's window. Default 1000.") })
                })
                put("required", JSONArray().put("sessionId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("path")?.length() ?: 0
                "Inject a synthetic $n-cell touch-drag into the terminal of session ${args.optString("sessionId").take(8)}…? (drives the real gesture handler)"
            },
        ) { args -> dragTerminal(args) },

        "open_developer_settings" to ToolHandler(
            description = "Open Android's Developer Options screen via ACTION_APPLICATION_DEVELOPMENT_SETTINGS so the user can flip Wireless debugging or other developer toggles. Tap-equivalent — the screen opens but no setting is changed without the user touching it.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> openDeveloperSettings() },

        "install_apk_from_url" to ToolHandler(
            description = "Download an APK from a URL and install it on the device. With Shizuku running and granted, install is silent via `pm install`. Without Shizuku, falls back to firing the system installer dialog (single user tap to confirm) — response includes `pending: true` so the caller knows to wait for the user. Useful for agent-driven self-update or sideloading over VPN where wireless ADB isn't reachable. NOTE: Android's network-security policy blocks cleartext http:// to anything but localhost, so an http:// URL on the LAN (e.g. a workstation IP) is rejected — use https://, or install_apk_from_backend (SFTP/rclone/Reticulum) which carries no cleartext.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "http(s) URL pointing at a signed APK file. Should resolve to APK bytes (no HTML wrapper).")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("url").take(60)} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromUrl(args) },

        "install_apk_from_backend" to ToolHandler(
            description = "Install an APK from a path on any connected backend (local, SSH/SFTP, SMB, rclone, Reticulum). Streams APK bytes via the existing FileBackend abstraction. Same Shizuku/system-installer fallback as install_apk_from_url. Because backend transfers can be slow (a big APK over SFTP/rclone/Reticulum), this validates synchronously (missing file, directory, size cap → immediate error) then streams + installs in the background, returning {pending:true, staging:true} right away rather than blocking past the request timeout. Confirm the result with get_app_info (and /mcp reconnect if Haven is updating itself). Gated by Settings → Agent endpoint → \"Allow agents to read file contents\" and confirmed per-call.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile ID, or 'local' for the device filesystem.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the APK file on the chosen backend.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "INSTALL APK from ${args.optString("path").take(60)} on profile ${args.optString("profileId")} — runs as code on this device. Only allow trusted sources."
            },
        ) { args -> installApkFromBackend(args) },

        "enable_wireless_adb" to ToolHandler(
            description = "Turn on Android's Wireless debugging (`adb connect` over WiFi) by setting `adb_wifi_enabled=1` via Shizuku. Requires Shizuku to be running and Haven to have its permission granted. NOTE: on Android 11+, a host that has never paired with this device must still complete the pairing-code flow manually — this tool cannot bypass that. For an already-paired host (the common case after a phone reboot) flipping the flag is enough.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Enable Wireless debugging on this device?" },
        ) { _ -> enableWirelessAdb() },

        "read_logcat" to ToolHandler(
            description = "Read recent Android system log lines via Shizuku, so the agent can observe foreign-app behaviour (network calls, crashes, lifecycle) during F-Droid tester reviews. Requires Shizuku running + granted (no separate READ_LOGS grant on Haven — logcat is read as Shizuku's shell uid, which already has the permission). Optional `packageName` resolves to a `--uid` filter via `pm list packages -U`; combine with `filter` for tag-level narrowing. Returns the raw logcat block; the agent parses it. `lines` is capped at 5000 and the response payload is capped at 256 KiB (truncated:true when either limit hits). Use this whenever an MR review needs log-level observation of a non-Haven app — the Haven local shell's Alpine proot can't reach /system/bin/logcat.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("lines", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Number of recent lines to return (default 200, capped at 5000). Maps to logcat -t.")
                    })
                    put("filter", JSONObject().apply {
                        put("type", "string")
                        put("description", "Standard logcat filter expression, e.g. 'NOVA:V *:S' to keep only NOVA-tagged lines, or '*:E' to keep only errors. Appended verbatim to the logcat command.")
                    })
                    put("packageName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Filter to this app only. Resolved to '--uid <uid>' via 'pm list packages -U'. Errors out if the package is not installed.")
                    })
                    put("pid", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Filter to this process pid only. Maps to logcat --pid <pid>. Use when you already have the pid (e.g. from a previous capture or pgrep).")
                    })
                    put("since", JSONObject().apply {
                        put("type", "string")
                        put("description", "Only return lines after this point. Pass a logcat timestamp ('MM-DD HH:MM:SS.SSS') or an epoch-ms integer. Maps to logcat -T.")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pkg = args.optString("packageName", "").ifEmpty { null }
                val pid = if (args.has("pid")) args.optInt("pid") else null
                val scope = pkg ?: pid?.let { "pid $it" } ?: "system-wide"
                "Allow agent to read Android logs ($scope) via Shizuku for this session?"
            },
        ) { args -> readLogcat(args) },

        "expose_adb" to ToolHandler(
            description = "Make the device's adb reachable from the workstation over 4G even with a system VPN active. Enables classic adb-over-TCP on a loopback port (default 5555) via Shizuku — no per-host pairing — then reverse-forwards 127.0.0.1:<port> over the existing MCP tunnel. Because the only phone-side hop is loopback (which Android never routes through a VpnService), adb stays reachable through any VPN. On the workstation: `adb connect localhost:<port>`. Requires Shizuku running + granted. Use install_apk_from_url/_from_backend for installs that don't need a full adb connection. Tear down with unexpose_adb.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("port", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Loopback adb-over-TCP port to expose (default 5555).")
                    })
                })
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { _ -> "Expose full adb device control to the workstation over the MCP tunnel?" },
        ) { args -> exposeAdb(args) },

        "unexpose_adb" to ToolHandler(
            description = "Tear down expose_adb: remove the adb reverse forward from the MCP tunnel and disable adb-over-TCP on the device (returns adbd to USB-only). Safe to call even if adb wasn't exposed.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Disable adb exposure on this device?" },
        ) { _ -> unexposeAdb() },

        // --- USB broker (Layer-B: re-expose the phone's USB devices) ---
        // The phone is the only thing that can open a USB device on a
        // non-rooted Android (the proot guest and adb shell are both denied
        // /dev/bus/usb). These tools let the agent enumerate and drive USB
        // directly; later slices bridge the same transfers into the guest.

        "list_usb_devices" to ToolHandler(
            description = "List USB devices attached to the phone (host/OTG). Each entry has deviceName (the stable /dev/bus/usb path used as the key for the other usb_* tools), vidPid, deviceClass, hasPermission, isOpen, and the interface/endpoint descriptors (id, class, endpoint address + direction + type). Manufacturer/product/serial strings are only filled once permission is held (call request_usb_permission). Read-only; never prompts.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbDevices() },

        "request_usb_permission" to ToolHandler(
            description = "Request the Android runtime USB permission for a device (pops the system grant dialog) and open it, caching the connection for usb_control_transfer / usb_bulk_transfer. Idempotent: a no-op if permission is already held and the device is open. Returns the device info with hasPermission/isOpen reflecting the result.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply {
                        put("type", "string")
                        put("description", "deviceName from list_usb_devices (the /dev/bus/usb/BBB/DDD path).")
                    })
                })
                put("required", JSONArray().put("deviceName"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Grant the agent access to USB device ${usbLabel(args.optString("deviceName"))}?" },
        ) { args -> requestUsbPermission(args) },

        "usb_control_transfer" to ToolHandler(
            description = "Perform a USB endpoint-0 control transfer on an opened device. Args: deviceName, requestType (bmRequestType, int — bit 7 set = device-to-host/IN), request (bRequest), value (wValue), index (wIndex), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). Returns bytesTransferred and, for IN transfers, dataBase64. The device must already be opened via request_usb_permission.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply { put("type", "string") })
                    put("requestType", JSONObject().apply { put("type", "integer"); put("description", "bmRequestType. Bit 7 (0x80) set = IN.") })
                    put("request", JSONObject().apply { put("type", "integer"); put("description", "bRequest.") })
                    put("value", JSONObject().apply { put("type", "integer"); put("description", "wValue.") })
                    put("index", JSONObject().apply { put("type", "integer"); put("description", "wIndex.") })
                    put("dataBase64", JSONObject().apply { put("type", "string"); put("description", "Base64 OUT payload; omit for IN.") })
                    put("length", JSONObject().apply { put("type", "integer"); put("description", "IN read length; ignored for OUT.") })
                    put("timeoutMs", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("deviceName").put("requestType").put("request").put("value").put("index"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "USB control transfer to ${usbLabel(args.optString("deviceName"))}" },
        ) { args -> usbControlTransfer(args) },

        "usb_bulk_transfer" to ToolHandler(
            description = "Perform a USB bulk or interrupt transfer on an opened device. Direction is taken from the endpoint descriptor. Args: deviceName, endpoint (bEndpointAddress, int), dataBase64 (OUT payload, omit for IN), length (IN read length), timeoutMs (default 1000). The owning interface is claimed automatically. Returns bytesTransferred and, for IN endpoints, dataBase64.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply { put("type", "string") })
                    put("endpoint", JSONObject().apply { put("type", "integer"); put("description", "bEndpointAddress from the interface descriptor.") })
                    put("dataBase64", JSONObject().apply { put("type", "string"); put("description", "Base64 OUT payload; omit for IN endpoints.") })
                    put("length", JSONObject().apply { put("type", "integer"); put("description", "IN read length; ignored for OUT.") })
                    put("timeoutMs", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("deviceName").put("endpoint"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "USB bulk transfer to ${usbLabel(args.optString("deviceName"))}" },
        ) { args -> usbBulkTransfer(args) },

        "usb_attach_to_guest" to ToolHandler(
            description = "Expose a USB device to the proot Linux guest: opens it (requesting permission if needed) and binds the haven-usb proxy on an abstract LocalSocket the guest can reach, then stages the haven-usb-probe binary into the guest. Returns the socketName, the in-guest probePath, and a probeCommand you can run via run_in_proot to verify reachability. For a CDC-ACM serial device it also returns serialBridgeCommand (the haven-usb-serial PTY bridge) so unmodified serial apps (e.g. LIRC's lircd/mode2) can open it as /dev/pts/N. deviceName is optional when exactly one device is attached. This is the entry point for the guest-side USB shim (LD_PRELOAD/DllMap for HID, a PTY bridge for serial).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply {
                        put("type", "string")
                        put("description", "deviceName from list_usb_devices; optional if only one device is attached.")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB device" }
                "Expose ${if (n.startsWith("/dev")) usbLabel(n) else n} to the Linux guest?"
            },
        ) { args -> usbAttachToGuest(args) },

        "detach_from_guest" to ToolHandler(
            description = "Stop the haven-usb guest proxy started by usb_attach_to_guest and release the brokered USB device handle (the guest's /dev/pts serial bridge or LD_PRELOAD HID routing stops working immediately). Pass keepOpen:true to leave the device handle open. The teardown counterpart to usb_attach_to_guest.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("keepOpen", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Leave the brokered device handle open (default false = fully release it).")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> detachFromGuest(args) },

        "start_usbip_export" to ToolHandler(
            description = "Start a userspace USB/IP server exporting a phone-attached USB device over TCP (default port 3240) so a remote Linux host can `usbip attach` it as a real local device node — every app there (ssh, libfido2, browsers) sees it, with the touch happening on the phone. Opens the device (requesting permission if needed) and returns the busid, bound port, and the client-side attach command. deviceName is optional when exactly one device is attached. Pass loopbackOnly:true to bind 127.0.0.1 only (for use behind an SSH/WireGuard tunnel); the default binds all interfaces for direct LAN attach. This is the remote-host counterpart to usb_attach_to_guest (which targets the local proot guest, where usbip can't run — the Android kernel has no vhci-hcd).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply {
                        put("type", "string")
                        put("description", "deviceName from list_usb_devices; optional if only one device is attached.")
                    })
                    put("loopbackOnly", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Bind 127.0.0.1 only (use behind a tunnel). Default false = all interfaces, LAN-reachable.")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB device" }
                "Export ${if (n.startsWith("/dev")) usbLabel(n) else n} over USB/IP to remote hosts?"
            },
        ) { args -> startUsbipExport(args) },

        "stop_usbip_export" to ToolHandler(
            description = "Stop the USB/IP server started by start_usbip_export (closes the listening socket and any active client connection) and release the brokered USB device handle. Pass keepOpen:true to leave the handle open for a fast re-export.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("keepOpen", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Leave the brokered device handle open for a fast re-export (default false = fully release it).")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> stopUsbipExport(args) },

        "list_usb_exports" to ToolHandler(
            description = "List active USB exports of phone-attached devices: the USB/IP server (start_usbip_export — to remote hosts) and the guest proxy (usb_attach_to_guest — to the local proot guest). Reports the exported device, busid/bound port, and whether a remote usbip client is currently attached. Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbExports() },

        "open_usb_drive" to ToolHandler(
            description = "Open a phone-attached USB drive (mass storage — flash drive, SSD, SD reader) inside an on-device QEMU Linux VM and surface its files as an ordinary connection (#287). Unlike usb_attach_to_guest (which gives the proot guest a char device), this gives the drive a REAL kernel, so ext4 / GPT / block partitions mount and their files are browseable. Flow: exports the drive over USB/IP, boots (or reuses, if another drive is already open) a small Alpine VM that imports it, mounts every partition (read-only unless `writable`), and runs sshd — then returns a loopback SSH/SFTP `profileId` you browse with list_directory / serve_file (and a terminal tab into the VM). A LUKS-encrypted partition mounts locked (reported in list_usb_drives' vm.locked) — call unlock_usb_drive_partition with its passphrase to mount it. The VM boot is slow (TCG, no KVM unrooted) + the first run installs packages, so this returns {status:\"starting\"} immediately — poll list_usb_drives until phase=ready (profileId set) or error. Consent-gated per session (mounting the user's disk is sensitive). Up to a phone-resource limit of concurrent drives (they share one VM, so this is a vhci-port/practical cap, not RAM); isochronous (webcam/audio) still can't pass.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deviceName", JSONObject().apply {
                        put("type", "string")
                        put("description", "deviceName from list_usb_devices / list_usb_drives; optional if exactly one USB drive is attached.")
                    })
                    put("writable", JSONObject().apply {
                        put("type", "boolean")
                        put(
                            "description",
                            "Mount read-write instead of the default read-only. An interrupted write (VM killed, app backgrounded under memory pressure) can corrupt the drive's filesystem — only set this when the caller genuinely needs to write.",
                        )
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val n = args.optString("deviceName").ifBlank { "the attached USB drive" }
                val label = if (n.startsWith("/dev")) usbLabel(n) else n
                if (args.optBoolean("writable", false)) {
                    "Open $label in a Linux VM READ-WRITE? An interrupted write can corrupt the drive."
                } else {
                    "Open $label in a Linux VM and mount its files?"
                }
            },
        ) { args -> openUsbDrive(args) },

        "list_usb_drives" to ToolHandler(
            description = "List phone-attached USB mass-storage drives (the candidates for open_usb_drive) and every currently-open USB-drive VM in `vms` (up to a phone-resource concurrency limit): busid, phase (idle/opening/ready/error), the loopback SSH `profileId`, whether it's mounted read-only, any locked (LUKS) partitions awaiting unlock_usb_drive_partition, and the mounted paths once ready. Read-only — poll this after open_usb_drive until the matching vms[] entry has phase=ready.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listUsbDrives() },

        "unlock_usb_drive_partition" to ToolHandler(
            description = "Unlock a LUKS-encrypted partition on an open USB-drive VM (see list_usb_drives' vms[].locked for candidates, e.g. \"sdb2\" → devicePath \"/dev/sdb2\") and mount it. Runs against the already-booted VM — no reboot. Returns the updated mount/locked lists; throws on a wrong passphrase.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("busid", JSONObject().apply {
                        put("type", "string")
                        put("description", "Which open drive's VM (see list_usb_drives' vms[].busid); optional if exactly one is open.")
                    })
                    put("devicePath", JSONObject().apply {
                        put("type", "string")
                        put("description", "e.g. /dev/sdb2 — the locked partition's device path inside the VM.")
                    })
                    put("passphrase", JSONObject().apply {
                        put("type", "string")
                        put("description", "The LUKS passphrase.")
                    })
                })
                put("required", JSONArray().put("devicePath").put("passphrase"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Unlock ${args.optString("devicePath")} with the supplied passphrase?" },
        ) { args -> unlockUsbDrivePartition(args) },

        "close_usb_drive" to ToolHandler(
            description = "Close a USB-drive VM opened by open_usb_drive: power off the VM, stop its USB/IP export, and remove the transient SSH profile + ephemeral key. Idempotent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("busid", JSONObject().apply {
                        put("type", "string")
                        put("description", "Which open drive to close (see list_usb_drives' vms[].busid); optional if exactly one is open.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> closeUsbDrive(args) },

        "delete_usb_appliance" to ToolHandler(
            description = "Delete the persistent USB-helper Linux appliance — the small installed Alpine VM (with usbip+ssh baked in) that open_usb_drive boots to mount drives. It's provisioned once and kept so repeat opens are fast; deleting it frees the disk (~280 MB) and forces a one-time re-provision (re-download + install) on the next open_usb_drive. Closes any live USB-drive VM first. Idempotent.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
        ) { _ -> deleteUsbAppliance() },

        "list_bridges" to ToolHandler(
            description = "Unified view of every phone capability Haven is currently **brokering** to a sink — the 'Bridges' registry (see docs/design/bridges.md). A bridge is one Android-held capability (a USB device, audio, etc.) re-exposed to a consumer that can't reach it directly: the AI agent, the local Linux guest, a local VM, a remote host, or the workstation. Generalises list_usb_exports across all bridge types. Each entry: source, sourceKind, sink, transport, state, plus type-specific detail (busid/port/profileId/mounts). Read-only.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listBridges() },

        "open_local_shell" to ToolHandler(
            description = "Open a fresh local Alpine PRoot shell session and return its sessionId. Equivalent to tapping the Terminal icon in the Connections top bar — creates the local shell profile if missing, registers a session, and connects it. The returned sessionId is immediately usable with send_terminal_input and read_terminal_scrollback. Use this when you need a clean bash REPL (e.g. when an existing session has Claude Code, vim, or another stdin-capturing process in front of it). Pass `plain: true` to bypass the user's session-manager preference (tmux / zellij / screen / byobu) and exec a bare login shell — required when the agent needs Haven's own scrollback ring to capture output, which doesn't happen when a multiplexer's status bar uses DECSTBM to reserve the bottom row. NOTE: if a live local shell already exists, this returns that sessionId regardless of `plain` (response `reused: true`); call disconnect_profile on the existing profile first if you need a fresh plain-shell respawn.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("plain", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Skip the user's sessionManager preference and exec /bin/busybox sh -l directly. Default false (UI-equivalent behaviour).")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openLocalShell(args) },

        // --- proot distro / desktop environment tools (issue #162 Phase 3b) ---
        // These let an agent (and the Settings → PRoot install log page)
        // drive and observe the proot install pipeline without ADB or
        // logcat. Failures land in the Room-backed log so they survive
        // app restarts and rotated logcat buffers.

        "inspect_proot" to ToolHandler(
            description = "Single rich read of the proot subsystem: active distro id, every Distro (id, label, family, installed, sizeMb, bytesOnDisk, postExtractHookIds, and installedDesktops — the desktop ids installed on THAT distro's rootfs, so the cross-distro picture is visible without switching active distro), every DesktopEnvironment with per-family Stable/Experimental/Broken compatibility and Experimental notes, current osSetupState (phase / step / progress / errorPhase / errorMessage / errorTail), current desktopSetupState (phase / errorMessage / errorTail), and the last 50 install-log events. The single endpoint to drive issue #162 verification.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("eventLimit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max install-log events to return. Default 50.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> inspectProot(args) },

        "list_distros" to ToolHandler(
            description = "Slim distro-only read of inspect_proot. Returns each Distro with installed/active/sizeMb/family. Use this when you only need the catalog and not the live state or log events.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDistros() },

        "list_desktop_environments" to ToolHandler(
            description = "Slim DE-only read of inspect_proot. Filters to DEs that have a package list for the active distro's family (matches the UI filter). Each entry includes per-family compatibility (Stable/Experimental/Broken), an Experimental note when relevant, installed?, and running? state.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listDesktopEnvironments() },

        "set_active_distro" to ToolHandler(
            description = "Switch the active proot distro WITHOUT installing anything — the lightweight counterpart to install_distro (which downloads). The active distro is the rootfs that run_in_proot, install_desktop, start_desktop and the desktop/USB tools all operate on, so this is how you drive cross-distro work over MCP (e.g. run_in_proot inside Void instead of the current active distro). The distro must already be installed — call list_distros for installed ids, or install_distro to add one. Returns the new active distro id, its family, and the desktops installed on it.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Installed distro id to make active (e.g. \"void\", \"archlinux\", \"alpine-3.21\"). See list_distros.")
                    })
                })
                put("required", JSONArray().put("distroId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> setActiveDistroTool(args) },

        "install_distro" to ToolHandler(
            description = "Set the given distro as active and trigger installRootfs(). Returns immediately; poll `inspect_proot.osSetupState` for progress (Downloading → Extracting → BootstrapHook → Baseline → Ready, or Error with attribution). Idempotent: if the distro is already installed, just switches active.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id from DistroCatalog (e.g. \"alpine-3.21\", \"debian-bookworm\", \"ubuntu-noble\", \"archlinux\", \"void\").")
                    })
                })
                put("required", JSONArray().put("distroId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Install Linux distribution \"${d.label}\" (≈${d.sizeEstimateMb} MB download)?"
                else "Install distro \"$id\"?"
            },
        ) { args -> installDistro(args) },

        "delete_distro" to ToolHandler(
            description = "Wipe a distro's rootfs and remove all installed DEs on it. Stops any running DEs first. Destructive — frees the disk space and is also the recovery path when an install lands in a broken state.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id to delete.")
                    })
                })
                put("required", JSONArray().put("distroId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("distroId")
                val d = sh.haven.core.local.proot.DistroCatalog.lookup(id)
                if (d != null) "Delete \"${d.label}\" and all its data? This cannot be undone."
                else "Delete distro \"$id\" and all its data? This cannot be undone."
            },
        ) { args -> deleteDistroTool(args) },

        "import_distro" to ToolHandler(
            description = "Import a custom rootfs tarball as a new distro (#284) — \"bring your own rootfs\". The tarball (http(s) URL or an on-device file path) is extracted to its own rootfs and registered as a first-class distro, so it then appears in list_distros / set_active_distro / install_desktop exactly like a built-in. Raw mode: no baseline packages and no distro hooks run — the rootfs is used as shipped; `family` only routes later package installs (apk/apt/pacman/xbps). Use this for proot-distro / Docker-export tarballs and for a SECOND instance of a distro you already have (give it a new id — that is how #302 multiple-instances is done). Returns immediately; poll inspect_proot.osSetupState (Downloading → Extracting → Ready/Error). Supported compression: .tar.gz and .tar.xz (zstd not yet supported — recompress first).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply {
                        put("type", "string")
                        put("description", "New distro id (slug: lowercase letters/digits/.-_, e.g. \"ubuntu-trixie\" or \"debian-test2\"). Must not collide with a built-in or existing custom id.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Human label shown in the picker (e.g. \"Ubuntu 26.04 (imported)\").")
                    })
                    put("family", JSONObject().apply {
                        put("type", "string")
                        put("description", "Package family for later installs: APK | APT | PACMAN | XBPS | NIX.")
                    })
                    put("source", JSONObject().apply {
                        put("type", "string")
                        put("description", "Rootfs tarball: an http(s):// URL, or an absolute on-device file path.")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional compression: TAR_GZ | TAR_XZ. Auto-detected from the source extension if omitted.")
                    })
                    put("stripComponents", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional leading path components to strip (proot-distro tarballs wrap in one dir → 1). Defaults to auto: tries 0, retries 1 if no bin/sh is found.")
                    })
                    put("sha256", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional SHA-256 to verify the download against. Skipped if omitted.")
                    })
                })
                put("required", JSONArray().put("id").put("family").put("source"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Import a custom rootfs as distro \"${args.optString("id")}\" from ${args.optString("source")}?"
            },
        ) { args -> importDistroTool(args) },

        "get_custom_binds" to ToolHandler(
            description = "List the user-defined extra proot bind mounts (#301) for a distro — the per-distro custom Android→guest mounts added on top of the fixed system binds. Pass distroId; omit to use the active distro. Returns each bind as {host, guest} plus its proot spec.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id; omit for the active distro.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getCustomBindsTool(args) },

        "set_custom_binds" to ToolHandler(
            description = "Replace the user-defined extra proot bind mounts (#301) for a distro — exposes arbitrary Android paths inside that distro's guest (interactive shell, desktop, and run_in_proot all pick them up). proot binds are read-write. Pass distroId (omit for active) and `binds`, an array of {host, guest?} objects (guest blank = same path as host). This REPLACES the whole list; pass [] to clear. Takes effect on the NEXT session/command, not already-running ones.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Distro id; omit for the active distro.")
                    })
                    put("binds", JSONObject().apply {
                        put("type", "array")
                        put("description", "Full replacement list. Each item: {host: \"/abs/android/path\", guest: \"/abs/guest/path\" (optional)}.")
                        put("items", JSONObject().apply { put("type", "object") })
                    })
                })
                put("required", JSONArray().put("binds"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val n = args.optJSONArray("binds")?.length() ?: 0
                "Set $n custom bind mount(s) for distro \"${args.optString("distroId").ifEmpty { "active" }}\"?"
            },
        ) { args -> setCustomBindsTool(args) },

        "install_desktop" to ToolHandler(
            description = "Install a desktop environment on the active distro. Calls ProotManager.setupDesktop which downloads packages, configures VNC, and writes the launcher. Poll `inspect_proot.desktopSetupState` for progress. Failures are attributed to a DePhase (Packages / VncConfig / Marker) in both the state and the install log.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\", \"openbox\", \"labwc-native\").")
                    })
                    put("vncPassword", JSONObject().apply {
                        put("type", "string")
                        put("description", "VNC password. Defaults to empty (SecurityTypes None).")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                val family = active.family
                val compat = de?.spec?.compatibilityOn(family)
                val suffix = when (compat) {
                    sh.haven.core.local.proot.Compatibility.Experimental -> " — experimental on ${family.name}"
                    sh.haven.core.local.proot.Compatibility.Broken -> " — known broken on ${family.name}"
                    else -> ""
                }
                val label = de?.label ?: deId
                "Install ${label} on ${active.label}${suffix}?"
            },
        ) { args -> installDesktopTool(args) },

        "uninstall_desktop" to ToolHandler(
            description = "Remove a desktop environment from the active distro. Stops it first if running. Calls ProotManager.uninstallDesktop.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to uninstall.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Uninstall ${de?.label ?: deId} from ${active.label}?"
            },
        ) { args -> uninstallDesktopTool(args) },

        "start_desktop" to ToolHandler(
            description = "Start an installed desktop environment on the active distro. Calls DesktopManager.startDesktop; the launch is asynchronous. Returns the allocated display + vncPort so callers can connect a VNC client. Poll `inspect_proot.desktopEnvironments[].running` (or list_desktop_environments) to confirm RUNNING state. NestedWayland DEs (Sway, Hyprland, niri) bring up a wlroots/smithay compositor on the headless backend inside the rootfs and expose it via wayvnc on the returned port; X11Vnc DEs spawn Xvnc + the desktop; NativeCompositor runs the JNI labwc bridge.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to start.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                val active = localSessionManager.prootManager.activeDistro
                "Start ${de?.label ?: deId} on ${active.label}?"
            },
        ) { args -> startDesktopTool(args) },

        "stop_desktop" to ToolHandler(
            description = "Stop a running desktop environment. Tears down the compositor / Xvnc process tree and releases the display number.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id to stop.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val deId = args.optString("deId")
                val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries.firstOrNull { it.spec.id == deId }
                "Stop ${de?.label ?: deId}?"
            },
        ) { args -> stopDesktopTool(args) },

        "read_desktop_log" to ToolHandler(
            description = "Read a running (or just-failed) desktop's RUNTIME logs — distinct from inspect_proot, which only covers install state. For nested-Wayland DEs (Sway / Hyprland / niri) returns the compositor's own stdout/stderr (compositor.log: the wlr/[ERROR] lines, output-enable, buffer-allocation failures) plus Haven's captured launch-process output (the `[haven]` progress markers + wayvnc lines). This is the diagnostic for grey-screen / no-frames / compositor-refuses-to-start issues — the data that otherwise requires opening a proot shell. Pass deId to target one DE; omit for all running desktops.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"sway\"). Omit for all running desktops.")
                    })
                    put("maxChars", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Cap compositor.log to its last N chars. Default 4000.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readDesktopLog(args) },

        "list_desktop_windows" to ToolHandler(
            description = "Enumerate the visible top-level windows on a running desktop (deId), so an agent can target a specific application window (e.g. KiCad's schematic editor vs. PCB editor) before capturing it. Returns { deId, count, windows:[{id,title,x,y,width,height}] }. Works on X11/VNC desktops (via xdotool) and Sway nested-Wayland desktops (via swaymsg get_tree); other nested-Wayland compositors (Hyprland/niri/cage) aren't enumerable yet — use capture_desktop for a whole-output screenshot there. Installs the X11 capture toolset (xdotool + ImageMagick) on first use.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Let the agent list the windows open on desktop '${args.optString("deId")}'"
            },
        ) { args -> listDesktopWindows(args) },

        "capture_desktop" to ToolHandler(
            description = "Capture a screenshot of a running desktop (deId) and return it INLINE as an image the agent can see directly — no second port or file download. Works for both X11/VNC desktops (via ImageMagick `import`) and nested-Wayland desktops — Sway / Hyprland / niri / cage (via `grim`, the wlroots screenshooter; auto-installed on first use). Whole screen by default; a single window via windowId (from list_desktop_windows) is X11/VNC only — nested-Wayland captures the whole output. The image is downscaled to maxWidth and JPEG-encoded by default to stay cheap over the MCP tunnel. Captures inside the guest, so it works even when the user isn't on the VNC tab. Returns the image plus { deId, width, height, format, source, windowId?, windowTitle? }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.")
                    })
                    put("windowId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional X11 window id from list_desktop_windows. Captures just that window (cropped to its geometry). Omit for the whole screen.")
                    })
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val win = args.optString("windowId").takeIf { it.isNotBlank() }
                val what = if (win != null) "a window on" else "the screen of"
                "Let the agent see $what desktop '${args.optString("deId")}'"
            },
        ) { args -> captureDesktop(args) },

        "capture_desktop_tab" to ToolHandler(
            description = "Capture what a remote-desktop VIEWER tab (RDP, VNC, or SPICE) is actually rendering, INLINE as an image — the framebuffer the user sees, with the server cursor composited on top at the tracked pointer position. This is distinct from capture_desktop, which screenshots an in-guest X11/VNC desktop; this one captures the RDP/VNC/SPICE client viewer (e.g. to verify colours and the cursor against a remote Windows/Linux server). Pass profileId to pick a tab (from list_desktop_sessions); omit it when exactly one desktop tab is open. Returns the image plus { profileId, protocol, width, height, hasCursor, cursorWidth?, cursorHeight?, hotspotX?, hotspotY?, pointerX?, pointerY?, format }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one tab is open.")
                    })
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale so the image is at most this many pixels wide. Default 1280 (clamped 160–4096).")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${profileLabel(pid)}'" else "the open remote desktop"
                "Let the agent see what $who is rendering"
            },
        ) { args -> captureDesktopTab(args) },

        "tap_desktop_tab" to ToolHandler(
            description = "Click a point on a remote-desktop VIEWER tab (RDP/VNC/SPICE) — inject a mouse click into the remote server. Coordinates are in the REMOTE framebuffer's pixel space (the same space capture_desktop_tab reports: 0..width, 0..height), NOT Haven's own UI (that's tap_haven_ui). Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one desktop tab is open. Buttons follow X11: 1=left (default), 2=middle, 3=right. Keyboard typing is not yet supported (the session abstraction has no key verb). Returns { profileId, protocol, x, y, button }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id of the desktop tab (from list_desktop_sessions). Omit when exactly one is open.") })
                    put("x", JSONObject().apply { put("type", "integer"); put("description", "Remote framebuffer X (0..width from capture_desktop_tab).") })
                    put("y", JSONObject().apply { put("type", "integer"); put("description", "Remote framebuffer Y (0..height from capture_desktop_tab).") })
                    put("button", JSONObject().apply { put("type", "integer"); put("description", "X11 button: 1=left (default), 2=middle, 3=right.") })
                })
                put("required", JSONArray().put("x").put("y"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${profileLabel(pid)}'" else "the open remote desktop"
                "Click (${args.optInt("x")},${args.optInt("y")}) on $who"
            },
        ) { args -> tapDesktopTab(args) },

        "scroll_desktop_tab" to ToolHandler(
            description = "Scroll a remote-desktop VIEWER tab (RDP/VNC/SPICE) by injecting mouse-wheel notches into the remote server. deltaY > 0 scrolls down, < 0 scrolls up; magnitude is the number of notches. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, deltaY }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id (from list_desktop_sessions). Omit when exactly one is open.") })
                    put("deltaY", JSONObject().apply { put("type", "integer"); put("description", "Wheel notches: >0 scrolls down, <0 scrolls up.") })
                })
                put("required", JSONArray().put("deltaY"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${profileLabel(pid)}'" else "the open remote desktop"
                "Scroll $who"
            },
        ) { args -> scrollDesktopTab(args) },

        "send_desktop_clipboard" to ToolHandler(
            description = "Set the clipboard on a remote-desktop VIEWER tab (RDP/VNC) to the given text, so it can be pasted inside the remote server (Ctrl+V / right-click paste). This is the closest substitute for typing while keyboard injection is unsupported. Pass profileId to pick a tab (from list_desktop_sessions); omit when exactly one is open. Returns { profileId, protocol, chars }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id (from list_desktop_sessions). Omit when exactly one is open.") })
                    put("text", JSONObject().apply { put("type", "string"); put("description", "Text to place on the remote clipboard.") })
                })
                put("required", JSONArray().put("text"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId").takeIf { it.isNotBlank() }
                val who = if (pid != null) "desktop '${profileLabel(pid)}'" else "the open remote desktop"
                "Set $who clipboard (${args.optString("text").length} chars)"
            },
        ) { args -> sendDesktopClipboard(args) },

        "capture_haven_ui" to ToolHandler(
            description = "Capture HAVEN'S OWN rendered screen — the app UI the user is looking at right now (Connections list, terminal tab, a dialog, the file browser, an agent overlay), NOT a remote desktop (capture_desktop_tab) or the terminal text (read_terminal_snapshot). This is the 'perceive' half of the self-hosting loop: after install_apk_from_backend deploys a build, capture_haven_ui lets the agent see the result and diff it. Returns the image plus { width, height, imageWidth, imageHeight, format }. width/height are the FULL window in pixels — pass tap_haven_ui / swipe_haven_ui coordinates in THAT space even when the returned image was downscaled via maxWidth. If Settings → screen security (FLAG_SECURE) is on, returns { secure: true } with no image (capture is intentionally blocked). Errors if Haven is not in the foreground.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale so the returned image is at most this many pixels wide (the reported width/height stay full-window). Default 1080 (clamped 160–4096).")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"jpeg\" (default, smaller) or \"png\" (lossless, larger).")
                    })
                })
            },
            // A screen capture is a "let the agent SEE Haven's screen" act,
            // matching capture_desktop_tab — ONCE_PER_SESSION, not NEVER,
            // because Haven's own UI can show credentials/keys. Loopback /
            // paired clients bypass per the standard consent model.
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Let the agent see Haven's own screen" },
        ) { args -> captureHavenUi(args) },

        "dump_haven_ui" to ToolHandler(
            description = "Dump Haven's OWN foreground UI as a structured element list — the in-app equivalent of `uiautomator dump`, so you get EXACT control bounds instead of estimating them off a capture_haven_ui image. Returns { width, height, count, nodes:[{text, contentDescription, editableText, role, clickable, disabled, bounds:[left,top,right,bottom], centerX, centerY}] } in the SAME window-pixel space tap_haven_ui / swipe_haven_ui use — read a control's centerX/centerY and tap it directly. Phase 1: the activity window only; Compose dialogs and bottom sheets render in separate windows that capture/tap/dump don't yet reach. FLAG_SECURE blocks it. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Let the agent read Haven's own screen structure" },
        ) { _ -> dumpHavenUi() },

        "tap_haven_ui" to ToolHandler(
            description = "Inject a tap (or, with holdMs > 0, a press-and-hold) into HAVEN'S OWN UI at window-pixel (x, y) — the same coordinate space capture_haven_ui reports in its width/height. This is the 'drive' half of the self-hosting loop: read a control's position from a capture_haven_ui image, then tap it. Drives the real touch pipeline (Compose clickables, nav tabs, dialog buttons). Refused while a consent prompt is showing (so an injected tap can't self-confirm) and when Haven is not foreground. Returns { delivered, reason?, x, y, holdMs }. Verify the effect with a follow-up capture_haven_ui.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().apply { put("type", "integer"); put("description", "Window-pixel X (0..width from capture_haven_ui).") })
                    put("y", JSONObject().apply { put("type", "integer"); put("description", "Window-pixel Y (0..height from capture_haven_ui).") })
                    put("holdMs", JSONObject().apply { put("type", "integer"); put("description", "Press-and-hold duration. 0 (default) = a quick tap; >~500 triggers a long-press. Clamped 0–10000.") })
                })
                put("required", JSONArray().put("x").put("y"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val hold = args.optLong("holdMs", 0L)
                val verb = if (hold > 0) "Press-and-hold (${hold}ms)" else "Tap"
                "$verb Haven's own UI at (${args.optInt("x")}, ${args.optInt("y")})?"
            },
        ) { args -> tapHavenUi(args) },

        "swipe_haven_ui" to ToolHandler(
            description = "Inject a swipe/drag into HAVEN'S OWN UI from (fromX, fromY) to (toX, toY) in window pixels (the coordinate space capture_haven_ui reports), over durationMs split into N steps. Drives pager flings (swipe between Connections/Terminal/Files tabs), list scrolls, and bottom-sheet drags. Refused while a consent prompt is showing and when Haven is not foreground. Returns { delivered, reason?, fromX, fromY, toX, toY, durationMs, steps }. Verify with capture_haven_ui.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("fromX", JSONObject().apply { put("type", "integer"); put("description", "Start X (window px).") })
                    put("fromY", JSONObject().apply { put("type", "integer"); put("description", "Start Y (window px).") })
                    put("toX", JSONObject().apply { put("type", "integer"); put("description", "End X (window px).") })
                    put("toY", JSONObject().apply { put("type", "integer"); put("description", "End Y (window px).") })
                    put("durationMs", JSONObject().apply { put("type", "integer"); put("description", "Total swipe time. Default 200 (clamped 1–10000). Longer = slower drag (less fling momentum).") })
                    put("steps", JSONObject().apply { put("type", "integer"); put("description", "ACTION_MOVE events between down and up. Default 16 (clamped 1–200).") })
                })
                put("required", JSONArray().put("fromX").put("fromY").put("toX").put("toY"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                "Swipe Haven's own UI (${args.optInt("fromX")}, ${args.optInt("fromY")}) → (${args.optInt("toX")}, ${args.optInt("toY")})?"
            },
        ) { args -> swipeHavenUi(args) },

        "create_standing_policy" to ToolHandler(
            description = "Propose a Tier-3 STANDING POLICY: a scoped, rate-capped, expiring grant that lets THIS client call the listed tools without a per-call consent prompt. The user's tap on this tool's consent sheet IS the installation — the sheet spells out the full scope. Use it when a workflow needs many consented calls in a row (e.g. a tap_haven_ui/swipe_haven_ui drive-and-verify loop) so the user grants the loop once instead of per tap. toolNames must be existing tools; some can never be covered (the policy tools themselves, install_apk_*, unpair_mcp_client). argConstraints (optional) pins arguments: every key given must exactly equal the call's argument (e.g. {\"profileId\":\"<id>\"} scopes the grant to one connection). Covered calls are still written to the audit log; the rate ceiling makes extra calls fall back to normal prompts; the policy expires on its own and can be revoked any time from Haven's Agent activity screen or via revoke_standing_policy. Returns { id, expiresAt }.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("description", JSONObject().apply { put("type", "string"); put("description", "Short human label for what this grant is for, shown on the consent sheet and the kill-switch list. E.g. \"Drive the UI to verify build 5.59.54\".") })
                    put("toolNames", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "Exact tool names the policy covers.")
                    })
                    put("maxCallsPerMinute", JSONObject().apply { put("type", "integer"); put("description", "Rate ceiling per rolling minute (default 60, clamped 1–600). Beyond it, calls fall back to per-call prompts.") })
                    put("expiresInMinutes", JSONObject().apply { put("type", "integer"); put("description", "Lifetime (default 60, clamped 5–1440). After expiry the policy never applies.") })
                    put("argConstraints", JSONObject().apply { put("type", "object"); put("description", "Optional exact-match argument pins, e.g. {\"profileId\":\"abc\"}.") })
                })
                put("required", JSONArray().put("description").put("toolNames"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val names = args.optJSONArray("toolNames")?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                } ?: "?"
                val rate = args.optInt("maxCallsPerMinute", 60).coerceIn(1, 600)
                val mins = args.optInt("expiresInMinutes", 60).coerceIn(5, 1440)
                val pins = args.optJSONObject("argConstraints")?.toString()?.let { " pinned to $it" } ?: ""
                "Install STANDING POLICY \"${args.optString("description")}\": allow [$names]$pins WITHOUT per-call prompts, up to $rate calls/min, for the next $mins min?"
            },
        ) { args -> createStandingPolicy(args) },

        "list_standing_policies" to ToolHandler(
            description = "List Tier-3 standing policies: id, client, description, covered tools, argConstraints, maxCallsPerMinute, expiresAt, remainingMinutes, enabled, active. Read-only.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> listStandingPolicies() },

        "revoke_standing_policy" to ToolHandler(
            description = "Revoke (delete) a standing policy by id — see list_standing_policies. Pure privilege reduction, so no prompt; the user's kill-switch lives on the Agent activity screen.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Policy id from list_standing_policies.") })
                })
                put("required", JSONArray().put("id"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> revokeStandingPolicy(args) },

        "launch_app_in_desktop" to ToolHandler(
            description = "Launch a GUI application into a RUNNING desktop (deId). X11/VNC desktops get DISPLAY/XAUTHORITY; nested-Wayland desktops (Sway/Hyprland/niri/cage) get XDG_RUNTIME_DIR/WAYLAND_DISPLAY. The software-GL fallback (LIBGL_ALWAYS_SOFTWARE=1, GALLIUM_DRIVER=llvmpipe) is exported either way, so GPU-less GL apps like KiCad/eeschema don't crash their canvas. Optionally waits for the app's window to appear and returns its windowId — pass that to capture_desktop to screenshot just that window (window-wait/windowId need enumeration: X11 and Sway; on other nested-Wayland compositors the app still launches but no windowId is returned). The app keeps running after this returns. For looking at saved design FILES prefer view_file (headless, no desktop needed); use this when you need the live interactive app.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id (e.g. \"xfce4\") of a RUNNING X11/VNC desktop.")
                    })
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command to launch, e.g. 'eeschema /root/proj/board.kicad_sch'.")
                    })
                    put("waitForWindowTitle", JSONObject().apply {
                        put("type", "string")
                        put("description", "If set, poll until a window whose title contains this substring (case-insensitive) appears; otherwise return the first new window seen.")
                    })
                    put("timeoutMs", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max ms to wait for the window. Default 15000, clamped 0..60000. 0 = launch and return without waiting.")
                    })
                })
                put("required", JSONArray().put("deId").put("command"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Launch '${args.optString("command")}' on desktop '${args.optString("deId")}'" },
        ) { args -> launchAppInDesktop(args) },
        "open_desktop_terminal" to ToolHandler(
            description = "Open an interactive local PRoot shell whose environment JOINS a RUNNING desktop (deId) — exports DISPLAY (X11/VNC) or WAYLAND_DISPLAY + XDG_RUNTIME_DIR (nested-Wayland / native labwc) — so you can drive the desktop's apps from the command line (e.g. launch/inspect GUI programs in the same session a user is viewing over VNC). Returns a sessionId usable with send_terminal_input / read_terminal_scrollback, plus the resolved display/waylandDisplay/xdgRuntimeDir. Always a fresh session (a reused plain shell would lack the display env). The desktop must already be RUNNING (start_desktop). Unlike launch_app_in_desktop (fire-and-forget single app), this gives you an interactive shell.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id of a RUNNING desktop (e.g. \"openbox\", \"xfce4\", \"sway\").")
                    })
                    put("plain", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Skip the user's sessionManager preference and exec a bare login shell. Default false.")
                    })
                })
                put("required", JSONArray().put("deId"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> openDesktopTerminal(args) },

        "gl_smoke_test" to ToolHandler(
            description = "Launch a GL app into a RUNNING desktop on the GPU PATH (venus/virpipe — NOT the llvmpipe software fallback that launch_app_in_desktop forces), screenshot it, and heuristically report whether the frame is non-blank. A regression check for the windowed-GL-present pipeline (a blank/white frame = GL didn't present). The verdict is reliable only for a FULL-FRAME GL app (a fullscreen / cage-kiosk GL test app like 'glxgears' or 'es2gears'); for a windowed app the 2D chrome masks a blank 3D pane, so rely on the returned image. Optionally writes the gpu_use_venus pref first. Returns the screenshot plus { passed, distinctColors, topColorFraction, gpuPath, windowId? }. Detects non-blank, not correctness.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("deId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Desktop environment id of a RUNNING desktop (prefer a fullscreen/kiosk GL surface for a clean verdict).")
                    })
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "GL app to launch, e.g. 'glxgears' or 'es2gears'.")
                    })
                    put("gpuUseVenus", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If set, write the gpu_use_venus pref before launching (true = venus+zink, false = virpipe). Omit to leave it unchanged.")
                    })
                    put("timeoutMs", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max ms to wait for the app window before capturing. Default 12000, clamped 0..60000.")
                    })
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale the returned image to at most this width. Default 1024 (clamped 160..4096).")
                    })
                })
                put("required", JSONArray().put("deId").put("command"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "GL smoke-test '${args.optString("command")}' on desktop '${args.optString("deId")}'" },
        ) { args -> glSmokeTest(args) },

        "view_file" to ToolHandler(
            description = "Render a file from the ACTIVE proot guest to an INLINE image the agent can see directly — no desktop, X server, VNC client, or GPU needed (fully headless / GL-free). Handles .kicad_sch and .kicad_pcb (via kicad-cli), .pdf (first page, or `page`), .svg, and raster images (png/jpg/jpeg/webp/bmp/gif). The result is downscaled to maxWidth and returned as an image content block, so it works even when no VNC desktop is running. Prefer this over capture_desktop for looking at design output (schematics, PCBs, PDFs, plots).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file inside the active proot guest (e.g. /root/proj/board.kicad_sch).")
                    })
                    put("maxWidth", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Downscale so the image is at most this many pixels wide. Default 1024 (clamped 160–4096).")
                    })
                    put("format", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"png\" (default, lossless) or \"jpeg\" (smaller over the tunnel).")
                    })
                    put("page", JSONObject().apply {
                        put("type", "integer")
                        put("description", "For multi-page PDFs, the 1-based page to render. Default 1.")
                    })
                })
                put("required", JSONArray().put("path"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> viewFile(args) },

        "read_guest_file" to ToolHandler(
            description = "Read a small file from the ACTIVE proot guest and return its contents to the agent (UTF-8 text; set asBase64 only for small binary). The reliable agent⇄guest text channel. Reads are capped to maxBytes. For large or binary files prefer serve_file (streams over a loopback URL — no base64 through the agent); for images/PDFs/schematics use view_file (renders to an inline picture); for anything the USER should see use present_media.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file inside the active proot guest.")
                    })
                    put("asBase64", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Return base64 instead of UTF-8 text — only for small binary files; prefer serve_file for larger binaries. Default false.")
                    })
                    put("maxBytes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Read at most this many bytes. Default 262144 (256 KiB), clamped 1..8388608.")
                    })
                })
                put("required", JSONArray().put("path"))
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> readGuestFile(args) },

        "write_guest_file" to ToolHandler(
            description = "Write a file into the ACTIVE proot guest. Supply `content` (UTF-8 text); for binary prefer upload_file (stages a device-cache file into the guest) over `contentBase64`. Parent directories are created by default. The reliable way to push agent-authored text files (scripts, generators, configs) into the guest without a terminal heredoc. For large files, send in ordered chunks: first chunk {append:false, final:false}, middle chunks {append:true, final:false}, last chunk {append:true, final:true} — the file lands in the guest only on the final chunk.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute destination path inside the active proot guest.")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "UTF-8 text to write. Mutually exclusive with contentBase64.")
                    })
                    put("contentBase64", JSONObject().apply {
                        put("type", "string")
                        put("description", "Base64-encoded bytes for small binary writes; prefer upload_file for larger/binary files. Mutually exclusive with content.")
                    })
                    put("mkdirs", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Create parent directories if missing. Default true.")
                    })
                    put("append", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Append this chunk to the buffered bytes for this path instead of starting fresh. Default false (truncate). Use true for chunks after the first when sending a large file in pieces.")
                    })
                    put("final", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Copy the buffered bytes into the guest now. Default true (single-call write). Set false on every chunk except the last; the file isn't written into the guest until final=true.")
                    })
                })
                put("required", JSONArray().put("path"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Write a file to '${args.optString("path")}' in the guest" },
        ) { args -> writeGuestFile(args) },

        "get_proot_install_log" to ToolHandler(
            description = "Return install-log events from the Room-backed ProotInstallLog table. Survives logcat rotation and app restarts. Filter by distroId and/or sinceMs (millis since epoch) to poll incrementally. Each event: id, timestamp, distroId, phase, deId?, exit?, ok, message?, logTail?.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distroId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Filter to a single distro. Omit for all distros.")
                    })
                    put("sinceMs", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Return only events with timestamp > sinceMs. Default 0 (return all).")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Max events. Default 100.")
                    })
                })
            },
            consentLevel = ConsentLevel.NEVER,
        ) { args -> getProotInstallLog(args) },

        "run_in_proot" to ToolHandler(
            description = "Run a shell command inside the ACTIVE distro's proot guest (the same rootfs the running desktop uses) and return its combined stdout+stderr. Distro-agnostic: invokes /bin/sh -lc in whatever distro is active (check inspect_proot.activeDistroId), so it works on Debian/Arch/Void, not just Alpine like open_local_shell. For long jobs (apt-get install, pip install) you can pass background:true to get a jobId immediately, then poll by calling again with that jobId — the response carries the accumulated output and, once finished, the exitCode. Even without background, a synchronous call that runs longer than ~30s is auto-backgrounded: it returns {jobId, status:\"running\", note, output:<partial>} instead of blocking past the MCP request timeout, and you poll it the same way. A quick command returns inline ({exitCode, output}). This is the agent's headless way to provision the guest (install packages, run kicad-cli ERC/DRC, etc.).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command to run via /bin/sh -lc in the active proot. Required unless polling via jobId.")
                    })
                    put("background", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, start the command and return a jobId immediately instead of blocking. Poll with that jobId. Default false.")
                    })
                    put("jobId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Poll a previously started background job. When set, `command` is ignored and the current status + accumulated output are returned.")
                    })
                    put("maxOutputChars", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Cap the returned output to its last N chars. Default 8000.")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val jid = args.optString("jobId").takeIf { it.isNotBlank() }
                if (jid != null) "Let the agent read output of guest job $jid"
                else "Let the agent run in the guest: ${args.optString("command").take(120)}"
            },
        ) { args -> runInProot(args) },

        "register_guest_service" to ToolHandler(
            description = "Register a long-lived helper process to run inside the ACTIVE distro's proot guest — typically an app-native MCP server (KiCad/FreeCAD/OpenSCAD) the agent drives for structured control. Haven supervises it: starts it (if autostart) when the MCP endpoint comes up, re-launches it after an app restart, and — when an MCP reverse-tunnel endpoint is configured — multiplexes its loopback `port` back to the remote MCP client alongside Haven's own endpoint (no adb forward needed). The registry is persisted per-distro. Returns the generated service id. Use start_guest_service to launch it now.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Human-readable name, e.g. \"KiCad MCP\".")
                    })
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command run via /bin/sh -lc in the guest, e.g. 'cd /root/kicad-mcp && UV_LINK_MODE=copy uv run python http_server.py'. Should run in the foreground (Haven owns the process).")
                    })
                    put("port", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Loopback TCP port the service listens on inside the guest (e.g. 8766). Multiplexed over the MCP reverse tunnel.")
                    })
                    put("autostart", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Re-launch automatically when Haven's MCP endpoint comes up / after app restart. Default true.")
                    })
                    put("isMcp", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "True if this service is itself a streamable-HTTP MCP server. Haven then aggregates its tools into its own MCP surface, namespaced 'guest_<id>_<tool>', so you call them through Haven directly. Default false.")
                    })
                    put("mcpPath", JSONObject().apply {
                        put("type", "string")
                        put("description", "HTTP path of the guest MCP endpoint when isMcp=true. Default '/mcp'.")
                    })
                })
                put("required", JSONArray().put("label").put("command").put("port"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Register guest service '${args.optString("label")}' (runs: ${args.optString("command").take(80)})" },
        ) { args -> registerGuestService(args) },

        "list_guest_services" to ToolHandler(
            description = "List guest services registered on the active distro with their live state (STOPPED/STARTING/RUNNING/ERROR), command, port, autostart flag, and last error/output tail. Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listGuestServices() },

        "start_guest_service" to ToolHandler(
            description = "Start a registered guest service by id (no-op if already running). Returns its state.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Service id from register_guest_service / list_guest_services.") })
                })
                put("required", JSONArray().put("id"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Start guest service ${args.optString("id")}" },
        ) { args -> startGuestService(args) },

        "stop_guest_service" to ToolHandler(
            description = "Stop a running guest service by id (leaves it registered).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Service id.") })
                })
                put("required", JSONArray().put("id"))
            },
        ) { args -> stopGuestService(args) },

        "unregister_guest_service" to ToolHandler(
            description = "Stop (if running) and remove a guest service from the registry by id.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("id", JSONObject().apply { put("type", "string"); put("description", "Service id.") })
                })
                put("required", JSONArray().put("id"))
            },
        ) { args -> unregisterGuestService(args) },

        "start_audio_bridge" to ToolHandler(
            description = "Start the proot audio bridge (#257): launches a PulseAudio daemon in the active distro and plays its output through the Android speaker — output only, no mic. Guest apps reach it via PULSE_SERVER (written to /etc/profile.d/pulse.sh and exported into desktop sessions), so apps launched from a login shell / desktop get sound. Installs pulseaudio on first use. Idempotent. Returns { state, port }.",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Start proot audio bridge" },
        ) { _ -> startAudioBridge() },

        "stop_audio_bridge" to ToolHandler(
            description = "Stop the proot audio bridge: kills the in-guest PulseAudio daemon and releases the Android AudioTrack.",
            inputSchema = emptyObjectSchema(),
            summarise = { _ -> "Stop proot audio bridge" },
        ) { _ -> stopAudioBridge() },

        "get_audio_bridge_status" to ToolHandler(
            description = "Proot audio bridge status: state (STOPPED/STARTING/RUNNING/ERROR), loopback PCM port, bytesStreamed so far, and any error. Read-only — a quick way to confirm guest audio is actually flowing.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> audioBridgeStatusJson() },

        "set_terminal_font_from_url" to ToolHandler(
            description = "Download a font from a URL, validate it, install it as Haven's terminal font (replacing any prior custom font), and return the saved path. The URL may point at a .ttf/.otf, or a .zip containing them (a Regular face is auto-extracted) — useful for repos like Maple/Nerd Fonts that ship only zips (#123, #177). WOFF/WOFF2 web fonts are rejected (Android can't render them). Requires the URL to be reachable from the device — use a tunneled URL (via add_port_forward LOCAL) to expose a workstation HTTP server back through the existing SSH session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "http(s) URL resolving to a .ttf/.otf, or a .zip of them (no HTML wrapper). WOFF/WOFF2 are not supported.")
                    })
                })
                put("required", JSONArray().put("url"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Download terminal font from ${args.optString("url").take(80)}?" },
        ) { args -> setTerminalFontFromUrl(args) },

        "convert_file" to ToolHandler(
            description = "Run ffmpeg to transcode a source URL (typically the playerUrl/playlistUrl from stream_sftp_file, or any URL ffmpeg can read) into a new file in Haven's app cache. Returns the cache path on success — use upload_file_to_sftp to put the result on a remote.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sourceUrl", JSONObject().apply { put("type", "string"); put("description", "URL ffmpeg should read from. http(s)://, file://, or any protocol ffmpeg supports.") })
                    put("container", JSONObject().apply { put("type", "string"); put("description", "Output container, e.g. 'mp4', 'mkv', 'webm'. Determines the file extension.") })
                    put("videoEncoder", JSONObject().apply { put("type", "string"); put("description", "Video codec, e.g. 'libx264', 'libx265', 'copy'. Default: copy.") })
                    put("audioEncoder", JSONObject().apply { put("type", "string"); put("description", "Audio codec, e.g. 'aac', 'libopus', 'copy'. Default: copy.") })
                    put("outputName", JSONObject().apply { put("type", "string"); put("description", "Optional output filename (without extension). Default: 'agent-convert-<timestamp>'.") })
                })
                put("required", JSONArray().put("sourceUrl").put("container"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val container = args.optString("container", "mp4")
                val ve = args.optString("videoEncoder", "copy")
                val ae = args.optString("audioEncoder", "copy")
                "Transcode source → $container (video: $ve, audio: $ae)?"
            },
        ) { args -> convertFile(args) },

        "list_workspaces" to ToolHandler(
            description = "List the user's saved workspace profiles — named bundles of terminal sessions, file-browser tabs, remote desktops, and Wayland that compose_workspace can reopen in one shot. Each entry has id, name, and itemCount. The kinds of items inside come from the same Kind enum the UI uses (TERMINAL / FILE_BROWSER / DESKTOP / WAYLAND).",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listWorkspaces() },

        "compose_workspace" to ToolHandler(
            description = "Launch every item of a saved workspace through the same WorkspaceLauncher the user's tap goes through — terminal sessions, file-browser tabs, remote desktops, and the Wayland tab open in dependency-friendly order (TERMINAL first so tunneled DESKTOPs attach to live SSH sessions). Returns the workspace id and item count; progress is surfaced live in the Connections screen banner.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("workspaceId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Workspace id from list_workspaces.")
                    })
                })
                put("required", JSONArray().put("workspaceId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val id = args.optString("workspaceId")
                val name = workspaceLabel(id)
                "Open workspace \"$name\"?"
            },
        ) { args -> composeWorkspace(args) },

        "list_ssh_keys" to ToolHandler(
            description = "List saved SSH keys available for SSH / Mosh / SFTP profiles. Returns id, label, keyType (e.g. ed25519, rsa, sk-ssh-ed25519@openssh.com), publicKeyOpenSsh, fingerprintSha256, isEncrypted (passphrase-protected), biometricProtected, enabledForAuth (whether it's offered in 'any saved key' auto-auth), verifyRequired (FIDO2/SK keys only: requires its PIN at sign-in), and createdAt. Set enabledForAuth / verifyRequired via set_ssh_key_option. Private key bytes are NEVER returned — they stay encrypted at rest.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSshKeys() },

        "import_ssh_key" to ToolHandler(
            description = "Import an OpenSSH / PEM / PKCS#8 / PuTTY PPK private key into the Haven key store. Pass `privateKey` (the text body, e.g. starting with `-----BEGIN OPENSSH PRIVATE KEY-----`), `label` (user-facing name), and optional `passphrase` (only if the key is encrypted). Returns the new key id, keyType, publicKeyOpenSsh (suitable for an `authorized_keys` line), and fingerprintSha256.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("privateKey", JSONObject().apply {
                        put("type", "string")
                        put("description", "Private key body in OpenSSH / PEM / PKCS#8 / PuTTY format. Pass the file's text contents verbatim, including BEGIN/END lines.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "User-facing label shown on the Keys screen and in profile pickers.")
                    })
                    put("passphrase", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Only required if the private key is encrypted at rest. Stored only briefly to decrypt the key for parsing; the saved entity keeps the original (still-encrypted) bytes.")
                    })
                })
                put("required", JSONArray().put("privateKey").put("label"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val label = args.optString("label", "(unnamed)")
                "Import SSH key \"$label\" into the Haven key store?"
            },
        ) { args -> importSshKey(args) },

        "delete_ssh_key" to ToolHandler(
            description = "Delete a saved SSH key by id. Profiles that referenced it via sshKeyId will fall through to password auth (or fail) on next connect — no cascade rewrite. Irreversible: the encrypted private key bytes are removed.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sshKeyId", JSONObject().apply {
                        put("type", "string")
                        put("description", "SSH key id from list_ssh_keys.")
                    })
                })
                put("required", JSONArray().put("sshKeyId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("sshKeyId")
                val label = runBlocking { sshKeyRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete SSH key \"$label\"? Cannot be undone."
            },
        ) { args -> deleteSshKey(args) },

        "set_ssh_key_option" to ToolHandler(
            description = "Set per-key options on a saved SSH key (the toggles on the Keys screen). " +
                "`keyId` (from list_ssh_keys) is required; pass either or both of: " +
                "`enabledForAuth` (bool) — whether the key takes part in 'any saved key' auto-auth (off = only used when a profile pins it); " +
                "`verifyRequired` (bool) — FIDO2/SK keys only — whether the key requires its PIN at every sign-in (true) or is touch-only (false); flips the SK flag in place without re-registering. " +
                "Returns the key's resulting enabledForAuth and verifyRequired. Biometric-protected SK keys can't have verifyRequired changed over MCP (no prompt available).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("keyId", JSONObject().apply {
                        put("type", "string"); put("description", "SSH key id from list_ssh_keys.")
                    })
                    put("enabledForAuth", JSONObject().apply {
                        put("type", "boolean"); put("description", "Include this key in 'any saved key' auto-auth.")
                    })
                    put("verifyRequired", JSONObject().apply {
                        put("type", "boolean"); put("description", "FIDO2/SK only: require the key's PIN at every sign-in.")
                    })
                })
                put("required", JSONArray().put("keyId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
        ) { args -> setSshKeyOption(args) },

        "list_snippets" to ToolHandler(
            description = "List terminal toolbar snippets (custom send-key macros reachable from the scissors button). " +
                "Returns each snippet's label, send (the literal text/escape sequence it types), and placement: " +
                "\"row1\"/\"row2\" (has a dedicated toolbar button on that row) or \"library\" (in the scissors sheet only, no button). " +
                "Manage them with set_snippet.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listSnippets() },

        "set_snippet" to ToolHandler(
            description = "Add, move, update or delete a terminal toolbar snippet (#244). `label` is required. " +
                "To add/update: pass `send` (the text to type; JSON escapes like \\n for Enter and \\u001b for Esc work) and optional " +
                "`placement` — \"row1\"/\"row2\" gives it a toolbar button on that row, \"library\" (the default) keeps it in the scissors " +
                "sheet only. Re-passing an existing label moves/updates it. To remove it from both the toolbar and the library, pass " +
                "`delete`:true. Returns the affected snippet's resulting placement and the full snippet list.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Snippet label (its name; also the button caption when placed on a row).")
                    })
                    put("send", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text the snippet types. Required for a new snippet. JSON escapes work: \\n = Enter, \\u001b = Esc.")
                    })
                    put("placement", JSONObject().apply {
                        put("type", "string")
                        put("description", "\"row1\", \"row2\" (toolbar button) or \"library\" (scissors-only; default).")
                    })
                    put("delete", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Remove the snippet from the toolbar and the library entirely.")
                    })
                })
                put("required", JSONArray().put("label"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
        ) { args -> setSnippet(args) },

        "list_totp_secrets" to ToolHandler(
            description = "List saved OATH-TOTP authenticator secrets (#178). Returns id, label, issuer, accountName, algorithm, digits, periodSeconds, and createdAt. The base32 secret itself is NEVER returned — it stays encrypted at rest. Reference an id as a `TOTP:<id>` token in create_connection's authMethods to auto-fill the SSH 'Verification code:' prompt.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTotpSecrets() },

        "create_totp_secret" to ToolHandler(
            description = "Store an OATH-TOTP secret so it can auto-fill an SSH keyboard-interactive OTP prompt (#178). Pass `otpauth` (an `otpauth://totp/...` URI) OR `secret` (a raw base32 string) plus an optional `label`. Returns the new secret id; reference it via a `TOTP:<id>` token in create_connection's authMethods.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("otpauth", JSONObject().apply {
                        put("type", "string")
                        put("description", "An otpauth://totp/Issuer:account?secret=...&... URI. Mutually exclusive with `secret`.")
                    })
                    put("secret", JSONObject().apply {
                        put("type", "string")
                        put("description", "A raw base32 TOTP secret (SHA1, 6 digits, 30s period assumed). Use `otpauth` instead when you have the full URI.")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional user-facing label. Defaults to the otpauth issuer/account or 'Authenticator'.")
                    })
                })
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val l = args.optString("label").ifBlank { "(from otpauth)" }
                "Store a TOTP authenticator secret \"$l\" in the Haven key store?"
            },
        ) { args -> createTotpSecret(args) },

        "delete_totp_secret" to ToolHandler(
            description = "Delete a saved TOTP secret by id. Profiles referencing it via a TOTP auth element fall through to a manual OTP prompt on next connect. Irreversible.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("totpSecretId", JSONObject().apply {
                        put("type", "string")
                        put("description", "TOTP secret id from list_totp_secrets.")
                    })
                })
                put("required", JSONArray().put("totpSecretId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val id = args.optString("totpSecretId")
                val label = runBlocking { totpSecretRepository.getById(id)?.label } ?: id.take(8) + "…"
                "Delete TOTP secret \"$label\"? Cannot be undone."
            },
        ) { args -> deleteTotpSecret(args) },

        "list_age_identities" to ToolHandler(
            description = "List saved age file-encryption identities (VISION §2). Returns id, label, the public `age1…` recipient (encrypt to this with encrypt_file or the file browser's Encrypt action), and createdAt. The private key (AGE-SECRET-KEY-1…) is NEVER returned — it stays encrypted at rest.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listAgeIdentities() },

        "create_age_identity" to ToolHandler(
            description = "Generate and store a new age X25519 encryption identity (VISION §2). Optional `label`. Returns the new id and its public `age1…` recipient. Tap-equivalent to Keys → + → Generate age identity.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional user-facing label. Defaults to 'age identity'.")
                    })
                })
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val l = args.optString("label").ifBlank { "age identity" }
                "Generate a new age encryption identity \"$l\" in the Haven key store?"
            },
        ) { args -> createAgeIdentity(args) },

        "encrypt_file" to ToolHandler(
            description = "Encrypt the file at `path` on `profileId` to age recipients, producing `<name>.age` in the same folder (VISION §2 — works on every backend: local, SFTP, SMB, rclone). `recipients` (optional) is a list of `age1…` strings; omit it to encrypt to ALL of your stored age identities (so you can decrypt it back). Drives the file browser's Encrypt (age) action via the UI command bus — the user sees it run and the output appear. Non-destructive (the original is kept). Use list_age_identities for recipients and list_directory to find paths.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile id (or 'local'). From list_connections.")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the file to encrypt.")
                    })
                    put("recipients", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "age1… recipients. Omit to encrypt to all stored identities.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Encrypt \"${args.optString("path")}\" with age?" },
        ) { args -> encryptFileViaAgent(args) },

        "decrypt_file" to ToolHandler(
            description = "Decrypt the `.age` file at `path` on `profileId` in place (strips `.age`) using any stored age identity (VISION §2). Drives the file browser's Decrypt (age) action via the UI command bus — the user sees it run. Fails to produce output if no stored identity matches the file's recipients.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Connection profile id (or 'local').")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Absolute path to the .age file.")
                    })
                })
                put("required", JSONArray().put("profileId").put("path"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Decrypt \"${args.optString("path")}\" with age?" },
        ) { args -> decryptFileViaAgent(args) },

        "list_tunnels" to ToolHandler(
            description = "List saved WireGuard / Tailscale tunnel configs available for Route-through on connection profiles. Returns id, label, type (WIREGUARD or TAILSCALE), and createdAt for each. The encrypted configText (wg-quick payload or Tailscale authkey blob) is NOT returned.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listTunnels() },

        "list_live_tunnels" to ToolHandler(
            description = "Return the live-tunnel snapshot from TunnelManager — every tunnel currently up, paired with the set of profile ids holding it. Useful for verifying refcount semantics in #149 integration tests: confirm the tunnel stays open while a sibling transport keeps it acquired, and that it tears down on the last release.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> listLiveTunnels() },

        "create_tunnel" to ToolHandler(
            description = "Add a new WireGuard, Tailscale, or Cloudflare Tunnel config. WIREGUARD: pass `configText` (wg-quick INI body). TAILSCALE: pass `tailscaleAuthKey` (and optional `tailscaleControlUrl` for Headscale). CLOUDFLARE_ACCESS: pass `accessHostname`; for Access-protected routes also pass `accessJwt` (from `cloudflared access token --app https://<host>`); optional `accessJumpDestination` for bastion-mode multi-target tunnels. Returns the new tunnel id, which can then be passed to set_profile_routing.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "User-facing label (also used to derive the Tailscale hostname).")
                    })
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("description", "WIREGUARD, TAILSCALE, or CLOUDFLARE_ACCESS.")
                    })
                    put("configText", JSONObject().apply {
                        put("type", "string")
                        put("description", "WireGuard wg-quick INI body. Required when type=WIREGUARD.")
                    })
                    put("tailscaleAuthKey", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tailscale single-use authkey (tskey-auth-...). Required when type=TAILSCALE.")
                    })
                    put("tailscaleControlUrl", JSONObject().apply {
                        put("type", "string")
                        put("description", "Self-hosted Headscale coordination URL. Optional — empty defaults to controlplane.tailscale.com.")
                    })
                    put("accessHostname", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Tunnel published hostname (e.g. ssh.example.com). Required when type=CLOUDFLARE_ACCESS.")
                    })
                    put("accessJwt", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Access JWT (`CF_Authorization` value). Optional — only needed when the Tunnel route is Access-protected.")
                    })
                    put("accessTeamDomain", JSONObject().apply {
                        put("type", "string")
                        put("description", "Cloudflare Access team domain (myteam.cloudflareaccess.com). Optional; only meaningful for Access-protected routes.")
                    })
                    put("accessExpiresAt", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Optional explicit JWT expiry (Unix epoch seconds). Defaults to parsing the `exp` claim out of accessJwt.")
                    })
                    put("accessJumpDestination", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional `Cf-Access-Jump-Destination` value for bastion-mode multi-target tunnels (e.g. internal-host:22).")
                    })
                })
                put("required", JSONArray().put("label").put("type"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("type")
                val label = args.optString("label", "(unnamed)")
                "Add $type tunnel \"$label\" to the keystore?"
            },
        ) { args -> createTunnel(args) },

        "delete_tunnel" to ToolHandler(
            description = "Delete a saved tunnel config by id. Profiles that referenced it via tunnelConfigId will fall through to direct dialling on next connect.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("tunnelConfigId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tunnel id from list_tunnels.")
                    })
                })
                put("required", JSONArray().put("tunnelConfigId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Delete tunnel ${args.optString("tunnelConfigId").take(8)}…?" },
        ) { args -> deleteTunnel(args) },

        "set_profile_routing" to ToolHandler(
            description = "Set or clear the Route-through configuration on a connection profile. Pass either tunnelConfigId (WireGuard / Tailscale tunnel from list_tunnels) OR proxyType+proxyHost+proxyPort (legacy SOCKS5 / SOCKS4 / HTTP), and the other field set is cleared — mutually exclusive at the data layer too. Pass `clear=true` to drop both and route direct.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Profile id from list_connections.")
                    })
                    put("tunnelConfigId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Tunnel id to route through. Mutually exclusive with proxyType.")
                    })
                    put("proxyType", JSONObject().apply {
                        put("type", "string")
                        put("description", "SOCKS5 | SOCKS4 | HTTP. Pair with proxyHost + proxyPort.")
                    })
                    put("proxyHost", JSONObject().apply {
                        put("type", "string")
                        put("description", "Proxy host. Required when proxyType is set.")
                    })
                    put("proxyPort", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Proxy port. Default 1080 (SOCKS) / 8080 (HTTP).")
                    })
                    put("proxyUser", JSONObject().apply {
                        put("type", "string")
                        put("description", "Proxy username, when the proxy requires authentication (#227). Optional. SOCKS4 sends userid only.")
                    })
                    put("proxyPassword", JSONObject().apply {
                        put("type", "string")
                        put("description", "Proxy password for SOCKS5 (RFC 1929) / HTTP Basic auth (#227). Optional; ignored for SOCKS4.")
                    })
                    put("clear", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "If true, clear both tunnelConfigId and proxyType. Profile routes direct.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                val pid = args.optString("profileId")
                val target = when {
                    args.optBoolean("clear", false) -> "direct"
                    args.has("tunnelConfigId") -> "tunnel ${args.optString("tunnelConfigId").take(8)}…"
                    args.has("proxyType") -> "${args.optString("proxyType")} ${args.optString("proxyHost")}:${args.optInt("proxyPort")}"
                    else -> "(unchanged)"
                }
                "Route \"${profileLabel(pid)}\" through $target?"
            },
        ) { args -> setProfileRouting(args) },

        "create_connection" to ToolHandler(
            description = "Create a saved connection profile. Supports connectionType=SSH, SMB, VNC, RDP, EMAIL. SSH-family fields: username (required), password (optional, stored), keyId (optional — references list_ssh_keys), ignoreSavedKeys (force password-only auth, never offer saved keys), useMosh (turn an SSH profile into a Mosh profile), sessionManager (optional: TMUX | ZELLIJ | SCREEN | BYOBU — attach through that multiplexer; omit for a plain shell). SMB: smbShare (required), username + password, smbDomain. VNC: vncUsername, vncPassword, vncPort, and vncSshForward + vncSshProfileId to tunnel VNC through a saved SSH profile. RDP: rdpUsername (required), rdpPassword, rdpDomain, rdpPort. SPICE: spicePassword (optional ticket — no username/domain), spicePort (default 5900), and spiceSshForward + spiceSshProfileId to tunnel SPICE through a saved SSH profile. EMAIL: emailProvider (\"imap\" default, or \"proton\"); username = the email address; password = the account/app-password; for IMAP set emailServer (required) + emailPort (993) + emailSmtpPort (465) + emailTls (true), plus emailSmtpServer when the SMTP host differs (e.g. smtp.gmail.com); for Proton add emailMailboxPassword if two-password mode. EMAIL host is optional (the tunnel-ingress/bastion SPA/knock guards), not the mail server. The new profile id is returned for follow-up calls (set_profile_routing, connect_profile). For Reticulum / rclone / local create the profile in the UI — those paths need OAuth / destination-hash flows the agent can't drive.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("label", JSONObject().apply { put("type", "string"); put("description", "User-facing label.") })
                    put("connectionType", JSONObject().apply { put("type", "string"); put("description", "SSH | SMB | VNC | RDP | EMAIL.") })
                    put("host", JSONObject().apply { put("type", "string"); put("description", "Target hostname or IP. For EMAIL this is the optional tunnel ingress/bastion (SPA/knock target), NOT the mail server — leave blank for a direct IMAP connection.") })
                    put("port", JSONObject().apply { put("type", "integer"); put("description", "TCP port. Defaults: SSH 22, SMB 445, VNC 5900, RDP 3389.") })
                    put("username", JSONObject().apply { put("type", "string"); put("description", "Username for SSH/SMB.") })
                    put("password", JSONObject().apply { put("type", "string"); put("description", "Password (stored). Optional for SSH if a key is used; some VNC/SMB setups allow guest.") })
                    put("smbShare", JSONObject().apply { put("type", "string"); put("description", "Share name (SMB). Required when connectionType=SMB.") })
                    put("smbDomain", JSONObject().apply { put("type", "string"); put("description", "AD/workgroup domain (SMB). Optional.") })
                    put("vncUsername", JSONObject().apply { put("type", "string"); put("description", "Username for VeNCrypt VNC.") })
                    put("vncPassword", JSONObject().apply { put("type", "string"); put("description", "VNC password.") })
                    put("vncSshForward", JSONObject().apply { put("type", "boolean"); put("description", "VNC only: tunnel the VNC connection through a saved SSH profile (set vncSshProfileId). The VNC target is reached at 127.0.0.1:<port> from the SSH server. Default false.") })
                    put("vncSshProfileId", JSONObject().apply { put("type", "string"); put("description", "VNC only: id of the SSH profile (from list_connections) to tunnel through when vncSshForward is true.") })
                    put("rdpUsername", JSONObject().apply { put("type", "string"); put("description", "Windows username (RDP). Required when connectionType=RDP.") })
                    put("rdpPassword", JSONObject().apply { put("type", "string"); put("description", "Windows password (RDP).") })
                    put("rdpDomain", JSONObject().apply { put("type", "string"); put("description", "AD domain (RDP). Optional.") })
                    put("spicePassword", JSONObject().apply { put("type", "string"); put("description", "SPICE only: ticket/password (stored). Optional — omit for an unticketed server.") })
                    put("spiceSshForward", JSONObject().apply { put("type", "boolean"); put("description", "SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId). The SPICE target is reached at 127.0.0.1:<port> from the SSH server. Default false.") })
                    put("spiceSshProfileId", JSONObject().apply { put("type", "string"); put("description", "SPICE only: id of the SSH profile (from list_connections) to tunnel through when spiceSshForward is true.") })
                    put("emailProvider", JSONObject().apply { put("type", "string"); put("description", "EMAIL only: \"imap\" (generic IMAP/SMTP, default) or \"proton\".") })
                    put("emailServer", JSONObject().apply { put("type", "string"); put("description", "EMAIL/imap only: IMAP server hostname (required for imap). Reached through the tunnel when one is set.") })
                    put("emailSmtpServer", JSONObject().apply { put("type", "string"); put("description", "EMAIL/imap only: SMTP submission host, when it differs from the IMAP host (e.g. smtp.gmail.com vs imap.gmail.com). Optional — defaults to emailServer.") })
                    put("emailPort", JSONObject().apply { put("type", "integer"); put("description", "EMAIL/imap only: IMAP port. Default 993.") })
                    put("emailSmtpPort", JSONObject().apply { put("type", "integer"); put("description", "EMAIL/imap only: SMTP port. Default 465.") })
                    put("emailTls", JSONObject().apply { put("type", "boolean"); put("description", "EMAIL/imap only: implicit TLS (SSL). Default true.") })
                    put("emailMailboxPassword", JSONObject().apply { put("type", "string"); put("description", "EMAIL/proton only: separate mailbox password for two-password-mode accounts.") })
                    put("tunnelConfigId", JSONObject().apply { put("type", "string"); put("description", "Optional: route the new profile through this tunnel (from list_tunnels). Equivalent to follow-up set_profile_routing.") })
                    put("tunnelOnly", JSONObject().apply { put("type", "boolean"); put("description", "SSH only: tunnel-only mode (#150). When true, the profile brings up the SSH transport and registers port forwards but does not open a terminal. Default false. Pair with auto_reconnect for autossh-style keepalive.") })
                    put("useMosh", JSONObject().apply { put("type", "boolean"); put("description", "SSH only: when true, the profile uses Mosh on top of the SSH bootstrap. SSH execs `mosh-server new -s`, parses MOSH CONNECT, then the UDP transport takes over. Default false.") })
                    put("keyId", JSONObject().apply { put("type", "string"); put("description", "SSH only: id of a saved SSH key (from list_ssh_keys) to authenticate with. Mutually optional with password.") })
                    put("ignoreSavedKeys", JSONObject().apply { put("type", "boolean"); put("description", "SSH-family only: when true, authenticate with password (and keyboard-interactive) only — saved keystore keys are never offered to the server. Lets a profile target a password-only server without the auto-key-offer suppressing the password prompt (#121). Default false.") })
                    put("authMethods", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "SSH only (#166): ordered multi-factor auth methods attempted in one connect, for servers requiring a chain like publickey,password. Each element is a token: \"PASSWORD\", \"KEY\" (any saved key), \"KEY:<keyId>\", \"KEYBOARD_INTERACTIVE\", or \"TOTP:<id>\" (auto-fill an OATH-TOTP code from list_totp_secrets, #178). Omit for the single-method default derived from keyId/password.")
                    })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Optional port-knock sequence fired before the real connect. Format: whitespace/comma-separated 'port[/proto]' tokens — e.g. '7000 8000 9000' (all TCP) or '7000/tcp 8000/udp 9000/tcp'. Empty = disabled.") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms (default 100). Ignored when portKnockSequence is empty.") })
                })
                put("required", JSONArray().put("label").put("connectionType").put("host"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val type = args.optString("connectionType")
                val label = args.optString("label", "(unnamed)")
                val host = args.optString("host", "?")
                val tunnelTag = if (args.optBoolean("tunnelOnly", false)) " [tunnel-only]" else ""
                val knockTag = args.optString("portKnockSequence").let {
                    if (it.isNotBlank()) " [knock: $it]" else ""
                }
                "Create $type profile \"$label\" → $host$tunnelTag$knockTag?"
            },
        ) { args -> createConnection(args) },

        "update_connection" to ToolHandler(
            description = "Edit fields on an existing connection profile (load → change → save). Pass profileId (required) plus only the fields you want to change — anything omitted is left as-is. Common SSH-family fields: label, host, port, username, password (stored, mapped to the profile's transport), keyId, ignoreSavedKeys (force password-only auth), useMosh. Desktop tunnels: vncSshForward + vncSshProfileId, rdpSshForward + rdpSshProfileId, spiceSshForward + spiceSshProfileId, smbSshForward + smbSshProfileId. Passwords are stored encrypted and never echoed back. For routing/proxy use set_profile_routing; for port-knock/SPA use set_port_knock/set_spa. Returns the updated profile (secrets redacted).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("label", JSONObject().apply { put("type", "string"); put("description", "New user-facing label.") })
                    put("host", JSONObject().apply { put("type", "string"); put("description", "New hostname or IP.") })
                    put("port", JSONObject().apply { put("type", "integer"); put("description", "New TCP port.") })
                    put("username", JSONObject().apply { put("type", "string"); put("description", "New username (SSH/SMB).") })
                    put("password", JSONObject().apply { put("type", "string"); put("description", "New password (stored encrypted). Mapped to the profile's transport (SSH/VNC/RDP/SMB). Pass an empty string to clear it.") })
                    put("keyId", JSONObject().apply { put("type", "string"); put("description", "SSH only: id of a saved key (list_ssh_keys). Empty string clears.") })
                    put("ignoreSavedKeys", JSONObject().apply { put("type", "boolean"); put("description", "SSH-family only: force password-only auth, never offer saved keystore keys (#121).") })
                    put("useMosh", JSONObject().apply { put("type", "boolean"); put("description", "SSH only: use Mosh on top of the SSH bootstrap.") })
                    put("vncSshForward", JSONObject().apply { put("type", "boolean"); put("description", "VNC only: tunnel through a saved SSH profile (set vncSshProfileId).") })
                    put("vncSshProfileId", JSONObject().apply { put("type", "string"); put("description", "VNC only: SSH profile id to tunnel through. Empty string clears.") })
                    put("rdpSshForward", JSONObject().apply { put("type", "boolean"); put("description", "RDP only: tunnel through a saved SSH profile (set rdpSshProfileId).") })
                    put("rdpSshProfileId", JSONObject().apply { put("type", "string"); put("description", "RDP only: SSH profile id to tunnel through. Empty string clears.") })
                    put("smbSshForward", JSONObject().apply { put("type", "boolean"); put("description", "SMB only: tunnel through a saved SSH profile (set smbSshProfileId).") })
                    put("smbSshProfileId", JSONObject().apply { put("type", "string"); put("description", "SMB only: SSH profile id to tunnel through. Empty string clears.") })
                    put("spiceSshForward", JSONObject().apply { put("type", "boolean"); put("description", "SPICE only: tunnel through a saved SSH profile (set spiceSshProfileId).") })
                    put("spiceSshProfileId", JSONObject().apply { put("type", "string"); put("description", "SPICE only: SSH profile id to tunnel through. Empty string clears.") })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val pid = args.optString("profileId")
                val changed = args.keys().asSequence()
                    .filter { it != "profileId" }
                    .toList()
                val fields = if (changed.isEmpty()) "(no changes)" else changed.joinToString(", ")
                "Update \"${profileLabel(pid)}\": $fields?"
            },
        ) { args -> updateConnection(args) },

        "set_port_knock" to ToolHandler(
            description = "Update the port-knock fields on an existing profile. Pass portKnockSequence='' (empty) to disable knocking. Format: 'port[/proto]' tokens — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'. Returns the updated profile summary.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Sequence string ('' to disable).") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms. Optional; default 100 when sequence becomes non-empty.") })
                })
                put("required", JSONArray().put("profileId").put("portKnockSequence"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val seq = args.optString("portKnockSequence")
                val target = profileLabel(args.optString("profileId"))
                if (seq.isBlank()) "Disable port knock on \"$target\"?"
                else "Set port knock on \"$target\" to: $seq?"
            },
        ) { args -> setPortKnock(args) },

        "test_port_knock" to ToolHandler(
            description = "Send a port-knock sequence to a host without committing or connecting anything. Bypasses the saved profile state — pass host + sequence directly. Returns ok/sent/durationMs/error so an agent can verify a knockd config end-to-end without opening a real session.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("host", JSONObject().apply { put("type", "string"); put("description", "Hostname or IP literal to knock against.") })
                    put("portKnockSequence", JSONObject().apply { put("type", "string"); put("description", "Sequence string — e.g. '7000 8000 9000' or '7000/tcp 8000/udp'.") })
                    put("portKnockDelayMs", JSONObject().apply { put("type", "integer"); put("description", "Inter-knock delay in ms (default 100).") })
                })
                put("required", JSONArray().put("host").put("portKnockSequence"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Send test knock '${args.optString("portKnockSequence")}' to ${args.optString("host")}?"
            },
        ) { args -> testPortKnock(args) },

        "set_spa" to ToolHandler(
            description = "Configure fwknop Single Packet Authorization (SPA) on an existing profile — the cryptographic alternative to port knocking. Pass spaKey='' to disable. spaAccessSpec is the port(s) to open, e.g. 'tcp/22' or 'tcp/22,udp/53'. allowMode is SOURCE (default; fwknopd opens for the packet's source IP), RESOLVE (resolve public IP), or EXPLICIT (use explicitIp). Returns the updated profile summary; key material is never echoed back.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("spaKey", JSONObject().apply { put("type", "string"); put("description", "Rijndael/AES key ('' to disable SPA).") })
                    put("spaKeyBase64", JSONObject().apply { put("type", "boolean"); put("description", "True if spaKey is base64 (fwknop --key-base64-rijndael).") })
                    put("spaHmacKey", JSONObject().apply { put("type", "string"); put("description", "Optional HMAC-SHA256 key. '' = no HMAC.") })
                    put("spaHmacKeyBase64", JSONObject().apply { put("type", "boolean"); put("description", "True if spaHmacKey is base64 (fwknop --key-base64-hmac).") })
                    put("spaAccessSpec", JSONObject().apply { put("type", "string"); put("description", "proto/port token(s), e.g. 'tcp/22'.") })
                    put("allowMode", JSONObject().apply { put("type", "string"); put("description", "SOURCE | RESOLVE | EXPLICIT. Default SOURCE.") })
                    put("explicitIp", JSONObject().apply { put("type", "string"); put("description", "Allow-IP/CIDR for EXPLICIT mode.") })
                    put("spaPort", JSONObject().apply { put("type", "integer"); put("description", "Destination UDP port (default 62201).") })
                })
                put("required", JSONArray().put("profileId").put("spaKey"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val target = profileLabel(args.optString("profileId"))
                if (args.optString("spaKey").isBlank()) "Disable SPA on \"$target\"?"
                else "Set SPA (${args.optString("spaAccessSpec").ifBlank { "tcp/?" }}) on \"$target\"?"
            },
        ) { args -> setSpa(args) },

        "test_spa" to ToolHandler(
            description = "Build and send one fwknop SPA packet to a host without committing a profile or connecting. Pass the key/access directly. Returns ok/bytesSent/spaPort/error so an agent can verify a fwknopd config end-to-end. Key material is never echoed back.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("host", JSONObject().apply { put("type", "string"); put("description", "Hostname or IP literal to send the SPA packet to.") })
                    put("spaKey", JSONObject().apply { put("type", "string"); put("description", "Rijndael/AES key.") })
                    put("spaKeyBase64", JSONObject().apply { put("type", "boolean"); put("description", "True if spaKey is base64.") })
                    put("spaHmacKey", JSONObject().apply { put("type", "string"); put("description", "Optional HMAC-SHA256 key.") })
                    put("spaHmacKeyBase64", JSONObject().apply { put("type", "boolean"); put("description", "True if spaHmacKey is base64.") })
                    put("spaAccessSpec", JSONObject().apply { put("type", "string"); put("description", "proto/port token(s), e.g. 'tcp/22'.") })
                    put("allowMode", JSONObject().apply { put("type", "string"); put("description", "SOURCE | RESOLVE | EXPLICIT. Default SOURCE.") })
                    put("explicitIp", JSONObject().apply { put("type", "string"); put("description", "Allow-IP/CIDR for EXPLICIT mode.") })
                    put("spaPort", JSONObject().apply { put("type", "integer"); put("description", "Destination UDP port (default 62201).") })
                })
                put("required", JSONArray().put("host").put("spaKey").put("spaAccessSpec"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                "Send test SPA (${args.optString("spaAccessSpec")}) to ${args.optString("host")}?"
            },
        ) { args -> testSpa(args) },

        "get_connection_log" to ToolHandler(
            description = "Read the most recent ConnectionLog entries for a profile. Use this to verify post-hoc what happened during a connect — including knock results (look for '[knock]' lines in verboseLog), TLS handshakes, and authentication. limit defaults to 10.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                    put("limit", JSONObject().apply { put("type", "integer"); put("description", "Max entries to return (default 10, max 100).") })
                })
                put("required", JSONArray().put("profileId"))
            },
        ) { args -> getConnectionLog(args) },

        "delete_connection" to ToolHandler(
            description = "Delete a saved connection profile by id. Disconnects any live session for the profile first. Use this after integration tests to clean up agent-created profiles.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply { put("type", "string"); put("description", "Profile id from list_connections.") })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args -> "Delete profile \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> deleteConnection(args) },

        "connect_profile" to ToolHandler(
            description = "Initiate a connection for a saved profile via the same code path a UI tap uses (route-through, stored password, key auth all apply). Posts an AgentUiCommand.ConnectProfile that ConnectionsViewModel observes — the actual connect happens asynchronously. Use list_sessions afterwards to confirm the session reached CONNECTED. If the profile needs a password that isn't stored and isn't a key, the UI password prompt will surface to the user. For an SSH profile that uses a session manager (tmux/zellij/screen), pass sessionName to attach or create a named session non-interactively; without it, a connect to a profile that has existing sessions surfaces the interactive picker — poll get_pending_session_picker and resolve it with answer_session_picker (no longer stalls unanswerable).",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("profileId", JSONObject().apply {
                        put("type", "string")
                        put("description", "Profile id from list_connections.")
                    })
                    put("sessionName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. SSH session-manager (tmux/zellij/screen) session to attach or create — attaches if it already exists, creates it otherwise. Skips the interactive session picker. Ignored for transports/profiles without a session manager.")
                    })
                })
                put("required", JSONArray().put("profileId"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "Connect to \"${profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> connectProfile(args) },

        "get_pending_auth_prompt" to ToolHandler(
            description = "Return the password / key-passphrase prompt Haven is currently waiting on for a stalled connect (its in-app fallback dialog), or { pending: false } if none. A connect started via connect_profile that needs a secret which isn't stored (a wrong/missing host password, or an assigned encrypted SSH key whose passphrase failed, #292) surfaces here instead of failing silently: { pending: true, profileId, label, requiresPassphrase } — requiresPassphrase=true means it wants the encrypted key's passphrase, false means a host/account password. Answer it with answer_auth_prompt. Read-only.",
            inputSchema = emptyObjectSchema(),
        ) { _ -> getPendingAuthPrompt() },

        "answer_auth_prompt" to ToolHandler(
            description = "Answer the prompt reported by get_pending_auth_prompt — supply the secret a user would type into Haven's fallback dialog and Haven re-drives the connect through the same path a UI tap uses. Pass `password` (the host password or the encrypted key's passphrase). Optional `username` overrides the login user; `rememberPassword` stores it on the profile. Returns { answered:false } when no prompt is pending. A wrong value just re-surfaces the prompt (poll get_pending_auth_prompt; it clears on success) — confirm the result with list_sessions.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("password", JSONObject().apply {
                        put("type", "string")
                        put("description", "The host/account password or the encrypted key's passphrase to unlock the stalled connect.")
                    })
                    put("username", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional. Override the login username for this attempt.")
                    })
                    put("rememberPassword", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Optional. Persist the password on the profile (default false).")
                    })
                })
                put("required", JSONArray().put("password"))
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { _ -> "Answer Haven's pending connection password prompt?" },
        ) { args -> answerAuthPrompt(args) },

        "get_pending_session_picker" to ToolHandler(
            description = "Return the session-manager picker Haven is currently waiting on, or { pending: false } if none. A connect_profile to a tmux/zellij/screen profile that has existing remote sessions (and no sessionName preselected) surfaces this picker instead of attaching: { pending: true, profileId, sessionId, sessionManager, sessionNames: [...], previousSessionNames: [...], suggestedNewName }. Answer it with answer_session_picker. Read-only. (Previously this picker was human-only and stalled the agent.)",
            inputSchema = emptyObjectSchema(),
            consentLevel = ConsentLevel.NEVER,
        ) { _ -> getPendingSessionPicker() },

        "answer_session_picker" to ToolHandler(
            description = "Answer the picker reported by get_pending_session_picker — attach to an existing remote session by name, or create a new one. Pass `sessionName` (must be one of the picker's sessionNames) to attach to it, or `createNew: true` to start a fresh session. Haven re-drives the attach through the same path a human tap uses (onSessionSelected). Returns { answered:false } when no picker is pending. Confirm the result with list_sessions / read_terminal_snapshot.",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("sessionName", JSONObject().apply {
                        put("type", "string")
                        put("description", "Existing remote session to attach to (from get_pending_session_picker.sessionNames). Omit and set createNew=true to start a new one.")
                    })
                    put("createNew", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Create a new session instead of attaching to an existing one (default false).")
                    })
                })
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args ->
                if (args.optBoolean("createNew", false)) "Create a new remote session?"
                else "Attach to remote session \"${args.optString("sessionName")}\"?"
            },
        ) { args -> answerSessionPicker(args) },
    )

    /** Return the list of tool definitions for MCP `tools/list`. */
    // ---- Guest MCP aggregation (WS3) ----
    // Tools from running guest-resident MCP servers (isMcp guest services)
    // are surfaced through Haven's own MCP, namespaced `guest_<svc>_<tool>`,
    // so the agent connects only to Haven. Discovery is lazy + TTL-cached;
    // McpServer is request/response-only (no SSE push), so guest tools appear
    // on the next tools/list rather than via a listChanged notification.
    private val guestClients = java.util.concurrent.ConcurrentHashMap<String, GuestMcpClient>()
    private data class GuestToolRef(val serviceId: String, val remoteName: String, val def: JSONObject)

    @Volatile private var guestToolCache: Map<String, GuestToolRef> = emptyMap()
    @Volatile private var guestToolCacheAt = 0L
    private val guestToolCacheTtlMs = 8_000L

    /** Force the next [guestTools] call to re-discover (after register/start/stop). */
    fun invalidateGuestTools() { guestToolCacheAt = 0L }

    private fun sanitizeNs(s: String): String = s.replace(Regex("[^A-Za-z0-9]"), "_")

    /** Namespaced tools aggregated from running guest MCP servers (TTL-cached). */
    private fun guestTools(): Map<String, GuestToolRef> {
        val now = System.currentTimeMillis()
        if (now - guestToolCacheAt < guestToolCacheTtlMs) return guestToolCache
        val gsm = localSessionManager.guestServiceManager
        val running = try { gsm.runningMcpServices() } catch (e: Exception) { emptyList() }
        val liveIds = running.map { it.id }.toSet()
        guestClients.keys.retainAll { it in liveIds }
        val out = LinkedHashMap<String, GuestToolRef>()
        for (spec in running) {
            val client = guestClients.getOrPut(spec.id) {
                GuestMcpClient("http://127.0.0.1:${spec.port}${spec.mcpPath}")
            }
            val defs = try {
                client.listTools()
            } catch (e: Exception) {
                android.util.Log.w("McpTools", "guest MCP ${spec.id} listTools failed: ${e.message}")
                continue
            }
            val ns = sanitizeNs(spec.id)
            for (d in defs) {
                val remote = d.optString("name").ifBlank { continue }
                val nsName = "guest_${ns}_$remote"
                out[nsName] = GuestToolRef(
                    spec.id, remote,
                    JSONObject().apply {
                        put("name", nsName)
                        put("description", "[${spec.label}] " + d.optString("description"))
                        put("inputSchema", d.optJSONObject("inputSchema") ?: JSONObject().apply { put("type", "object") })
                    },
                )
            }
        }
        guestToolCache = out
        guestToolCacheAt = now
        return out
    }

    fun definitions(): List<JSONObject> {
        val local = tools.map { (name, handler) ->
            JSONObject().apply {
                put("name", name)
                put("description", handler.description)
                put("inputSchema", handler.inputSchema)
            }
        }
        val guest = guestTools().values.map { it.def }
        return local + guest
    }

    /**
     * Look up the consent gating metadata for a tool. Returns null for an
     * unknown tool (the dispatcher will surface that as an MCP error
     * regardless). Exposed to [McpServer] so consent prompting happens
     * before the handler runs. Proxied guest-MCP tools get a single
     * once-per-session gate keyed to the guest service.
     */
    fun consentFor(name: String): ToolConsent? {
        tools[name]?.let { return ToolConsent(level = it.consentLevel, summary = it.summarise) }
        val ref = guestToolCache[name] ?: guestTools()[name] ?: return null
        val label = localSessionManager.guestServiceManager.registered()
            .firstOrNull { it.id == ref.serviceId }?.label ?: ref.serviceId
        return ToolConsent(level = ConsentLevel.ONCE_PER_SESSION) {
            "Call guest MCP tool '${ref.remoteName}' on '$label'"
        }
    }

    /**
     * Per-call context that handlers can read. McpServer sets
     * [currentClientHint] right before invoking [call]; handlers like
     * `queue_terminal_input` consult it to thread the calling MCP
     * client's name through to the AgentConsentManager pre-delivery
     * prompt. Volatile because, while the JSON-RPC dispatcher is
     * sequential per connection, multiple HTTP connections can race —
     * the worst case is a stale hint, which is benign (the prompt
     * just shows the wrong client name).
     */
    @Volatile
    private var currentClientHint: String? = null

    /**
     * Terminal outcome of the most recent backgrounded backend install
     * (install_apk_from_backend returns `pending` and finishes off-call, so the
     * result is otherwise only in logcat). Surfaced in get_app_info so an agent
     * can confirm the install landed without parsing logs. (#245 follow-up)
     */
    @Volatile
    private var lastInstallResult: JSONObject? = null

    /** Call a tool by name. Throws [McpError] for bad input. */
    suspend fun call(name: String, arguments: JSONObject, clientHint: String? = null): JSONObject {
        currentClientHint = clientHint
        // Per-connection MCP gate + activity light: if the tool targets a connection
        // (a profileId arg, or a sessionId that maps to one), refuse when the user has
        // disabled MCP for it, otherwise mark it agent-active so its row indicator lights.
        resolveTargetProfile(arguments)?.let { profileId ->
            if (connectionRepository.isMcpEnabled(profileId) == false) {
                throw McpError(-32603, "MCP is disabled for this connection — re-enable it from its MCP icon on the Connections screen.")
            }
            agentActivityHolder.touch(profileId)
        }
        tools[name]?.let { return it.handle(arguments) }

        // Proxied guest-MCP tool: forward to the guest server and pass its
        // content array back through the __mcpContent reserved key.
        val ref = guestToolCache[name] ?: guestTools()[name]
            ?: throw McpError(-32602, "Unknown tool: $name")
        val client = guestClients[ref.serviceId]
            ?: throw McpError(-32603, "Guest MCP '${ref.serviceId}' is not connected")
        val result = withContext(Dispatchers.IO) {
            try {
                client.callTool(ref.remoteName, arguments)
            } catch (e: Exception) {
                throw McpError(-32603, "Guest MCP call '${ref.remoteName}' failed: ${e.message}")
            }
        }
        val guestContent = result.optJSONArray("content")
        return if (guestContent != null) {
            JSONObject().apply {
                put("__mcpContent", guestContent)
                if (result.optBoolean("isError", false)) put("isError", true)
            }
        } else {
            result
        }
    }

    /** The connection a tool call targets: an explicit profileId arg, else the profile behind a sessionId. */
    private fun resolveTargetProfile(args: JSONObject): String? {
        args.optString("profileId").ifBlank { null }?.let { return it }
        val sid = args.optString("sessionId").ifBlank { null } ?: return null
        return sessionManagerRegistry.allSessions.firstOrNull { it.sessionId == sid }?.profileId
    }

    // --- Tool implementations ---

    private fun getAppInfo(): JSONObject = JSONObject().apply {
        put("app", "haven")
        put("version", sh.haven.app.BuildConfig.VERSION_NAME)
        put("versionCode", sh.haven.app.BuildConfig.VERSION_CODE)
        put("buildType", sh.haven.app.BuildConfig.BUILD_TYPE)
        // Loosely-advertised feature flags — what's reachable from which tool
        put("capabilities", JSONArray().apply {
            put("ssh")
            put("sftp")
            put("smb")
            put("rclone")
            put("vnc")
            put("rdp")
            put("spice")
            put("reticulum")
            put("mosh")
            put("eternal_terminal")
            put("proot")
            put("wayland")
            put("ffmpeg")
        })
        // Terminal result of the last backgrounded backend install, if any —
        // lets a caller confirm a pending install landed. (#245 follow-up)
        lastInstallResult?.let { put("lastInstall", it) }
        // Which MCP carriers are actually open right now — this call itself
        // proves SOME carrier works, but an agent reasoning about
        // reliability (e.g. "why did my connection drop") benefits from
        // seeing the near (SSH) carrier's live state without a separate
        // lookup. See McpNearCarrier for what "near" means today.
        put("mcpCarriers", JSONObject().apply {
            val collision = mcpStatusHolder.wireguardCollision.value
            put("wireguardCollision", collision?.let { "${it.vpnInterface} @ ${it.address}" } ?: JSONObject.NULL)
            val near = mcpStatusHolder.nearCarrier.value
            put("near", JSONObject().apply {
                put("active", near.active)
                put("profileId", near.profileId ?: JSONObject.NULL)
                put("profileLabel", near.profileLabel ?: JSONObject.NULL)
            })
        })
    }

    private suspend fun listPairedClients(): JSONObject {
        val allowed = preferencesRepository.mcpAllowedClients.first().sorted()
        val bypass = preferencesRepository.mcpBypassConsentClients.first()
        val arr = JSONArray()
        for (name in allowed) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("autoApprove", name in bypass)
                put("isCaller", name == currentClientHint)
            })
        }
        return JSONObject().apply {
            put("count", allowed.size)
            put("clients", arr)
        }
    }

    private suspend fun unpairMcpClient(args: JSONObject): JSONObject {
        val name = args.optString("name", "").trim()
            .ifEmpty { throw McpError(-32602, "Missing 'name' — the client to un-pair (see list_paired_clients)") }
        val allowed = preferencesRepository.mcpAllowedClients.first()
        if (name !in allowed) {
            throw McpError(-32602, "No paired client named '$name'. Call list_paired_clients for exact names.")
        }
        // removeMcpAllowedClient also drops the name from the persistent
        // auto-approve set, so this fully revokes the client's standing trust.
        preferencesRepository.removeMcpAllowedClient(name)
        val remaining = preferencesRepository.mcpAllowedClients.first().sorted()
        return JSONObject().apply {
            put("unpaired", name)
            put("remainingCount", remaining.size)
            put("remaining", JSONArray(remaining))
        }
    }

    private suspend fun listConnections(): JSONObject {
        val profiles = connectionRepository.getAll()
        val arr = JSONArray()
        for (p in profiles) arr.put(profileToJson(p))
        return JSONObject().apply {
            put("count", profiles.size)
            put("connections", arr)
        }
    }

    private fun profileToJson(p: ConnectionProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("label", p.label)
        put("connectionType", p.connectionType)
        put("host", p.host)
        put("port", p.port)
        put("username", p.username)
        put("groupId", p.groupId ?: JSONObject.NULL)
        put("jumpProfileId", p.jumpProfileId ?: JSONObject.NULL)
        put("authType", p.authType.name)
        put("hasStoredPassword", !p.sshPassword.isNullOrEmpty())
        put("hasKey", p.keyId != null)
        if (p.ignoreSavedKeys) put("ignoreSavedKeys", true)
        if (p.authMethodSpecs.size > 1) {
            put("authMethods", org.json.JSONArray(p.authMethodSpecs.map { it.serialize() }))
        }
        put("lastConnected", p.lastConnected ?: JSONObject.NULL)
        if (p.useMosh) put("useMosh", true)
        if (p.useEternalTerminal) put("useEternalTerminal", true)
        // VNC-specific fields
        if (p.vncPort != null) put("vncPort", p.vncPort)
        if (!p.vncUsername.isNullOrEmpty()) put("vncUsername", p.vncUsername)
        // RDP
        if (!p.rdpUsername.isNullOrEmpty()) put("rdpUsername", p.rdpUsername)
        if (!p.rdpDomain.isNullOrEmpty()) put("rdpDomain", p.rdpDomain)
        // SMB
        if (!p.smbShare.isNullOrEmpty()) put("smbShare", p.smbShare)
        // Rclone
        if (!p.rcloneRemoteName.isNullOrEmpty()) put("rcloneRemote", p.rcloneRemoteName)
        if (!p.rcloneProvider.isNullOrEmpty()) put("rcloneProvider", p.rcloneProvider)
        // Proxy
        if (!p.proxyType.isNullOrEmpty()) {
            put("proxyType", p.proxyType)
            put("proxyHost", p.proxyHost ?: JSONObject.NULL)
            put("proxyPort", p.proxyPort)
        }
        // WireGuard / Tailscale routing — tunnel config id (resolve via list_tunnels).
        if (!p.tunnelConfigId.isNullOrEmpty()) {
            put("tunnelConfigId", p.tunnelConfigId)
        }
        // #150 — non-default reconnect / tunnel-only flags. Defaults
        // (autoReconnect=true, max=5, networkChange=true, tunnelOnly=false)
        // are omitted to keep the JSON terse for the common case.
        if (!p.autoReconnect) put("autoReconnect", false)
        if (p.reconnectMaxAttempts != 5) put("reconnectMaxAttempts", p.reconnectMaxAttempts)
        if (!p.reconnectOnNetworkChange) put("reconnectOnNetworkChange", false)
        if (p.tunnelOnly) put("tunnelOnly", true)
        // Per-profile session manager (tmux / zellij / screen / null = bare
        // shell). Surfaced so agents picking a clean-bash test target can
        // filter on it without first connecting.
        if (!p.sessionManager.isNullOrEmpty()) put("sessionManager", p.sessionManager)
        if (p.disableAltScreen) put("disableAltScreen", true)
        if (!p.portKnockSequence.isNullOrEmpty()) {
            put("portKnockSequence", p.portKnockSequence)
            put("portKnockDelayMs", p.portKnockDelayMs)
        }
        // SPA presence only — never surface the key/HMAC-key material.
        if (!p.spaKey.isNullOrEmpty() && !p.spaAccessSpec.isNullOrEmpty()) {
            put("spaEnabled", true)
            put("spaAccessSpec", p.spaAccessSpec)
            put("spaAllowMode", p.spaAllowMode)
            put("spaPort", p.spaPort)
        }
    }

    private fun listSessions(): JSONObject {
        val sessions = sessionManagerRegistry.allSessions
        val sshStates = sshSessionManager.sessions.value
        val arr = JSONArray()
        for (s in sessions) {
            arr.put(JSONObject().apply {
                put("sessionId", s.sessionId)
                put("profileId", s.profileId)
                put("label", s.label)
                put("status", s.status.name)
                put("transport", s.transport.name)
                if (s.transport == sh.haven.core.ssh.Transport.SSH) {
                    val sshState = sshStates[s.sessionId]
                    if (sshState != null) {
                        put("sessionManager", sshState.sessionManager.name)
                        put("chosenSessionName", sshState.chosenSessionName ?: JSONObject.NULL)
                        put("hasShell", sshState.shellChannel != null)
                        put("hasSftp", sshState.sftpChannel != null)
                        put("jumpSessionId", sshState.jumpSessionId ?: JSONObject.NULL)
                        put("activeForwards", JSONArray().apply {
                            for (f in sshState.activeForwards) {
                                put(JSONObject().apply {
                                    put("ruleId", f.ruleId)
                                    put("type", f.type.name)
                                    put("bindAddress", f.bindAddress)
                                    put("bindPort", f.bindPort)
                                    put("actualBoundPort", f.actualBoundPort)
                                    put("targetHost", f.targetHost)
                                    put("targetPort", f.targetPort)
                                })
                            }
                        })
                    }
                }
            })
        }
        return JSONObject().apply {
            put("count", sessions.size)
            put("sessions", arr)
        }
    }

    private fun listDesktopSessions(): JSONObject {
        val statuses = desktopSessionRegistry.statuses.value
        val arr = JSONArray()
        for ((profileId, status) in statuses) {
            arr.put(JSONObject().apply {
                put("profileId", profileId)
                put("status", status.name.lowercase())
            })
        }
        return JSONObject().apply {
            put("count", statuses.size)
            put("desktops", arr)
        }
    }

    /** RcloneClient must be initialised before any RPC calls — idempotent. */
    private fun ensureRcloneReady() {
        rcloneClient.initialize()
    }

    private fun listRcloneRemotes(): JSONObject {
        return try {
            ensureRcloneReady()
            val remotes = rcloneClient.listRemotes()
            JSONObject().apply {
                put("count", remotes.size)
                put("remotes", JSONArray().apply { remotes.forEach { put(it) } })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list rclone remotes: ${e.message}")
        }
    }

    // ---- EMAIL (IMAP/Gmail or Proton) tools — the MCP-drivable mail path (#EMAIL) ----

    /** Non-blank, trimmed string elements of a JSON array (empty when null). */
    private fun JSONArray?.toTrimmedStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optString(i, "").trim().ifBlank { null } }
    }

    private fun requireMailSession(profileId: String): String =
        mailSessionManager.getSessionIdForProfile(profileId)
            ?: throw McpError(-32603, "Profile $profileId is not a connected email account — call connect_profile first.")

    /** The engine client backing [sessionId] (Proton or IMAP), routed by the session manager. */
    private fun mailClientFor(sessionId: String): sh.haven.core.mail.MailClient =
        mailSessionManager.clientForSession(sessionId)
            ?: throw McpError(-32603, "Session $sessionId has no registered mail engine.")

    // --- Mail Rules (inbound-email automation) ---

    private suspend fun listMailRules(): JSONObject {
        val rules = mailRuleRepository.observeRules().first()
        return JSONObject().apply {
            put("count", rules.size)
            put("rules", JSONArray().apply {
                rules.forEach { r ->
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("name", r.name)
                        put("enabled", r.enabled)
                        put("orderIndex", r.orderIndex)
                        put("accountProfileId", r.accountProfileId ?: JSONObject.NULL)
                        put("folderId", r.folderId)
                        put("stopOnMatch", r.stopOnMatch)
                        put("notifyOnFire", r.notifyOnFire)
                        put("criteria", JSONObject(r.criteriaJson))
                        put("actions", JSONArray(r.actionsJson))
                        put("lastFiredAt", r.lastFiredAt ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    private suspend fun createMailRule(args: JSONObject): JSONObject {
        val name = args.optString("name").ifBlank { throw McpError(-32602, "Missing required argument: name") }
        val criteria = args.optJSONObject("criteria") ?: throw McpError(-32602, "Missing required argument: criteria")
        val actions = args.optJSONArray("actions") ?: throw McpError(-32602, "Missing required argument: actions")
        // Validate by round-tripping through the parser; reject empty/unrecognised input.
        val parsedCriteria = runCatching { sh.haven.core.data.mailrule.MailRuleJson.criteriaFromJson(criteria.toString()) }
            .getOrElse { throw McpError(-32602, "Invalid criteria: ${it.message}") }
        if (parsedCriteria.conditions.isEmpty()) throw McpError(-32602, "criteria has no recognised conditions")
        val parsedActions = runCatching { sh.haven.core.data.mailrule.MailRuleJson.actionsFromJson(actions.toString()) }
            .getOrElse { throw McpError(-32602, "Invalid actions: ${it.message}") }
        if (parsedActions.isEmpty()) throw McpError(-32602, "actions is empty or has no recognised entries")

        val rule = sh.haven.core.data.db.entities.MailRule(
            name = name,
            enabled = args.optBoolean("enabled", true),
            orderIndex = args.optInt("orderIndex", 0),
            accountProfileId = args.optString("accountProfileId").ifBlank { null },
            folderId = args.optString("folderId").ifBlank { "INBOX" },
            criteriaJson = criteria.toString(),
            actionsJson = actions.toString(),
            stopOnMatch = args.optBoolean("stopOnMatch", false),
            notifyOnFire = args.optBoolean("notifyOnFire", false),
        )
        mailRuleRepository.saveRule(rule)
        return JSONObject().apply {
            put("created", true)
            put("id", rule.id)
            put("conditions", parsedCriteria.conditions.size)
            put("actions", parsedActions.size)
            put("masterEnabled", preferencesRepository.mailAutomationEnabled.first())
        }
    }

    private suspend fun deleteMailRule(args: JSONObject): JSONObject {
        val id = args.optString("id").ifBlank { throw McpError(-32602, "Missing required argument: id") }
        val existed = mailRuleRepository.getRule(id) != null
        mailRuleRepository.deleteRule(id)
        return JSONObject().put("deleted", existed).put("id", id)
    }

    private suspend fun getMailAutomationStatus(): JSONObject {
        val rules = mailRuleRepository.observeRules().first()
        val firings = mailRuleRepository.recentFirings(50)
        val pending = mailRuleRepository.pendingActions()
        return JSONObject().apply {
            put("masterEnabled", preferencesRepository.mailAutomationEnabled.first())
            put("ruleCount", rules.size)
            put("enabledRuleCount", rules.count { it.enabled })
            put("recentFirings", JSONArray().apply {
                firings.forEach { f ->
                    put(JSONObject().apply {
                        put("ruleId", f.ruleId ?: JSONObject.NULL)
                        put("kind", f.kind)
                        put("profileId", f.profileId)
                        put("folderId", f.folderId)
                        put("uid", f.uid)
                        put("subject", f.messageSubject ?: JSONObject.NULL)
                        put("firedAt", f.firedAt)
                        put("outcome", f.outcomeSummary ?: JSONObject.NULL)
                    })
                }
            })
            put("pendingActions", JSONArray().apply {
                pending.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("ruleId", p.ruleId)
                        put("subject", p.messageSubject ?: JSONObject.NULL)
                        put("queuedAt", p.queuedAt)
                    })
                }
            })
        }
    }

    private suspend fun listMailFolders(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val folders = mailClientFor(sessionId).listFolders(sessionId)
            JSONObject().apply {
                put("count", folders.size)
                put("folders", JSONArray().apply {
                    folders.forEach { f ->
                        put(JSONObject().apply {
                            put("id", f.id)
                            put("name", f.name)
                            put("type", f.type)
                            // Special-use class so the agent can pick Inbox/Sent/Trash/etc.
                            put("role", f.role.name.lowercase())
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list mail folders: ${e.message}")
        }
    }

    private suspend fun listMailMessages(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifEmpty { sh.haven.core.mail.MailFolder.INBOX_ID }
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val sessionId = requireMailSession(profileId)
        return try {
            val msgs = mailClientFor(sessionId).listMessages(sessionId, folderId, desc = true, limit = limit, offset = offset)
            JSONObject().apply {
                put("folderId", folderId)
                put("offset", offset)
                put("count", msgs.size)
                put("messages", mailEnvelopes(msgs))
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list mail messages: ${e.message}")
        }
    }

    /** Envelope JSON array shared by list_mail_messages and search_mail. */
    private fun mailEnvelopes(msgs: List<sh.haven.core.mail.MailMessage>): JSONArray =
        JSONArray().apply {
            msgs.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id)
                    put("subject", m.subject)
                    put("from", m.from?.display() ?: "")
                    put("unread", m.unread)
                    put("time", m.timeSeconds)
                    put("numAttachments", m.numAttachments)
                })
            }
        }

    private suspend fun searchMail(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifEmpty { "INBOX" }
        val criteria = sh.haven.core.mail.MailSearchCriteria(
            from = args.optString("from").ifBlank { null },
            to = args.optString("to").ifBlank { null },
            subject = args.optString("subject").ifBlank { null },
            bodyText = args.optString("body").ifBlank { null },
            unreadOnly = args.optBoolean("unreadOnly", false),
            sinceEpochSec = if (args.has("sinceEpochSec")) args.optLong("sinceEpochSec") else null,
            beforeEpochSec = if (args.has("beforeEpochSec")) args.optLong("beforeEpochSec") else null,
        )
        if (criteria.isEmpty) {
            throw McpError(-32602, "Provide at least one search criterion (from/to/subject/body/unreadOnly/sinceEpochSec/beforeEpochSec)")
        }
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        val sessionId = requireMailSession(profileId)
        return try {
            val msgs = mailClientFor(sessionId).search(sessionId, folderId, criteria, limit)
            JSONObject().apply {
                put("folderId", folderId)
                put("count", msgs.size)
                put("messages", mailEnvelopes(msgs))
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to search mail: ${e.message}")
        }
    }

    private suspend fun saveMailDraft(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val to = args.optJSONArray("to").toTrimmedStringList()
        val cc = args.optJSONArray("cc").toTrimmedStringList()
        val bcc = args.optJSONArray("bcc").toTrimmedStringList()
        val subject = args.optString("subject")
        val body = args.optString("body")
        val inReplyToMessageId = args.optString("inReplyToMessageId").ifBlank { null }
        val sessionId = requireMailSession(profileId)
        val attachments = resolveSendAttachments(args.optJSONArray("attachments"))
        val mail = sh.haven.core.mail.OutgoingMail(
            to = to, cc = cc, bcc = bcc, subject = subject, bodyText = body,
            attachments = attachments, inReplyToMessageId = inReplyToMessageId,
        )
        return try {
            val draftFolderId = mailClientFor(sessionId).saveDraft(sessionId, mail)
            JSONObject().put("saved", true).put("draftFolderId", draftFolderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to save draft: ${e.message}")
        }
    }

    private suspend fun createMailFolder(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val name = args.optString("name").ifBlank {
            throw McpError(-32602, "Missing required argument: name")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val folderId = mailClientFor(sessionId).createFolder(sessionId, name)
            JSONObject().put("created", true).put("folderId", folderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to create mail folder: ${e.message}")
        }
    }

    private suspend fun deleteMailFolder(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val folderId = args.optString("folderId").ifBlank {
            throw McpError(-32602, "Missing required argument: folderId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            mailClientFor(sessionId).deleteFolder(sessionId, folderId)
            JSONObject().put("deleted", true).put("folderId", folderId)
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to delete mail folder: ${e.message}")
        }
    }

    private suspend fun readMailMessage(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        val sessionId = requireMailSession(profileId)
        return try {
            val raw = mailClientFor(sessionId).getMessageRaw(sessionId, messageId)
            val parsed = sh.haven.feature.mail.MimeParser.parse(raw)
            JSONObject().apply {
                put("subject", parsed.subject)
                put("from", parsed.from)
                put("to", JSONArray().apply { parsed.to.forEach { put(it) } })
                put("cc", JSONArray().apply { parsed.cc.forEach { put(it) } })
                parsed.dateMillis?.let { put("dateMillis", it) }
                put("bodyWasHtml", parsed.bodyWasHtml)
                put("body", parsed.bodyText)
                put("attachments", JSONArray().apply {
                    parsed.attachments.forEach { a ->
                        put(JSONObject().apply {
                            put("index", a.index)
                            put("filename", a.filename)
                            put("mimeType", a.mimeType)
                            put("sizeBytes", a.sizeBytes)
                            put("isInline", a.isInline)
                            a.contentId?.let { put("contentId", it) }
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to read mail message: ${e.message}")
        }
    }

    private suspend fun modifyMailMessage(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        val op = args.optString("op").ifEmpty {
            throw McpError(-32602, "Missing required argument: op")
        }.lowercase()
        val sessionId = requireMailSession(profileId)
        val client = mailClientFor(sessionId)
        return try {
            when (op) {
                "mark_read" -> client.setSeen(sessionId, messageId, true)
                "mark_unread" -> client.setSeen(sessionId, messageId, false)
                "flag" -> client.setFlagged(sessionId, messageId, true)
                "unflag" -> client.setFlagged(sessionId, messageId, false)
                "move" -> {
                    val dest = args.optString("destFolderId").ifEmpty {
                        throw McpError(-32602, "op=move requires destFolderId (see list_mail_folders)")
                    }
                    client.moveMessage(sessionId, messageId, dest)
                }
                "copy" -> {
                    val dest = args.optString("destFolderId").ifEmpty {
                        throw McpError(-32602, "op=copy requires destFolderId (see list_mail_folders)")
                    }
                    client.copyMessage(sessionId, messageId, dest)
                }
                "delete" -> client.deleteMessage(sessionId, messageId)
                else -> throw McpError(-32602, "Unknown op '$op' (mark_read|mark_unread|flag|unflag|move|copy|delete)")
            }
            JSONObject().put("ok", true).put("op", op).put("messageId", messageId)
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to $op mail message: ${e.message}")
        }
    }

    private suspend fun sendMail(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val to = args.optJSONArray("to").toTrimmedStringList()
        if (to.isEmpty()) {
            throw McpError(-32602, "Missing required argument: to (at least one recipient)")
        }
        if (!args.has("subject")) throw McpError(-32602, "Missing required argument: subject")
        if (!args.has("body")) throw McpError(-32602, "Missing required argument: body")
        val subject = args.optString("subject")
        val body = args.optString("body")
        val cc = args.optJSONArray("cc").toTrimmedStringList()
        val bcc = args.optJSONArray("bcc").toTrimmedStringList()
        val inReplyToMessageId = args.optString("inReplyToMessageId").ifBlank { null }
        val sessionId = requireMailSession(profileId)
        val attachments = resolveSendAttachments(args.optJSONArray("attachments"))
        val mail = sh.haven.core.mail.OutgoingMail(
            to = to, cc = cc, bcc = bcc, subject = subject, bodyText = body,
            attachments = attachments, inReplyToMessageId = inReplyToMessageId,
        )
        return try {
            val result = mailClientFor(sessionId).send(sessionId, mail)
            // Audit-log the send so it shows in Settings → connection log.
            runCatching {
                connectionLogRepository.logEvent(
                    profileId,
                    sh.haven.core.data.db.entities.ConnectionLog.Status.CONNECTED,
                    details = "Sent mail to ${to.size} recipient(s)" +
                        (if (subject.isNotBlank()) " — \"$subject\"" else ""),
                )
            }
            JSONObject().apply {
                put("sent", true)
                result.messageId?.let { put("messageId", it) }
                put("appendedToSent", result.appendedToSent)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to send mail: ${e.message}")
        }
    }

    /** Whole-attachment-in-memory cap for save/attach (writeBytes + ByteArrayDataSource buffer in RAM). */
    private val mailAttachmentMaxBytes: Long = 25L * 1024 * 1024

    /**
     * Resolve a `send_mail` `attachments` array — each `{ profileId, path }` on a
     * connected backend — into in-memory [OutgoingAttachment]s, enforcing the
     * per-file size cap before reading. Empty/absent → no attachments.
     */
    private suspend fun resolveSendAttachments(
        arr: JSONArray?,
    ): List<sh.haven.core.mail.OutgoingAttachment> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<sh.haven.core.mail.OutgoingAttachment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: throw McpError(-32602, "attachments[$i] must be an object { profileId, path }")
            val pid = o.optString("profileId").ifEmpty {
                throw McpError(-32602, "attachments[$i].profileId is required")
            }
            val path = o.optString("path").ifEmpty {
                throw McpError(-32602, "attachments[$i].path is required")
            }
            val backend = transportSelector.resolveFileBackend(pid)?.backend
                ?: throw McpError(-32603, "No connected backend for profile $pid (attachments[$i])")
            val size = try {
                backend.stat(path).size
            } catch (e: Exception) {
                throw McpError(-32602, "attachments[$i]: cannot stat $path on $pid: ${e.message}")
            }
            if (size > mailAttachmentMaxBytes) {
                throw McpError(
                    -32603,
                    "attachments[$i] ($path) is $size bytes — exceeds ${mailAttachmentMaxBytes / (1024 * 1024)} MiB cap",
                )
            }
            val bytes = try {
                backend.readBytes(path)
            } catch (e: Exception) {
                throw McpError(-32603, "attachments[$i]: failed to read $path: ${e.message}")
            }
            val name = sanitizeFilename(path.substringAfterLast('/'))
            out += sh.haven.core.mail.OutgoingAttachment(name, guessContentType(name), bytes)
        }
        return out
    }

    /**
     * Save one attachment from a message on a connected EMAIL profile to any
     * connected filesystem (or "local"). Engine-neutral — works for IMAP and
     * Proton via getMessageRaw. Decodes the attachment at [attachmentIndex]
     * (from read_mail_message) and writes it under destPath (a directory) on
     * destProfileId's backend, named after the attachment (sanitised) or an
     * optional destFilename; a name collision gets " (1)", " (2)", … The path
     * actually written is returned.
     */
    private suspend fun saveMailAttachment(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val messageId = args.optString("messageId").ifEmpty {
            throw McpError(-32602, "Missing required argument: messageId")
        }
        if (!args.has("attachmentIndex")) {
            throw McpError(-32602, "Missing required argument: attachmentIndex")
        }
        val attachmentIndex = args.optInt("attachmentIndex", -1)
        if (attachmentIndex < 0) throw McpError(-32602, "attachmentIndex must be a non-negative integer")
        val destProfileId = args.optString("destProfileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: destProfileId")
        }
        val destDir = args.optString("destPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: destPath (destination directory)")
        }

        val sessionId = requireMailSession(profileId)
        val extracted = try {
            val raw = mailClientFor(sessionId).getMessageRaw(sessionId, messageId)
            sh.haven.feature.mail.MimeParser.extractAttachment(raw, attachmentIndex)
        } catch (e: IndexOutOfBoundsException) {
            throw McpError(-32602, "No attachment at index $attachmentIndex (see read_mail_message)")
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to read attachment: ${e.message}")
        }
        if (extracted.bytes.size > mailAttachmentMaxBytes) {
            throw McpError(
                -32603,
                "Attachment is ${extracted.bytes.size} bytes — exceeds ${mailAttachmentMaxBytes / (1024 * 1024)} MiB cap",
            )
        }

        val resolution = transportSelector.resolveFileBackend(destProfileId)
            ?: throw McpError(-32603, "No connected backend for profile $destProfileId")
        val backend = resolution.backend
        val name = sanitizeFilename(args.optString("destFilename").ifBlank { extracted.filename })
        val destPath = uniqueDestPath(backend, destDir.trimEnd('/'), name)
        try {
            backend.writeBytes(destPath, extracted.bytes)
        } catch (e: Exception) {
            throw McpError(-32603, "Save failed: ${e.message}")
        }
        JSONObject().apply {
            put("saved", true)
            put("destProfileId", destProfileId)
            put("backend", backend.label)
            put("destPath", destPath)
            put("filename", name)
            put("bytes", extracted.bytes.size)
        }
    }

    /** Basename only; strip path separators, control chars, and "." / ".." traversal. */
    private fun sanitizeFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f]"), "")
            .trim()
        return if (base.isBlank() || base == "." || base == "..") "attachment" else base
    }

    /** First non-existing `<dir>/<name>`, appending " (n)" before the extension on collision. */
    private suspend fun uniqueDestPath(
        backend: sh.haven.feature.sftp.transport.FileBackend,
        dir: String,
        name: String,
    ): String {
        fun join(n: String) = if (dir.isEmpty()) n else "$dir/$n"
        suspend fun exists(p: String): Boolean = try {
            backend.stat(p); true
        } catch (e: Exception) {
            false
        }
        if (!exists(join(name))) return join(name)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 1..99) {
            val cand = join("$stem ($n)$ext")
            if (!exists(cand)) return cand
        }
        return join("$stem (${System.currentTimeMillis()})$ext")
    }

    private suspend fun startRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val srcFs = args.optString("srcFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifEmpty {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifEmpty {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> sh.haven.core.rclone.SyncMode.COPY
            "sync", "mirror" -> sh.haven.core.rclone.SyncMode.SYNC
            "move" -> sh.haven.core.rclone.SyncMode.MOVE
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { arr ->
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val filters = sh.haven.core.rclone.SyncFilters(
            includePatterns = includes,
            excludePatterns = excludes,
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
        )
        val config = sh.haven.core.rclone.SyncConfig(
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            filters = filters,
            dryRun = args.optBoolean("dryRun", false),
        )
        try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            val jobId = rcloneClient.startSync(config)
            JSONObject().apply {
                put("jobId", jobId)
                put("mode", mode.name.lowercase())
                put("rcMethod", mode.rcMethod)
                put("srcFs", srcFs)
                put("dstFs", dstFs)
                put("dryRun", config.dryRun)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to start rclone sync: ${e.message}")
        }
    }

    private suspend fun getRcloneSyncStatus(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("active", false)
                }
            }
            val status = rcloneClient.getJobStatus(jobId)
            val stats = rcloneClient.getStats()
            JSONObject().apply {
                put("active", true)
                put("jobId", jobId)
                put("finished", status.finished)
                put("success", status.success)
                status.error?.let { put("error", it) }
                put("duration", status.duration)
                put("stats", JSONObject().apply {
                    put("bytes", stats.bytes)
                    put("totalBytes", stats.totalBytes)
                    put("speed", stats.speed)
                    put("transfers", stats.transfers)
                    put("totalTransfers", stats.totalTransfers)
                    put("errors", stats.errors)
                    put("deletes", stats.deletes)
                    put("deletedDirs", stats.deletedDirs)
                    if (stats.lastError.isNotEmpty()) put("lastError", stats.lastError)
                })
                // Per-file errors with their object names — the diagnostic that
                // core/stats.lastError can't give (#157).
                if (stats.errors > 0) {
                    val failed = rcloneClient.getErroredTransfers()
                    if (failed.isNotEmpty()) {
                        put("failedFiles", org.json.JSONArray().apply {
                            failed.forEach { f ->
                                put(JSONObject().apply {
                                    put("name", f.name)
                                    put("error", f.error)
                                })
                            }
                        })
                    }
                }
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone sync status: ${e.message}")
        }
    }

    private suspend fun cancelRcloneSync(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            ensureRcloneReady()
            val jobId = if (args.has("jobId") && !args.isNull("jobId")) {
                args.optLong("jobId")
            } else {
                rcloneClient.activeSyncJobId ?: return@withContext JSONObject().apply {
                    put("cancelled", false)
                    put("reason", "no active job")
                }
            }
            rcloneClient.cancelJob(jobId)
            JSONObject().apply {
                put("cancelled", true)
                put("jobId", jobId)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to cancel rclone sync: ${e.message}")
        }
    }

    private fun getRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            val s = rcloneClient.getStats()
            JSONObject().apply {
                put("bytes", s.bytes)
                put("totalBytes", s.totalBytes)
                put("speed", s.speed)
                put("transfers", s.transfers)
                put("totalTransfers", s.totalTransfers)
                put("errors", s.errors)
                put("deletes", s.deletes)
                put("deletedDirs", s.deletedDirs)
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to get rclone stats: ${e.message}")
        }
    }

    private fun resetRcloneStats(): JSONObject {
        return try {
            ensureRcloneReady()
            rcloneClient.resetStats()
            JSONObject().apply { put("reset", true) }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to reset rclone stats: ${e.message}")
        }
    }

    private suspend fun listSavedSyncProfiles(): JSONObject = withContext(Dispatchers.IO) {
        val rows = syncProfileRepository.observeAll().first()
        val arr = JSONArray()
        for (p in rows) {
            arr.put(syncProfileToJson(p))
        }
        JSONObject().apply {
            put("count", rows.size)
            put("profiles", arr)
        }
    }

    private suspend fun saveSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.optString("name").ifBlank {
            throw McpError(-32602, "Missing required argument: name")
        }
        val srcFs = args.optString("srcFs").ifBlank {
            throw McpError(-32602, "Missing required argument: srcFs")
        }
        val dstFs = args.optString("dstFs").ifBlank {
            throw McpError(-32602, "Missing required argument: dstFs")
        }
        val modeStr = args.optString("mode").ifBlank {
            throw McpError(-32602, "Missing required argument: mode")
        }
        val mode = when (modeStr.lowercase()) {
            "copy" -> "COPY"
            "sync", "mirror" -> "SYNC"
            "move" -> "MOVE"
            else -> throw McpError(-32602, "Unknown mode: $modeStr (expected copy/sync/move)")
        }
        val includes = args.optJSONArray("includePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val excludes = args.optJSONArray("excludePatterns")?.let { a ->
            List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val existingId = if (args.has("id") && !args.isNull("id")) args.optString("id").ifBlank { null } else null
        val existing = existingId?.let { syncProfileRepository.getById(it) }
        val profile = sh.haven.core.data.db.entities.SyncProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            srcFs = srcFs,
            dstFs = dstFs,
            mode = mode,
            includePatterns = includes.joinToString("\n"),
            excludePatterns = excludes.joinToString("\n"),
            minSize = args.optString("minSize").ifBlank { null },
            maxSize = args.optString("maxSize").ifBlank { null },
            bandwidthLimit = args.optString("bandwidthLimit").ifBlank { null },
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastRunAt = existing?.lastRunAt,
        )
        syncProfileRepository.save(profile)
        syncProfileToJson(profile)
    }

    private suspend fun queueTerminalInput(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val text = args.optString("text").also {
            if (it.isEmpty()) throw McpError(-32602, "Missing required argument: text")
        }
        val explicitSessionId = args.optString("sessionId").ifEmpty { null }
        val mcpPort = mcpPortProvider()
        val sessionId = explicitSessionId
            ?: sshSessionManager.findRemoteForwardSession(mcpPort)
            ?: run {
                // No SSH session carries the MCP tunnel. Fall back to the sole
                // open terminal so a direct-HTTP agent (no SSH -R) can still
                // drive a single terminal; with none open, give an actionable
                // "connect a profile first" error rather than the opaque
                // tunnel message that left agents looping (#213).
                val ids = terminalSessionRegistry.sessions.value.keys
                when (ids.size) {
                    0 -> throw McpError(
                        -32603,
                        "No terminal session is connected. Connect a profile first " +
                            "(list_connections then connect_profile, or open a Local Shell), " +
                            "then retry — or pass an explicit sessionId from list_sessions.",
                    )
                    1 -> ids.first()
                    else -> throw McpError(
                        -32603,
                        "No SSH session carries the MCP reverse tunnel on port $mcpPort, and " +
                            "several terminal sessions are open (${ids.joinToString(", ")}). " +
                            "Pass sessionId explicitly (list_sessions). For the auto-detection " +
                            "default to work, an SSH profile must have the MCP reverse-tunnel " +
                            "toggle on and be connected.",
                    )
                }
            }
        val promptPattern = args.optString("promptPattern").ifEmpty { DEFAULT_PROMPT_PATTERN }
        val timeoutSeconds = args.optInt("timeoutSeconds", 60).coerceIn(1, 600)
        // submitKey: see [DEFAULT_SUBMIT_KEY] for the rationale on `\r`
        // vs `\n` vs `""`. Callers override to fit the target program.
        val submitKey = if (args.has("submitKey") && !args.isNull("submitKey")) {
            args.optString("submitKey")
        } else DEFAULT_SUBMIT_KEY
        val queueId = terminalInputQueue.enqueue(
            sessionId = sessionId,
            text = text,
            promptPattern = promptPattern,
            timeoutSeconds = timeoutSeconds,
            submitKey = submitKey,
            clientHint = currentClientHint,
        )
        JSONObject().apply {
            put("queueId", queueId)
            put("sessionId", sessionId)
            put("promptPattern", promptPattern)
            put("timeoutSeconds", timeoutSeconds)
            put("submitKey", submitKey)
            put("textLength", text.length)
        }
    }

    private suspend fun deleteSyncProfile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = args.optString("id").ifBlank {
            throw McpError(-32602, "Missing required argument: id")
        }
        val existed = syncProfileRepository.getById(id) != null
        syncProfileRepository.delete(id)
        JSONObject().apply {
            put("deleted", existed)
            put("id", id)
        }
    }

    private fun syncProfileToJson(p: sh.haven.core.data.db.entities.SyncProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("srcFs", p.srcFs)
        put("dstFs", p.dstFs)
        put("mode", p.mode.lowercase())
        put("includePatterns", JSONArray().apply {
            p.includePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        put("excludePatterns", JSONArray().apply {
            p.excludePatterns.split("\n").filter { it.isNotBlank() }.forEach { put(it) }
        })
        p.minSize?.let { put("minSize", it) }
        p.maxSize?.let { put("maxSize", it) }
        p.bandwidthLimit?.let { put("bandwidthLimit", it) }
        put("createdAt", p.createdAt)
        p.lastRunAt?.let { put("lastRunAt", it) }
    }

    /**
     * Backend-agnostic listing — collapses [listSftpDirectory] and
     * [listRcloneDirectory] (which stay registered as deprecated
     * aliases). Resolves the [FileBackend] from `profileId` via
     * [transportSelector], so adding a new backend lights this up
     * automatically.
     */
    private suspend fun listDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", "/").ifEmpty { "/" }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val arr = JSONArray()
        try {
            for (entry in resolution.backend.list(path)) {
                arr.put(JSONObject().apply {
                    put("name", entry.name)
                    put("path", entry.path)
                    put("isDir", entry.isDirectory)
                    put("size", entry.size)
                    put("mtime", entry.modifiedTime)
                    put("permissions", entry.permissions)
                    entry.mimeType?.let { put("mimeType", it) }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Backend-agnostic upload via [FileBackend.writeBytes]. Loads the
     * source into memory; agents wanting streaming for large files
     * still use the SSH-only [uploadFileToSftp] until #126 stage 4+
     * promotes streaming to [FileBackend].
     */
    private suspend fun uploadFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val data = source.readBytes()
        try {
            resolution.backend.writeBytes(remotePath, data)
        } catch (e: Exception) {
            throw McpError(-32603, "Upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    /**
     * Backend-agnostic file delete via [FileBackend.delete]. Refuses
     * directories — `delete_file` always passes `isDirectory = false`
     * so an agent that wants directory deletion has to use a separate
     * verb when one ships.
     */
    private suspend fun deleteFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        try {
            resolution.backend.delete(path, isDirectory = false)
        } catch (e: Exception) {
            throw McpError(-32603, "Delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", resolution.backend.label)
            put("path", path)
            put("deleted", true)
        }
    }

    private suspend fun listSftpDirectory(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path", ".").ifEmpty { "." }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val arr = JSONArray()
        try {
            session.list(path) { attrs ->
                arr.put(JSONObject().apply {
                    put("name", attrs.filename)
                    put("isDir", attrs.isDirectory)
                    put("size", attrs.size)
                    put("mtime", attrs.modifiedTimeSeconds.toLong())
                    put("permissions", attrs.permissions)
                })
                sh.haven.core.ssh.sftp.ListResult.CONTINUE
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $path: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    private suspend fun streamSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        val size = try {
            session.stat(path).size
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to stat $path: ${e.message}")
        }

        val streamPort = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = size,
            contentType = guessContentType(path),
            opener = { offset ->
                val s = sshSessionManager.openSftpSession(profileId)
                    ?: throw java.io.IOException("SFTP not connected for profile $profileId")
                // SftpStreamServer.Opener.open is a non-suspend fun interface,
                // called from the HTTP worker thread — runBlocking is fine here.
                runBlocking {
                    s.openInputStream(path, if (offset > 0) offset else 0L)
                }
            },
        )
        val sourceUrl = "http://127.0.0.1:$streamPort$urlPath"
        val hlsPort = hlsStreamServer.startFile(sourceUrl)
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("size", size)
            put("sourceUrl", sourceUrl)
            put("hlsPort", hlsPort)
            put("playlistUrl", "http://127.0.0.1:$hlsPort/stream.m3u8")
            put("playerUrl", "http://127.0.0.1:$hlsPort/")
        }
    }

    private fun stopStream(): JSONObject {
        hlsStreamServer.stop()
        return JSONObject().apply { put("stopped", true) }
    }

    /**
     * Backend-agnostic file download bridge. Resolves the profile via
     * [transportSelector], stats the path for size+mime, publishes a
     * loopback HTTP entry on [sftpStreamServer], and returns the URL.
     *
     * The capability gate ([UserPreferencesRepository.agentAllowFileRead])
     * is also enforced in [McpServer] *before* the consent prompt so a
     * disabled call fails immediately. The check here is belt-and-braces
     * for any in-process caller that might bypass the server dispatcher.
     */
    private suspend fun serveFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }

        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }

        val ref = publishFileLoopback(profileId, path)
        JSONObject().apply {
            put("profileId", profileId)
            put("backend", ref.backendLabel)
            put("path", path)
            put("size", ref.size)
            put("mimeType", ref.contentType)
            put("url", ref.url)
        }
    }

    private data class LoopbackRef(
        val url: String,
        val size: Long,
        val contentType: String,
        val backendLabel: String,
    )

    /**
     * Resolve [profileId]'s backend, stat [path], publish it on
     * [sftpStreamServer] as a token-protected loopback HTTP entry, and return
     * the URL + metadata. Shared by serve_file (agent downloads) and
     * present_web (in-app WebView). Does NOT enforce the file-read capability
     * gate — callers that expose bytes to the *agent* (serve_file) check it
     * first; present_web only renders to the user.
     */
    private suspend fun publishFileLoopback(profileId: String, path: String): LoopbackRef {
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend
        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; not a single file")
        }
        val contentType = guessContentType(path)
        val port = sftpStreamServer.start()
        val urlPath = sftpStreamServer.publish(
            path = path,
            size = entry.size,
            contentType = contentType,
            opener = { offset ->
                // Re-resolve on each Range request so a reconnected
                // session picks up the fresh client/channel rather than
                // capturing a stale one from publish-time.
                val live = kotlinx.coroutines.runBlocking {
                    transportSelector.resolveFileBackend(profileId)?.backend
                } ?: throw java.io.IOException("Backend disconnected for $profileId")
                kotlinx.coroutines.runBlocking { live.openInputStream(path, offset) }
            },
        )
        servedFileTracker.register(profileId, path)
        return LoopbackRef(
            url = "http://127.0.0.1:$port$urlPath",
            size = entry.size,
            contentType = contentType,
            backendLabel = backend.label,
        )
    }

    private fun guessContentType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "html", "htm" -> "text/html"
            "txt", "text", "log" -> "text/plain"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }

    private suspend fun openConvertDialogWithArgs(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val sourcePath = args.optString("sourcePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourcePath")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog(
            profileId = profileId,
            sourcePath = sourcePath,
            container = args.optString("container").ifEmpty { null },
            videoEncoder = args.optString("videoEncoder").ifEmpty { null },
            audioEncoder = args.optString("audioEncoder").ifEmpty { null },
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("sourcePath", sourcePath)
            put("container", command.container ?: JSONObject.NULL)
            put("videoEncoder", command.videoEncoder ?: JSONObject.NULL)
            put("audioEncoder", command.audioEncoder ?: JSONObject.NULL)
        }
    }

    private fun focusTerminalSession(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val command = sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession(sessionId)
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("sessionId", sessionId)
        }
    }

    private suspend fun openFileInEditor(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.OpenInEditor(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private suspend fun navigateSftpBrowser(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty { "/" }
        // Best-effort existence check so a typo'd profileId returns a
        // useful error instead of silently posting a command nobody
        // collects. "local" is a synthetic profile that has no DB row.
        if (profileId != "local") {
            connectionRepository.getById(profileId)
                ?: throw McpError(-32602, "Unknown profileId: $profileId")
        }
        val command = sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath(
            profileId = profileId,
            path = path,
        )
        val delivered = agentUiCommandBus.emit(command)
        return JSONObject().apply {
            put("delivered", delivered)
            put("profileId", profileId)
            put("path", path)
        }
    }

    private fun playFile(args: JSONObject): JSONObject {
        val url = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val explicitMime = args.optString("mimeType", "").ifEmpty { null }
        val resolvedMime = explicitMime ?: run {
            // Best-effort extension-based MIME guess so the system app
            // picker has something to filter on. Falls back to video/*
            // for typical SFTP/rclone playback.
            val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), resolvedMime)
            // Required when launching from a non-Activity context (we
            // hold the application Context here, not the current
            // Activity). Without this Android refuses to start the
            // target activity.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // For content:// URIs the granting flag lets the chosen
            // player read through Haven's FileProvider; harmless on
            // http(s) URLs.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("dispatched", true)
                put("url", url)
                put("mimeType", resolvedMime)
            }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(-32603, "No installed app can play '$resolvedMime' from this URL")
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to dispatch player intent: ${e.message}")
        }
    }

    /**
     * Push an image or sound for the user to see/hear inline. References a
     * file Haven can reach — a backend `profileId` + `path` (incl. "local"
     * for the device / proot-guest cache) or a ready `url` — streams it into
     * a cache handle (so the overlay's image decoder / [MediaPlayer] read a
     * real file), and hands it to [presentationManager], which the top-of-tree
     * `PresentationHost` renders as a dismissible bottom sheet. The media
     * bytes never pass through the agent context (no base64). Fire and forget:
     * returns as soon as the item is queued, never waits on the user. See
     * [AgentPresentationManager].
     */
    private fun presentMedia(args: JSONObject): JSONObject {
        if (args.optString("dataBase64").isNotEmpty()) {
            throw McpError(
                -32602,
                "present_media no longer takes dataBase64 — reference the file instead: " +
                    "profileId (\"local\" for the device / proot-guest cache, or an SSH/SMB/rclone " +
                    "profile id) + path, or a url (e.g. from serve_file).",
            )
        }
        val caption = args.optString("caption", "").ifEmpty { null }
        val explicitMime = args.optString("mimeType", "").ifEmpty { null }
        val autoPlay = args.optBoolean("autoPlay", false)
        val url = args.optString("url", "").ifEmpty { null }
        val path = args.optString("path", "").ifEmpty { null }
        val profileId = args.optString("profileId", "local").ifEmpty { "local" }
        if (url == null && path == null) {
            throw McpError(
                -32602,
                "present_media needs a file reference: profileId + path, or url.",
            )
        }

        // Stage + enqueue OFF the MCP call's critical path so the response acks
        // immediately. Staging reads up to MAX_PRESENT_BYTES off a (possibly
        // laggy) backend; doing it inline meant a delivered image still read
        // back to the agent as a *timeout* when the read outlasted its call
        // window (serve_file, which only binds a server, never had this). The
        // overlay is the real user-facing artifact, so a staging failure is
        // logged, not surfaced — the agent already has its optimistic ack.
        presentScope.launch {
            try {
                val srcName: String
                val file: File = if (url != null) {
                    srcName = url.substringBefore('?').substringAfterLast('/')
                    downloadToPresentCache(url)
                } else {
                    srcName = path!!.substringAfterLast('/')
                    streamBackendToPresentCache(profileId, path)
                }
                // Determine MIME: explicit wins; else from the file name; else
                // sniff the head bytes for a known image signature (audio can't
                // be sniffed reliably, so it needs an extension or explicit mime).
                val head = ByteArray(32)
                val n = file.inputStream().use { it.read(head) }
                val mime = explicitMime
                    ?: guessContentType(srcName).takeIf { it.startsWith("image/") || it.startsWith("audio/") }
                    ?: sniffImageMime(if (n > 0) head.copyOf(n) else head)
                if (mime == null) {
                    file.delete()
                    logPresentFailure(profileId, url ?: path, "could not determine media type — pass mimeType")
                    return@launch
                }
                val kind = when {
                    mime.startsWith("image/") -> sh.haven.core.data.agent.PresentedMediaKind.IMAGE
                    mime.startsWith("audio/") -> sh.haven.core.data.agent.PresentedMediaKind.AUDIO
                    else -> {
                        file.delete()
                        logPresentFailure(profileId, url ?: path, "unsupported media type '$mime' (image/* and audio/* only)")
                        return@launch
                    }
                }
                presentationManager.present(
                    kind = kind,
                    filePath = file.absolutePath,
                    mimeType = mime,
                    caption = caption,
                    autoPlay = autoPlay,
                )
            } catch (t: Throwable) {
                logPresentFailure(profileId, url ?: path, t.message ?: t.toString())
            }
        }
        return JSONObject().apply { put("presented", true) }
    }

    /**
     * Log a present_media / present_web staging failure. The agent already
     * received its optimistic {presented:true} ack (the overlay is the real
     * artifact), so a failed stage is a user-visible no-op — surface it to
     * logcat, and to the connection log when it's a profile-backed read
     * (logEvent no-ops on an orphan/"local" id via its FK guard).
     */
    private suspend fun logPresentFailure(profileId: String, ref: String?, reason: String) {
        android.util.Log.w("McpTools", "present staging failed for ${ref ?: "?"}: $reason")
        runCatching {
            connectionLogRepository.logEvent(
                profileId,
                sh.haven.core.data.db.entities.ConnectionLog.Status.FAILED,
                details = "present: ${ref ?: ""} — $reason",
            )
        }
    }

    /**
     * Copy [input] into a fresh cache file (named with [ext]), capping at
     * [MAX_PRESENT_BYTES] so a referenced file can't fill the cache. The
     * cache file is owned by [AgentPresentationManager] from return — it
     * deletes it on dismissal / eviction.
     */
    private fun copyToPresentCache(input: java.io.InputStream, ext: String): File {
        val file = File(context.cacheDir, "haven-present-${System.currentTimeMillis()}.$ext")
        try {
            var total = 0L
            val buf = ByteArray(64 * 1024)
            input.use { ins ->
                file.outputStream().use { out ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        total += r
                        if (total > MAX_PRESENT_BYTES) {
                            throw McpError(
                                -32602,
                                "media exceeds $MAX_PRESENT_BYTES bytes — use present_app or play_file for large media",
                            )
                        }
                        out.write(buf, 0, r)
                    }
                }
            }
            return file
        } catch (t: Throwable) {
            runCatching { file.delete() }
            throw t
        }
    }

    /** Stream a file off a connected backend into a present-cache handle. */
    private suspend fun streamBackendToPresentCache(profileId: String, path: String): File {
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend
        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) throw McpError(-32602, "$path is a directory")
        if (entry.size > MAX_PRESENT_BYTES) {
            throw McpError(
                -32602,
                "$path is ${entry.size} bytes; caps at $MAX_PRESENT_BYTES — use present_app or play_file for large media",
            )
        }
        val ext = path.substringAfterLast('.', "").ifEmpty { "bin" }
        return copyToPresentCache(backend.openInputStream(path, 0), ext)
    }

    /** Fetch a URL (e.g. a serve_file loopback URL) into a present-cache handle. */
    private fun downloadToPresentCache(urlStr: String): File {
        val url = java.net.URL(urlStr)
        val conn = url.openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val ext = urlStr.substringBefore('?').substringAfterLast('.', "").ifEmpty { "bin" }
        return copyToPresentCache(conn.getInputStream(), ext)
    }

    /**
     * Show HTML / SVG / PDF inline. HTML/SVG are served over a loopback URL
     * and loaded in an in-app WebView; a PDF is streamed to a cache file and
     * paged by PdfRenderer (it needs a local fd). Reference by a ready `url`
     * or a backend `profileId` + `path`. Bytes never pass through the agent
     * context. Fire and forget; returns as soon as the item is queued.
     */
    private fun presentWeb(args: JSONObject): JSONObject {
        val caption = args.optString("caption", "").ifEmpty { null }
        val explicitUrl = args.optString("url", "").ifEmpty { null }
        val path = args.optString("path", "").ifEmpty { null }

        // The url case needs no staging (it already IS a URL) — present it
        // inline and return the full ack, including the url for reference.
        if (explicitUrl != null) {
            val mime = guessContentType(explicitUrl.substringBefore('?'))
            val id = presentationManager.presentWeb(
                url = explicitUrl,
                filePath = null,
                mimeType = mime,
                caption = caption,
            )
            return JSONObject().apply {
                put("presented", true)
                put("id", id)
                put("url", explicitUrl)
                put("mimeType", mime)
            }
        }
        if (path == null) {
            throw McpError(-32602, "present_web needs a url, or profileId + path.")
        }
        val profileId = args.optString("profileId", "local").ifEmpty { "local" }
        val mime = guessContentType(path)

        // The path case reads/serves the file off a backend; stage it OFF the
        // call's critical path (see present_media) so a slow read can't turn a
        // delivered view into a timeout. Failures are logged, not surfaced.
        presentScope.launch {
            try {
                if (mime == "application/pdf") {
                    // PdfRenderer needs a seekable local file, not a URL.
                    val pdfFile = streamBackendToPresentCache(profileId, path)
                    presentationManager.presentWeb(
                        url = null,
                        filePath = pdfFile.absolutePath,
                        mimeType = mime,
                        caption = caption,
                    )
                } else {
                    val loopbackUrl = publishFileLoopback(profileId, path).url
                    presentationManager.presentWeb(
                        url = loopbackUrl,
                        filePath = null,
                        mimeType = mime,
                        caption = caption,
                    )
                }
            } catch (t: Throwable) {
                logPresentFailure(profileId, path, t.message ?: t.toString())
            }
        }
        return JSONObject().apply { put("presented", true) }
    }

    /**
     * Post a real system notification through Haven's NotificationManager.
     *
     * Always uses the dedicated [AGENT_NOTIFICATION_CHANNEL_ID] channel so
     * the user can mute "agent test notifications" without silencing SSH /
     * desktop / renewal notifications. The channel is created lazily on
     * first call and pinned for the lifetime of the process.
     *
     * Fails early with a friendly McpError if the system-wide notification
     * toggle is off — POST_NOTIFICATIONS is declared in the manifest and
     * granted by the user during the connections flow, but on rare devices
     * (or fresh installs that skipped the prompt) it can still be denied.
     */
    private fun raiseNotification(args: JSONObject): JSONObject {
        val title = args.optString("title").ifEmpty {
            throw McpError(-32602, "title is required")
        }
        val body = args.optString("body").ifEmpty {
            throw McpError(-32602, "body is required")
        }
        val priorityStr = args.optString("priority", "default").lowercase()
        val priority = when (priorityStr) {
            "min" -> NotificationCompat.PRIORITY_MIN
            "low" -> NotificationCompat.PRIORITY_LOW
            "default", "" -> NotificationCompat.PRIORITY_DEFAULT
            "high" -> NotificationCompat.PRIORITY_HIGH
            "max" -> NotificationCompat.PRIORITY_MAX
            else -> throw McpError(
                -32602,
                "priority must be one of: min, low, default, high, max (got '$priorityStr')",
            )
        }
        val ongoing = args.optBoolean("ongoing", false)

        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) {
            throw McpError(
                -32603,
                "Notifications are disabled for Haven — grant the POST_NOTIFICATIONS permission in system settings before calling raise_notification.",
            )
        }
        ensureAgentNotificationChannel()

        val id = nextAgentNotificationId.getAndIncrement()
        val notification = NotificationCompat.Builder(context, AGENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()

        try {
            managerCompat.notify(id, notification)
        } catch (e: SecurityException) {
            throw McpError(
                -32603,
                "Could not post notification: ${e.message ?: "permission denied"}",
            )
        }
        return JSONObject().apply {
            put("posted", true)
            put("id", id)
            put("channel", AGENT_NOTIFICATION_CHANNEL_ID)
        }
    }

    private fun ensureAgentNotificationChannel() {
        if (agentNotificationChannelEnsured) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AGENT_NOTIFICATION_CHANNEL_ID,
            "Agent test notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications posted by the MCP raise_notification tool — used by the agent to drive notification-listener / wake / DND apps during tester reviews. Mute this channel to silence agent notifications without affecting Haven's connection or renewal notifications."
        }
        nm.createNotificationChannel(channel)
        agentNotificationChannelEnsured = true
    }

    private fun ensureInstallNotificationChannel() {
        if (installNotificationChannelEnsured) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "App update install",
            // HIGH so the tap-to-install prompt can surface as a heads-up when
            // Haven is backgrounded — Android blocks a background app from
            // launching the installer directly, so the user's tap is the way in.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Tap-to-install prompts for an APK staged by install_apk_from_backend / _from_url when Shizuku isn't available for a silent install."
        }
        nm.createNotificationChannel(channel)
        installNotificationChannelEnsured = true
    }

    /**
     * Launch [command] as a single-app cage kiosk in the guest and surface
     * its live VNC view in the present_media overlay. Blocks (on IO) until
     * the kiosk's VNC port is up — [DesktopManager.startAppWindow] does the
     * wait — then enqueues an APP_WINDOW presentation pointing at it. On
     * failure surfaces the compositor log tail for diagnosis and cleans up
     * the dead session.
     */
    private suspend fun presentApp(args: JSONObject): JSONObject {
        val command = args.optString("command").ifEmpty {
            throw McpError(-32602, "command is required (the GUI app to run, e.g. 'imv /root/x.png')")
        }
        val caption = args.optString("caption", "").ifEmpty { null }
        val fullscreen = args.optBoolean("fullscreen", false)
        // null = use the global default; the persisted def keeps that choice.
        val resolutionArg = args.optString("resolution", "").ifEmpty { null }
        val scaleArg = if (args.has("scale")) args.optDouble("scale").toFloat() else null
        val runAsRoot = args.optBoolean("runAsRoot", false)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val dm = localSessionManager.desktopManager
        val resolution = resolutionArg ?: preferencesRepository.appWindowDefaultResolution.first()
        val scale = scaleArg ?: preferencesRepository.appWindowDefaultScale.first()
        // The cage runs the app as a single-app sway kiosk; install sway+wayvnc
        // on demand (slow + non-streaming) so present_app works on a fresh distro.
        if (!withContext(Dispatchers.IO) { dm.ensureCageRuntime() }) {
            throw McpError(-32603, "the cage runtime (sway/wayvnc) isn't installed for '${prootManager.activeDistroId}' and couldn't be installed automatically")
        }
        val rooted = if (runAsRoot) dm.ensureRunAsRoot() else false
        val session = withContext(Dispatchers.IO) { dm.startAppWindow(command, resolution, scale, runAsRoot = rooted) }
        if (session.state == sh.haven.core.local.DesktopManager.DesktopState.RUNNING) {
            presentationManager.presentAppWindow(
                host = "127.0.0.1",
                port = session.vncPort,
                sessionId = session.sessionId,
                caption = caption ?: "App: $command",
                fullscreen = fullscreen,
                scale = scale,
                resolution = resolution,
            )
            // Record the launch so the user can restart this window from
            // Desktop settings later. Fire-and-forget — never fail the tool
            // because persistence hiccuped.
            runCatching {
                preferencesRepository.upsertAppWindowDef(
                    label = caption ?: command,
                    command = command,
                    createdBy = sh.haven.core.data.preferences.AppWindowOrigin.AGENT,
                    fullscreen = fullscreen,
                    resolution = resolutionArg,
                    scale = scaleArg,
                    runAsRoot = if (runAsRoot) true else null,
                )
            }
            return JSONObject().apply {
                put("presented", true)
                put("sessionId", session.sessionId)
                put("vncPort", session.vncPort)
                put("state", "running")
            }
        }
        // ERROR: capture the compositor log tail, then clean up the dead
        // session so it doesn't linger in the registry.
        val logTail = dm.appWindowCompositorLog(session.sessionId)?.takeLast(800)?.trim()
        withContext(Dispatchers.IO) { dm.stopAppWindow(session.sessionId) }
        throw McpError(
            -32603,
            buildString {
                append(session.errorMessage ?: "app window failed to start")
                append(". The app + its Wayland deps must be installed in the guest, ")
                append("and the Sway DE installed (provides sway + wayvnc; present_app runs the ")
                append("app as a single-app sway kiosk because cage 0.1.4 crashes on the headless backend).")
                if (!logTail.isNullOrBlank()) append(" Compositor log: ").append(logTail)
            },
        )
    }

    /**
     * Read a present_app window's captured output (compositor + app stdout/stderr,
     * merged by the cage). By sessionId for a live window, else the newest log —
     * which survives a crashed/exited app, the case where it's most needed.
     */
    private fun readAppWindowLog(args: JSONObject): JSONObject {
        val dm = localSessionManager.desktopManager
        val sessionId = args.optString("sessionId").ifEmpty { null }
        val maxBytes = args.optInt("maxBytes", 16384).coerceIn(256, 262144)
        val (display, text) = if (sessionId != null) {
            val log = dm.appWindowCompositorLog(sessionId)
                ?: throw McpError(
                    -32602,
                    "no live app window '$sessionId' (it may have exited — omit sessionId to read the newest log)",
                )
            (dm.appWindows.value[sessionId]?.displayNumber ?: -1) to log
        } else {
            val latest = dm.latestAppWindowLog()
                ?: throw McpError(-32603, "no app-window logs yet — launch one with present_app first")
            latest.first to latest.second.readText()
        }
        val truncated = text.length > maxBytes
        val tail = if (truncated) text.takeLast(maxBytes) else text
        return JSONObject().apply {
            if (sessionId != null) put("sessionId", sessionId)
            put("display", display)
            put("bytes", tail.toByteArray().size)
            put("truncated", truncated)
            put("log", tail)
        }
    }

    /**
     * Enumerate installed GUI apps in the active guest (the launcher catalog the
     * Browse-installed-apps menu shows). Reads `.desktop` files off the rootfs
     * and resolves icons via [sh.haven.core.local.GuestAppScanner]; returns a
     * lightweight JSON view (icon bytes stay on-device).
     */
    private suspend fun listGuestApps(): JSONObject {
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val result = withContext(Dispatchers.IO) {
            sh.haven.core.local.GuestAppScanner(prootManager).scan()
        }
        return JSONObject().apply {
            put("count", result.total)
            put("iconsResolved", result.iconsResolved)
            put("apps", JSONArray().apply {
                result.apps.forEach { app ->
                    put(JSONObject().apply {
                        put("name", app.name)
                        put("exec", app.exec)
                        put("hasIcon", app.iconPath != null)
                        put("categories", JSONArray().apply { app.categories.forEach { put(it) } })
                    })
                }
            })
        }
    }

    /**
     * Best-effort image-format detection from leading magic bytes, so
     * [presentMedia] can accept a raw image payload without an explicit
     * mimeType. Returns null for anything it doesn't recognise (including
     * all audio).
     */
    private fun sniffImageMime(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && b[0] == 0x47.toByte() && b[1] == 0x49.toByte() &&
            b[2] == 0x46.toByte() -> "image/gif" // "GIF"
        b.size >= 12 && b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
            b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
            b[10] == 0x42.toByte() && b[11] == 0x50.toByte() -> "image/webp" // "RIFF"…"WEBP"
        else -> null
    }

    private fun readTerminalScrollback(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val requested = args.optInt("maxBytes", 16384)
        val capped = requested.coerceIn(1, 256 * 1024)
        // sessionId can come from either the SSH or the local-shell
        // manager; try both rather than forcing the agent to know
        // which transport it's looking at.
        val sshBytes = sshSessionManager.readAgentScrollback(sessionId, capped)
        val bytes = sshBytes
            ?: localSessionManager.readAgentScrollback(sessionId, capped)
            ?: throw McpError(
                -32603,
                "No scrollback available for session $sessionId — open a terminal tab on this session first",
            )
        // Whether the underlying session has ended. Without this an agent
        // can't tell a quiet shell from a dead one — the ring keeps returning
        // the last output (e.g. frozen at the login banner) with no signal.
        val ended = if (sshBytes != null) {
            sshSessionManager.getSession(sessionId) == null
        } else {
            val ls = localSessionManager.getActiveSession(sessionId)
            ls == null || !ls.isAlive()
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("byteCount", bytes.size)
            // UTF-8 with malformed-replacement so any partial codepoint
            // at the buffer head doesn't poison the whole response.
            put("text", String(bytes, Charsets.UTF_8))
            put("ended", ended)
        }
    }

    // --- Write-tool implementations -------------------------------------

    private suspend fun disconnectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        // Cross-transport hammer; the registry already knows which
        // transports have sessions for this profile and only acts where
        // there's something to do, so it's safe to call unconditionally.
        sessionManagerRegistry.disconnectProfile(profileId)
        // Match ConnectionsViewModel.disconnect — close the profile's VNC/RDP
        // Desktop tab (via the lease) and release any auto-opened SSH-tunnel
        // dependent (#121) plus the WG / Tailscale refcount (#149). Bypassing
        // these from the MCP path was the original gap — without them, a
        // profile disconnected through the agent path left its tunnel handle
        // live, and live_tunnels surfaced the bogus "still depending" state.
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        return JSONObject().apply {
            put("profileId", profileId)
            put("disconnected", true)
        }
    }

    private suspend fun addPortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val typeStr = args.optString("type").ifEmpty {
            throw McpError(-32602, "Missing required argument: type")
        }
        val type = try {
            PortForwardRule.Type.valueOf(typeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, "Invalid type '$typeStr' — expected LOCAL, REMOTE, or DYNAMIC")
        }
        val bindPort = args.optInt("bindPort", -1).takeIf { it >= 0 }
            ?: throw McpError(-32602, "Missing required argument: bindPort")
        val rule = PortForwardRule(
            profileId = profileId,
            type = type,
            bindAddress = args.optString("bindAddress", "127.0.0.1"),
            bindPort = bindPort,
            targetHost = args.optString("targetHost", "localhost"),
            targetPort = args.optInt("targetPort", 0),
            enabled = true,
        )
        portForwardRepository.save(rule)

        // Reticulum profiles tunnel over the rnsh exec substrate (nc), not
        // an SSH session — dispatch to the forward server instead.
        if (reticulumSessionManager.isProfileConnected(profileId)) {
            val destHash = reticulumSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == sh.haven.core.reticulum.ReticulumSessionManager.SessionState.Status.CONNECTED }
                ?.destinationHash
            if (destHash != null) {
                val bound = when (rule.type) {
                    PortForwardRule.Type.LOCAL -> reticulumForwardServer.startLocalForward(
                        profileId, destHash, rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
                    PortForwardRule.Type.DYNAMIC -> reticulumForwardServer.startDynamicForward(
                        profileId, destHash, rule.bindAddress, rule.bindPort)
                    PortForwardRule.Type.REMOTE -> throw McpError(-32602,
                        "Remote (-R) forwarding is not supported over Reticulum yet")
                }
                return@withContext JSONObject().apply {
                    put("ruleId", rule.id)
                    put("activated", true)
                    put("actualBoundPort", bound)
                }
            }
        }

        // Activate immediately if the profile has a connected session.
        // Mirrors the UI's savePortForwardRule path. Multiple connected
        // sessions per profile are unusual but possible (multi-tab); we
        // pick the first connected one rather than fanning out.
        val session = sshSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
        var actualBoundPort: Int? = null
        if (session != null) {
            val info = SshSessionManager.PortForwardInfo(
                ruleId = rule.id,
                type = when (rule.type) {
                    PortForwardRule.Type.LOCAL -> SshSessionManager.PortForwardType.LOCAL
                    PortForwardRule.Type.REMOTE -> SshSessionManager.PortForwardType.REMOTE
                    PortForwardRule.Type.DYNAMIC -> SshSessionManager.PortForwardType.DYNAMIC
                },
                bindAddress = rule.bindAddress,
                bindPort = rule.bindPort,
                targetHost = rule.targetHost,
                targetPort = rule.targetPort,
            )
            sshSessionManager.applyPortForwards(session.sessionId, listOf(info))
            // Read the actually-bound port back from the live session so
            // a bindPort=0 (REMOTE OS-pick) request can be reported back
            // to the agent.
            actualBoundPort = sshSessionManager.getSession(session.sessionId)
                ?.activeForwards
                ?.firstOrNull { it.ruleId == rule.id }
                ?.actualBoundPort
        }
        JSONObject().apply {
            put("ruleId", rule.id)
            put("activated", session != null)
            actualBoundPort?.let { put("actualBoundPort", it) }
        }
    }

    private suspend fun removePortForward(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val ruleId = args.optString("ruleId").ifEmpty {
            throw McpError(-32602, "Missing required argument: ruleId")
        }
        // Look up the rule to find the owning profile so we know which
        // session (if any) currently holds the live forward. Repository
        // doesn't expose getById today; iterate everyone's enabled rules
        // — list is tiny in practice (<10 rules per profile).
        var owningProfileId: String? = null
        var owningRule: PortForwardRule? = null
        for (p in connectionRepository.getAll()) {
            val r = portForwardRepository.getEnabledForProfile(p.id).firstOrNull { it.id == ruleId }
            if (r != null) { owningProfileId = p.id; owningRule = r; break }
        }
        if (owningProfileId != null) {
            if (reticulumSessionManager.isProfileConnected(owningProfileId)) {
                // Reticulum forwards key on the bound port (bindPort=0 OS-pick
                // not deactivatable by id in v1).
                owningRule?.let { reticulumForwardServer.stopForward(owningProfileId, it.bindPort) }
            } else {
                val session = sshSessionManager.getSessionsForProfile(owningProfileId)
                    .firstOrNull { it.status == SshSessionManager.SessionState.Status.CONNECTED }
                if (session != null) {
                    val forward = session.activeForwards.firstOrNull { it.ruleId == ruleId }
                    if (forward != null) {
                        sshSessionManager.removePortForward(session.sessionId, forward)
                    }
                }
            }
        }
        portForwardRepository.delete(ruleId)
        JSONObject().apply {
            put("ruleId", ruleId)
            put("deactivated", owningProfileId != null)
            put("deleted", true)
        }
    }

    private suspend fun uploadFileToSftp(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val localPath = args.optString("localPath").ifEmpty {
            throw McpError(-32602, "Missing required argument: localPath")
        }
        val remotePath = args.optString("remotePath").ifEmpty {
            throw McpError(-32602, "Missing required argument: remotePath")
        }
        // Confine the source to context.cacheDir so a malicious agent
        // can't exfiltrate arbitrary device files via the upload.
        val cacheRoot = context.cacheDir.canonicalFile
        val source = try {
            File(localPath).canonicalFile
        } catch (e: Exception) {
            throw McpError(-32602, "Cannot resolve localPath: ${e.message}")
        }
        if (!source.path.startsWith(cacheRoot.path + File.separator) && source.path != cacheRoot.path) {
            throw McpError(-32602, "localPath must be inside Haven's app cache (${cacheRoot.path})")
        }
        if (!source.exists() || !source.isFile) {
            throw McpError(-32602, "localPath does not point to a regular file")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            source.inputStream().use { input ->
                session.upload(input, source.length(), remotePath) { _, _ -> }
            }
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP upload failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("remotePath", remotePath)
            put("bytesUploaded", source.length())
        }
    }

    private suspend fun deleteSftpFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        val session = sshSessionManager.openSftpSession(profileId)
            ?: throw McpError(-32603, "No connected SFTP session for profile $profileId")
        try {
            // Refuse directories — `rm` and `rmdir` are different ops
            // in SFTP and the agent should pick the right verb.
            val attrs = session.stat(path)
            if (attrs.isDirectory) {
                throw McpError(-32602, "Refusing to delete directory '$path' — use a separate rmdir tool when one exists")
            }
            session.rm(path)
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "SFTP delete failed: ${e.message}")
        }
        JSONObject().apply {
            put("profileId", profileId)
            put("path", path)
            put("deleted", true)
        }
    }

    // --- Terminal snapshot / selection / clipboard / preference helpers ---

    /**
     * Resolve which terminal session a terminal-driving tool should act on.
     *
     * An explicit `sessionId` is used verbatim (a stale id then surfaces the
     * registry's own "no registered terminal tab" error downstream). When the
     * caller omits it, default to the sole open terminal session. With none
     * open, throw an **actionable** error telling the agent to connect a
     * profile first — rather than the opaque "missing sessionId" result that
     * left an external agent looping with no feedback (#213). With several
     * open, ask the caller to pick one.
     */
    private fun resolveTerminalSessionId(explicit: String): String {
        if (explicit.isNotEmpty()) return explicit
        val ids = terminalSessionRegistry.sessions.value.keys
        return when (ids.size) {
            0 -> throw McpError(
                -32603,
                "No terminal session is connected. Connect a profile first " +
                    "(list_connections then connect_profile, or open a Local Shell), " +
                    "then retry — or pass an explicit sessionId from list_sessions.",
            )
            1 -> ids.first()
            else -> throw McpError(
                -32602,
                "Several terminal sessions are open (${ids.joinToString(", ")}); " +
                    "pass sessionId to pick one (see list_sessions).",
            )
        }
    }

    private fun requireRegistryEntry(sessionId: String): sh.haven.feature.terminal.agent.TerminalSessionRegistry.Entry {
        if (sessionId.isEmpty()) throw McpError(-32602, "Missing required argument: sessionId")
        return terminalSessionRegistry.get(sessionId)
            ?: throw McpError(-32603, "No registered terminal tab for session $sessionId — open a terminal tab on this session first")
    }

    private fun readTerminalSnapshot(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val includeSegs = args.optBoolean("includeSemanticSegments", false)
        val maxLines = if (args.has("maxLines")) args.optInt("maxLines", Int.MAX_VALUE) else Int.MAX_VALUE
        val snap = entry.emulator.buildAgentSnapshot(
            includeSemanticSegments = includeSegs,
            maxLines = maxLines,
        )
        val scrollPos = entry.scrollController?.scrollbackPosition ?: 0
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("rows", snap.rows)
            put("cols", snap.cols)
            put("cursorRow", snap.cursorRow)
            put("cursorCol", snap.cursorCol)
            put("cursorVisible", snap.cursorVisible)
            put("terminalTitle", snap.terminalTitle)
            put("scrollbackSize", snap.scrollbackSize)
            put("scrollbackPosition", scrollPos)
            // Remote-driven terminal modes, scanned from the output stream
            // by MouseModeTracker. Null flows (headless agent shells) report
            // the inactive defaults.
            put("mouseMode", entry.mouseMode?.value ?: false)
            put("activeMouseMode", entry.activeMouseMode?.value ?: JSONObject.NULL)
            put("bracketPasteMode", entry.bracketPasteMode?.value ?: false)
            // Last-seen OSC events from this tab's OscHandler — useful for
            // asserting OSC 52 / 7 / 8 / 9 round-trips. Null when no event
            // of that type has been seen (or no OSC handler is wired).
            val osc = entry.oscHandler
            put("oscEvents", JSONObject().apply {
                put("lastClipboardSet", osc?.lastClipboardSet ?: JSONObject.NULL)
                put("lastCwd", osc?.lastCwd ?: JSONObject.NULL)
                put("lastHyperlink", osc?.lastHyperlink ?: JSONObject.NULL)
                put(
                    "lastNotification",
                    osc?.lastNotification?.let { (title, body) ->
                        JSONObject().apply {
                            put("title", title)
                            put("body", body)
                        }
                    } ?: JSONObject.NULL,
                )
            })
            put("lines", JSONArray().apply {
                snap.lines.forEach { line ->
                    put(JSONObject().apply {
                        put("text", line.text)
                        put("softWrapped", line.softWrapped)
                        if (includeSegs) {
                            put("semanticSegments", JSONArray().apply {
                                line.semanticSegments.forEach { seg ->
                                    put(JSONObject().apply {
                                        put("startCol", seg.startCol)
                                        put("endCol", seg.endCol)
                                        put("type", seg.type)
                                        put("promptId", seg.promptId)
                                        if (seg.metadata != null) put("metadata", seg.metadata)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
    }

    private fun getSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController — the tab may not be the foreground tab")
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", if (range == null) JSONObject.NULL else JSONObject().apply {
                put("startRow", range.startRow)
                put("startCol", range.startCol)
                put("endRow", range.endRow)
                put("endCol", range.endCol)
            })
        }
    }

    private fun readClipboard(): JSONObject {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()
        } else null
        return JSONObject().apply {
            put("text", text ?: JSONObject.NULL)
        }
    }

    private fun writeClipboard(args: JSONObject): JSONObject {
        val text = args.optString("text", "")
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: throw McpError(-32603, "ClipboardManager unavailable on this device")
        cm.setPrimaryClip(android.content.ClipData.newPlainText("haven-mcp", text))
        return JSONObject().apply {
            put("bytesWritten", text.toByteArray(Charsets.UTF_8).size)
        }
    }

    private val preferenceWhitelist = setOf(
        "terminal_scrollback_rows",
        "terminal_tap_to_position_cursor",
        "terminal_font_size",
        "mouse_input_enabled",
        "terminal_right_click",
        // Resize the terminal (reflow/SIGWINCH) to fit above the soft keyboard
        // instead of render-shifting (#206/#242). MCP-drivable so the reflow
        // path is testable without driving the Settings UI.
        "reflow_terminal_on_keyboard",
        // Terminal colour scheme group (#139/#165). Values are
        // TerminalColorScheme enum names (e.g. DRACULA, NORD). When
        // auto-switch is on the active scheme follows the system light/dark
        // mode via the light/dark keys; otherwise the manual key is used.
        // MCP-drivable so the scheme-persistence path (#209) is testable
        // without driving the Settings UI.
        "terminal_color_scheme",
        "terminal_auto_switch_scheme",
        "terminal_light_color_scheme",
        "terminal_dark_color_scheme",
        // Terminal background opacity (float 0.0..1.0). Below 1.0 the terminal
        // renders over the device wallpaper. MCP-drivable so the see-through
        // path is testable without dragging the Settings slider.
        "terminal_background_opacity",
        // SSH profile id the MCP server tunnels its loopback listener back
        // to (dedicated headless `-R`). Empty string clears it. See
        // McpTunnelManager.
        "mcp_tunnel_endpoint_profile_id",
        // Whether the MCP server also binds on the active WireGuard tunnel.
        "mcp_wireguard_enabled",
        // Whether the MCP server also binds the device's Wi-Fi/LAN address
        // (direct same-network reach, gated by pairing). MCP-drivable so the
        // transport-robustness path is testable without the Settings UI.
        "mcp_lan_bind_enabled",
        // Tunnel config id of the WireGuard tunnel the MCP server actively
        // keeps up as its carrier (empty string clears it → attach-to-first
        // -live behaviour).
        "mcp_wireguard_tunnel_config_id",
        // Master opt-in for exposing USB devices to the proot guest (gates
        // usb_attach_to_guest). MCP-drivable so integration tests can flip it.
        "usb_guest_exposure_enabled",
        // Master switch for inbound-email automation (Mail Rules). MCP-drivable so
        // the engine can be armed without the Settings UI.
        "mail_automation_enabled",
        // Experimental GPU stack for accelerated desktops: off = virgl/virpipe
        // (GL 2.1), on = venus + zink (modern GL, ~3.2 core) with wl_shm CPU-copy
        // present. MCP-drivable so the GPU-present path is testable without the
        // Settings UI. See DesktopManager.gpuPassthroughEnv.
        "gpu_use_venus",
        // Keyboard toolbar layout (JSON: array of rows; strings = built-in key
        // ids, {label,send} objects = custom snippet keys). MCP-drivable so the
        // toolbar can be reconfigured without the Settings GUI — get returns the
        // current JSON, set validates and replaces it. See ToolbarLayout.
        "toolbar_layout",
        // Audit logging of connection lifecycle events (connects, disconnects,
        // failures) to Settings → View connection log. Off by default. MCP-
        // drivable so an agent diagnosing a connection issue can enable logging,
        // reproduce, then read get_connection_log — e.g. an immediately exiting
        // local shell (#294).
        "connection_logging_enabled",
        // Local proot launch toggles (#300 / #301). These live in
        // ProotManager's own SharedPreferences (not the DataStore repo), so
        // get/set route through prootManager below. MCP-drivable so the launch
        // behaviour is testable/restorable without driving the Desktop → Manage
        // toggles — the toggle-state gap that previously forced UI tapping.
        "remap_low_ports",
        "share_storage_with_guest",
        "bind_android_system",
    )

    private suspend fun getPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        val value: Any = when (key) {
            "terminal_scrollback_rows" -> preferencesRepository.terminalScrollbackRows.first()
            "terminal_tap_to_position_cursor" -> preferencesRepository.terminalTapToPositionCursor.first()
            "terminal_font_size" -> preferencesRepository.terminalFontSize.first()
            "mouse_input_enabled" -> preferencesRepository.mouseInputEnabled.first()
            "terminal_right_click" -> preferencesRepository.terminalRightClick.first()
            "reflow_terminal_on_keyboard" -> preferencesRepository.reflowTerminalOnKeyboard.first()
            "terminal_color_scheme" -> preferencesRepository.terminalColorScheme.first().name
            "terminal_auto_switch_scheme" -> preferencesRepository.terminalAutoSwitchScheme.first()
            "terminal_light_color_scheme" -> preferencesRepository.terminalLightColorScheme.first().name
            "terminal_dark_color_scheme" -> preferencesRepository.terminalDarkColorScheme.first().name
            "mcp_tunnel_endpoint_profile_id" -> preferencesRepository.mcpTunnelEndpointProfileId.first() ?: ""
            "mcp_wireguard_enabled" -> preferencesRepository.mcpWireguardEnabled.first()
            "mcp_lan_bind_enabled" -> preferencesRepository.mcpLanBindEnabled.first()
            "mcp_wireguard_tunnel_config_id" -> preferencesRepository.mcpWireguardTunnelConfigId.first() ?: ""
            "usb_guest_exposure_enabled" -> preferencesRepository.usbGuestExposureEnabled.first()
            "mail_automation_enabled" -> preferencesRepository.mailAutomationEnabled.first()
            "connection_logging_enabled" -> preferencesRepository.connectionLoggingEnabled.first()
            "gpu_use_venus" -> preferencesRepository.gpuUseVenus.first()
            "toolbar_layout" -> preferencesRepository.toolbarLayoutJson.first()
            // Proot launch toggles live in ProotManager (#300 / #301).
            "remap_low_ports" -> prootManager.remapLowPorts
            "share_storage_with_guest" -> prootManager.shareStorageWithGuest
            "bind_android_system" -> prootManager.bindAndroidSystem
            else -> throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        return JSONObject().apply {
            put("key", key)
            put("value", value)
        }
    }

    private suspend fun setPreference(args: JSONObject): JSONObject {
        val key = args.optString("key").ifEmpty {
            throw McpError(-32602, "Missing required argument: key")
        }
        if (key !in preferenceWhitelist) {
            throw McpError(-32602, "Preference $key is not in the whitelist")
        }
        if (!args.has("value")) throw McpError(-32602, "Missing required argument: value")
        val rawValue = args.opt("value")
        // Coerce permissively — different MCP clients serialise JSON
        // numerics inconsistently. JSONObject.opt() may hand back
        // Integer, Long, Double, or even a String that parses as a
        // number; the caller's intent is unambiguous either way.
        fun coerceInt(): Int = when (rawValue) {
            is Number -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
                ?: throw McpError(-32602, "value must be an integer for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be an integer for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        fun coerceBool(): Boolean = when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.toBooleanStrictOrNull()
                ?: throw McpError(-32602, "value must be true/false for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be a boolean for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        fun coerceFloat(): Float = when (rawValue) {
            is Number -> rawValue.toFloat()
            is String -> rawValue.toFloatOrNull()
                ?: throw McpError(-32602, "value must be a number for $key, got \"$rawValue\"")
            else -> throw McpError(-32602, "value must be a number for $key, got ${rawValue?.javaClass?.simpleName}")
        }
        // Parse a TerminalColorScheme enum name (case-insensitive). Reject
        // unknown names rather than silently defaulting to HAVEN — a caller
        // that fat-fingers the scheme should hear about it, not have its
        // setting silently dropped.
        fun coerceScheme(): UserPreferencesRepository.TerminalColorScheme {
            val name = (rawValue as? String)
                ?: throw McpError(-32602, "value must be a scheme name string for $key, got ${rawValue?.javaClass?.simpleName}")
            return UserPreferencesRepository.TerminalColorScheme.entries
                .find { it.name.equals(name, ignoreCase = true) }
                ?: throw McpError(
                    -32602,
                    "Unknown colour scheme \"$name\" for $key. Valid: " +
                        UserPreferencesRepository.TerminalColorScheme.entries.joinToString(", ") { it.name },
                )
        }
        when (key) {
            "terminal_scrollback_rows" -> preferencesRepository.setTerminalScrollbackRows(coerceInt())
            "terminal_font_size" -> preferencesRepository.setTerminalFontSize(coerceInt())
            "terminal_tap_to_position_cursor" -> preferencesRepository.setTerminalTapToPositionCursor(coerceBool())
            "mouse_input_enabled" -> preferencesRepository.setMouseInputEnabled(coerceBool())
            "terminal_right_click" -> preferencesRepository.setTerminalRightClick(coerceBool())
            "reflow_terminal_on_keyboard" -> preferencesRepository.setReflowTerminalOnKeyboard(coerceBool())
            "terminal_color_scheme" -> preferencesRepository.setTerminalColorScheme(coerceScheme())
            "terminal_auto_switch_scheme" -> preferencesRepository.setTerminalAutoSwitchScheme(coerceBool())
            "terminal_light_color_scheme" -> preferencesRepository.setTerminalLightColorScheme(coerceScheme())
            "terminal_dark_color_scheme" -> preferencesRepository.setTerminalDarkColorScheme(coerceScheme())
            "terminal_background_opacity" -> preferencesRepository.setTerminalBackgroundOpacity(coerceFloat())
            "mcp_tunnel_endpoint_profile_id" ->
                preferencesRepository.setMcpTunnelEndpointProfileId((rawValue as? String)?.ifBlank { null })
            "mcp_wireguard_enabled" -> preferencesRepository.setMcpWireguardEnabled(coerceBool())
            "mcp_lan_bind_enabled" -> preferencesRepository.setMcpLanBindEnabled(coerceBool())
            "mcp_wireguard_tunnel_config_id" ->
                preferencesRepository.setMcpWireguardTunnelConfigId((rawValue as? String)?.ifBlank { null })
            "usb_guest_exposure_enabled" -> preferencesRepository.setUsbGuestExposureEnabled(coerceBool())
            "mail_automation_enabled" -> preferencesRepository.setMailAutomationEnabled(coerceBool())
            "connection_logging_enabled" -> preferencesRepository.setConnectionLoggingEnabled(coerceBool())
            "gpu_use_venus" -> preferencesRepository.setGpuUseVenus(coerceBool())
            "toolbar_layout" -> {
                val json = (rawValue as? String)
                    ?: throw McpError(-32602, "value must be a toolbar-layout JSON string for $key")
                ToolbarLayout.validate(json)?.let { throw McpError(-32602, "Invalid toolbar layout: $it") }
                preferencesRepository.setToolbarLayoutJson(json)
            }
            // Proot launch toggles live in ProotManager (#300 / #301). Routing
            // through its setters updates the StateFlow, so the Desktop → Manage
            // UI reflects the change too.
            "remap_low_ports" -> prootManager.setRemapLowPorts(coerceBool())
            "share_storage_with_guest" -> prootManager.setShareStorageWithGuest(coerceBool())
            "bind_android_system" -> prootManager.setBindAndroidSystem(coerceBool())
        }
        return JSONObject().apply {
            put("key", key)
            put("value", rawValue)
        }
    }

    private fun parseSelectionMode(s: String?): org.connectbot.terminal.SelectionMode = when (s?.uppercase()) {
        null, "", "CHARACTER" -> org.connectbot.terminal.SelectionMode.CHARACTER
        "WORD" -> org.connectbot.terminal.SelectionMode.WORD
        "LINE" -> org.connectbot.terminal.SelectionMode.LINE
        "NONE" -> org.connectbot.terminal.SelectionMode.NONE
        else -> throw McpError(-32602, "Unknown selection mode: $s")
    }

    private fun startSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val mode = parseSelectionMode(args.optString("mode", "CHARACTER"))
        // The Compose-side SelectionController.startSelection short-circuits
        // when `selectionManager.mode != NONE` (so the gesture handler can't
        // re-anchor while a selection is already up). Mirror "replaces any
        // existing selection on this session" by clearing first; then
        // startSelection(mode) sets both mode and an initial range, and
        // updateSelectionStart/End move it to the caller's (row, col).
        controller.clearSelection()
        controller.startSelection(mode)
        controller.updateSelectionStart(row, col)
        controller.updateSelectionEnd(row, col)
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("active", controller.isSelectionActive)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
        }
    }

    private fun setComposeMode(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.composeController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ComposeController — open a terminal tab on this session first")
        if (args.has("enabled")) {
            if (args.optBoolean("enabled")) controller.startComposeMode() else controller.stopComposeMode()
        } else {
            controller.toggleComposeMode()
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("composeModeActive", controller.isComposeModeActive)
            put("composedText", controller.getComposedText())
        }
    }

    private fun extendSelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        controller.updateSelectionEnd(row, col)
        val range = controller.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
        }
    }

    private fun dragSelectionTo(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val selection = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val scroll = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        if (!args.has("toRow")) throw McpError(-32602, "Missing required argument: toRow")
        if (!args.has("toCol")) throw McpError(-32602, "Missing required argument: toCol")
        val toRow = args.optInt("toRow")
        val toCol = args.optInt("toCol")
        if (toCol < 0) throw McpError(-32602, "toCol must be >= 0")
        val rows = entry.emulator.dimensions.rows
        // Translate the requested logical row into a viewport-anchored
        // endpoint plus a signed step count. The Compose gesture handler
        // does this implicitly because the pointer reports a Y position;
        // MCP works in logical rows so the agent can request "drag 30
        // rows into scrollback" without knowing the viewport height.
        //
        // Sign convention here matches scroll_terminal: positive steps =
        // scroll back into older content (so a finger at the top edge
        // dragging UP into scrollback is positive); negative = scroll
        // forward toward the live screen. Each step is one row of scroll
        // paired with one shiftSelectionStartByRows of the same sign.
        val (clampedRow, requestedSteps) = when {
            toRow < 0 -> 0 to (-toRow)                          // into scrollback
            toRow >= rows -> (rows - 1) to (rows - 1 - toRow)   // toward live
            else -> toRow to 0
        }
        var actualSteps = 0
        var clamped = false
        if (requestedSteps != 0) {
            val stepDirection = if (requestedSteps > 0) +1 else -1
            val totalSteps = kotlin.math.abs(requestedSteps)
            // Read the clamp threshold from the emulator's live agent
            // snapshot rather than the ScrollController's `maxScrollback`
            // property. The controller reads through the Compose-side
            // `screenState`, which only refreshes on recomposition — for
            // sessions whose tab isn't currently the foreground (the
            // typical agent-shell case), the cached scrollback size lags
            // the underlying emulator by an arbitrary amount.
            // `buildAgentSnapshot` reads the same `_snapshot.value`
            // that's updated on every processPendingUpdates(), so it's
            // authoritative even when the Compose subtree is paused.
            val maxScrollback = entry.emulator.buildAgentSnapshot(maxLines = 0).scrollbackSize
            repeat(totalSteps) {
                val canStep = if (stepDirection > 0) {
                    scroll.scrollbackPosition < maxScrollback
                } else {
                    scroll.scrollbackPosition > 0
                }
                if (!canStep) {
                    clamped = true
                    return@repeat
                }
                scroll.scrollBy(stepDirection)
                selection.shiftSelectionStartByRows(stepDirection)
                actualSteps += stepDirection
            }
        }
        selection.updateSelectionEnd(clampedRow, toCol)
        val range = selection.getSelectionRange()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("range", range?.let {
                JSONObject().apply {
                    put("startRow", it.startRow); put("startCol", it.startCol)
                    put("endRow", it.endRow); put("endCol", it.endCol)
                }
            } ?: JSONObject.NULL)
            put("scrollbackPosition", scroll.scrollbackPosition)
            put("scrollSteps", actualSteps)
            put("clamped", clamped)
            put("text", selection.getSelectedText())
        }
    }

    private fun copySelection(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val controller = entry.selectionController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active SelectionController")
        val text = controller.copySelection()
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("text", text)
        }
    }

    private fun tapTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val row = args.optInt("row", -1).also { if (it < 0) throw McpError(-32602, "row must be >= 0") }
        val col = args.optInt("col", -1).also { if (it < 0) throw McpError(-32602, "col must be >= 0") }
        val beforeCol = entry.emulator.buildAgentSnapshot().cursorCol
        val handled = entry.emulator.tapToPositionCursorOnPrompt(row, col)
        val afterCol = entry.emulator.buildAgentSnapshot().cursorCol
        val deltaCols = afterCol - beforeCol
        val dispatched = when {
            !handled -> "NONE"
            deltaCols < 0 -> "LEFT"
            deltaCols > 0 -> "RIGHT"
            else -> "NONE"
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("handled", handled)
            put("deltaCols", deltaCols)
            put("dispatched", dispatched)
        }
    }

    private fun scrollTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val scrollController = entry.scrollController
            ?: throw McpError(-32603, "Terminal tab for session $sessionId has no active ScrollController")
        val lines = if (args.has("lines")) args.optInt("lines") else throw McpError(-32602, "Missing required argument: lines")
        scrollController.scrollBy(lines)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("scrollbackPosition", scrollController.scrollbackPosition)
        }
    }

    private fun sendTerminalInput(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val text = args.optString("text", "")
        val bracketed = args.optBoolean("bracketedPaste", false)
        val returnSnapshot = args.optBoolean("returnSnapshot", false)

        // Named keys → real control bytes the `text` param can't express
        // cleanly (a "\r" in text arrives as two literal chars; a raw-mode REPL
        // treats "\n" as newline-insert, not submit). #14
        val keyBytes = StringBuilder()
        args.optJSONArray("keys")?.let { keys ->
            for (i in 0 until keys.length()) {
                val name = keys.optString(i)
                keyBytes.append(
                    namedKeyToBytes(name) ?: throw McpError(
                        -32602,
                        "Unknown key '$name'. Supported: enter, esc, tab, space, backspace, " +
                            "delete, up, down, left, right, home, end, pageup, pagedown, " +
                            "ctrl-a/c/d/e/l/u/w/z.",
                    ),
                )
            }
        }
        if (text.isEmpty() && keyBytes.isEmpty()) {
            throw McpError(-32602, "Provide text and/or keys")
        }

        // Wrap text in bracketed-paste markers when asked, so a raw-mode REPL
        // (Claude Code, readline, vim) treats multi-line input as one paste
        // rather than interleaved keystrokes that fight its submit handling.
        val body = if (text.isNotEmpty() && bracketed) "\u001b[200~$text\u001b[201~" else text

        // Hard cap (≈two screens) keeps consent reviewable and avoids an agent
        // pasting megabytes through the terminal.
        val totalBytes = body.toByteArray(Charsets.UTF_8).size +
            keyBytes.toString().toByteArray(Charsets.UTF_8).size
        if (totalBytes > 4096) {
            throw McpError(-32602, "input too long: $totalBytes bytes (max 4096)")
        }

        // Send the body first, then the keys as a SEPARATE write after a brief
        // settle — so a submit key (Enter) arrives in its own read() and is seen
        // as a discrete keypress, not folded into the text burst by a raw-mode
        // REPL's input batching (verified on Claude Code: text+\r in one burst
        // stages without submitting; a separate \r submits). #14
        if (body.isNotEmpty()) sendRawInput(sessionId, body)
        if (body.isNotEmpty() && keyBytes.isNotEmpty()) {
            try {
                Thread.sleep(SUBMIT_SETTLE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (keyBytes.isNotEmpty()) sendRawInput(sessionId, keyBytes.toString())

        return JSONObject().apply {
            put("sessionId", sessionId)
            put("delivered", true)
            put("bytesSent", totalBytes)
            if (returnSnapshot) {
                runCatching { readTerminalSnapshot(JSONObject().put("sessionId", sessionId)) }
                    .getOrNull()?.let { put("snapshot", it) }
            }
        }
    }

    /**
     * Write a raw string to a session's PTY. Tries SSH first, then the
     * local-shell manager; surfaces the most informative "no session" error.
     */
    private fun sendRawInput(sessionId: String, s: String) {
        val sshErr = try {
            sshSessionManager.sendInput(sessionId, s)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        if (sshErr != null) {
            try {
                localSessionManager.sendInput(sessionId, s)
            } catch (e: IllegalStateException) {
                throw McpError(-32603, e.message ?: sshErr)
            }
        }
    }

    /**
     * Deliver one message to a raw-mode REPL as a single submitted turn:
     * bracketed paste + settle + Enter + snapshot. Thin wrapper over
     * [sendTerminalInput] for agent↔agent conversation. #14/#19
     */
    private fun sendToAgent(args: JSONObject): JSONObject {
        val message = args.optString("message").ifEmpty {
            throw McpError(-32602, "Missing required argument: message")
        }
        return sendTerminalInput(
            JSONObject().apply {
                if (args.has("sessionId")) put("sessionId", args.optString("sessionId"))
                put("text", message)
                put("bracketedPaste", true)
                put("keys", JSONArray().put("enter"))
                put("returnSnapshot", true)
                // Bound the ack snapshot to a screenful so a delivered message
                // doesn't read back as a timeout behind a megabyte of scrollback
                // over the tunnel (deliver-then-return). An explicit maxLines wins.
                put("maxLines", if (args.has("maxLines")) args.optInt("maxLines") else SEND_TO_AGENT_SNAPSHOT_LINES)
            },
        )
    }

    /** Map a named key to the control bytes a PTY expects. Null = unknown. */
    private fun namedKeyToBytes(name: String): String? = when (name.lowercase().trim()) {
        "enter", "return", "cr" -> "\r"
        "newline", "lf" -> "\n"
        "esc", "escape" -> "\u001b"
        "tab" -> "\t"
        "space" -> " "
        "backspace", "bs" -> "\u007f"
        "delete", "del" -> "\u001b[3~"
        "ctrl-a", "ctrl+a", "c-a" -> "\u0001"
        "ctrl-c", "ctrl+c", "c-c" -> "\u0003"
        "ctrl-d", "ctrl+d", "c-d" -> "\u0004"
        "ctrl-e", "ctrl+e", "c-e" -> "\u0005"
        "ctrl-l", "ctrl+l", "c-l" -> "\u000c"
        "ctrl-u", "ctrl+u", "c-u" -> "\u0015"
        "ctrl-w", "ctrl+w", "c-w" -> "\u0017"
        "ctrl-z", "ctrl+z", "c-z" -> "\u001a"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "right" -> "\u001b[C"
        "left" -> "\u001b[D"
        "home" -> "\u001b[H"
        "end" -> "\u001b[F"
        "pageup", "pgup" -> "\u001b[5~"
        "pagedown", "pgdn" -> "\u001b[6~"
        else -> null
    }

    private fun feedTerminalOutput(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val feed = entry.feedOutput
            ?: throw McpError(
                -32603,
                "Session $sessionId has no output pipeline — feed_terminal_output is not supported for headless agent shells",
            )
        val hasText = args.has("text")
        val hasB64 = args.has("bytesBase64")
        if (hasText == hasB64) {
            throw McpError(-32602, "Provide exactly one of: text, bytesBase64")
        }
        val bytes: ByteArray = if (hasText) {
            args.optString("text").toByteArray(Charsets.UTF_8)
        } else {
            try {
                android.util.Base64.decode(args.optString("bytesBase64"), android.util.Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw McpError(-32602, "bytesBase64 is not valid base64: ${e.message}")
            }
        }
        if (bytes.size > 65536) {
            throw McpError(-32602, "payload too long: ${bytes.size} bytes (max 65536)")
        }
        // feedOutput is internally synchronized against the live data
        // callback, so this is safe to call straight from the MCP thread.
        feed(bytes, 0, bytes.size)
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("bytesFed", bytes.size)
        }
    }

    private fun dragTerminal(args: JSONObject): JSONObject {
        val sessionId = resolveTerminalSessionId(args.optString("sessionId"))
        val entry = requireRegistryEntry(sessionId)
        val injector = entry.gestureInjector
            ?: throw McpError(
                -32603,
                "Session $sessionId has no gesture injector — its terminal tab must be the foreground tab",
            )
        val pathArr = args.optJSONArray("path")
            ?: throw McpError(-32602, "Missing required argument: path")
        if (pathArr.length() == 0) {
            throw McpError(-32602, "path must have at least one cell")
        }
        if (pathArr.length() > 256) {
            throw McpError(-32602, "path too long: ${pathArr.length()} cells (max 256)")
        }
        val path = ArrayList<Pair<Int, Int>>(pathArr.length())
        for (i in 0 until pathArr.length()) {
            val cell = pathArr.optJSONObject(i)
                ?: throw McpError(-32602, "path[$i] must be an object { row, col }")
            if (!cell.has("row") || !cell.has("col")) {
                throw McpError(-32602, "path[$i] must have both row and col")
            }
            path.add(cell.getInt("row") to cell.getInt("col"))
        }
        val pressMs = if (args.has("pressMs")) args.optLong("pressMs", 900L) else 900L
        val stepMs = if (args.has("stepMs")) args.optLong("stepMs", 30L) else 30L
        val holdMs = if (args.has("holdMs")) args.optLong("holdMs", 1000L) else 1000L
        if (pressMs < 0 || stepMs < 0 || holdMs < 0) {
            throw McpError(-32602, "pressMs / stepMs / holdMs must be non-negative")
        }
        try {
            injector.injectDrag(path, pressMs, stepMs, holdMs)
        } catch (e: IllegalArgumentException) {
            throw McpError(-32602, e.message ?: "invalid drag_terminal arguments")
        } catch (e: IllegalStateException) {
            throw McpError(-32603, e.message ?: "gesture injection failed")
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("cells", path.size)
            put("approxDurationMs", pressMs + stepMs * (path.size - 1).coerceAtLeast(0) + holdMs)
        }
    }

    private fun openDeveloperSettings(): JSONObject {
        // ACTION_APPLICATION_DEVELOPMENT_SETTINGS lands directly on the
        // Developer Options screen on every Android version we support;
        // nothing in there changes without an explicit user tap, so
        // this is a tap-equivalent action — same consent shape as
        // play_file.
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            JSONObject().apply { put("dispatched", true) }
        } catch (e: android.content.ActivityNotFoundException) {
            throw McpError(
                -32603,
                "Could not open Developer Options — most likely Developer Mode is not yet enabled. Toggle Settings → About phone → Build number 7 times to unlock it.",
            )
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to open Developer Settings: ${e.message}")
        }
    }

    /** Shared upper bound on staged APK size — 200 MiB; release APKs are ~80–100 MiB. */
    private val agentInstallMaxBytes: Long = 200L * 1024 * 1024

    /** One copy of the Shizuku-missing hint, reused by [runShizukuOrThrow]. */
    private val shizukuInstallHint =
        "Install Shizuku from https://shizuku.rikka.app and grant Haven permission, then retry."

    /**
     * Shared Shizuku-exec wrapper for the privileged tools. Maps the two
     * failure modes every caller used to handle identically — Shizuku
     * unavailable (an [IllegalStateException], answered with
     * [shizukuInstallHint]) and a generic exec failure — into [McpError].
     * When [requireSuccess] is true (default) a non-zero exit is also
     * surfaced, tagged with [label]; pass false when the caller inspects the
     * output/exit itself (e.g. an exact readback or a tolerated empty result).
     *
     * Does NOT cover the install path ([installStagedApkBlocking]) — that one
     * deliberately catches the unavailable case to fall back to the system
     * installer rather than throw.
     */
    private fun runShizukuOrThrow(
        cmd: String,
        label: String,
        requireSuccess: Boolean = true,
    ): WaylandSocketHelper.ShizukuExecResult {
        val result = try {
            WaylandSocketHelper.execAsShizuku(cmd)
        } catch (e: IllegalStateException) {
            throw McpError(-32603, "${e.message}. $shizukuInstallHint")
        } catch (e: Exception) {
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        if (requireSuccess && result.exitCode != 0) {
            throw McpError(-32603, "$label exited ${result.exitCode}: ${result.output.take(500)}")
        }
        return result
    }

    /** Stage a fresh File handle under cacheDir/agent-install/. */
    private fun stageApkFile(): File {
        val dir = File(context.cacheDir, "agent-install").apply { mkdirs() }
        return File(dir, "haven-agent-install-${System.currentTimeMillis()}.apk")
    }

    /**
     * Verify that the staged file's first two bytes are the zip
     * magic ("PK") — cheap defence against an HTML 200, wrong file
     * type, or a truncated download.
     */
    private fun assertApkMagic(target: File, written: Long) {
        val firstFour = target.inputStream().use { stream ->
            ByteArray(4).also { stream.read(it) }
        }
        if (firstFour[0] != 0x50.toByte() || firstFour[1] != 0x4B.toByte()) {
            target.delete()
            throw McpError(
                -32603,
                "Got $written bytes but they don't start with the zip/APK magic — wrong path/URL?",
            )
        }
    }

    /**
     * Stage an APK by copying [openInput]'s stream into a fresh cache file,
     * enforcing [agentInstallMaxBytes]. Shared by [installApkFromUrl] and
     * [installApkFromBackend]. [openInput] runs inside the guarded block so a
     * failure to open the source maps through [onFailure] too. On any failure
     * the partial file is deleted; the size-cap breach throws its own McpError.
     * Returns (stagedFile, bytesWritten).
     */
    private suspend fun streamToStagedApk(
        openInput: suspend () -> java.io.InputStream,
        onFailure: (Exception) -> String,
    ): Pair<File, Long> {
        val target = stageApkFile()
        val written = try {
            openInput().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > agentInstallMaxBytes) {
                            target.delete()
                            throw McpError(-32603, "APK exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap")
                        }
                        output.write(buf, 0, n)
                    }
                    total
                }
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, onFailure(e))
        }
        return target to written
    }

    private suspend fun installApkFromUrl(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        val url = try {
            java.net.URL(urlString)
        } catch (e: Exception) {
            throw McpError(-32602, "Invalid URL: ${e.message}")
        }
        if (url.protocol !in setOf("http", "https")) {
            throw McpError(-32602, "Only http(s) URLs are supported (got ${url.protocol})")
        }
        // Stage the APK in app cache, then pipe it into `pm install -S`
        // via Shizuku (or hand off to the system installer dialog).
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Haven/1.0 (install_apk_from_url)")
        }
        val (target, written) = try {
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    throw McpError(-32603, "HTTP ${conn.responseCode} from ${url.host}")
                }
            } catch (e: McpError) {
                throw e
            } catch (e: Exception) {
                throw McpError(-32603, "Download failed: ${e.message}")
            }
            streamToStagedApk({ conn.inputStream }) { "Download failed: ${it.message}" }
        } finally {
            conn.disconnect()
        }
        // Sanity check — APK is a zip, magic bytes 0x50 0x4B ("PK").
        // Reject HTML / plain-text bodies that 200'd despite being the
        // wrong content type. Cheap and catches the most common
        // wrong-URL failure mode.
        assertApkMagic(target, written)
        return@withContext installStagedApk(target, written)
    }

    /**
     * Install a staged APK. For a foreign package this is synchronous and
     * returns the install result. For a **self-update** (the APK's package is
     * ours) the `pm install` commit replaces this very process, so a
     * synchronous response would never reach the MCP client — it reads as a
     * timeout (the friction repeatedly hit during agent-driven testing). So:
     * detect a self-install from the APK manifest (no Shizuku needed), return
     * an immediate `restarting` ack, and defer the install just long enough
     * for the ack to flush over the (reverse) MCP tunnel.
     */
    private fun installStagedApk(target: File, written: Long): JSONObject {
        val pkg = runCatching {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.packageName
        }.getOrNull()
        if (pkg == context.packageName) {
            backgroundScope.launch {
                delay(800) // let the ack flush before pm install replaces us
                runCatching { installStagedApkBlocking(target, written) }
                    .onFailure {
                        android.util.Log.w("McpTools", "deferred self-install failed: ${it.message}")
                    }
            }
            return JSONObject().apply {
                put("installed", false)
                put("restarting", true)
                put("bytesStaged", written)
                put("package", pkg)
                put(
                    "note",
                    "Installing Haven over itself — the MCP connection drops when the new " +
                        "process starts. Reconnect (/mcp reconnect) and confirm the running " +
                        "build with get_app_info.",
                )
            }
        }
        return installStagedApkBlocking(target, written)
    }

    /**
     * Install an APK that's already been staged at [target]. Tries
     * the Shizuku-driven `pm install -S` path first; falls back to
     * firing the system installer dialog via `Intent.ACTION_VIEW`
     * when Shizuku is unavailable.
     *
     * Caller owns [target]'s creation; this function takes ownership
     * of cleanup. On the system-installer fallback path the file
     * survives long enough for the installer to read it and is
     * cleaned up by a delayed Handler.
     */
    private fun installStagedApkBlocking(target: File, written: Long): JSONObject {
        // Hand off to Shizuku — `pm install -S <size>` reads APK bytes
        // from stdin. Working directory of the shell process doesn't
        // need to see our cache dir; we pipe FileInputStream directly.
        //
        // NOTE: do NOT wrap the Shizuku call in a `finally { delete }`
        // here — the Shizuku-unavailable branch needs the staged APK
        // to survive long enough for the system installer to read it
        // via FileProvider. Each terminal path handles its own cleanup.
        val result = try {
            sh.haven.core.local.WaylandSocketHelper.execAsShizukuWithStdin(
                cmd = "pm install -S $written",
                stdin = target.inputStream(),
            )
        } catch (e: IllegalStateException) {
            // Shizuku unavailable — fall back to the system installer
            // dialog. The user gets one confirmation prompt on-device;
            // the MCP call returns immediately with pending=true. The
            // staged APK is intentionally not deleted here — the
            // system installer reads it via FileProvider after this
            // function returns. A delayed Handler cleans up after 5
            // min in case the user dismisses the dialog.
            return launchSystemInstaller(target, written, e.message)
        } catch (e: Exception) {
            target.delete()
            throw McpError(-32603, "Shizuku exec failed: ${e.message}")
        }
        // Shizuku path took ownership of the bytes; delete the staged
        // APK regardless of the install outcome.
        target.delete()
        if (result.exitCode != 0 || !result.output.contains("Success")) {
            throw McpError(
                -32603,
                "pm install exited ${result.exitCode}: ${result.output.take(500)}",
            )
        }
        return JSONObject().apply {
            put("installed", true)
            put("bytesDownloaded", written)
            put("output", result.output)
        }
    }

    private suspend fun installApkFromBackend(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val path = args.optString("path").ifEmpty {
            throw McpError(-32602, "Missing required argument: path")
        }
        // Same gate as serve_file — installing an APK reads bytes
        // from a connected backend, so it falls under "allow agents
        // to read file contents". The system installer still asks
        // for confirmation on the Shizuku-less path; on the Shizuku
        // path the EVERY_CALL consent on this tool covers it.
        if (!preferencesRepository.agentAllowFileRead.first()) {
            throw McpError(
                -32011,
                "agent file read is disabled — enable in Settings → Agent endpoint",
            )
        }
        val resolution = transportSelector.resolveFileBackend(profileId)
            ?: throw McpError(-32603, "No connected backend for profile $profileId")
        val backend = resolution.backend

        val entry = try {
            backend.stat(path)
        } catch (t: Throwable) {
            throw McpError(-32603, "Failed to stat $path: ${t.message}")
        }
        if (entry.isDirectory) {
            throw McpError(-32602, "$path is a directory; need an APK file")
        }
        if (entry.size > agentInstallMaxBytes) {
            throw McpError(
                -32603,
                "$path is ${entry.size} bytes — exceeds ${agentInstallMaxBytes / (1024 * 1024)} MiB cap",
            )
        }

        // Streaming a large APK from a backend (SFTP over a flaky tunnel,
        // rclone over the network, Reticulum over the mesh) routinely outlasts
        // the MCP client's request budget — the call reads as a timeout even
        // though the install completes (observed installing Haven over itself
        // via the WG-tunnelled "near" SFTP). The existing self-install deferral
        // only covers the `pm install` commit, not this upstream download, so
        // the early-ack never reached the client. Fix: after the fast
        // validation above (gate / stat / dir / size cap — all return errors
        // synchronously), run the stream + install in the background and return
        // a pending ack the caller confirms with get_app_info.
        val sizeBytes = entry.size
        val backendLabel = backend.label
        backgroundScope.launch {
            runCatching {
                val (target, written) = streamToStagedApk({ backend.openInputStream(path, 0) }) {
                    "Read from $backendLabel failed: ${it.message}"
                }
                assertApkMagic(target, written)
                installStagedApk(target, written)
            }.onFailure {
                android.util.Log.w("McpTools", "backend APK install failed ($path): ${it.message}")
                recordInstallOutcome(profileId, path, backendLabel, ok = false, detail = it.message)
            }.onSuccess {
                android.util.Log.i("McpTools", "backend APK install completed ($path): $it")
                // installStagedApk returns {installed} (Shizuku) or {pending}
                // (system-installer handoff) — both mean the background work
                // reached the install step without error.
                val staged = it.optBoolean("pending", false)
                recordInstallOutcome(
                    profileId, path, backendLabel,
                    ok = true,
                    detail = if (staged) "awaiting on-device install confirmation" else "installed",
                )
            }
        }
        val shizukuReady = sh.haven.core.local.WaylandSocketHelper.isShizukuAvailable() &&
            sh.haven.core.local.WaylandSocketHelper.hasShizukuPermission()
        JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("staging", true)
            put("bytes", sizeBytes)
            // Tells the caller whether the install will be silent (Shizuku) or
            // need a user tap on the staged-update notification (no Shizuku).
            put("shizukuReady", shizukuReady)
            put(
                "message",
                "Streaming $sizeBytes bytes from $backendLabel and installing in the " +
                    "background — backend transfers can outlast the request timeout, so this " +
                    "returns immediately rather than blocking. " +
                    (if (shizukuReady) {
                        "Shizuku is available: install is silent. "
                    } else {
                        "No Shizuku: a 'Haven update ready — tap to install' notification will " +
                            "appear (it survives backgrounding) — tap it to confirm. "
                    }) +
                    "Confirm the running build with get_app_info; if Haven is updating itself " +
                    "the MCP link drops on restart, so reconnect (/mcp reconnect) first.",
            )
        }
    }

    /**
     * Persist the terminal outcome of a backgrounded backend install so it's
     * discoverable after the `pending` ack: a queryable snapshot in
     * get_app_info's `lastInstall`, plus a connection-log entry (visible in
     * Settings → connection log; no-ops on an orphan/"local" profile id via the
     * repository's FK guard). (#245 follow-up)
     */
    private suspend fun recordInstallOutcome(
        profileId: String,
        path: String,
        backendLabel: String,
        ok: Boolean,
        detail: String?,
    ) {
        lastInstallResult = JSONObject().apply {
            put("ok", ok)
            put("path", path)
            put("backend", backendLabel)
            detail?.let { put("detail", it) }
        }
        runCatching {
            connectionLogRepository.logEvent(
                profileId,
                if (ok) {
                    sh.haven.core.data.db.entities.ConnectionLog.Status.CONNECTED
                } else {
                    sh.haven.core.data.db.entities.ConnectionLog.Status.FAILED
                },
                details = "install_apk_from_backend: $path — ${detail ?: if (ok) "ok" else "failed"}",
            )
        }
    }

    /**
     * Shizuku-less fallback for [installApkFromUrl] — fires the
     * system installer dialog via `Intent.ACTION_VIEW` with a
     * FileProvider URI for the staged APK. Returns a structured
     * response indicating the install is pending user confirmation
     * on-device.
     *
     * The APK stays in [target] until the system installer reads
     * it. A delayed cleanup job removes the file after a generous
     * window so it doesn't linger when the user dismisses the
     * dialog.
     */
    private fun launchSystemInstaller(target: java.io.File, written: Long, shizukuReason: String?): JSONObject {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
        } catch (e: Exception) {
            target.delete()
            throw McpError(
                -32603,
                "FileProvider URI build failed: ${e.message}. Cannot show system installer.",
            )
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        // Best-effort direct launch — works when Haven is foreground; Android
        // suppresses an activity start from the background (the install dialog
        // that "vanishes" when you switch away). The notification below is the
        // robust path: the user's tap is a foreground gesture the OS lets
        // through, so the installer appears even when Haven was backgrounded.
        val directLaunched = try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }

        var notified = false
        val versionName = runCatching {
            context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.versionName
        }.getOrNull()
        try {
            val managerCompat = NotificationManagerCompat.from(context)
            if (managerCompat.areNotificationsEnabled()) {
                ensureInstallNotificationChannel()
                val piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
                val pending = android.app.PendingIntent.getActivity(
                    context, target.absolutePath.hashCode(), intent, piFlags,
                )
                val notif = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Haven update ready")
                    .setContentText(
                        versionName?.let { "Tap to install Haven $it" }
                            ?: "Tap to install the staged update",
                    )
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setContentIntent(pending)
                    // Asks the OS to surface it immediately as a heads-up — the
                    // closest a backgrounded app gets to "comes to the foreground".
                    .setFullScreenIntent(pending, true)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context)
                    .notify(target.absolutePath.hashCode(), notif)
                notified = true
            }
        } catch (_: Exception) {
            // Notification is best-effort; a failed post must not abort the install.
        }

        // Keep the staged APK long enough for the user to tap the notification
        // (the direct dialog reads it in seconds; the notification path can be
        // minutes), then clean up so it doesn't linger.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { target.delete() },
            15 * 60 * 1000L,
        )

        if (!directLaunched && !notified) {
            target.delete()
            throw McpError(
                -32603,
                "Couldn't launch the installer or post a tap-to-install notification " +
                    "(Shizuku unavailable: ${shizukuReason ?: "no detail"}). " +
                    "Enable notifications for Haven, or start Shizuku for silent installs.",
            )
        }
        return JSONObject().apply {
            put("installed", false)
            put("pending", true)
            put("bytesDownloaded", written)
            put("shizukuReady", false)
            put("notified", notified)
            put(
                "message",
                buildString {
                    append("Shizuku unavailable (${shizukuReason ?: "not running"}) — no silent install. ")
                    if (notified) {
                        append("Posted a 'Haven update ready — tap to install' notification that " +
                            "survives Haven being backgrounded; tap it to confirm. ")
                    }
                    if (directLaunched) {
                        append("Also opened the installer directly (visible if Haven is in the foreground). ")
                    }
                    append("Start Shizuku for fully silent installs.")
                },
            )
        }
    }

    /**
     * Run `logcat -d` via Shizuku and return the captured block.
     *
     * Shizuku-as-shell-uid (2000) already holds READ_LOGS, so we don't have
     * to grant the permission to Haven itself — every call routes through
     * the same execAsShizuku path the other privileged tools use. That keeps
     * Haven's own permission set minimal and reuses the user's existing
     * Shizuku trust decision.
     *
     * `filter`, `packageName`, `pid` and `since` are all interpolated into
     * a `sh -c` command line, so each arg is validated to reject shell
     * metacharacters (`;`, `|`, `&`, `$`, backtick, newline) before reaching
     * the shell. `lines` and the response payload are capped so a sloppy
     * agent can't OOM the device or the MCP transport.
     */
    private suspend fun readLogcat(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requestedLines = if (args.has("lines")) args.getInt("lines") else 200
        if (requestedLines < 1) {
            throw McpError(-32602, "lines must be >= 1")
        }
        val lines = requestedLines.coerceAtMost(MAX_LOGCAT_LINES)
        val truncatedByLines = requestedLines > MAX_LOGCAT_LINES

        val filter = args.optString("filter", "")
        if (filter.containsShellMetacharacter()) {
            throw McpError(-32602, "filter contains a shell metacharacter (; | & \$ ` newline)")
        }
        val packageName = args.optString("packageName", "").ifEmpty { null }
        val pid = if (args.has("pid")) args.getInt("pid").also {
            if (it < 1) throw McpError(-32602, "pid must be >= 1")
        } else null
        val since = args.optString("since", "").ifEmpty { null }
        if (since != null && since.containsShellMetacharacter()) {
            throw McpError(-32602, "since contains a shell metacharacter")
        }

        val uid: Int? = packageName?.let { resolvePackageUid(it) }

        val cmd = buildString {
            append("logcat -d -t $lines")
            if (uid != null) append(" --uid $uid")
            if (pid != null) append(" --pid $pid")
            if (since != null) append(" -T '$since'")
            if (filter.isNotEmpty()) {
                append(' ')
                append(filter)
            }
        }

        val result = runShizukuOrThrow(cmd, "logcat")

        var output = result.output
        var truncatedByBytes = false
        if (output.length > MAX_LOGCAT_BYTES) {
            output = output.substring(output.length - MAX_LOGCAT_BYTES)
            truncatedByBytes = true
            // Snap to the next line boundary so the first reported line
            // isn't a partial chunk from the middle of an earlier line.
            val nl = output.indexOf('\n')
            if (nl in 0 until 512) output = output.substring(nl + 1)
        }
        val lineCount = when {
            output.isEmpty() -> 0
            output.endsWith('\n') -> output.count { it == '\n' }
            else -> output.count { it == '\n' } + 1
        }
        JSONObject().apply {
            put("ok", true)
            put("lines", lineCount)
            put("truncated", truncatedByLines || truncatedByBytes)
            put("output", output)
            if (uid != null) put("uid", uid)
        }
    }

    /**
     * Resolve a package name to its installed UID via Shizuku-issued
     * `pm list packages -U <name>`. Returns the first exact-match line's
     * uid; throws an McpError with the same Shizuku-install hint as the
     * other privileged tools when Shizuku isn't reachable, and a
     * "package not installed" error when no exact match is found.
     */
    private fun resolvePackageUid(packageName: String): Int {
        if (packageName.isEmpty() ||
            packageName.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '.' && it != '_' }
        ) {
            throw McpError(-32602, "Invalid packageName: '$packageName'")
        }
        // `pm list packages -U <name>` returns exit 0 even when nothing
        // matches (empty output), so don't fail on a non-zero exit here —
        // the no-match case is handled by the parse below.
        val result = runShizukuOrThrow(
            "pm list packages -U $packageName", "pm list", requireSuccess = false,
        )
        // `pm list packages -U <name>` substring-matches the filter, so we
        // re-anchor to an exact "package:<name> " (or tab) prefix. Output
        // lines look like: "package:com.example uid:10123"
        val line = result.output.lineSequence()
            .firstOrNull {
                it.startsWith("package:$packageName ") ||
                    it.startsWith("package:$packageName\t")
            }
            ?: throw McpError(-32603, "Package not installed: $packageName")
        val uidMatch = Regex("uid:(\\d+)").find(line)
            ?: throw McpError(-32603, "Could not parse uid from pm output: $line")
        return uidMatch.groupValues[1].toInt()
    }

    private suspend fun enableWirelessAdb(): JSONObject = withContext(Dispatchers.IO) {
        // `cmd settings put global adb_wifi_enabled 1` is the
        // Android-11+ canonical flag. On older Android (<=10) the
        // setting key didn't exist and adb-over-tcp was driven by the
        // `service.adb.tcp.port` system property; we only target API
        // 26+ so the modern path is enough — older devices fall back
        // to the user toggling Developer Options by hand.
        runShizukuOrThrow("cmd settings put global adb_wifi_enabled 1", "settings put")
        // Read back the bound port so the agent can `adb connect <ip>:<port>` directly.
        val portReadback = try {
            WaylandSocketHelper.execAsShizuku("cmd settings get global adb_wifi_port")
        } catch (_: Exception) {
            null
        }
        val port = portReadback?.output?.trim()?.toIntOrNull()
        // Best-effort LAN IP discovery (excludes loopback/down/IPv6).
        val ip = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
        JSONObject().apply {
            put("enabled", true)
            put("ip", ip ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL)
            put(
                "note",
                if (port != null) "If the host has paired before, run: adb connect ${ip ?: "<phone-ip>"}:$port"
                else "Open Settings → Developer Options → Wireless debugging on the device to read the pairing port if you need to pair a new host.",
            )
        }
    }

    /**
     * Expose the device's adb to the workstation over the MCP tunnel, VPN-proof.
     * Enables classic adb-over-TCP on [DEFAULT_ADB_PORT] (or the requested port)
     * via Shizuku, persists the port so the forward is re-armed across tunnel
     * rebuilds, then binds the reverse forward onto the live tunnel session. The
     * phone-side hop is loopback, so it bypasses any system VpnService.
     */
    private suspend fun exposeAdb(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val port = if (args.has("port")) args.getInt("port") else DEFAULT_ADB_PORT
        if (port < 1024 || port > 65535) {
            throw McpError(-32602, "port must be between 1024 and 65535")
        }
        enableAdbTcpipViaShizuku(port)
        preferencesRepository.setMcpAdbExposedPort(port)
        val bound = mcpTunnelManager.exposeAdbPort(port)
        JSONObject().apply {
            put("method", "tcpip")
            put("adbPort", port)
            put("activated", bound)
            put("connect", "adb connect localhost:$port")
            put(
                "note",
                if (bound) {
                    "adbd exposed on the MCP tunnel endpoint (127.0.0.1:$port). On the " +
                        "workstation run: adb connect localhost:$port — the loopback hop " +
                        "bypasses any system VPN. Verify with `adb devices`."
                } else {
                    "adb enabled and armed, but the MCP tunnel is not up right now — the " +
                        "forward binds automatically once the tunnel connects, then: " +
                        "adb connect localhost:$port."
                },
            )
        }
    }

    /**
     * Reverse of [exposeAdb]: drop the reverse forward, clear the persisted port,
     * and return adbd to USB-only. Best-effort on the Shizuku step so the more
     * important security action (removing the forward) always completes.
     */
    private suspend fun unexposeAdb(): JSONObject = withContext(Dispatchers.IO) {
        val port = preferencesRepository.mcpAdbExposedPort.first() ?: DEFAULT_ADB_PORT
        mcpTunnelManager.unexposeAdbPort(port)
        preferencesRepository.setMcpAdbExposedPort(null)
        disableAdbTcpipViaShizuku()
        JSONObject().apply {
            put("disabled", true)
            put("adbPort", port)
            put("note", "Removed the adb reverse forward and disabled adb-over-TCP on the device.")
        }
    }

    /**
     * Enable classic adb-over-TCP on `127.0.0.1:[port]` (and every interface)
     * via Shizuku: set `service.adb.tcp.port` then bounce adbd to pick it up.
     * Unlike Android-11 wireless debugging this needs no per-host pairing and
     * uses a fixed port. `service.adb.tcp.port` is volatile (cleared on reboot),
     * so the exposure is per-boot and re-armed by re-running the tool.
     *
     * Throws [McpError] if Shizuku is unavailable/unpermitted, or if the prop
     * didn't take / adbd couldn't be restarted (e.g. SELinux denies `ctl.restart`
     * to the shell uid and `stop/start adbd` is also denied — then Shizuku must
     * be in root mode).
     */
    private fun enableAdbTcpipViaShizuku(port: Int) {
        val cmd = "setprop service.adb.tcp.port $port && " +
            "(setprop ctl.restart adbd 2>/dev/null || (stop adbd; start adbd)) && " +
            "echo ADBTCP port=\$(getprop service.adb.tcp.port)"
        // requireSuccess=false: the precise success criterion is the port
        // readback below (adbd restart can print noise yet still succeed).
        val result = runShizukuOrThrow(cmd, "adb-over-TCP", requireSuccess = false)
        val readBack = Regex("port=(\\d+)").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (result.exitCode != 0 || readBack != port) {
            throw McpError(
                -32603,
                "Could not enable adb-over-TCP on :$port (exit=${result.exitCode}, " +
                    "service.adb.tcp.port=$readBack). Output: ${result.output.trim()}. " +
                    "If adbd restart is blocked, ensure Shizuku is running in root mode.",
            )
        }
    }

    /** Disable adb-over-TCP (return adbd to USB-only). Best-effort. */
    private fun disableAdbTcpipViaShizuku() {
        val cmd = "setprop service.adb.tcp.port -1 && " +
            "(setprop ctl.restart adbd 2>/dev/null || (stop adbd; start adbd))"
        runCatching { WaylandSocketHelper.execAsShizuku(cmd) }
    }

    // ---- USB broker tools (Slice 1) --------------------------------------

    /** Short device label for consent prompts: "Evolv DNA 100C (9999:0001)" or just the vid:pid. */
    private fun usbLabel(deviceName: String): String =
        runCatching {
            usbBroker.listDevices().firstOrNull { it.deviceName == deviceName }?.let { d ->
                val name = d.productName ?: d.manufacturerName
                if (name != null) "$name (${d.vidPid})" else d.vidPid
            }
        }.getOrNull() ?: deviceName

    private fun usbDeviceJson(d: sh.haven.core.usb.UsbDeviceInfo): JSONObject = JSONObject().apply {
        put("deviceName", d.deviceName)
        put("vidPid", d.vidPid)
        put("vendorId", d.vendorId)
        put("productId", d.productId)
        put("deviceClass", d.deviceClass)
        put("manufacturerName", d.manufacturerName ?: JSONObject.NULL)
        put("productName", d.productName ?: JSONObject.NULL)
        put("serialNumber", d.serialNumber ?: JSONObject.NULL)
        put("hasPermission", d.hasPermission)
        put("isOpen", d.isOpen)
        put("interfaces", JSONArray().apply {
            d.interfaces.forEach { iface ->
                put(JSONObject().apply {
                    put("id", iface.id)
                    put("interfaceClass", iface.interfaceClass)
                    put("interfaceSubclass", iface.interfaceSubclass)
                    put("interfaceProtocol", iface.interfaceProtocol)
                    put("endpoints", JSONArray().apply {
                        iface.endpoints.forEach { ep ->
                            put(JSONObject().apply {
                                put("address", ep.address)
                                put("direction", ep.direction)
                                put("type", ep.type)
                                put("maxPacketSize", ep.maxPacketSize)
                            })
                        }
                    })
                })
            }
        })
    }

    private suspend fun listUsbDevices(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        JSONObject().apply {
            put("count", devices.size)
            put("devices", JSONArray().apply { devices.forEach { put(usbDeviceJson(it)) } })
        }
    }

    private suspend fun requestUsbPermission(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required (from list_usb_devices)")
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        usbDeviceJson(info)
    }

    private suspend fun usbControlTransfer(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required")
        }
        val requestType = args.getInt("requestType")
        val request = args.getInt("request")
        val value = args.getInt("value")
        val index = args.getInt("index")
        val timeoutMs = args.optInt("timeoutMs", 1000)
        val out = args.optString("dataBase64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        val length = args.optInt("length", out?.size ?: 0)
        val result = try {
            usbBroker.controlTransfer(deviceName, requestType, request, value, index, out, length, timeoutMs)
        } catch (e: Exception) {
            throw McpError(-32603, "controlTransfer failed: ${e.message}")
        }
        JSONObject().apply {
            put("bytesTransferred", result.bytesTransferred)
            if (result.data.isNotEmpty()) {
                put("dataBase64", Base64.encodeToString(result.data, Base64.NO_WRAP))
            }
        }
    }

    private suspend fun usbBulkTransfer(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val deviceName = args.optString("deviceName").ifBlank {
            throw McpError(-32602, "deviceName is required")
        }
        val endpoint = args.getInt("endpoint")
        val timeoutMs = args.optInt("timeoutMs", 1000)
        val out = args.optString("dataBase64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        val length = args.optInt("length", out?.size ?: 0)
        val result = try {
            usbBroker.bulkTransfer(deviceName, endpoint, out, length, timeoutMs)
        } catch (e: Exception) {
            throw McpError(-32603, "bulkTransfer failed: ${e.message}")
        }
        JSONObject().apply {
            put("bytesTransferred", result.bytesTransferred)
            if (result.data.isNotEmpty()) {
                put("dataBase64", Base64.encodeToString(result.data, Base64.NO_WRAP))
            }
        }
    }

    /**
     * Mono DllMap config that routes a HidSharp-based app's USB access to the
     * guest shim: libudev fully (the shim implements all 27 udev functions) and
     * the hidraw libc fileops per-function (other libc calls keep going to real
     * libc). Place beside the assembly declaring the [DllImport]s (HidSharp.dll
     * → HidSharp.dll.config). [shimPath] is the absolute in-guest shim path.
     */
    private fun monoDllMapConfig(shimPath: String): String = """
        <configuration>
          <dllmap dll="libudev.so.0" target="$shimPath"/>
          <dllmap dll="libudev.so.1" target="$shimPath"/>
          <dllmap dll="libc">
            <dllentry dll="$shimPath" name="open"  target="open"/>
            <dllentry dll="$shimPath" name="close" target="close"/>
            <dllentry dll="$shimPath" name="read"  target="read"/>
            <dllentry dll="$shimPath" name="write" target="write"/>
            <dllentry dll="$shimPath" name="ioctl" target="ioctl"/>
            <dllentry dll="$shimPath" name="poll"  target="poll"/>
          </dllmap>
        </configuration>
    """.trimIndent()

    private suspend fun usbAttachToGuest(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Master opt-in gate (Settings → "Expose USB devices to the Linux
        // guest"). Off by default — guest exposure lets any guest app reach the
        // device, so it needs a deliberate user switch on top of per-call consent.
        if (!preferencesRepository.usbGuestExposureEnabled.first()) {
            throw McpError(
                -32603,
                "USB-to-guest is disabled. Enable Settings → \"Expose USB devices to the Linux guest\" " +
                    "(or set the usb_guest_exposure_enabled preference) first. The direct usb_* transfer tools work without it.",
            )
        }
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val deviceName = requested ?: run {
            val devices = usbBroker.listDevices()
            when (devices.size) {
                0 -> throw McpError(-32602, "No USB devices attached.")
                1 -> devices.single().deviceName
                else -> throw McpError(-32602, "Multiple USB devices attached — pass deviceName. Found: ${devices.joinToString { it.deviceName }}")
            }
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        val socketName = usbProxyServer.start(deviceName)
        val probePath = localSessionManager.prootManager.stageHavenUsbArtifacts()
        val shimPath = localSessionManager.prootManager.havenUsbShimGuestPath
        // CDC-ACM serial bridge: for a serial device, point off-the-shelf serial
        // apps at a real guest PTY backed by the brokered device (no kernel
        // cdc_acm, no LD_PRELOAD) via the staged haven-usb-serial helper.
        val serialPath = localSessionManager.prootManager.havenUsbSerialGuestPath
        val cdcData = info.interfaces.firstOrNull { it.interfaceClass == 10 }
        val bulkOutEp = cdcData?.endpoints?.firstOrNull { it.type == "bulk" && it.direction == "out" }?.address
        val bulkInEp = cdcData?.endpoints?.firstOrNull { it.type == "bulk" && it.direction == "in" }?.address
        val isCdcAcm = info.interfaces.any { it.interfaceClass == 2 && it.interfaceSubclass == 2 } &&
            bulkOutEp != null && bulkInEp != null
        JSONObject().apply {
            put("device", usbDeviceJson(info))
            put("socketName", socketName)
            put("socketNamespace", "abstract")
            put("probePath", probePath ?: JSONObject.NULL)
            if (probePath != null) put("probeCommand", probePath)
            put("shimPath", shimPath)
            if (isCdcAcm && bulkOutEp != null && bulkInEp != null) {
                val cmd = "%s 0x%02x 0x%02x".format(serialPath, bulkOutEp, bulkInEp)
                put("cdcAcm", true)
                put("serialBridgeCommand", cmd)
                put(
                    "serialBridgeNote",
                    "CDC-ACM serial device. Run `$cmd` via run_in_proot(background:true); it prints " +
                        "`pts: /dev/pts/N`. Point an unmodified serial app at that path, e.g. " +
                        "`lircd --driver irtoy --device /dev/pts/N` or `mode2 --driver irtoy --device /dev/pts/N`.",
                )
            }
            // For a NATIVE HID app, prepend this so its /dev/hidraw* opens are
            // routed to the brokered device (no real node, no root).
            put("ldPreloadWrapper", "LD_PRELOAD=$shimPath")
            put("hidrawTestCommand", "LD_PRELOAD=$shimPath /usr/local/bin/haven-hidraw-test /dev/hidraw0")
            // For a MONO/.NET HID app (e.g. HidSharp-based), LD_PRELOAD can't
            // interpose P/Invoke — use a DllMap config beside the assembly that
            // declares the [DllImport]s (HidSharp.dll -> HidSharp.dll.config),
            // mapping libudev wholesale + the hidraw libc fileops to the shim.
            put("monoDllMapConfigName", "HidSharp.dll.config")
            put("monoDllMapConfig", monoDllMapConfig(shimPath))
            put("note", "Proxy bound on abstract socket \\0$socketName. Native apps: prepend ldPreloadWrapper (verify with hidrawTestCommand via run_in_proot). Mono apps: write monoDllMapConfig as monoDllMapConfigName next to the assembly's HidSharp.dll, then run the app under mono.")
        }
    }

    private suspend fun detachFromGuest(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Capture before stop() clears it, so we can release the right handle.
        val deviceName = usbProxyServer.proxyDeviceName
        usbProxyServer.stop()
        val keepOpen = args.optBoolean("keepOpen", false)
        if (!keepOpen && deviceName != null) usbBroker.closeDevice(deviceName)
        JSONObject().apply {
            put("stopped", true)
            put("deviceReleased", !keepOpen && deviceName != null)
        }
    }

    private suspend fun startUsbipExport(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val deviceName = requested ?: run {
            val devices = usbBroker.listDevices()
            when (devices.size) {
                0 -> throw McpError(-32602, "No USB devices attached.")
                1 -> devices.single().deviceName
                else -> throw McpError(-32602, "Multiple USB devices attached — pass deviceName. Found: ${devices.joinToString { it.deviceName }}")
            }
        }
        val info = try {
            usbBroker.openDevice(deviceName)
        } catch (e: Exception) {
            throw McpError(-32603, "USB open failed: ${e.message}")
        }
        val loopbackOnly = args.optBoolean("loopbackOnly", false)
        val bind = if (loopbackOnly) "127.0.0.1" else null
        val port = usbIpServer.start(deviceName, bindAddress = bind)
        // busid as the kernel client expects it: "<busnum>-<devnum>" from /dev/bus/usb/BBB/DDD.
        val parts = deviceName.trimEnd('/').split('/')
        val busid = "${parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1}-${parts.lastOrNull()?.toIntOrNull() ?: 1}"
        JSONObject().apply {
            put("device", usbDeviceJson(info))
            put("busid", busid)
            put("port", port)
            put("bind", bind ?: "0.0.0.0")
            put("attachCommand", "sudo modprobe vhci-hcd && sudo usbip attach -r <phone-ip> -b $busid")
            put("detachNote", "Detach on the client with `sudo usbip detach -p 00` (port from `usbip port`).")
            put(
                "note",
                "USB/IP server bound on ${bind ?: "0.0.0.0"}:$port exporting $busid. On a Linux client with the " +
                    "usbip tool + vhci-hcd module loaded, run the attachCommand (replace <phone-ip> with the phone's " +
                    "address). loopbackOnly=$loopbackOnly — when true, reach it through a forwarded port.",
            )
        }
    }

    private suspend fun stopUsbipExport(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // Capture before stop() clears exportedDeviceName, so we release the right handle.
        val deviceName = usbIpServer.exportedDeviceName
        usbIpServer.stop()
        val keepOpen = args.optBoolean("keepOpen", false)
        if (!keepOpen && deviceName != null) usbBroker.closeDevice(deviceName)
        JSONObject().apply {
            put("stopped", true)
            put("deviceReleased", !keepOpen && deviceName != null)
        }
    }

    private suspend fun listUsbExports(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        fun deviceFor(name: String?): Any =
            name?.let { n -> devices.firstOrNull { it.deviceName == n }?.let { usbDeviceJson(it) } } ?: JSONObject.NULL
        val usbipName = usbIpServer.exportedDeviceName
        val usbip = JSONObject().apply {
            put("running", usbIpServer.isRunning)
            put("deviceName", usbipName ?: JSONObject.NULL)
            put("boundPort", usbIpServer.boundPort ?: JSONObject.NULL)
            if (usbipName != null) {
                val parts = usbipName.trimEnd('/').split('/')
                put("busid", "${parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1}-${parts.lastOrNull()?.toIntOrNull() ?: 1}")
            }
            put("clientCount", usbIpServer.clientCount)
            put("clientAttached", usbIpServer.clientCount > 0)
            put("device", deviceFor(usbipName))
        }
        val proxyName = usbProxyServer.proxyDeviceName
        val proxy = JSONObject().apply {
            put("running", usbProxyServer.isRunning)
            put("deviceName", proxyName ?: JSONObject.NULL)
            put("socketName", usbProxyServer.socketName)
            put("device", deviceFor(proxyName))
        }
        JSONObject().apply {
            put("usbip", usbip)
            put("proxy", proxy)
        }
    }

    /**
     * Resolve which open drive's busid a call means: the explicit [argName] arg
     * if given, else the single currently-open session, else an McpError
     * listing the ambiguity (mirrors UsbDriveVmManager.resolveDrive's shape for
     * deviceName). Up to QemuManager.MAX_CONCURRENT_DRIVES can be open at once.
     */
    private fun resolveUsbDriveBusid(args: JSONObject, argName: String = "busid"): String {
        args.optString(argName).takeIf { it.isNotBlank() }?.let { return it }
        val sessions = usbDriveVmManager.sessions.value
        return when (sessions.size) {
            0 -> throw McpError(-32602, "No USB drive is open.")
            1 -> sessions.keys.single()
            else -> throw McpError(
                -32602,
                "Multiple USB drives open — pass $argName. Open: ${sessions.keys.joinToString()}",
            )
        }
    }

    private suspend fun openUsbDrive(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val requested = args.optString("deviceName").takeIf { it.isNotBlank() }
        val writable = args.optBoolean("writable", false)
        val deviceName = try {
            usbDriveVmManager.open(requested, writable)
        } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
            throw McpError(-32603, e.message ?: "Failed to open USB drive")
        }
        JSONObject().apply {
            put("status", "starting")
            put("deviceName", deviceName)
            put("readOnly", !writable)
            put(
                "note",
                "Booting a Linux VM and mounting the drive (slow under TCG; the first run installs packages). " +
                    "Poll list_usb_drives until the matching vms[] entry has phase=ready (profileId set), then browse " +
                    "its mounts with list_directory(profileId, path). A LUKS-encrypted partition mounts locked (see " +
                    "locked) — call unlock_usb_drive_partition (with busid, since more than one drive can be open) " +
                    "with its passphrase. Up to a phone-resource limit of concurrent drives; open_usb_drive errors if " +
                    "already at that limit.",
            )
        }
    }

    private suspend fun unlockUsbDrivePartition(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val busid = resolveUsbDriveBusid(args)
        val devicePath = args.optString("devicePath").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "devicePath is required (see the locked list from list_usb_drives, e.g. /dev/sdb2)")
        val passphrase = args.optString("passphrase").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "passphrase is required")
        try {
            usbDriveVmManager.unlockPartition(busid, devicePath, passphrase)
        } catch (e: sh.haven.app.usb.UsbDriveVmManager.UsbVmException) {
            throw McpError(-32603, e.message ?: "Failed to unlock partition")
        }
        val st = usbDriveVmManager.sessions.value[busid]
        JSONObject().apply {
            put("unlocked", true)
            put("mounts", JSONArray().apply { st?.mounts?.forEach { put(it) } })
            put("locked", JSONArray().apply { st?.locked?.forEach { put(it) } })
        }
    }

    private suspend fun listUsbDrives(): JSONObject = withContext(Dispatchers.IO) {
        val drives = usbDriveVmManager.massStorageDevices()
        val sessions = usbDriveVmManager.sessions.value
        JSONObject().apply {
            put("drives", JSONArray().apply { drives.forEach { put(usbDeviceJson(it)) } })
            put("vms", JSONArray().apply {
                sessions.forEach { (busid, st) ->
                    put(
                        JSONObject().apply {
                            put("busid", busid)
                            put("phase", st.phase.name.lowercase())
                            put("stage", st.stage)
                            put("deviceName", st.deviceName ?: JSONObject.NULL)
                            put("profileId", st.profileId ?: JSONObject.NULL)
                            put("sshPort", st.sshPort)
                            put("mounts", JSONArray().apply { st.mounts.forEach { put(it) } })
                            put("readOnly", st.readOnly)
                            put("locked", JSONArray().apply { st.locked.forEach { put(it) } })
                            put("error", st.error ?: JSONObject.NULL)
                        },
                    )
                }
            })
            // Whether the persistent helper appliance is provisioned (first open
            // provisions it once; subsequent opens are fast). delete_usb_appliance
            // clears it.
            put("applianceProvisioned", usbDriveVmManager.applianceProvisioned)
        }
    }

    private suspend fun closeUsbDrive(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val busid = resolveUsbDriveBusid(args)
        usbDriveVmManager.close(busid)
        JSONObject().apply { put("closed", true) }
    }

    private suspend fun deleteUsbAppliance(): JSONObject = withContext(Dispatchers.IO) {
        val was = usbDriveVmManager.applianceProvisioned
        usbDriveVmManager.deleteAppliance()
        JSONObject().apply {
            put("deleted", was)
            put("note", "USB-helper appliance removed; the next open_usb_drive re-provisions it (one-time).")
        }
    }

    /**
     * The Bridges registry (Phase 0, read-only reflection): aggregate every
     * capability Haven is currently brokering to a sink. Inline for now — no
     * BridgeRegistry abstraction until ≥1 caller needs it beyond this + the UI
     * (docs/design/bridges.md). Generalises listUsbExports.
     */
    private suspend fun listBridges(): JSONObject = withContext(Dispatchers.IO) {
        val devices = usbBroker.listDevices()
        fun usbSource(name: String?): String {
            if (name == null) return "USB device"
            return devices.firstOrNull { it.deviceName == name }?.productName?.let { "USB: $it" } ?: "USB $name"
        }
        fun busidOf(name: String): String {
            val p = name.trimEnd('/').split('/')
            return "${p.getOrNull(p.size - 2)?.toIntOrNull() ?: 1}-${p.lastOrNull()?.toIntOrNull() ?: 1}"
        }
        val bridges = JSONArray()
        fun add(source: String, kind: String, sink: String, transport: String, state: String, detail: JSONObject.() -> Unit = {}) {
            bridges.put(JSONObject().apply {
                put("source", source); put("sourceKind", kind); put("sink", sink)
                put("transport", transport); put("state", state); detail()
            })
        }

        // USB device → remote host (USB/IP)
        if (usbIpServer.isRunning) {
            val n = usbIpServer.exportedDeviceName
            add(usbSource(n), "usb", "remote-host", "usbip", "active") {
                if (n != null) put("busid", busidOf(n))
                put("port", usbIpServer.boundPort ?: JSONObject.NULL)
                put("clients", usbIpServer.clientCount)
            }
        }
        // USB device → Linux guest (haven-usb shim)
        if (usbProxyServer.isRunning) {
            val n = usbProxyServer.proxyDeviceName
            add(usbSource(n), "usb", "linux-guest", "haven-usb-shim", "active") {
                put("socket", usbProxyServer.socketName)
            }
        }
        // USB drive(s) → local VM (#287) — one bridge entry per open drive.
        usbDriveVmManager.sessions.value.values.forEach { vm ->
            add(usbSource(vm.deviceName), "usb", "local-vm", "qemu+usbip", vm.phase.name.lowercase()) {
                if (vm.busid != null) put("busid", vm.busid)
                put("profileId", vm.profileId ?: JSONObject.NULL)
                put("mounts", JSONArray().apply { vm.mounts.forEach { put(it) } })
                if (vm.stage.isNotBlank()) put("stage", vm.stage)
            }
        }
        // Audio out → Linux guest (PulseAudio over loopback)
        val audio = localSessionManager.audioBridge.statusNow()
        if (audio.state == sh.haven.core.local.AudioBridge.State.RUNNING ||
            audio.state == sh.haven.core.local.AudioBridge.State.STARTING
        ) {
            add("Audio out", "audio", "linux-guest", "pulseaudio-tcp", audio.state.name.lowercase()) {
                put("port", audio.port)
            }
        }
        // adb → workstation (reverse tunnel)
        val adbPort = preferencesRepository.mcpAdbExposedPort.first()
        if (adbPort != null) {
            add("adb", "adb", "workstation", "reverse-tunnel", "active") { put("port", adbPort) }
        }

        JSONObject().apply {
            put("bridges", bridges)
            put("count", bridges.length())
            put(
                "note",
                "Every phone capability Haven is currently brokering to a sink (agent / linux-guest / " +
                    "local-vm / remote-host / workstation). Read-only; generalises list_usb_exports. " +
                    "Bridges/Devices manager — docs/design/bridges.md.",
            )
        }
    }

    private suspend fun openLocalShell(args: JSONObject = JSONObject()): JSONObject =
        attachAgentShell(plain = args.optBoolean("plain", false), desktopEnv = null, reuse = true)

    /**
     * Open (or, when [reuse], adopt) the canonical "Local Shell" PRoot session,
     * spin up a headless PTY + agent emulator, and return its session JSON.
     * When [desktopEnv] is non-null the shell joins a running desktop (#285)
     * via DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — pass [reuse]=false in
     * that case so a fresh session actually carries the env (a reused plain
     * shell would lack it).
     */
    private suspend fun attachAgentShell(
        plain: Boolean,
        desktopEnv: Map<String, String>?,
        reuse: Boolean,
    ): JSONObject = withContext(Dispatchers.IO) {
        // Find or seed the canonical "Local Shell" profile, mirroring
        // ConnectionsViewModel.connectLocalTerminal so the agent and
        // the user reach the same place. We deliberately do NOT trigger
        // a rootfs install from here — that's a long-running install
        // step that belongs behind explicit user consent in the UI; if
        // the rootfs is missing the session falls back to /system/bin/sh,
        // same as the existing UI fallback.
        val all = connectionRepository.getAll()
        val existing = all.firstOrNull {
            it.connectionType == "LOCAL" && !it.useAndroidShell
        }
        val profile = existing ?: run {
            val seeded = ConnectionProfile(
                label = "Local Shell",
                host = "localhost",
                username = "",
                port = 0,
                connectionType = "LOCAL",
                useAndroidShell = false,
            )
            connectionRepository.save(seeded)
            connectionRepository.getAll()
                .firstOrNull { it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell }
                ?: seeded
        }
        // Reuse a connected session for this profile if one is already
        // alive — there's no point spinning up a second PRoot when the
        // existing one is exactly what the agent wants. Multiple shells
        // on the same profile is a valid pattern, but the simple "give
        // me a sessionId I can type into" use case is best served by
        // the shortest path.
        val alive = if (reuse) {
            localSessionManager.getSessionsForProfile(profile.id)
                .firstOrNull { it.status == LocalSessionManager.SessionState.Status.CONNECTED }
        } else {
            null
        }
        val sessionId = if (alive != null) {
            alive.sessionId
        } else {
            val sid = localSessionManager.registerSession(
                profile.id, profile.label, profile.useAndroidShell, profile.prootDistroId,
                desktopEnv = desktopEnv,
            )
            try {
                localSessionManager.connectSession(sid)
            } catch (e: Exception) {
                localSessionManager.updateStatus(sid, LocalSessionManager.SessionState.Status.ERROR)
                localSessionManager.removeSession(sid)
                throw McpError(-32603, "Failed to open local shell: ${e.message}")
            }
            sid
        }
        // Spin up the PTY without a UI tab on top so the agent can
        // immediately type into the session. Idempotent — a no-op if
        // the user already has a terminal tab open on this profile.
        // Also tees the data stream into a TerminalEmulator and
        // registers it with terminalSessionRegistry so the structured
        // MCP tools (read_terminal_snapshot, tap_terminal,
        // send_terminal_input) work against headless agent shells —
        // not just sessions the user has visited in the Terminal tab
        // (gap surfaced during v5.32.0-rc2 MCP testing). When a UI
        // tab is later built for the same session, syncSessions skips
        // it because LocalSession is already attached, so the
        // registry's headless emulator stays the source of truth for
        // agent reads.
        if (terminalSessionRegistry.get(sessionId) == null) {
            val scrollbackRows = preferencesRepository.terminalScrollbackRows.first()
            val agentEmulator = org.connectbot.terminal.TerminalEmulatorFactory.create(
                initialRows = 24,
                initialCols = 80,
                onKeyboardInput = { data ->
                    try {
                        localSessionManager.sendInput(sessionId, String(data, Charsets.UTF_8))
                    } catch (e: Exception) {
                        // Session went away mid-write; agents recover via list_sessions.
                    }
                },
                onResize = { dims ->
                    // When a UI tab adopts this headless session, HavenTerminal
                    // sizes the emulator from the Compose canvas — propagate
                    // that to the underlying PTY so `clear`/nano/etc. see the
                    // real viewport. Looking the LocalSession up each call
                    // tolerates the chicken-and-egg between emulator creation
                    // and startHeadlessShell.
                    localSessionManager.getActiveSession(sessionId)?.resize(dims.columns, dims.rows)
                },
                maxScrollbackLines = scrollbackRows,
            )
            // writeInput must happen on the main looper — libvterm's
            // OSC dispatch is wired against the same thread that
            // initialised the native state, and cross-thread writes
            // succeed at the cell level but silently drop OSC callbacks
            // (notably 133 prompt markers and OSC 2 title). Mirroring
            // EmulatorWriteBuffer's main-thread post pattern.
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            localSessionManager.startHeadlessShell(
                sessionId,
                extraOnData = { data, off, len ->
                    val copy = data.copyOfRange(off, off + len)
                    mainHandler.post { agentEmulator.writeInput(copy, 0, copy.size) }
                },
                plain = plain,
            )
            terminalSessionRegistry.register(sessionId, agentEmulator)
        } else {
            // No-op when an existing LocalSession is attached, regardless of
            // the original plain choice — see LocalSessionManager.startHeadlessShell.
            localSessionManager.startHeadlessShell(sessionId, plain = plain)
        }
        // The PTY reader thread starts asynchronously, so wait briefly for the
        // shell's first output before returning — otherwise an immediate
        // send_terminal_input / read_terminal_scrollback races an unattached
        // PTY (input ignored, scrollback empty). `ready` tells the agent
        // whether output is already flowing.
        val ready = localSessionManager.awaitFirstOutput(sessionId)
        JSONObject().apply {
            put("sessionId", sessionId)
            put("profileId", profile.id)
            put("label", profile.label)
            put("reused", alive != null)
            put("plain", plain)
            put("ready", ready)
        }
    }

    /**
     * Open a local PRoot shell whose environment joins a RUNNING desktop (#285)
     * — DISPLAY / WAYLAND_DISPLAY / XDG_RUNTIME_DIR — so the agent can drive the
     * desktop's apps from a shell. Always a fresh session (a reused plain shell
     * would lack the display env). Returns the session JSON plus the resolved
     * display vars for verification.
     */
    private suspend fun openDesktopTerminal(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val env = try {
            localSessionManager.desktopManager.resolveClientEnv(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Failed to resolve desktop env for $deId")
        }
        val base = attachAgentShell(
            plain = args.optBoolean("plain", false),
            desktopEnv = env,
            reuse = false,
        )
        return base.apply {
            put("desktopDeId", de.spec.id)
            put("display", env["DISPLAY"] ?: JSONObject.NULL)
            put("waylandDisplay", env["WAYLAND_DISPLAY"] ?: JSONObject.NULL)
            put("xdgRuntimeDir", env["XDG_RUNTIME_DIR"] ?: JSONObject.NULL)
        }
    }

    private suspend fun setTerminalFontFromUrl(args: JSONObject): JSONObject {
        val urlString = args.optString("url").ifEmpty {
            throw McpError(-32602, "Missing required argument: url")
        }
        // Delegate to the shared installer so the agent and the user
        // surfaces touch identical bytes — same download path, same
        // validation, same path layout, same preference write.
        return when (val r = terminalFontInstaller.installFromUrl(urlString)) {
            is TerminalFontInstaller.Result.Success -> JSONObject().apply {
                put("path", r.path)
                put("bytesDownloaded", r.bytesInstalled)
            }
            is TerminalFontInstaller.Result.Failure -> throw McpError(-32603, r.message)
        }
    }

    private suspend fun convertFile(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val sourceUrl = args.optString("sourceUrl").ifEmpty {
            throw McpError(-32602, "Missing required argument: sourceUrl")
        }
        val container = args.optString("container").ifEmpty {
            throw McpError(-32602, "Missing required argument: container")
        }
        val videoEncoder = args.optString("videoEncoder", "copy").ifEmpty { "copy" }
        val audioEncoder = args.optString("audioEncoder", "copy").ifEmpty { "copy" }
        if (!ffmpegExecutor.isAvailable()) {
            throw McpError(-32603, "ffmpeg is not available in this build")
        }
        val outputDir = File(context.cacheDir, "agent-convert").apply { mkdirs() }
        val outputName = args.optString("outputName", "").ifEmpty {
            "agent-convert-${System.currentTimeMillis()}"
        }
        val outputFile = File(outputDir, "$outputName.$container")
        val cmd = TranscodeCommand(input = sourceUrl, output = outputFile.absolutePath)
            .videoCodec(videoEncoder)
            .audioCodec(audioEncoder)
            .build()
        val started = System.currentTimeMillis()
        val result = ffmpegExecutor.execute(cmd)
        val elapsedMs = System.currentTimeMillis() - started
        if (result.exitCode != 0) {
            // Surface the last few stderr lines so the agent can react
            // to common failures (codec missing, source unreachable).
            val tail = result.stderr.lineSequence().toList().takeLast(8).joinToString("\n")
            throw McpError(-32603, "ffmpeg exit ${result.exitCode}: $tail")
        }
        JSONObject().apply {
            put("outputPath", outputFile.absolutePath)
            put("sizeBytes", outputFile.length())
            put("durationMs", elapsedMs)
            put("container", container)
            put("videoEncoder", videoEncoder)
            put("audioEncoder", audioEncoder)
        }
    }

    private fun listRcloneDirectory(args: JSONObject): JSONObject {
        val remote = args.optString("remote").ifEmpty {
            throw McpError(-32602, "Missing required argument: remote")
        }
        val path = args.optString("path", "")
        return try {
            ensureRcloneReady()
            val entries = rcloneClient.listDirectory(remote, path)
            JSONObject().apply {
                put("remote", remote)
                put("path", path)
                put("count", entries.size)
                put("entries", JSONArray().apply {
                    for (e in entries) {
                        put(JSONObject().apply {
                            put("name", e.name)
                            put("path", e.path)
                            put("isDir", e.isDir)
                            put("size", e.size)
                            put("mimeType", e.mimeType)
                            put("modTime", e.modTime)
                        })
                    }
                })
            }
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list $remote:$path : ${e.message}")
        }
    }

    /** #181: introspect a provider's basic (non-advanced) config fields. */
    private fun listRcloneProviderOptions(args: JSONObject): JSONObject {
        val provider = args.optString("provider").ifEmpty {
            throw McpError(-32602, "Missing required argument: provider")
        }
        return try {
            ensureRcloneReady()
            val info = rcloneClient.listProviders().firstOrNull { it.name == provider }
                ?: throw McpError(-32603, "Unknown rclone provider: $provider")
            JSONObject().apply {
                put("provider", provider)
                put("description", info.description)
                put("options", JSONArray().apply {
                    info.options.filter { !it.advanced }.forEach { o ->
                        put(JSONObject().apply {
                            put("name", o.name)
                            put("help", o.help)
                            put("required", o.required)
                            put("isPassword", o.isPassword)
                            put("default", o.default)
                            put("type", o.type)
                        })
                    }
                })
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "Failed to list provider options for $provider: ${e.message}")
        }
    }

    /**
     * #181: create/replace a credentials-based rclone remote and verify it by
     * listing the root. Mirrors the connection editor's "Configure & verify"
     * (RcloneConfigViewModel) so the non-OAuth flow is MCP-drivable for testing.
     */
    private fun configureRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifEmpty {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        val provider = args.optString("provider").ifEmpty {
            throw McpError(-32602, "Missing required argument: provider")
        }
        val paramsJson = args.optJSONObject("parameters")
            ?: throw McpError(-32602, "Missing required argument: parameters")
        val params = mutableMapOf<String, String>()
        paramsJson.keys().forEach { k -> params[k] = paramsJson.get(k).toString() }
        return try {
            ensureRcloneReady()
            if (name in rcloneClient.listRemotes()) rcloneClient.deleteRemote(name)
            val state = rcloneClient.createRemote(name, provider, params)
            if (state.error.isNotEmpty()) {
                throw McpError(-32603, "rclone config/create error: ${state.error}")
            }
            state.option?.let {
                throw McpError(-32603, "rclone still needs option '${it.name}' — add it to parameters")
            }
            val entries = rcloneClient.listDirectory(name, "")
            JSONObject().apply {
                put("created", true)
                put("verified", true)
                put("remoteName", name)
                put("provider", provider)
                put("entryCount", entries.size)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "configure_rclone_remote failed: ${e.message}")
        }
    }

    private suspend fun deleteRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifBlank {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        return try {
            ensureRcloneReady()
            val existed = name in rcloneClient.listRemotes()
            if (existed) rcloneClient.deleteRemote(name)
            // Symmetric cleanup: drop any RCLONE profile pointing at this remote so
            // deletion can't leave the inverse ghost (a profile with no remote) (#269).
            val removedProfiles = mutableListOf<String>()
            for (p in connectionRepository.getAll()) {
                if (p.connectionType == "RCLONE" && p.rcloneRemoteName == name) {
                    sessionManagerRegistry.disconnectProfile(p.id)
                    connectionRepository.delete(p.id)
                    removedProfiles += p.id
                }
            }
            JSONObject().apply {
                put("deleted", existed)
                put("remoteName", name)
                put("removedProfiles", JSONArray().apply { removedProfiles.forEach { put(it) } })
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "delete_rclone_remote failed: ${e.message}")
        }
    }

    /**
     * Headless rclone.conf import. Mirrors
     * [sh.haven.feature.connections.RcloneConfigViewModel.importRemotes]
     * (non-interactive create + orphan rollback, #269) for the MCP path.
     */
    private suspend fun importRcloneConfig(args: JSONObject): JSONObject {
        val configText = args.optString("configText").ifBlank {
            throw McpError(-32602, "Missing required argument: configText")
        }
        val onlyNames = args.optJSONArray("names")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet() }
        val parsed = when (val r = RcloneConfigParser.parse(configText)) {
            is RcloneConfigParseResult.Encrypted ->
                throw McpError(-32602, "That rclone.conf is password-encrypted; decrypt it first.")
            is RcloneConfigParseResult.Success -> r.remotes
        }
        return try {
            ensureRcloneReady()
            val created = JSONArray()
            val skipped = JSONArray()
            val failed = JSONObject()
            val existing = runCatching { rcloneClient.listRemotes().toSet() }.getOrDefault(emptySet())
            for (remote in parsed) {
                if (onlyNames != null && remote.name !in onlyNames) continue
                if (remote.type.isBlank() || remote.name in existing) { skipped.put(remote.name); continue }
                try {
                    val state = rcloneClient.createRemote(
                        remote.name, remote.type, remote.params,
                        noObscure = true, nonInteractive = true,
                    )
                    if (state.error.isNotEmpty()) error(state.error)
                    connectionRepository.save(
                        ConnectionProfile(
                            label = remote.name,
                            host = "",
                            username = "",
                            connectionType = "RCLONE",
                            rcloneRemoteName = remote.name,
                            rcloneProvider = remote.type,
                        ),
                    )
                    created.put(remote.name)
                } catch (e: Exception) {
                    runCatching { rcloneClient.deleteRemote(remote.name) } // roll back any ghost
                    failed.put(remote.name, e.message ?: "failed")
                }
            }
            JSONObject().apply {
                put("created", created)
                put("skipped", skipped)
                put("failed", failed)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "import_rclone_config failed: ${e.message}")
        }
    }

    private fun updateRcloneRemote(args: JSONObject): JSONObject {
        val name = args.optString("remoteName").ifBlank {
            throw McpError(-32602, "Missing required argument: remoteName")
        }
        val paramsJson = args.optJSONObject("parameters")
            ?: throw McpError(-32602, "Missing required argument: parameters")
        val params = mutableMapOf<String, String>()
        paramsJson.keys().forEach { k -> params[k] = paramsJson.get(k).toString() }
        return try {
            ensureRcloneReady()
            if (name !in rcloneClient.listRemotes()) {
                throw McpError(-32602, "No rclone remote named '$name' (see list_rclone_remotes).")
            }
            rcloneClient.updateRemote(name, params)
            JSONObject().apply {
                put("updated", true)
                put("remoteName", name)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError(-32603, "update_rclone_remote failed: ${e.message}")
        }
    }

    // --- Workspace tools -------------------------------------------------

    private suspend fun listWorkspaces(): JSONObject {
        val profiles = workspaceRepository.observeAll().first()
        val arr = JSONArray()
        for (profile in profiles) {
            val items = workspaceRepository.observeItems(profile.id).first()
            val kindCounts = items.groupingBy { it.kind.name }.eachCount()
            arr.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("itemCount", items.size)
                put("kinds", JSONObject().apply {
                    for ((kind, n) in kindCounts) put(kind, n)
                })
                put("createdAt", profile.createdAt)
                put("updatedAt", profile.updatedAt)
            })
        }
        return JSONObject().apply {
            put("count", profiles.size)
            put("workspaces", arr)
        }
    }

    /**
     * Dispatch [workspaceLauncher] for the supplied workspace id.
     * The launcher walks items via [agentUiCommandBus] tryEmit calls,
     * which are non-blocking — the actual session/tab opening happens
     * in the corresponding feature ViewModels' collectors. So this
     * returns once every item has been dispatched (fast), with the
     * launcher state machine settled at Completed/Cancelled/Failed.
     * Live progress also shows up in the Connections-screen banner
     * via the same StateFlow the user's tap drives.
     */
    private suspend fun composeWorkspace(args: JSONObject): JSONObject {
        val workspaceId = args.optString("workspaceId").ifEmpty {
            throw McpError(-32602, "Missing required argument: workspaceId")
        }
        val workspace = workspaceRepository.getWorkspace(workspaceId)
            ?: throw McpError(-32602, "No workspace with id $workspaceId")
        workspaceLauncher.launch(workspaceId)
        val finalState = workspaceLauncher.state.value
        return JSONObject().apply {
            put("workspaceId", workspaceId)
            put("workspaceName", workspace.profile.name)
            put("itemCount", workspace.items.size)
            put("outcome", finalState::class.simpleName ?: "Unknown")
        }
    }

    // ---- #149 tunnel + routing tools --------------------------------------

    private suspend fun listTunnels(): JSONObject {
        val tunnels = tunnelConfigRepository.getAll()
        val arr = JSONArray()
        for (t in tunnels) {
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("label", t.label)
                put("type", t.type)
                put("createdAt", t.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", tunnels.size)
            put("tunnels", arr)
        }
    }

    private suspend fun listSshKeys(): JSONObject {
        val keys = sshKeyRepository.getAll()
        val arr = JSONArray()
        for (k in keys) {
            arr.put(JSONObject().apply {
                put("id", k.id)
                put("label", k.label)
                put("keyType", k.keyType)
                put("publicKeyOpenSsh", k.publicKeyOpenSsh)
                put("fingerprintSha256", k.fingerprintSha256)
                put("isEncrypted", k.isEncrypted)
                put("biometricProtected", k.biometricProtected)
                put("enabledForAuth", k.enabledForAuth)
                // verify-required (PIN) lives in the SK blob's flags; decrypt
                // non-biometric SK keys to surface it (null for non-SK or when
                // unreadable). Biometric keys are skipped to avoid a prompt.
                if (k.keyType.startsWith("sk-")) {
                    val vr = if (!k.biometricProtected) {
                        runCatching {
                            sshKeyRepository.getDecryptedKeyBytes(k.id)?.let {
                                sh.haven.core.fido.SkKeyData.deserialize(it).flags.toInt() and 0x04 != 0
                            }
                        }.getOrNull()
                    } else null
                    put("verifyRequired", vr ?: JSONObject.NULL)
                }
                put("hasCertificate", k.certificateBytes != null)
                put("caConfigId", k.caConfigId ?: JSONObject.NULL)
                put("certIssuedAt", k.certIssuedAt ?: JSONObject.NULL)
                put("createdAt", k.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", keys.size)
            put("keys", arr)
        }
    }

    private suspend fun setSshKeyOption(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val keyId = args.optString("keyId").ifBlank { throw IllegalArgumentException("keyId required") }
        val key = sshKeyRepository.getById(keyId)
            ?: throw IllegalArgumentException("SSH key not found: $keyId")
        val changed = JSONArray()

        if (args.has("enabledForAuth")) {
            sshKeyRepository.setEnabledForAuth(keyId, args.getBoolean("enabledForAuth"))
            changed.put("enabledForAuth=${args.getBoolean("enabledForAuth")}")
        }
        if (args.has("verifyRequired")) {
            if (!key.keyType.startsWith("sk-")) {
                throw IllegalArgumentException("verifyRequired only applies to FIDO2/SK keys")
            }
            val required = args.getBoolean("verifyRequired")
            val plain = sshKeyRepository.getDecryptedKeyBytes(keyId)
                ?: throw IllegalArgumentException(
                    "Couldn't read the key (biometric-protected keys can't be changed over MCP)",
                )
            val sk = sh.haven.core.fido.SkKeyData.deserialize(plain)
            val newFlags: Byte = if (required) 0x05 else 0x01
            if (sk.flags != newFlags) {
                sshKeyRepository.save(
                    key.copy(
                        privateKeyBytes =
                            sh.haven.core.fido.SkKeyData.serialize(sk.copy(flags = newFlags)),
                    ),
                )
            }
            changed.put("verifyRequired=$required")
        }

        val updated = sshKeyRepository.getById(keyId)!!
        val vr = if (updated.keyType.startsWith("sk-") && !updated.biometricProtected) {
            runCatching {
                sshKeyRepository.getDecryptedKeyBytes(keyId)?.let {
                    sh.haven.core.fido.SkKeyData.deserialize(it).flags.toInt() and 0x04 != 0
                }
            }.getOrNull()
        } else {
            null
        }
        JSONObject().apply {
            put("id", keyId)
            put("label", updated.label)
            put("keyType", updated.keyType)
            put("enabledForAuth", updated.enabledForAuth)
            put("verifyRequired", vr ?: JSONObject.NULL)
            put("changed", changed)
        }
    }

    // --- Toolbar snippets (#244) -------------------------------------------

    private fun snippetPlacement(layout: ToolbarLayout, item: ToolbarItem.Custom): String = when {
        layout.row1.contains(item) -> "row1"
        layout.row2.contains(item) -> "row2"
        else -> "library"
    }

    private fun snippetsArray(layout: ToolbarLayout, library: List<ToolbarItem.Custom>): JSONArray {
        val arr = JSONArray()
        SnippetOps.allSnippets(layout, library).forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("label", s.label)
                    put("send", s.send)
                    put("placement", snippetPlacement(layout, s))
                },
            )
        }
        return arr
    }

    private suspend fun listSnippets(): JSONObject = withContext(Dispatchers.IO) {
        val layout = preferencesRepository.toolbarLayout.first()
        val library = preferencesRepository.snippetLibrary.first()
        JSONObject().apply { put("snippets", snippetsArray(layout, library)) }
    }

    private suspend fun setSnippet(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val label = args.optString("label").trim()
        if (label.isEmpty()) throw IllegalArgumentException("label required")
        val layout = preferencesRepository.toolbarLayout.first()
        val library = preferencesRepository.snippetLibrary.first()
        val all = SnippetOps.allSnippets(layout, library)
        val sendArg = if (args.has("send")) args.getString("send") else null

        if (args.optBoolean("delete", false)) {
            val target = all.firstOrNull { it.label == label && (sendArg == null || it.send == sendArg) }
                ?: throw IllegalArgumentException("No snippet with label \"$label\"")
            val (newLayout, newLib) = SnippetOps.delete(layout, library, target)
            preferencesRepository.setToolbarLayout(newLayout)
            preferencesRepository.setSnippetLibrary(newLib)
            return@withContext JSONObject().apply {
                put("action", "deleted")
                put("label", label)
                put("snippets", snippetsArray(newLayout, newLib))
            }
        }

        val existing = all.firstOrNull { it.label == label }
        val send = sendArg
            ?: existing?.send
            ?: throw IllegalArgumentException("send required for new snippet \"$label\"")
        val item = ToolbarItem.Custom(label, send)
        // Strip any prior entry with this label so a send/placement change moves cleanly.
        val baseLayout = ToolbarLayout(
            layout.rows.map { r -> r.filterNot { it is ToolbarItem.Custom && it.label == label } },
        )
        val baseLib = library.filterNot { it.label == label }
        val rowIndex = when (args.optString("placement", "library").lowercase()) {
            "row1", "r1", "1", "top" -> 0
            "row2", "r2", "2", "bottom" -> 1
            else -> null // library / off / scissors
        }
        val (newLayout, newLib) = SnippetOps.place(baseLayout, baseLib, item, rowIndex)
        preferencesRepository.setToolbarLayout(newLayout)
        preferencesRepository.setSnippetLibrary(newLib)
        JSONObject().apply {
            put("action", if (existing == null) "added" else "updated")
            put("label", label)
            put("placement", snippetPlacement(newLayout, item))
            put("snippets", snippetsArray(newLayout, newLib))
        }
    }

    private suspend fun importSshKey(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val privateKey = args.optString("privateKey").ifBlank {
            throw IllegalArgumentException("privateKey required")
        }
        val label = args.optString("label").ifBlank {
            throw IllegalArgumentException("label required")
        }
        val passphrase = args.optString("passphrase").takeIf { it.isNotEmpty() }

        val fileBytes = privateKey.toByteArray(Charsets.UTF_8)
        val imported = try {
            sh.haven.core.ssh.SshKeyImporter.import(fileBytes, passphrase)
        } catch (e: sh.haven.core.ssh.SshKeyImporter.EncryptedKeyException) {
            throw IllegalArgumentException(
                "Key is passphrase-encrypted — pass `passphrase` to import.",
            )
        } catch (e: sh.haven.core.ssh.SshKeyImporter.SkKeyDetectedException) {
            throw IllegalArgumentException(
                "FIDO2 SK keys must be imported via the Keys → Discover from security key " +
                    "flow on-device — they aren't pasteable text.",
            )
        }

        val entity = sh.haven.core.data.db.entities.SshKey(
            label = label,
            keyType = imported.keyType,
            privateKeyBytes = imported.privateKeyBytes,
            publicKeyOpenSsh = imported.publicKeyOpenSsh,
            fingerprintSha256 = imported.fingerprintSha256,
            isEncrypted = imported.isEncrypted,
        )
        sshKeyRepository.save(entity)

        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("keyType", entity.keyType)
            put("publicKeyOpenSsh", entity.publicKeyOpenSsh)
            put("fingerprintSha256", entity.fingerprintSha256)
            put("isEncrypted", entity.isEncrypted)
        }
    }

    private suspend fun deleteSshKey(args: JSONObject): JSONObject {
        val id = args.optString("sshKeyId").ifBlank {
            throw IllegalArgumentException("sshKeyId required")
        }
        val existing = sshKeyRepository.getById(id)
            ?: throw IllegalArgumentException("No SSH key with id $id")
        sshKeyRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun listTotpSecrets(): JSONObject {
        val secrets = totpSecretRepository.getAll()
        val arr = JSONArray()
        for (s in secrets) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("label", s.label)
                put("issuer", s.issuer ?: JSONObject.NULL)
                put("accountName", s.accountName ?: JSONObject.NULL)
                put("algorithm", s.algorithm)
                put("digits", s.digits)
                put("periodSeconds", s.periodSeconds)
                put("createdAt", s.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", secrets.size)
            put("secrets", arr)
        }
    }

    private suspend fun listAgeIdentities(): JSONObject {
        val ids = ageIdentityRepository.getAll()
        val arr = JSONArray()
        for (i in ids) {
            arr.put(JSONObject().apply {
                put("id", i.id)
                put("label", i.label)
                put("recipient", i.recipient)
                put("createdAt", i.createdAt)
            })
        }
        return JSONObject().apply {
            put("count", ids.size)
            put("identities", arr)
        }
    }

    private suspend fun createAgeIdentity(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val label = args.optString("label").takeIf { it.isNotBlank() } ?: "age identity"
        val row = ageIdentityRepository.create(label)
        JSONObject().apply {
            put("id", row.id)
            put("label", row.label)
            put("recipient", row.recipient)
        }
    }

    private suspend fun encryptFileViaAgent(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank { throw IllegalArgumentException("profileId required") }
        val path = args.optString("path").ifBlank { throw IllegalArgumentException("path required") }
        val recipientsArg = args.optJSONArray("recipients")
        val recipients = if (recipientsArg != null && recipientsArg.length() > 0) {
            (0 until recipientsArg.length()).map { recipientsArg.getString(it) }
        } else {
            ageIdentityRepository.getAll().map { it.recipient }
        }
        if (recipients.isEmpty()) {
            throw IllegalArgumentException(
                "No recipients given and no stored age identities — create one with create_age_identity or pass recipients.",
            )
        }
        val delivered = agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.EncryptFile(profileId, path, recipients),
        )
        return JSONObject().apply {
            put("dispatched", delivered)
            put("path", path)
            put("output", "$path.age")
            put("recipientCount", recipients.size)
            put("note", "Encrypting in the file browser; confirm with list_directory once it completes.")
        }
    }

    private suspend fun decryptFileViaAgent(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank { throw IllegalArgumentException("profileId required") }
        val path = args.optString("path").ifBlank { throw IllegalArgumentException("path required") }
        val delivered = agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.DecryptFile(profileId, path),
        )
        return JSONObject().apply {
            put("dispatched", delivered)
            put("path", path)
            put("output", path.removeSuffix(".age"))
            put("note", "Decrypting in the file browser; confirm with list_directory once it completes.")
        }
    }

    private suspend fun createTotpSecret(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val otpauth = args.optString("otpauth").takeIf { it.isNotBlank() }
        val rawSecret = args.optString("secret").takeIf { it.isNotBlank() }
        val input = otpauth ?: rawSecret
            ?: throw IllegalArgumentException("Pass either `otpauth` or `secret`")
        val parsed = sh.haven.core.security.OtpAuthUri.parse(input)
            ?: throw IllegalArgumentException("Not a valid otpauth:// URI or base32 secret")
        val labelOverride = args.optString("label").takeIf { it.isNotBlank() }
        val entity = sh.haven.core.data.db.entities.TotpSecret(
            label = labelOverride ?: parsed.label,
            secret = parsed.secret,
            issuer = parsed.issuer,
            accountName = parsed.accountName,
            algorithm = parsed.algorithm.name,
            digits = parsed.digits,
            periodSeconds = parsed.periodSeconds,
        )
        totpSecretRepository.save(entity)
        JSONObject().apply {
            put("id", entity.id)
            put("label", entity.label)
            put("issuer", entity.issuer ?: JSONObject.NULL)
            put("accountName", entity.accountName ?: JSONObject.NULL)
            put("algorithm", entity.algorithm)
            put("digits", entity.digits)
            put("periodSeconds", entity.periodSeconds)
            put("authMethodToken", "TOTP:${entity.id}")
        }
    }

    private suspend fun deleteTotpSecret(args: JSONObject): JSONObject {
        val id = args.optString("totpSecretId").ifBlank {
            throw IllegalArgumentException("totpSecretId required")
        }
        val existing = totpSecretRepository.getById(id)
            ?: throw IllegalArgumentException("No TOTP secret with id $id")
        totpSecretRepository.delete(id)
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun listLiveTunnels(): JSONObject {
        val live = tunnelManager.liveSnapshot()
        val arr = JSONArray()
        for (entry in live) {
            arr.put(JSONObject().apply {
                put("configId", entry.configId)
                put("dependentCount", entry.dependentProfileIds.size)
                put("dependentProfileIds", JSONArray().apply {
                    entry.dependentProfileIds.forEach { put(it) }
                })
            })
        }
        return JSONObject().apply {
            put("count", live.size)
            put("liveTunnels", arr)
        }
    }

    private suspend fun createTunnel(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val typeRaw = args.optString("type").uppercase()
        val type = when (typeRaw) {
            "WIREGUARD" -> TunnelConfigType.WIREGUARD
            "TAILSCALE" -> TunnelConfigType.TAILSCALE
            "CLOUDFLARE_ACCESS" -> TunnelConfigType.CLOUDFLARE_ACCESS
            else -> throw IllegalArgumentException(
                "type must be WIREGUARD, TAILSCALE, or CLOUDFLARE_ACCESS"
            )
        }
        val configBytes: ByteArray = when (type) {
            TunnelConfigType.WIREGUARD -> {
                val wgQuick = args.optString("configText")
                if (wgQuick.isBlank()) {
                    throw IllegalArgumentException("configText required for WIREGUARD type")
                }
                wgQuick.toByteArray()
            }
            TunnelConfigType.CLOUDFLARE_ACCESS -> {
                // MCP path supports unprotected Cloudflare Tunnel routes
                // (hostname only) as well as Access-protected ones
                // (additionally requiring a JWT). The in-app WebView
                // sign-in flow is interactive and can't be driven by an
                // agent — agents wanting Access auth must already hold
                // a JWT (e.g. from `cloudflared access token --app <host>`).
                val hostname = args.optString("accessHostname")
                if (hostname.isBlank()) {
                    throw IllegalArgumentException(
                        "accessHostname required for CLOUDFLARE_ACCESS type"
                    )
                }
                val jwt = args.optString("accessJwt")
                val teamDomain = args.optString("accessTeamDomain")
                val jumpDestination = args.optString("accessJumpDestination")
                val explicitExpiry = args.optLong("accessExpiresAt", 0L)
                val derivedExpiry = if (explicitExpiry > 0) {
                    explicitExpiry
                } else if (jwt.isNotBlank()) {
                    sh.haven.core.security.JwtPayload.parse(jwt)
                        ?.expiresAtSeconds ?: 0L
                } else 0L
                sh.haven.core.tunnel.CloudflareAccessConfigBlob(
                    hostname = hostname,
                    teamDomain = teamDomain,
                    jwt = jwt,
                    jwtExpiresAt = derivedExpiry,
                    jumpDestination = jumpDestination,
                ).encode()
            }
            TunnelConfigType.TAILSCALE -> {
                val authKey = args.optString("tailscaleAuthKey")
                if (authKey.isBlank()) {
                    throw IllegalArgumentException("tailscaleAuthKey required for TAILSCALE type")
                }
                val controlUrl = args.optString("tailscaleControlUrl")
                sh.haven.core.tunnel.TailscaleConfigBlob(authKey, controlUrl).encode()
            }
        }
        val config = TunnelConfig(
            label = label,
            type = type.name,
            configText = configBytes,
        )
        tunnelConfigRepository.save(config)
        return JSONObject().apply {
            put("id", config.id)
            put("label", config.label)
            put("type", config.type)
            put("createdAt", config.createdAt)
        }
    }

    private suspend fun deleteTunnel(args: JSONObject): JSONObject {
        val id = args.optString("tunnelConfigId").ifBlank {
            throw IllegalArgumentException("tunnelConfigId required")
        }
        val existing = tunnelConfigRepository.getById(id)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        tunnelConfigRepository.delete(id)
        // If a live tunnel depends on this config, ask TunnelManager to
        // release every dependent — they fall through to direct on next
        // connect. (The current TunnelManager API is profileId-keyed so
        // the simplest correct thing is to release each dependent.)
        tunnelManager.liveSnapshot()
            .firstOrNull { it.configId == id }
            ?.dependentProfileIds
            ?.forEach { tunnelManager.release(it) }
        return JSONObject().apply {
            put("deleted", true)
            put("id", id)
            put("label", existing.label)
        }
    }

    private suspend fun setProfileRouting(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val clear = args.optBoolean("clear", false)
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        val proxyType = if (args.has("proxyType")) args.optString("proxyType") else null
        val proxyHost = if (args.has("proxyHost")) args.optString("proxyHost") else null
        val proxyPort = if (args.has("proxyPort")) args.optInt("proxyPort", 1080) else null
        val proxyUser = if (args.has("proxyUser")) args.optString("proxyUser") else null
        val proxyPassword = if (args.has("proxyPassword")) args.optString("proxyPassword") else null

        val updated = when {
            clear -> profile.copy(
                tunnelConfigId = null,
                proxyType = null,
                proxyHost = null,
                proxyUser = null,
                proxyPassword = null,
            )
            !tunnelConfigId.isNullOrBlank() -> {
                tunnelConfigRepository.getById(tunnelConfigId)
                    ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
                profile.copy(
                    tunnelConfigId = tunnelConfigId,
                    proxyType = null,
                    proxyHost = null,
                    proxyUser = null,
                    proxyPassword = null,
                )
            }
            !proxyType.isNullOrBlank() -> {
                if (proxyType !in setOf("SOCKS5", "SOCKS4", "HTTP")) {
                    throw IllegalArgumentException("proxyType must be SOCKS5, SOCKS4, or HTTP")
                }
                if (proxyHost.isNullOrBlank()) {
                    throw IllegalArgumentException("proxyHost required when proxyType is set")
                }
                profile.copy(
                    tunnelConfigId = null,
                    proxyType = proxyType,
                    proxyHost = proxyHost,
                    proxyPort = proxyPort ?: profile.proxyPort,
                    // #227: only touch creds when the caller passed them, so
                    // re-routing without creds keeps any previously set ones.
                    proxyUser = if (proxyUser != null) proxyUser.ifBlank { null } else profile.proxyUser,
                    proxyPassword = if (proxyType == "SOCKS4") null
                        else if (proxyPassword != null) proxyPassword.ifBlank { null } else profile.proxyPassword,
                )
            }
            else -> throw IllegalArgumentException(
                "set_profile_routing needs one of: tunnelConfigId, proxyType+proxyHost, or clear=true"
            )
        }
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("profileId", updated.id)
            put("tunnelConfigId", updated.tunnelConfigId ?: JSONObject.NULL)
            put("proxyType", updated.proxyType ?: JSONObject.NULL)
            put("proxyHost", updated.proxyHost ?: JSONObject.NULL)
            put("proxyPort", updated.proxyPort)
            // Never echo the password; just report whether proxy auth is set.
            put("proxyUser", updated.proxyUser ?: JSONObject.NULL)
            put("proxyAuth", !updated.proxyUser.isNullOrEmpty())
        }
    }

    /**
     * Edit fields on an existing profile (load → copy changed → save). Only
     * keys present in [args] change; everything else is preserved. Password
     * maps to the transport-specific column based on the profile's type, and
     * is never echoed back. Referenced ids (keyId, *SshProfileId) are validated.
     */
    private suspend fun updateConnection(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")

        fun str(key: String, current: String?): String? =
            if (args.has(key)) args.optString(key).ifBlank { null } else current
        fun bool(key: String, current: Boolean): Boolean =
            if (args.has(key)) args.optBoolean(key) else current

        // Validate referenced ids before saving so a typo fails here, not at connect.
        val newKeyId = str("keyId", existing.keyId)
        if (args.has("keyId") && !newKeyId.isNullOrBlank() && sshKeyRepository.getById(newKeyId) == null) {
            throw IllegalArgumentException("key $newKeyId not found")
        }
        for (refKey in listOf("vncSshProfileId", "rdpSshProfileId", "smbSshProfileId", "spiceSshProfileId")) {
            if (args.has(refKey)) {
                val ref = args.optString(refKey).ifBlank { null }
                if (ref != null && connectionRepository.getById(ref) == null) {
                    throw IllegalArgumentException("$refKey $ref not found")
                }
            }
        }

        // Password maps to the transport-specific column.
        val newPassword: (current: String?) -> String? = { current ->
            if (args.has("password")) args.optString("password").ifBlank { null } else current
        }

        // `port` maps to the base port AND the transport-specific port column —
        // VNC/RDP/SMB dial vncPort/rdpPort/smbPort, not the base port (mirrors
        // create_connection, which sets both).
        val portChanged = args.has("port")
        val newPort = if (portChanged) args.optInt("port", existing.port) else existing.port

        val updated = existing.copy(
            label = if (args.has("label")) args.optString("label").ifBlank { existing.label } else existing.label,
            host = if (args.has("host")) args.optString("host").ifBlank { existing.host } else existing.host,
            port = newPort,
            vncPort = if (portChanged && existing.connectionType == "VNC") newPort else existing.vncPort,
            rdpPort = if (portChanged && existing.connectionType == "RDP") newPort else existing.rdpPort,
            smbPort = if (portChanged && existing.connectionType == "SMB") newPort else existing.smbPort,
            spicePort = if (portChanged && existing.connectionType == "SPICE") newPort else existing.spicePort,
            username = if (args.has("username")) args.optString("username") else existing.username,
            sshPassword = if (existing.connectionType == "SSH") newPassword(existing.sshPassword) else existing.sshPassword,
            vncPassword = if (existing.connectionType == "VNC") newPassword(existing.vncPassword) else existing.vncPassword,
            rdpPassword = if (existing.connectionType == "RDP") newPassword(existing.rdpPassword) else existing.rdpPassword,
            smbPassword = if (existing.connectionType == "SMB") newPassword(existing.smbPassword) else existing.smbPassword,
            spicePassword = if (existing.connectionType == "SPICE") newPassword(existing.spicePassword) else existing.spicePassword,
            keyId = newKeyId,
            ignoreSavedKeys = bool("ignoreSavedKeys", existing.ignoreSavedKeys),
            useMosh = bool("useMosh", existing.useMosh),
            vncSshForward = bool("vncSshForward", existing.vncSshForward),
            vncSshProfileId = str("vncSshProfileId", existing.vncSshProfileId),
            rdpSshForward = bool("rdpSshForward", existing.rdpSshForward),
            rdpSshProfileId = str("rdpSshProfileId", existing.rdpSshProfileId),
            smbSshForward = bool("smbSshForward", existing.smbSshForward),
            smbSshProfileId = str("smbSshProfileId", existing.smbSshProfileId),
            spiceSshForward = bool("spiceSshForward", existing.spiceSshForward),
            spiceSshProfileId = str("spiceSshProfileId", existing.spiceSshProfileId),
        )
        connectionRepository.save(updated)
        return profileToJson(updated)
    }

    private suspend fun createConnection(args: JSONObject): JSONObject {
        val label = args.optString("label").ifBlank { throw IllegalArgumentException("label required") }
        val type = args.optString("connectionType").uppercase().ifBlank {
            throw IllegalArgumentException("connectionType required")
        }
        if (type !in setOf("SSH", "SMB", "VNC", "RDP", "SPICE", "EMAIL")) {
            throw IllegalArgumentException("connectionType must be SSH, SMB, VNC, RDP, SPICE, or EMAIL (use the UI for LOCAL / RCLONE / RETICULUM)")
        }
        // EMAIL's host is the optional tunnel-ingress/bastion (SPA/knock target),
        // not the mail server — so it may be blank; every other type requires it.
        val host = args.optString("host")
        if (type != "EMAIL" && host.isBlank()) throw IllegalArgumentException("host required")
        val username = args.optString("username")
        val password = args.optString("password")
        val tunnelConfigId = if (args.has("tunnelConfigId")) args.optString("tunnelConfigId") else null
        if (!tunnelConfigId.isNullOrBlank()) {
            tunnelConfigRepository.getById(tunnelConfigId)
                ?: throw IllegalArgumentException("tunnel config $tunnelConfigId not found")
        }

        val defaultPort = when (type) {
            "SSH" -> 22
            "SMB" -> 445
            "VNC" -> 5900
            "RDP" -> 3389
            "SPICE" -> 5900
            "EMAIL" -> 0
            else -> 22
        }
        val port = if (args.has("port")) args.optInt("port", defaultPort) else defaultPort

        val tunnelOnly = args.optBoolean("tunnelOnly", false)
        if (tunnelOnly && type != "SSH") {
            throw IllegalArgumentException("tunnelOnly is only meaningful for SSH connections")
        }

        // #121: password-only — never offer saved keys. Belongs on the SSH
        // profile (for VNC-over-SSH set it on the referenced SSH profile, since
        // the VNC profile itself does no SSH key auth).
        val ignoreSavedKeys = args.optBoolean("ignoreSavedKeys", false)

        // #166: optional ordered auth-method list. Each element is a token:
        // "PASSWORD", "KEY" (any saved key), "KEY:<keyId>", or
        // "KEYBOARD_INTERACTIVE". Serialised newline-per-token to match
        // ConnectionProfile.authMethods. Empty/absent = derive from keyId.
        val authMethodsArr = args.optJSONArray("authMethods")
        val authMethodsText = if (authMethodsArr != null) {
            (0 until authMethodsArr.length())
                .joinToString("\n") { authMethodsArr.getString(it).trim() }
        } else ""

        // Validate the knock sequence eagerly so an invalid string is
        // rejected at create time rather than silently saved and only
        // surfaced when the user (or agent) tries to connect. Empty/blank
        // means "no knock configured" — equivalent to omitting the field.
        val rawKnock = args.optString("portKnockSequence")
        val knockDelay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val parsedKnock = sh.haven.core.knock.KnockSequence.parse(rawKnock, knockDelay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val knockSequence = if (parsedKnock != null) rawKnock.trim() else null

        val profile = when (type) {
            "SSH" -> ConnectionProfile(
                label = label,
                host = host,
                port = port,
                username = username,
                sshPassword = password.ifBlank { null },
                connectionType = "SSH",
                useMosh = args.optBoolean("useMosh", false),
                keyId = args.optString("keyId").ifBlank { null },
                authMethods = authMethodsText,
                ignoreSavedKeys = ignoreSavedKeys,
                sessionManager = parseSessionManager(args.optString("sessionManager")),
                tunnelConfigId = tunnelConfigId,
                tunnelOnly = tunnelOnly,
                portKnockSequence = knockSequence,
                portKnockDelayMs = knockDelay,
            )
            "SMB" -> {
                val share = args.optString("smbShare").ifBlank {
                    throw IllegalArgumentException("smbShare required for SMB")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = username,
                    connectionType = "SMB",
                    smbPort = port,
                    smbShare = share,
                    smbDomain = args.optString("smbDomain").ifBlank { null },
                    smbPassword = password.ifBlank { null },
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "VNC" -> {
                // VNC-over-SSH-tunnel: forward the VNC connection through a
                // saved SSH profile (vncSshProfileId). Validate the referenced
                // profile exists so a typo fails at create time, not connect.
                val vncSshForward = args.optBoolean("vncSshForward", false)
                val vncSshProfileId = args.optString("vncSshProfileId").ifBlank { null }
                if (vncSshForward && vncSshProfileId != null &&
                    connectionRepository.getById(vncSshProfileId) == null
                ) {
                    throw IllegalArgumentException("vncSshProfileId $vncSshProfileId not found")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = "",
                    connectionType = "VNC",
                    vncPort = port,
                    vncUsername = args.optString("vncUsername").ifBlank { null },
                    vncPassword = args.optString("vncPassword").ifBlank { null },
                    vncSshForward = vncSshForward,
                    vncSshProfileId = vncSshProfileId,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "RDP" -> {
                val rdpUser = args.optString("rdpUsername").ifBlank {
                    throw IllegalArgumentException("rdpUsername required for RDP")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = rdpUser,
                    connectionType = "RDP",
                    rdpPort = port,
                    rdpUsername = rdpUser,
                    rdpPassword = args.optString("rdpPassword").ifBlank { null },
                    rdpDomain = args.optString("rdpDomain").ifBlank { null },
                    rdpSshForward = false,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "SPICE" -> {
                // SPICE-over-SSH-tunnel: forward through a saved SSH profile.
                val spiceSshForward = args.optBoolean("spiceSshForward", false)
                val spiceSshProfileId = args.optString("spiceSshProfileId").ifBlank { null }
                if (spiceSshForward && spiceSshProfileId != null &&
                    connectionRepository.getById(spiceSshProfileId) == null
                ) {
                    throw IllegalArgumentException("spiceSshProfileId $spiceSshProfileId not found")
                }
                ConnectionProfile(
                    label = label,
                    host = host,
                    port = port,
                    username = "",
                    connectionType = "SPICE",
                    spicePort = port,
                    spicePassword = args.optString("spicePassword").ifBlank { null },
                    spiceSshForward = spiceSshForward,
                    spiceSshProfileId = spiceSshProfileId,
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            "EMAIL" -> {
                val provider = args.optString("emailProvider").ifBlank { "imap" }.lowercase()
                val emailUser = username.ifBlank {
                    throw IllegalArgumentException("username (email address) required for EMAIL")
                }
                val server = args.optString("emailServer").ifBlank { null }
                if (provider == "imap" && server == null) {
                    throw IllegalArgumentException("emailServer required for an IMAP EMAIL profile")
                }
                ConnectionProfile(
                    label = label,
                    host = host, // optional tunnel ingress that SPA/knock guards
                    port = 0,
                    username = emailUser,
                    connectionType = "EMAIL",
                    emailProvider = provider,
                    emailUsername = emailUser,
                    emailPassword = password.ifBlank { null },
                    emailMailboxPassword = args.optString("emailMailboxPassword").ifBlank { null },
                    emailServer = server,
                    emailSmtpServer = args.optString("emailSmtpServer").ifBlank { null },
                    emailPort = if (args.has("emailPort")) args.optInt("emailPort", 993) else 993,
                    emailSmtpPort = if (args.has("emailSmtpPort")) args.optInt("emailSmtpPort", 465) else 465,
                    emailTls = args.optBoolean("emailTls", true),
                    tunnelConfigId = tunnelConfigId,
                    portKnockSequence = knockSequence,
                    portKnockDelayMs = knockDelay,
                )
            }
            else -> error("unreachable")
        }
        connectionRepository.save(profile)
        return JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("connectionType", profile.connectionType)
            put("host", profile.host)
            put("port", profile.port)
            put("tunnelConfigId", profile.tunnelConfigId ?: JSONObject.NULL)
            if (profile.tunnelOnly) put("tunnelOnly", true)
            if (knockSequence != null) {
                put("portKnockSequence", knockSequence)
                put("portKnockDelayMs", knockDelay)
            }
        }
    }

    private suspend fun setPortKnock(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val rawSeq = args.optString("portKnockSequence")
        val delay = if (args.has("portKnockDelayMs")) {
            args.optInt("portKnockDelayMs", existing.portKnockDelayMs)
        } else {
            existing.portKnockDelayMs
        }
        val parsed = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
        val newSeq = if (parsed != null) rawSeq.trim() else null
        val updated = existing.copy(
            portKnockSequence = newSeq,
            portKnockDelayMs = delay,
        )
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("id", updated.id)
            put("label", updated.label)
            put("portKnockSequence", newSeq ?: JSONObject.NULL)
            put("portKnockDelayMs", delay)
            put("steps", parsed?.steps?.size ?: 0)
        }
    }

    private suspend fun testPortKnock(args: JSONObject): JSONObject {
        val host = args.optString("host").ifBlank {
            throw IllegalArgumentException("host required")
        }
        val rawSeq = args.optString("portKnockSequence").ifBlank {
            throw IllegalArgumentException("portKnockSequence required")
        }
        val delay = if (args.has("portKnockDelayMs")) args.optInt("portKnockDelayMs", 100) else 100
        val seq = sh.haven.core.knock.KnockSequence.parse(rawSeq, delay)
            .getOrElse { throw IllegalArgumentException("portKnockSequence: ${it.message}") }
            ?: throw IllegalArgumentException("portKnockSequence parsed to empty")
        val result = portKnocker.knock(host, seq)
        return JSONObject().apply {
            put("host", host)
            put("sequence", seq.format())
            put("ok", result.ok)
            put("sentSteps", result.sentSteps)
            put("totalSteps", seq.steps.size)
            put("durationMs", result.totalDurationMs)
            put("error", result.error?.message ?: JSONObject.NULL)
        }
    }

    private suspend fun setSpa(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        val rawKey = args.optString("spaKey")
        val accessSpec = if (args.has("spaAccessSpec")) args.optString("spaAccessSpec") else existing.spaAccessSpec
        // Validate the resulting config (unless disabling).
        val parsed = sh.haven.core.spa.SpaConfig.parse(
            key = rawKey,
            keyIsBase64 = args.optBoolean("spaKeyBase64", existing.spaKeyBase64),
            hmacKey = if (args.has("spaHmacKey")) args.optString("spaHmacKey") else existing.spaHmacKey,
            hmacKeyIsBase64 = args.optBoolean("spaHmacKeyBase64", existing.spaHmacKeyBase64),
            accessSpec = accessSpec,
            allowMode = if (args.has("allowMode")) args.optString("allowMode") else existing.spaAllowMode,
            explicitIp = if (args.has("explicitIp")) args.optString("explicitIp") else existing.spaExplicitIp,
            spaPort = if (args.has("spaPort")) args.optInt("spaPort", existing.spaPort) else existing.spaPort,
        ).getOrElse { throw IllegalArgumentException("spa: ${it.message}") }
        val updated = if (parsed == null) {
            existing.copy(spaKey = null, spaAccessSpec = null)
        } else {
            existing.copy(
                spaKey = rawKey.trim(),
                spaKeyBase64 = parsed.keyIsBase64,
                spaHmacKey = parsed.hmacKey,
                spaHmacKeyBase64 = parsed.hmacKeyIsBase64,
                spaAccessSpec = parsed.accessSpec,
                spaAllowMode = parsed.allowMode.name,
                spaExplicitIp = parsed.explicitIp,
                spaPort = parsed.spaPort,
            )
        }
        connectionRepository.save(updated)
        return JSONObject().apply {
            put("id", updated.id)
            put("label", updated.label)
            put("spaEnabled", parsed != null)
            put("spaAccessSpec", updated.spaAccessSpec ?: JSONObject.NULL)
            put("spaAllowMode", updated.spaAllowMode)
            put("spaPort", updated.spaPort)
            put("hasHmacKey", !updated.spaHmacKey.isNullOrEmpty())
        }
    }

    private suspend fun testSpa(args: JSONObject): JSONObject {
        val host = args.optString("host").ifBlank {
            throw IllegalArgumentException("host required")
        }
        val config = sh.haven.core.spa.SpaConfig.parse(
            key = args.optString("spaKey"),
            keyIsBase64 = args.optBoolean("spaKeyBase64", false),
            hmacKey = args.optString("spaHmacKey").ifBlank { null },
            hmacKeyIsBase64 = args.optBoolean("spaHmacKeyBase64", false),
            accessSpec = args.optString("spaAccessSpec"),
            allowMode = args.optString("allowMode").ifBlank { "SOURCE" },
            explicitIp = args.optString("explicitIp").ifBlank { null },
            spaPort = if (args.has("spaPort")) args.optInt("spaPort", 62201) else 62201,
        ).getOrElse { throw IllegalArgumentException("spa: ${it.message}") }
            ?: throw IllegalArgumentException("spaKey and spaAccessSpec are required")
        val result = spaSender.send(host, config)
        return JSONObject().apply {
            put("host", host)
            put("accessSpec", config.accessSpec)
            put("spaPort", config.spaPort)
            put("ok", result.ok)
            put("bytesSent", result.packetLen)
            put("durationMs", result.totalDurationMs)
            put("error", result.error?.message ?: JSONObject.NULL)
        }
    }

    private suspend fun getConnectionLog(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val limit = args.optInt("limit", 10).coerceIn(1, 100)
        // ConnectionLogRepository observes a Flow — take the latest snapshot
        // and slice it down to the requested limit. Newest first.
        val entries = connectionLogRepository.observeForProfile(profileId, limit)
            .first()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("timestamp", e.timestamp)
                put("status", e.status.name)
                put("durationMs", e.durationMs)
                put("details", e.details ?: JSONObject.NULL)
                put("verboseLog", e.verboseLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("profileId", profileId)
            put("count", entries.size)
            put("entries", arr)
        }
    }

    private suspend fun deleteConnection(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val existing = connectionRepository.getById(profileId)
            ?: return JSONObject().apply { put("deleted", false); put("reason", "not found") }
        // Tear down any live session for this profile (incl. its VNC/RDP
        // Desktop tab via the lease), then drop tunnel refcount, then delete.
        sessionManagerRegistry.disconnectProfile(profileId)
        sshSessionManager.teardownTunnelDependent(profileId)
        tunnelManager.release(profileId)
        connectionRepository.delete(profileId)
        return JSONObject().apply {
            put("deleted", true)
            put("id", profileId)
            put("label", existing.label)
        }
    }

    private suspend fun connectProfile(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifBlank {
            throw IllegalArgumentException("profileId required")
        }
        val sessionName = args.optString("sessionName").ifBlank { null }
        val profile = connectionRepository.getById(profileId)
            ?: throw IllegalArgumentException("profile $profileId not found")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.ConnectProfile(profileId, sessionName)
        )
        return JSONObject().apply {
            put("profileId", profileId)
            put("label", profile.label)
            put("dispatched", true)
            put(
                "note",
                "Connect dispatched asynchronously through the same path a UI tap uses. Poll list_sessions for status."
            )
        }
    }

    private fun getPendingAuthPrompt(): JSONObject {
        val p = pendingAuthPromptHolder.prompt.value
            ?: return JSONObject().put("pending", false)
        return JSONObject().apply {
            put("pending", true)
            put("profileId", p.profileId)
            put("label", p.label)
            put("requiresPassphrase", p.requiresPassphrase)
        }
    }

    private suspend fun answerAuthPrompt(args: JSONObject): JSONObject {
        val pending = pendingAuthPromptHolder.prompt.value
            ?: return JSONObject().apply {
                put("answered", false)
                put("note", "No auth prompt is pending. Start a connect with connect_profile first.")
            }
        val password = args.optString("password")
        if (password.isEmpty()) throw IllegalArgumentException("password required")
        val username = args.optString("username").ifBlank { null }
        val remember = args.optBoolean("rememberPassword", false)
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.AnswerAuthPrompt(password, username, remember)
        )
        return JSONObject().apply {
            put("answered", true)
            put("profileId", pending.profileId)
            put(
                "note",
                "Answer dispatched. get_pending_auth_prompt clears on success and re-surfaces on a wrong secret; confirm with list_sessions."
            )
        }
    }

    /**
     * Validate + normalise a session-manager name for create_profile. Stored
     * uppercase to match SessionManager.valueOf in ConnectionsViewModel. Blank
     * => null (no session manager). Throws on an unknown name.
     */
    private fun parseSessionManager(raw: String?): String? {
        val v = raw?.trim()?.uppercase()?.ifBlank { null } ?: return null
        val valid = setOf("TMUX", "ZELLIJ", "SCREEN", "BYOBU")
        if (v !in valid) {
            throw IllegalArgumentException("sessionManager must be one of ${valid.joinToString(", ")} (or omit for none)")
        }
        return v
    }

    private fun getPendingSessionPicker(): JSONObject {
        val s = sessionSelectionHolder.selection.value
            ?: return JSONObject().put("pending", false)
        return JSONObject().apply {
            put("pending", true)
            put("profileId", s.profileId)
            put("sessionId", s.sessionId)
            put("sessionManager", s.managerLabel)
            put("sessionNames", JSONArray().apply { s.sessionNames.forEach { put(it) } })
            put("previousSessionNames", JSONArray().apply { s.previousSessionNames.forEach { put(it) } })
            put("suggestedNewName", s.suggestedNewName)
        }
    }

    private suspend fun answerSessionPicker(args: JSONObject): JSONObject {
        val pending = sessionSelectionHolder.selection.value
            ?: return JSONObject().apply {
                put("answered", false)
                put("note", "No session picker is pending. connect_profile (without sessionName) to a session-manager profile that has existing sessions surfaces it.")
            }
        val createNew = args.optBoolean("createNew", false)
        val name = args.optString("sessionName").ifBlank { null }
        val chosen: String? = when {
            createNew -> null // null sessionName => onSessionSelected creates a new uniquely-named session
            name != null -> {
                if (name !in pending.sessionNames) {
                    throw McpError(-32602, "sessionName \"$name\" is not in the picker (${pending.sessionNames}); pass createNew=true to make a new one")
                }
                name
            }
            else -> throw McpError(-32602, "pass sessionName=<existing> (from get_pending_session_picker) or createNew=true")
        }
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.AnswerSessionSelection(pending.sessionId, chosen)
        )
        return JSONObject().apply {
            put("answered", true)
            put("sessionId", pending.sessionId)
            put("attached", chosen ?: "(new session)")
            put("note", "Attach dispatched via onSessionSelected. Confirm with list_sessions / read_terminal_snapshot.")
        }
    }

    companion object {
        /**
         * Port the MCP HTTP server binds on the device and the matching
         * `-R` forward Haven installs on the SSH session. Kept in sync
         * with `feature/connections`'s MCP_REVERSE_TUNNEL_PORT (`#161`).
         */
        internal const val MCP_REVERSE_TUNNEL_PORT = 8730

        /**
         * Default loopback port for classic `adb tcpip` exposed via
         * [exposeAdb]. 5555 is the conventional adb-over-TCP port and needs no
         * per-host pairing (unlike Android-11 wireless debugging).
         */
        private const val DEFAULT_ADB_PORT = 5555

        /**
         * Upper bound on a [presentMedia] payload (8 MiB). present_media
         * holds the bytes in a cache file and decodes them on the UI side;
         * anything larger should go through serve_file/play_file instead
         * of inline base64 over JSON-RPC.
         */
        private const val MAX_PRESENT_BYTES = 8 * 1024 * 1024

        /**
         * Best-effort fallback regex matching the trailing
         * prompt glyph of common interactive shells and REPLs
         * at end-of-line. Covers `$` (bash/sh/zsh user), `#`
         * (root), `%` (csh), `>` (cmd.exe-style and traditional
         * minimal), and `❯` (Claude Code, fish, starship).
         * Multiline + ANSI-stripped before matching in
         * [TerminalInputQueue]; the watcher's tail-only scope
         * (last 512 chars of stripped scrollback) keeps the
         * character class from matching `$` redirects or `>`
         * in earlier output.
         *
         * Callers should supply their own `promptPattern` when
         * they know what's running on the other end — e.g.
         * `\[y/N\]\s*$` for an install script,
         * `Password:\s*$` for a password prompt. The default
         * exists for the convenience of the "drive an
         * interactive shell or REPL" case where the exact
         * prompt isn't pinned in advance.
         */
        internal const val DEFAULT_PROMPT_PATTERN = "[\\\$#%>❯]\\s*\$"

        /**
         * Default key bytes sent after the queued text. `\r` (CR) is
         * what TTYs in cooked mode translate to NL, and what programs
         * in raw mode (Claude Code, vim, less, fzf, readline-based
         * shells) read as the Enter key. Callers can override:
         * - `\n` (LF) for line-buffered programs reading stdin
         *   directly without a tty.
         * - `""` (empty) to leave the text in the input buffer
         *   without submitting — e.g. pre-filling a prompt the user
         *   will edit further before pressing Enter themselves.
         */
        internal const val DEFAULT_SUBMIT_KEY = "\r"

        /**
         * Pause between sending the text body and a trailing submit key in
         * send_terminal_input, so the submit lands in its own read() rather than
         * folding into the text burst (a raw-mode REPL's input batching otherwise
         * stages the text without submitting — verified on Claude Code). #14
         */
        internal const val SUBMIT_SETTLE_MS = 150L

        /**
         * Default cap on the post-send snapshot send_to_agent returns. An
         * unbounded full-scrollback snapshot is a large response that, over a
         * tunnel, can outlast the caller's MCP timeout — the message delivers
         * but the call reads back as a timeout (pilz hit this over WireGuard,
         * while a manual send_terminal_input with the same write path but a
         * smaller snapshot succeeded). A screenful is what a sender wants to
         * see anyway; an explicit maxLines still wins.
         */
        internal const val SEND_TO_AGENT_SNAPSHOT_LINES = 50
    }

    // --- proot / desktop environment tool implementations (issue #162 Phase 3b) ---

    private val prootManager get() = localSessionManager.prootManager

    /** Background scope for fire-and-forget installs. Survives the tool
     * call return so the agent can poll progress; SupervisorJob keeps
     * one failing install from cancelling siblings. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live background `run_in_proot` jobs, keyed by jobId. */
    private class ProotJob {
        val output = StringBuilder()
        @Volatile var running = true
        @Volatile var exitCode: Int? = null
    }
    private val prootJobs = java.util.concurrent.ConcurrentHashMap<String, ProotJob>()

    /**
     * A synchronous [runInProot] that hasn't finished within this window is
     * auto-converted to a background job and a pollable jobId returned,
     * rather than blocking the single HTTP response until the MCP client's
     * request timeout fires (`-32001`). Held well under the common 60s
     * client default so `apt-get install` / large-output commands hand back
     * a job instead of timing out (#279 / #240).
     */
    private val autoBackgroundMs = 30_000L

    /**
     * Start [command] in the active proot as a streaming background job and
     * return its jobId. Output is appended line-by-line into the job buffer;
     * [ProotJob.running] flips false and [ProotJob.exitCode] is set when it
     * exits. Shared by run_in_proot's explicit `background:true` path and the
     * auto-background fallback below.
     */
    private fun startProotJob(command: String): String {
        val jobId = java.util.UUID.randomUUID().toString()
        val job = ProotJob()
        prootJobs[jobId] = job
        backgroundScope.launch {
            try {
                val proc = prootManager.startCommandInProot(command)
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(job.output) { job.output.append(line).append('\n') }
                }
                job.exitCode = proc.waitFor()
            } catch (e: Exception) {
                synchronized(job.output) { job.output.append("\n[run_in_proot error] ${e.message}\n") }
                job.exitCode = -1
            } finally {
                job.running = false
            }
        }
        return jobId
    }

    private fun distroToJson(
        d: sh.haven.core.local.proot.Distro,
        installed: Boolean,
        active: Boolean,
        bytesOnDisk: Long,
    ): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("label", d.label)
        put("family", d.family.name)
        put("installed", installed)
        put("active", active)
        put("sizeEstimateMb", d.sizeEstimateMb)
        put("bytesOnDisk", bytesOnDisk)
        put("baselinePackages", JSONArray().apply {
            d.baselinePackages.forEach { put(it) }
        })
        put("postExtractHookIds", JSONArray().apply {
            d.postExtractHooks.forEach { put(it.id) }
        })
        put("rootfsArches", JSONArray().apply {
            d.rootfsSources.keys.forEach { put(it.abi) }
        })
        // Which desktops are installed on THIS distro's rootfs — read
        // per-distro so the cross-distro picture is visible without
        // switching the active distro. Empty for non-installed distros.
        put("installedDesktops", JSONArray().apply {
            prootManager.installedDesktopsFor(d.id).forEach { put(it.spec.id) }
        })
    }

    private fun rootfsBytesOnDisk(distroId: String): Long {
        // Honor ProotManager's legacy-path fallback (alpine-3.21 → alpine).
        val dir = prootManager.rootfsDirFor(distroId)
        if (!dir.exists()) return 0L
        // Cheap: just the top-level directory size — accurate to within
        // the order of magnitude. Recursive du would block several
        // seconds for a 100-MB rootfs.
        return runCatching {
            java.nio.file.Files.walk(dir.toPath()).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }
                    .mapToLong {
                        runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L)
                    }
                    .sum()
            }
        }.getOrDefault(0L)
    }

    private fun desktopToJson(
        de: sh.haven.core.local.ProotManager.DesktopEnvironment,
        activeFamily: sh.haven.core.local.proot.PackageFamily,
        installed: Boolean,
        running: Boolean,
    ): JSONObject = JSONObject().apply {
        put("id", de.spec.id)
        put("label", de.label)
        put("sizeEstimateMb", de.spec.sizeEstimateMb)
        put("installed", installed)
        put("running", running)
        put("isNative", de.isNative)
        put("isWayland", de.isWayland)
        put("verifyBinary", de.spec.verifyBinary)
        put("compatibility", de.spec.compatibilityOn(activeFamily).name)
        de.spec.compatibilityNoteOn(activeFamily)?.let { put("compatibilityNote", it) }
        val pkgs = de.spec.packagesPerFamily[activeFamily]
        if (pkgs != null) {
            put("packages", JSONArray().apply { pkgs.forEach { put(it) } })
        } else {
            put("packages", JSONObject.NULL)
        }
        put("packageFamilies", JSONArray().apply {
            de.spec.packagesPerFamily.keys.forEach { put(it.name) }
        })
    }

    private fun osSetupStateToJson(
        state: sh.haven.core.local.ProotManager.SetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.SetupState.NotInstalled -> {
                put("phase", "NotInstalled")
            }
            is sh.haven.core.local.ProotManager.SetupState.Downloading -> {
                put("phase", "Downloading")
                put("percent", state.progress)
            }
            is sh.haven.core.local.ProotManager.SetupState.Extracting -> {
                put("phase", "Extracting")
            }
            is sh.haven.core.local.ProotManager.SetupState.Initializing -> {
                put("phase", "Initializing")
                put("step", state.step)
            }
            is sh.haven.core.local.ProotManager.SetupState.Ready -> {
                put("phase", "Ready")
            }
            is sh.haven.core.local.ProotManager.SetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun desktopSetupStateToJson(
        state: sh.haven.core.local.ProotManager.DesktopSetupState,
    ): JSONObject = JSONObject().apply {
        when (state) {
            is sh.haven.core.local.ProotManager.DesktopSetupState.Idle ->
                put("phase", "Idle")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Installing -> {
                put("phase", "Installing")
                put("step", state.step)
            }
            is sh.haven.core.local.ProotManager.DesktopSetupState.Complete ->
                put("phase", "Complete")
            is sh.haven.core.local.ProotManager.DesktopSetupState.Error -> {
                put("phase", "Error")
                put("errorPhase", state.phase.name)
                put("errorMessage", state.message)
                if (state.logTail.isNotEmpty()) put("errorTail", state.logTail)
            }
        }
    }

    private fun installLogEventToJson(
        e: sh.haven.core.data.db.entities.ProotInstallLog,
    ): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("timestamp", e.timestamp)
        put("distroId", e.distroId)
        put("phase", e.phase)
        e.deId?.let { put("deId", it) }
        if (e.exit != null) put("exit", e.exit) else put("exit", JSONObject.NULL)
        put("ok", e.ok)
        e.message?.let { put("message", it) }
        e.logTail?.let { put("logTail", it) }
    }

    private suspend fun inspectProot(args: JSONObject): JSONObject {
        val eventLimit = args.optInt("eventLimit", 50).coerceIn(1, 500)
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys

        val distrosJson = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        val desktopsJson = JSONArray().apply {
            sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { active.family in it.spec.packagesPerFamily.keys }
                .forEach { de ->
                    put(
                        desktopToJson(
                            de = de,
                            activeFamily = active.family,
                            installed = de in installedDes,
                            running = de in runningDes,
                        ),
                    )
                }
        }
        val events = prootInstallLogRepository.querySince(
            distroId = null,
            sinceMs = 0L,
            limit = eventLimit,
        )
        val eventsJson = JSONArray().apply {
            // querySince returns ASC; reverse so the most recent event is at index 0
            events.asReversed().forEach { put(installLogEventToJson(it)) }
        }

        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("distros", distrosJson)
            put("desktopEnvironments", desktopsJson)
            put("osSetupState", osSetupStateToJson(pm.state.value))
            put("desktopSetupState", desktopSetupStateToJson(pm.desktopState.value))
            put("recentInstallLog", eventsJson)
        }
    }

    private fun setActiveDistroTool(args: JSONObject): JSONObject {
        val pm = prootManager
        val id = args.optString("distroId").trim()
        if (id.isEmpty()) throw McpError(-32602, "distroId is required")
        val distro = sh.haven.core.local.proot.DistroCatalog.lookup(id)
            ?: throw McpError(-32602, "Unknown distro id '$id'. See list_distros for valid ids.")
        if (distro !in pm.installedDistros) {
            throw McpError(-32603, "Distro '$id' (${distro.label}) is not installed — use install_distro first.")
        }
        pm.setActiveDistroId(id)
        return JSONObject().apply {
            put("activeDistroId", pm.activeDistroId)
            put("label", distro.label)
            put("family", distro.family.name)
            put("installedDesktops", JSONArray(pm.installedDesktopsFor(id).map { it.id }))
        }
    }

    private fun listDistros(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installed = pm.installedDistros.map { it.id }.toSet()
        val arr = JSONArray().apply {
            sh.haven.core.local.proot.DistroCatalog.all.forEach { d ->
                put(
                    distroToJson(
                        d = d,
                        installed = d.id in installed,
                        active = d.id == active.id,
                        bytesOnDisk = if (d.id in installed) rootfsBytesOnDisk(d.id) else 0L,
                    ),
                )
            }
        }
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("count", arr.length())
            put("distros", arr)
        }
    }

    private fun listDesktopEnvironments(): JSONObject {
        val pm = prootManager
        val active = pm.activeDistro
        val installedDes = pm.installedDesktops
        val runningDes = localSessionManager.desktopManager.desktops.value.keys
        val arr = JSONArray().apply {
            sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { active.family in it.spec.packagesPerFamily.keys }
                .forEach { de ->
                    put(
                        desktopToJson(
                            de = de,
                            activeFamily = active.family,
                            installed = de in installedDes,
                            running = de in runningDes,
                        ),
                    )
                }
        }
        return JSONObject().apply {
            put("activeDistroId", active.id)
            put("activeFamily", active.family.name)
            put("count", arr.length())
            put("desktopEnvironments", arr)
        }
    }

    private suspend fun installDistro(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        val wasInstalled = target.id in pm.installedDistros.map { it.id }
        if (pm.activeDistroId != target.id) {
            pm.setActiveDistroId(target.id)
        }
        // Launch the install on the IO dispatcher and return immediately.
        // Callers poll via inspect_proot.osSetupState — matches the UI
        // pattern of "tap Install, watch the progress block update".
        backgroundScope.launch {
            try {
                pm.installRootfs()
            } catch (_: Exception) {
                // The catch in installRootfs already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("alreadyInstalled", wasInstalled)
            put("status", "started")
            put("poll", "inspect_proot.osSetupState")
        }
    }

    private suspend fun deleteDistroTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "distroId is required")
        val target = sh.haven.core.local.proot.DistroCatalog.lookup(distroId)
            ?: throw McpError(-32602, "Unknown distroId: $distroId")
        val pm = prootManager
        // Stop any DEs on this distro before yanking the rootfs.
        if (pm.activeDistroId == target.id) {
            localSessionManager.desktopManager.stopAll()
        }
        pm.deleteDistro(target.id)
        // Drop install-log rows for this distro too — the rootfs is gone,
        // its events would just be confusing on a re-install.
        prootInstallLogRepository.deleteForDistro(target.id)
        return JSONObject().apply {
            put("distroId", target.id)
            put("label", target.label)
            put("status", "deleted")
        }
    }

    private fun importDistroTool(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "id is required")
        val source = args.optString("source").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "source is required")
        val family = runCatching {
            sh.haven.core.local.proot.PackageFamily.valueOf(args.optString("family").uppercase())
        }.getOrNull() ?: throw McpError(-32602, "family must be one of APK, APT, PACMAN, XBPS, NIX")
        val label = args.optString("label").ifBlank { id }
        // Auto-detect compression from the source extension if not given.
        val format = when (val f = args.optString("format")) {
            "TAR_GZ" -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
            "TAR_XZ" -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
            "TAR_ZSTD" -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
            "" -> when {
                source.endsWith(".tar.xz") || source.endsWith(".txz") -> sh.haven.core.local.proot.RootfsFormat.TAR_XZ
                source.endsWith(".tar.zst") || source.endsWith(".tar.zstd") -> sh.haven.core.local.proot.RootfsFormat.TAR_ZSTD
                else -> sh.haven.core.local.proot.RootfsFormat.TAR_GZ
            }
            else -> throw McpError(-32602, "Unknown format: $f")
        }
        val strip = if (args.has("stripComponents")) args.optInt("stripComponents", 0) else 0
        val sha = args.optString("sha256").takeIf { it.isNotEmpty() }
        if (sh.haven.core.local.proot.DistroCatalog.isBuiltin(id)) {
            throw McpError(-32602, "'$id' is a built-in distro id — pick another")
        }
        backgroundScope.launch {
            try {
                prootManager.importRootfs(id, label, family, source, format, strip, sha)
            } catch (_: Exception) {
                // importRootfs's catch already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("id", id)
            put("label", label)
            put("family", family.name)
            put("status", "started")
            put("poll", "inspect_proot.osSetupState")
        }
    }

    private fun getCustomBindsTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() } ?: prootManager.activeDistroId
        val binds = prootManager.customBinds(distroId)
        return JSONObject().apply {
            put("distroId", distroId)
            put("binds", JSONArray().apply {
                binds.forEach {
                    put(JSONObject().apply {
                        put("host", it.host)
                        put("guest", it.guest)
                        put("spec", it.spec())
                    })
                }
            })
        }
    }

    private fun setCustomBindsTool(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() } ?: prootManager.activeDistroId
        val arr = args.optJSONArray("binds") ?: throw McpError(-32602, "binds (array) is required")
        val binds = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim()
            if (host.isEmpty()) return@mapNotNull null
            sh.haven.core.local.proot.CustomBind(host, o.optString("guest").trim())
        }
        prootManager.setCustomBinds(distroId, binds)
        return JSONObject().apply {
            put("distroId", distroId)
            put("count", binds.size)
            put("binds", JSONArray().apply { binds.forEach { put(it.spec()) } })
            put("note", "Takes effect on the next session/command for this distro.")
        }
    }

    private suspend fun installDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val vncPassword = args.optString("vncPassword", "")
        val pm = prootManager
        val active = pm.activeDistro
        if (active.family !in de.spec.packagesPerFamily.keys) {
            throw McpError(
                -32602,
                "${de.label} has no package list for ${active.family} — supported: " +
                    "${de.spec.packagesPerFamily.keys}",
            )
        }
        backgroundScope.launch {
            try {
                pm.setupDesktop(vncPassword, de)
            } catch (_: Exception) {
                // setupDesktop's catch already pushes Error state.
            }
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("activeDistroId", active.id)
            put("compatibility", de.spec.compatibilityOn(active.family).name)
            de.spec.compatibilityNoteOn(active.family)?.let { put("compatibilityNote", it) }
            put("status", "started")
            put("poll", "inspect_proot.desktopSetupState")
        }
    }

    private suspend fun uninstallDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        val pm = prootManager
        localSessionManager.desktopManager.stopDesktop(de)
        pm.uninstallDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "uninstalled")
        }
    }

    private suspend fun startDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        if (de !in prootManager.installedDesktops) {
            throw McpError(
                -32602,
                "${de.label} is not installed — call install_desktop first.",
            )
        }
        val dm = localSessionManager.desktopManager
        dm.startDesktop(de)
        // DesktopManager.startDesktop stays at STARTING until a caller
        // confirms the VNC port is up and calls markRunning — the UI path
        // (DesktopViewModel.startDesktop) does this, but the MCP path
        // didn't, so MCP-started desktops sat at STARTING forever (task
        // #23). Do the same port-poll → markRunning here so the state
        // finalizes. Native (labwc) self-finalizes RUNNING via the JNI
        // bridge, so skip the poll for it. We don't open an in-app VNC
        // viewer (that's UI-only; McpTools has no DesktopViewModel) — the
        // returned vncPort lets an external VNC client connect.
        if (!de.isNative) {
            val port = dm.getVncPort(de) ?: 5901
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (dm.desktops.value[de]?.state ==
                        sh.haven.core.local.DesktopManager.DesktopState.ERROR
                    ) break
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        dm.markRunning(de)
                        break
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
        }
        val instance = dm.desktops.value[de]
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("state", instance?.state?.name ?: "UNKNOWN")
            put("displayNumber", instance?.displayNumber ?: -1)
            put("vncPort", instance?.vncPort ?: -1)
            instance?.errorMessage?.let { put("errorMessage", it) }
            put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
            put("poll", "list_desktop_environments[].running")
        }
    }

    private fun stopDesktopTool(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")
        localSessionManager.desktopManager.stopDesktop(de)
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("label", de.label)
            put("status", "stopped")
        }
    }

    private fun readDesktopLog(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
        val maxChars = args.optInt("maxChars", 4000).coerceIn(200, 50000)
        val dm = localSessionManager.desktopManager
        val instances = dm.desktops.value
        val targets = if (deId != null) {
            val de = sh.haven.core.local.ProotManager.DesktopEnvironment.entries
                .firstOrNull { it.spec.id == deId }
                ?: throw McpError(-32602, "Unknown deId: $deId")
            listOf(de)
        } else {
            instances.keys.toList()
        }
        val arr = JSONArray()
        for (de in targets) {
            val inst = instances[de]
            val compLog = dm.compositorLogFor(de)
                ?.let { if (it.length > maxChars) it.takeLast(maxChars) else it }
            arr.put(JSONObject().apply {
                put("deId", de.spec.id)
                put("label", de.label)
                put("state", inst?.state?.name ?: "STOPPED")
                put("displayNumber", inst?.displayNumber ?: -1)
                put("vncPort", inst?.vncPort ?: -1)
                put("launchKind", de.spec.launch::class.simpleName ?: "unknown")
                inst?.errorMessage?.let { put("errorMessage", it) }
                put("capturedOutput", JSONArray(dm.capturedOutputFor(de)))
                put("compositorLog", compLog ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("desktops", arr)
        }
    }

    private fun desktopByIdOrThrow(deId: String): sh.haven.core.local.ProotManager.DesktopEnvironment =
        sh.haven.core.local.ProotManager.DesktopEnvironment.entries
            .firstOrNull { it.spec.id == deId }
            ?: throw McpError(-32602, "Unknown deId: $deId")

    private suspend fun listDesktopWindows(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val windows = try {
            localSessionManager.desktopManager.listWindows(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Window enumeration failed")
        }
        val arr = JSONArray()
        windows.forEach { w ->
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("title", w.title)
                put("x", w.x)
                put("y", w.y)
                put("width", w.width)
                put("height", w.height)
            })
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("count", arr.length())
            put("windows", arr)
        }
    }

    private suspend fun captureDesktop(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotEmpty() }
            ?: throw McpError(-32602, "deId is required")
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager
        val windowId = args.optString("windowId").takeIf { it.isNotBlank() }
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        // When a window is requested, resolve its geometry first so we can
        // crop the full-screen capture to it app-side (a single reliable
        // capture path beats per-window-id capture on bare Xvnc).
        var crop: IntArray? = null
        var windowTitle: String? = null
        if (windowId != null) {
            val win = try {
                dm.listWindows(de).firstOrNull { it.id == windowId }
            } catch (e: Exception) {
                throw McpError(-32603, e.message ?: "Window lookup failed")
            } ?: throw McpError(
                -32602,
                "Window '$windowId' not found on '${de.spec.id}' — call list_desktop_windows first",
            )
            crop = intArrayOf(win.x, win.y, win.width, win.height)
            windowTitle = win.title
        }

        val png = try {
            dm.capture(de)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "Capture failed")
        }
        val (b64, w, h) = withContext(Dispatchers.Default) {
            encodeCapture(png, crop, maxWidth, format)
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("width", w)
            put("height", h)
            put("format", format)
            put("source", "guest")
            windowId?.let { put("windowId", it) }
            windowTitle?.let { put("windowTitle", it) }
            // Reserved keys: McpServer lifts these into an MCP image content
            // block and strips them from structuredContent / the text echo.
            put("__imageBase64", b64)
            put("__mimeType", if (format == "jpeg") "image/jpeg" else "image/png")
        }
    }

    /**
     * Capture a remote-desktop VIEWER tab's rendered framebuffer (RDP/VNC), with
     * the server cursor composited at the tracked pointer position, and return
     * it inline. Distinct from [captureDesktop] (in-guest X11). Reads the live
     * frame via the [DesktopFrameHandle] the tab registered in
     * [desktopSessionRegistry]; no DesktopViewModel coupling.
     */
    private suspend fun captureDesktopTab(args: JSONObject): JSONObject {
        val explicitPid = args.optString("profileId").takeIf { it.isNotBlank() }
        val handles = desktopSessionRegistry.frameHandles()
        val pid = explicitPid ?: when (handles.size) {
            1 -> handles.keys.first()
            0 -> throw McpError(-32602, "No remote-desktop tab is open. Use connect_profile, then list_desktop_sessions.")
            else -> throw McpError(
                -32602,
                "Multiple desktop tabs open (${handles.keys.joinToString()}); pass profileId.",
            )
        }
        val handle = desktopSessionRegistry.frameHandle(pid)
            ?: throw McpError(-32602, "No capturable desktop tab for profile '$pid' (call list_desktop_sessions).")
        val src = handle.frame()
            ?: throw McpError(-32603, "Desktop '$pid' has not rendered a frame yet — wait for it to connect.")

        val maxWidth = args.optInt("maxWidth", 1280).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"

        val cursor = handle.cursor()
        val (px, py) = handle.pointer()

        val (b64, w, h) = withContext(Dispatchers.Default) {
            // Composite the cursor onto a mutable copy so the source frame
            // (shared with the live viewer) is never mutated.
            var bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            if (cursor != null) {
                android.graphics.Canvas(bmp).drawBitmap(
                    cursor.bitmap,
                    (px - cursor.hotspotX).toFloat(),
                    (py - cursor.hotspotY).toFloat(),
                    null,
                )
            }
            val fullW = bmp.width
            val fullH = bmp.height
            if (maxWidth in 1 until bmp.width) {
                val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
            }
            val out = java.io.ByteArrayOutputStream()
            if (format == "jpeg") {
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            } else {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), fullW, fullH)
        }

        return JSONObject().apply {
            put("profileId", pid)
            put("protocol", handle.protocol)
            put("width", w)
            put("height", h)
            put("hasCursor", cursor != null)
            if (cursor != null) {
                put("cursorWidth", cursor.bitmap.width)
                put("cursorHeight", cursor.bitmap.height)
                put("hotspotX", cursor.hotspotX)
                put("hotspotY", cursor.hotspotY)
                put("pointerX", px)
                put("pointerY", py)
            }
            put("format", format)
            // Reserved keys: McpServer lifts these into an MCP image content
            // block and strips them from structuredContent / the text echo.
            put("__imageBase64", b64)
            put("__mimeType", if (format == "jpeg") "image/jpeg" else "image/png")
        }
    }

    /**
     * Resolve the [DesktopInputHandle] for a remote-desktop tab the same way
     * [captureDesktopTab] resolves frame handles: explicit profileId, or the
     * sole open tab. Used by the remote-desktop input tools.
     */
    private fun resolveDesktopInput(args: JSONObject): Pair<String, DesktopInputHandle> {
        val explicitPid = args.optString("profileId").takeIf { it.isNotBlank() }
        val handles = desktopSessionRegistry.inputHandles()
        val pid = explicitPid ?: when (handles.size) {
            1 -> handles.keys.first()
            0 -> throw McpError(-32602, "No remote-desktop tab is open. Use connect_profile, then list_desktop_sessions.")
            else -> throw McpError(-32602, "Multiple desktop tabs open (${handles.keys.joinToString()}); pass profileId.")
        }
        val handle = desktopSessionRegistry.inputHandle(pid)
            ?: throw McpError(-32602, "No controllable desktop tab for profile '$pid' (call list_desktop_sessions).")
        return pid to handle
    }

    /** Inject a mouse click into a remote-desktop tab (RDP/VNC). */
    private fun tapDesktopTab(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val x = requireIntArg(args, "x")
        val y = requireIntArg(args, "y")
        val button = args.optInt("button", 1).coerceIn(1, 7)
        h.mouseClick(x, y, button)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol)
            put("x", x); put("y", y); put("button", button)
        }
    }

    /** Inject mouse-wheel scrolling into a remote-desktop tab (RDP/VNC). */
    private fun scrollDesktopTab(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        val deltaY = requireIntArg(args, "deltaY")
        h.mouseWheel(deltaY)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol); put("deltaY", deltaY)
        }
    }

    /** Set the remote-desktop tab's clipboard text (RDP/VNC). */
    private fun sendDesktopClipboard(args: JSONObject): JSONObject {
        val (pid, h) = resolveDesktopInput(args)
        if (!args.has("text")) throw McpError(-32602, "text is required")
        val text = args.optString("text")
        h.clipboard(text)
        return JSONObject().apply {
            put("profileId", pid); put("protocol", h.protocol); put("chars", text.length)
        }
    }

    /**
     * Capture Haven's OWN rendered UI via [HavenUiBridge], encode it inline
     * as an image, and report the FULL window dimensions so a follow-up
     * tap_haven_ui / swipe_haven_ui lands in the same pixel space (even when
     * the returned image was downscaled). Maps the bridge's secure /
     * no-foreground outcomes to an honest signal rather than a black frame.
     */
    private suspend fun captureHavenUi(args: JSONObject): JSONObject {
        val maxWidth = args.optInt("maxWidth", 1080).coerceIn(160, 4096)
        val format = if (args.optString("format", "jpeg").lowercase() == "png") "png" else "jpeg"
        return when (val result = havenUiBridge.captureScreen()) {
            is HavenUiBridge.CaptureResult.Ok -> {
                val (b64, iw, ih) = withContext(Dispatchers.Default) {
                    encodeBitmapScaled(result.bitmap, maxWidth, format)
                }
                result.bitmap.recycle()
                JSONObject().apply {
                    // Full window pixels — the coordinate space for tap_haven_ui.
                    put("width", result.width)
                    put("height", result.height)
                    // Dimensions of the (possibly downscaled) returned image.
                    put("imageWidth", iw)
                    put("imageHeight", ih)
                    put("format", format)
                    // Reserved keys: McpServer lifts these into an MCP image content block.
                    put("__imageBase64", b64)
                    put("__mimeType", if (format == "jpeg") "image/jpeg" else "image/png")
                }
            }
            HavenUiBridge.CaptureResult.Secure -> JSONObject().apply {
                put("secure", true)
                put(
                    "message",
                    "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be captured. " +
                        "Turn off Settings → screen security to allow it.",
                )
            }
            is HavenUiBridge.CaptureResult.NoForeground -> throw McpError(-32603, result.reason)
            is HavenUiBridge.CaptureResult.Failed -> throw McpError(-32603, result.reason)
        }
    }

    private suspend fun dumpHavenUi(): JSONObject {
        return when (val result = havenUiBridge.dumpUi()) {
            is HavenUiBridge.DumpResult.Ok -> {
                val arr = JSONArray()
                for (n in result.nodes) {
                    arr.put(JSONObject().apply {
                        n.text?.let { put("text", it) }
                        n.contentDescription?.let { put("contentDescription", it) }
                        n.editableText?.let { put("editableText", it) }
                        n.role?.let { put("role", it) }
                        put("clickable", n.clickable)
                        if (n.disabled) put("disabled", true)
                        put("bounds", JSONArray().put(n.left).put(n.top).put(n.right).put(n.bottom))
                        put("centerX", (n.left + n.right) / 2)
                        put("centerY", (n.top + n.bottom) / 2)
                    })
                }
                JSONObject().apply {
                    put("width", result.width)
                    put("height", result.height)
                    put("count", result.nodes.size)
                    put("nodes", arr)
                }
            }
            HavenUiBridge.DumpResult.Secure -> JSONObject().apply {
                put("secure", true)
                put("message", "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be dumped.")
            }
            is HavenUiBridge.DumpResult.NoForeground -> throw McpError(-32603, result.reason)
            is HavenUiBridge.DumpResult.Failed -> throw McpError(-32603, result.reason)
        }
    }

    /**
     * Capture Haven's own screen for the `ui://haven/screen` MCP resource —
     * the file-shaped sibling of [captureHavenUi]. Returns (base64 PNG,
     * mimeType); throws [McpError] for the secure / no-foreground / failed
     * cases (resources/read has no structured "secure" channel, so they map
     * to read errors). Called by `McpServer.handleResourcesRead`.
     */
    suspend fun readUiScreenResource(): Pair<String, String> {
        return when (val r = havenUiBridge.captureScreen()) {
            is HavenUiBridge.CaptureResult.Ok -> {
                val enc = withContext(Dispatchers.Default) { encodeBitmapScaled(r.bitmap, 1080, "png") }
                r.bitmap.recycle()
                enc.first to "image/png"
            }
            HavenUiBridge.CaptureResult.Secure -> throw McpError(
                -32603,
                "Screen security (FLAG_SECURE) is on — Haven's own UI cannot be captured. " +
                    "Turn off Settings → screen security to allow it.",
            )
            is HavenUiBridge.CaptureResult.NoForeground -> throw McpError(-32603, r.reason)
            is HavenUiBridge.CaptureResult.Failed -> throw McpError(-32603, r.reason)
        }
    }

    private suspend fun tapHavenUi(args: JSONObject): JSONObject {
        val x = requireIntArg(args, "x")
        val y = requireIntArg(args, "y")
        val holdMs = args.optLong("holdMs", 0L).coerceIn(0L, 10_000L)
        return injectResultJson(havenUiBridge.dispatchTap(x, y, holdMs)).apply {
            put("x", x); put("y", y); put("holdMs", holdMs)
        }
    }

    private suspend fun swipeHavenUi(args: JSONObject): JSONObject {
        val fromX = requireIntArg(args, "fromX")
        val fromY = requireIntArg(args, "fromY")
        val toX = requireIntArg(args, "toX")
        val toY = requireIntArg(args, "toY")
        val durationMs = args.optLong("durationMs", 200L).coerceIn(1L, 10_000L)
        val steps = args.optInt("steps", 16).coerceIn(1, 200)
        val result = havenUiBridge.dispatchSwipe(fromX, fromY, toX, toY, durationMs, steps)
        return injectResultJson(result).apply {
            put("fromX", fromX); put("fromY", fromY); put("toX", toX); put("toY", toY)
            put("durationMs", durationMs); put("steps", steps)
        }
    }

    private fun injectResultJson(result: HavenUiBridge.InjectResult): JSONObject = JSONObject().apply {
        when (result) {
            HavenUiBridge.InjectResult.Delivered -> put("delivered", true)
            is HavenUiBridge.InjectResult.Refused -> {
                put("delivered", false)
                put("reason", result.reason)
            }
        }
    }

    private fun requireIntArg(args: JSONObject, name: String): Int {
        if (!args.has(name)) throw McpError(-32602, "$name is required")
        return args.optInt(name)
    }

    // --- Tier-3 standing policies (create/list/revoke; enforcement is in McpServer) ---

    private suspend fun createStandingPolicy(args: JSONObject): JSONObject {
        val client = currentClientHint?.takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "No client identity on this call — initialize with clientInfo.name first.")
        val description = args.optString("description").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "description is required")
        val namesArr = args.optJSONArray("toolNames")
            ?: throw McpError(-32602, "toolNames is required")
        val toolNames = (0 until namesArr.length()).map { namesArr.optString(it) }.filter { it.isNotBlank() }
        if (toolNames.isEmpty()) throw McpError(-32602, "toolNames must be a non-empty array of tool names")
        toolNames.forEach { t ->
            if (t in StandingPolicyEnforcer.NEVER_COVERABLE_TOOLS) {
                throw McpError(-32602, "'$t' can never be covered by a standing policy (Tier-4 / policy-management tools stay per-action)")
            }
            if (t !in tools) {
                throw McpError(-32602, "Unknown tool: '$t' (standing policies cover built-in tools only)")
            }
        }
        val rate = args.optInt("maxCallsPerMinute", 60).coerceIn(1, 600)
        val mins = args.optInt("expiresInMinutes", 60).coerceIn(5, 1440)
        val constraints = args.optJSONObject("argConstraints")?.toString()
        val policy = sh.haven.core.data.db.entities.StandingPolicy(
            clientHint = client,
            description = description,
            toolNamesJson = JSONArray(toolNames).toString(),
            argConstraintsJson = constraints,
            maxCallsPerMinute = rate,
            expiresAt = System.currentTimeMillis() + mins * 60_000L,
        )
        standingPolicyRepository.savePolicy(policy)
        return JSONObject().apply {
            put("id", policy.id)
            put("clientHint", client)
            put("toolNames", JSONArray(toolNames))
            put("maxCallsPerMinute", rate)
            put("expiresInMinutes", mins)
            put("expiresAt", policy.expiresAt)
        }
    }

    private suspend fun listStandingPolicies(): JSONObject {
        standingPolicyRepository.purgeExpired() // lazy housekeeping
        val now = System.currentTimeMillis()
        val policies = standingPolicyRepository.allPolicies()
        return JSONObject().apply {
            put("count", policies.size)
            put("policies", JSONArray().apply {
                policies.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("clientHint", p.clientHint)
                        put("description", p.description)
                        runCatching { put("toolNames", JSONArray(p.toolNamesJson)) }
                        p.argConstraintsJson?.let { c -> runCatching { put("argConstraints", JSONObject(c)) } }
                        put("maxCallsPerMinute", p.maxCallsPerMinute)
                        put("expiresAt", p.expiresAt)
                        put("remainingMinutes", ((p.expiresAt - now) / 60_000L).coerceAtLeast(0))
                        put("enabled", p.enabled)
                        put("active", p.enabled && p.expiresAt > now)
                    })
                }
            })
        }
    }

    private suspend fun revokeStandingPolicy(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val existing = standingPolicyRepository.getPolicy(id)
            ?: throw McpError(-32602, "No standing policy with id '$id' (see list_standing_policies)")
        standingPolicyRepository.deletePolicy(id)
        return JSONObject().apply {
            put("revoked", true)
            put("id", id)
            put("description", existing.description)
        }
    }

    /**
     * Scale [bmp] so its width is at most [maxWidth] (aspect-preserving),
     * encode to [format] ("jpeg"/"png"), and return (base64, width, height).
     * A copy is made only when downscaling; the scratch copy is recycled,
     * the caller's [bmp] is never touched.
     */
    private fun encodeBitmapScaled(bmp: Bitmap, maxWidth: Int, format: String): Triple<String, Int, Int> {
        val out = if (maxWidth in 1 until bmp.width) {
            val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
        } else {
            bmp
        }
        val baos = java.io.ByteArrayOutputStream()
        if (format == "jpeg") out.compress(Bitmap.CompressFormat.JPEG, 75, baos)
        else out.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val result = Triple(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP), out.width, out.height)
        if (out !== bmp) out.recycle()
        return result
    }

    /**
     * Decode captured PNG bytes, optionally crop to [crop] (x,y,w,h),
     * downscale to [maxWidth], and re-encode. Returns (base64, width,
     * height). Crop rect is clamped to the bitmap bounds so a window that
     * extends past the screen edge still yields a valid image.
     */
    private fun encodeCapture(
        pngBytes: ByteArray,
        crop: IntArray?,
        maxWidth: Int,
        format: String,
    ): Triple<String, Int, Int> {
        var bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            ?: throw McpError(-32603, "Could not decode captured image")
        if (crop != null) {
            val x = crop[0].coerceIn(0, bmp.width - 1)
            val y = crop[1].coerceIn(0, bmp.height - 1)
            val cw = crop[2].coerceIn(1, bmp.width - x)
            val ch = crop[3].coerceIn(1, bmp.height - y)
            bmp = Bitmap.createBitmap(bmp, x, y, cw, ch)
        }
        if (maxWidth in 1 until bmp.width) {
            val nh = (bmp.height.toFloat() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            bmp = Bitmap.createScaledBitmap(bmp, maxWidth, nh, true)
        }
        val out = java.io.ByteArrayOutputStream()
        if (format == "jpeg") {
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        } else {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp.width, bmp.height)
    }

    /**
     * Launch a GUI app into a running X11 desktop (software-GL fallback set
     * by [DesktopManager.launchApp]) and optionally poll [listWindows] until
     * its window appears, returning the windowId so the agent can capture it.
     */
    private suspend fun launchAppInDesktop(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val waitTitle = args.optString("waitForWindowTitle").takeIf { it.isNotBlank() }
        val timeoutMs = args.optInt("timeoutMs", 15000).coerceIn(0, 60000)
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager

        // Snapshot existing windows so we can spot the NEW one.
        val before: Set<String> = try {
            dm.listWindows(de).map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val appId = try {
            dm.launchApp(de, command)
        } catch (e: Exception) {
            throw McpError(-32603, e.message ?: "launch failed")
        }

        var winId: String? = null
        var winTitle = ""
        if (timeoutMs > 0) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && winId == null) {
                kotlinx.coroutines.delay(600)
                val wins = try { dm.listWindows(de) } catch (e: Exception) { emptyList() }
                val w = if (waitTitle != null) {
                    wins.firstOrNull { it.title.contains(waitTitle, ignoreCase = true) }
                } else {
                    wins.firstOrNull { it.id !in before && it.title.isNotBlank() }
                        ?: wins.firstOrNull { it.id !in before }
                }
                if (w != null) {
                    winId = w.id
                    winTitle = w.title
                }
            }
        }
        return JSONObject().apply {
            put("deId", de.spec.id)
            put("appId", appId)
            put("command", command)
            if (winId != null) {
                put("windowId", winId)
                put("windowTitle", winTitle)
                put("hint", "pass windowId to capture_desktop to screenshot just this window")
            } else {
                put("windowId", JSONObject.NULL)
                put(
                    "note",
                    if (timeoutMs == 0) {
                        "launched without waiting for a window"
                    } else {
                        "no matching window within ${timeoutMs}ms (still starting? try list_desktop_windows)"
                    },
                )
            }
        }
    }

    /**
     * Launch a GL app on the GPU path, screenshot it, and heuristically judge
     * whether the frame is non-blank — a regression check for the windowed-GL
     * present pipeline (memory: project_3d_desktop_gl_prusaslicer_test, where a
     * blank canvas came back pure white). Reliable only for a full-frame GL app;
     * the returned image is the ground truth. Reuses [encodeCapture]'s image
     * path so `McpServer` lifts `__imageBase64` into an MCP image block.
     */
    private suspend fun glSmokeTest(args: JSONObject): JSONObject {
        val deId = args.optString("deId").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "deId is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val timeoutMs = args.optInt("timeoutMs", 12000).coerceIn(0, 60000)
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val de = desktopByIdOrThrow(deId)
        val dm = localSessionManager.desktopManager

        if (args.has("gpuUseVenus")) {
            preferencesRepository.setGpuUseVenus(args.getBoolean("gpuUseVenus"))
        }

        val before: Set<String> = try {
            dm.listWindows(de).map { it.id }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val appId = dm.launchApp(de, command, gpu = true)

        var windowId: String? = null
        if (timeoutMs > 0) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(600)
                val fresh = try {
                    dm.listWindows(de).firstOrNull { it.id !in before }
                } catch (e: Exception) {
                    null
                }
                if (fresh != null) {
                    windowId = fresh.id
                    break
                }
            }
        }
        // Let the GL app paint a few frames before sampling.
        kotlinx.coroutines.delay(1500)

        val png = dm.capture(de)
        val (b64, w, h) = encodeCapture(png, null, maxWidth, "jpeg")

        // Analyse the FULL-resolution capture (not the downscaled JPEG) so the
        // subsample sees real pixels.
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw McpError(-32603, "Could not decode captured image")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val verdict = sh.haven.core.local.GlCanvasProbe.analyze(px, bmp.width, bmp.height)
        val venus = preferencesRepository.gpuUseVenus.first()

        return JSONObject().apply {
            put("deId", de.spec.id)
            put("command", command)
            put("appId", appId)
            put("gpuPath", if (venus) "venus+zink" else "virpipe")
            put("windowId", windowId ?: JSONObject.NULL)
            put("passed", verdict.nonBlank)
            put("distinctColors", verdict.distinctColors)
            put("topColorFraction", verdict.topColorFraction)
            put("sampled", verdict.sampled)
            put(
                "note",
                "non-blank heuristic over the whole frame; reliable for a full-frame GL app, " +
                    "not a correctness check — inspect the image",
            )
            put("width", w)
            put("height", h)
            put("format", "jpeg")
            put("__imageBase64", b64)
            put("__mimeType", "image/jpeg")
        }
    }

    /**
     * Render a guest file to an inline image the agent can see, fully
     * headless (no X / no GL). The render runs inside the proot writing a PNG
     * to `/tmp` (bound to the app cacheDir), which we read back off the app's
     * filesystem and re-encode via [encodeCapture] — the same image-content
     * path as [captureDesktop], so `McpServer` lifts `__imageBase64` into an
     * MCP image block. This is the reliable "show me the design" loop that
     * doesn't depend on a running VNC desktop.
     */
    private suspend fun viewFile(args: JSONObject): JSONObject {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val maxWidth = args.optInt("maxWidth", 1024).coerceIn(160, 4096)
        val format = if (args.optString("format", "png").lowercase() == "jpeg") "jpeg" else "png"
        val page = args.optInt("page", 1).coerceAtLeast(1)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }

        val ext = path.substringAfterLast('.', "").lowercase()
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        val outName = "haven-view-${System.currentTimeMillis()}.png" // guest /tmp == app cacheDir
        val outGuest = "/tmp/$outName"
        val q = "'" + path.replace("'", "'\\''") + "'" // shell-quote the source path

        // Build the render pipeline + the binaries it needs. `\$` escapes keep
        // shell variables/substitutions literal; `$outGuest`/`$maxWidth`/etc.
        // are Kotlin interpolations baked in here.
        val needed: List<String>
        val renderCmd: String
        when (ext) {
            "png", "jpg", "jpeg", "webp", "bmp", "gif" -> {
                needed = emptyList()
                renderCmd = "cp $q $outGuest"
            }
            "svg" -> {
                needed = listOf("rsvg-convert")
                renderCmd = "rsvg-convert -w $maxWidth $q -o $outGuest"
            }
            "pdf" -> {
                needed = listOf("pdftoppm")
                // -singlefile drops pdftoppm's "-NN" page suffix so it lands on $outGuest.
                renderCmd = "pdftoppm -png -r 150 -f $page -l $page -singlefile $q ${outGuest.removeSuffix(".png")}"
            }
            "kicad_sch" -> {
                needed = listOf("rsvg-convert")
                renderCmd = "d=\$(mktemp -d) && kicad-cli sch export svg -o \$d $q && " +
                    "rsvg-convert -w $maxWidth \"\$d/$base.svg\" -o $outGuest"
            }
            "kicad_pcb" -> {
                needed = listOf("rsvg-convert")
                // KiCad 10 made --layers mandatory for `pcb export svg`; a sensible default
                // set (copper + silkscreen + fab + outline) renders a recognisable board.
                renderCmd = "d=\$(mktemp -d) && kicad-cli pcb export svg " +
                    "--layers F.Cu,B.Cu,F.SilkS,B.SilkS,F.Fab,Edge.Cuts -o \$d/pcb.svg $q && " +
                    "rsvg-convert -w $maxWidth \$d/pcb.svg -o $outGuest"
            }
            else -> throw McpError(
                -32602,
                "Unsupported file type '.$ext' — view_file handles kicad_sch, kicad_pcb, pdf, svg, png, jpg, jpeg, webp, bmp, gif",
            )
        }

        if (needed.isNotEmpty()) {
            val (ready, detail) = prootManager.ensureRenderTools(needed)
            if (!ready) throw McpError(-32603, "Render tools unavailable: $detail")
        }
        if (ext == "kicad_sch" || ext == "kicad_pcb") {
            // kicad-cli is heavy; never auto-install — surface a clear error if absent.
            val (probe, _) = prootManager.runCommandInProot(
                "command -v kicad-cli >/dev/null 2>&1 && echo HAVE || echo MISSING",
            )
            if (!probe.contains("HAVE")) {
                throw McpError(-32603, "kicad-cli not found in the guest — install KiCad to render .$ext files")
            }
        }

        val script = "rm -f $outGuest; { $renderCmd ; } > $outGuest.log 2>&1; echo EXIT:\$?"
        val (out, _) = prootManager.runCommandInProot(script)

        val outFile = File(context.cacheDir, outName)
        val logFile = File(context.cacheDir, "$outName.log")
        try {
            if (!outFile.exists() || outFile.length() == 0L) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Render produced no image for $path (${log.take(500).trim()}; $out)")
            }
            val bytes = outFile.readBytes()
            val (b64, w, h) = withContext(Dispatchers.Default) {
                encodeCapture(bytes, null, maxWidth, format)
            }
            return JSONObject().apply {
                put("path", path)
                put("rendered", "$ext → $format")
                put("width", w)
                put("height", h)
                put("format", format)
                // Reserved keys: McpServer lifts these into an MCP image content block.
                put("__imageBase64", b64)
                put("__mimeType", if (format == "jpeg") "image/jpeg" else "image/png")
            }
        } finally {
            outFile.delete()
            logFile.delete()
        }
    }

    /**
     * Read a guest file's bytes back to the agent. Stages a copy under
     * `/tmp` (== app cacheDir) so we can read it app-side, capped to
     * [maxBytes]. Returns UTF-8 text or base64. The reliable agent⇄guest
     * read channel — no base64 hand-copying over run_in_proot output.
     */
    private suspend fun readGuestFile(args: JSONObject): JSONObject {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val asBase64 = args.optBoolean("asBase64", false)
        val maxBytes = args.optInt("maxBytes", 262144).coerceIn(1, 8 * 1024 * 1024)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val q = "'" + path.replace("'", "'\\''") + "'"
        val staged = "haven-read-${System.currentTimeMillis()}"
        val script = "rm -f /tmp/$staged; { [ -f $q ] && cp -- $q /tmp/$staged ; } > /tmp/$staged.log 2>&1; echo EXIT:\$?"
        val (out, _) = prootManager.runCommandInProot(script)
        val staging = File(context.cacheDir, staged)
        val logFile = File(context.cacheDir, "$staged.log")
        try {
            if (!staging.exists()) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Could not read $path (missing or not a regular file?) (${log.take(300).trim()}; $out)")
            }
            val total = staging.length()
            val bytes = staging.inputStream().use { ins ->
                val buf = ByteArray(maxBytes)
                var read = 0
                while (read < maxBytes) {
                    val n = ins.read(buf, read, maxBytes - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
            return JSONObject().apply {
                put("path", path)
                put("totalBytes", total)
                put("returnedBytes", bytes.size)
                put("truncated", total > bytes.size)
                if (asBase64) {
                    put("encoding", "base64")
                    put("contentBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                } else {
                    put("encoding", "utf8")
                    put("content", String(bytes, Charsets.UTF_8))
                }
            }
        } finally {
            staging.delete()
            logFile.delete()
        }
    }

    /**
     * Write agent-supplied bytes into the guest. Stages the bytes under
     * `/tmp` (== app cacheDir) then `cp`s them to [path] inside the proot,
     * creating parent dirs by default. Accepts UTF-8 `content` or binary
     * `contentBase64` (exactly one).
     *
     * Supports chunked transfer for large files so a single huge base64
     * payload doesn't have to fit one JSON-RPC request (#214):
     * - `append` (default false): false truncates/creates the per-path
     *   staging buffer; true appends this chunk to it.
     * - `final` (default true): true copies the buffer into the guest and
     *   reports `bytesWritten`; false only buffers and reports
     *   `bufferedBytes` without touching the guest yet.
     * Single-call usage (no `append`/`final`) is identical to before.
     * Chunks are assumed delivered in order over sequential calls.
     */
    private suspend fun writeGuestFile(args: JSONObject): JSONObject {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "path is required (absolute guest path)")
        if (!path.startsWith("/")) throw McpError(-32602, "path must be an absolute guest path")
        val hasText = args.has("content")
        val hasB64 = args.has("contentBase64")
        if (hasText == hasB64) throw McpError(-32602, "provide exactly one of content or contentBase64")
        val mkdirs = args.optBoolean("mkdirs", true)
        val append = args.optBoolean("append", false)
        val final = args.optBoolean("final", true)
        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }
        val bytes = if (hasB64) {
            try {
                Base64.decode(args.getString("contentBase64"), Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw McpError(-32602, "contentBase64 is not valid base64: ${e.message}")
            }
        } else {
            args.getString("content").toByteArray(Charsets.UTF_8)
        }
        // Staging buffer keyed deterministically by the target path so a
        // sequence of chunk calls reuses the same file. append=false
        // truncates (a fresh transfer self-heals over any abandoned
        // buffer); append=true accumulates.
        val staged = "haven-write-" + Integer.toHexString(path.hashCode())
        val staging = File(context.cacheDir, staged)
        val logFile = File(context.cacheDir, "$staged.log")
        if (append) staging.appendBytes(bytes) else staging.writeBytes(bytes)

        // Non-final chunk: buffered only, guest untouched.
        if (!final) {
            return JSONObject().apply {
                put("path", path)
                put("bufferedBytes", staging.length())
                put("status", "buffered")
            }
        }

        // Final (or single-call): copy the accumulated buffer into the guest.
        val q = "'" + path.replace("'", "'\\''") + "'"
        try {
            val mk = if (mkdirs) "mkdir -p -- \"\$(dirname -- $q)\" && " else ""
            val script = "{ $mk cp -- /tmp/$staged $q ; } > /tmp/$staged.log 2>&1; echo EXIT:\$?"
            val (out, _) = prootManager.runCommandInProot(script)
            if (!out.contains("EXIT:0")) {
                val log = if (logFile.exists()) logFile.readText() else ""
                throw McpError(-32603, "Write failed for $path (${log.take(300).trim()}; $out)")
            }
            return JSONObject().apply {
                put("path", path)
                put("bytesWritten", staging.length())
                put("status", "written")
            }
        } finally {
            staging.delete()
            logFile.delete()
        }
    }

    private suspend fun runInProot(args: JSONObject): JSONObject {
        val maxOut = args.optInt("maxOutputChars", 8000).coerceIn(200, 200_000)
        fun tail(s: String) = if (s.length > maxOut) s.takeLast(maxOut) else s

        // Poll an existing background job.
        val pollId = args.optString("jobId").takeIf { it.isNotBlank() }
        if (pollId != null) {
            val job = prootJobs[pollId]
                ?: throw McpError(-32602, "Unknown jobId: $pollId (it may have been lost on app restart)")
            val out = synchronized(job.output) { job.output.toString() }
            return JSONObject().apply {
                put("jobId", pollId)
                put("status", if (job.running) "running" else "done")
                if (!job.running) put("exitCode", job.exitCode ?: -1)
                put("output", tail(out))
            }
        }

        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required (or pass jobId to poll a background job)")

        if (!prootManager.isRootfsInstalled) {
            throw McpError(-32603, "Active distro '${prootManager.activeDistroId}' has no installed rootfs")
        }

        // Background mode: stream the process output into a job buffer and
        // return immediately so long installs don't hold the request open.
        if (args.optBoolean("background", false)) {
            val jobId = startProotJob(command)
            return JSONObject().apply {
                put("jobId", jobId)
                put("status", "running")
                put("activeDistroId", prootManager.activeDistroId)
                put("poll", "run_in_proot with jobId=$jobId")
            }
        }

        // Synchronous mode — start the command as a job and wait up to
        // [autoBackgroundMs] for it to finish. A quick command returns its
        // full output + exitCode inline (and its job is dropped); a long one
        // (apt-get install, big output) is handed back as a pollable jobId
        // rather than blocking the HTTP response into a client `-32001`
        // request timeout (#279 / #240).
        val jobId = startProotJob(command)
        val job = prootJobs.getValue(jobId)
        val finished = withTimeoutOrNull(autoBackgroundMs) {
            while (job.running) delay(50)
            true
        } != null
        val out = synchronized(job.output) { job.output.toString() }
        if (finished) {
            prootJobs.remove(jobId)
            return JSONObject().apply {
                put("activeDistroId", prootManager.activeDistroId)
                put("exitCode", job.exitCode ?: -1)
                put("output", tail(out))
            }
        }
        return JSONObject().apply {
            put("jobId", jobId)
            put("status", "running")
            put("activeDistroId", prootManager.activeDistroId)
            put("output", tail(out))
            put(
                "note",
                "Command still running after ${autoBackgroundMs / 1000}s — auto-backgrounded to " +
                    "avoid a request timeout. Poll with run_in_proot(jobId=$jobId).",
            )
            put("poll", "run_in_proot with jobId=$jobId")
        }
    }

    private fun guestServiceToJson(
        inst: sh.haven.core.local.GuestServiceManager.GuestServiceInstance?,
        spec: sh.haven.core.local.GuestServiceManager.GuestServiceSpec,
    ): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("label", spec.label)
        put("command", spec.command)
        put("port", spec.port)
        put("autostart", spec.autostart)
        put("isMcp", spec.isMcp)
        if (spec.isMcp) put("mcpPath", spec.mcpPath)
        put("state", inst?.state?.name ?: "STOPPED")
        inst?.errorMessage?.let { put("errorMessage", it) }
    }

    private fun registerGuestService(args: JSONObject): JSONObject {
        val label = args.optString("label").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "label is required")
        val command = args.optString("command").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "command is required")
        val port = args.optInt("port", 0).takeIf { it in 1..65535 }
            ?: throw McpError(-32602, "port must be 1..65535")
        val autostart = if (args.has("autostart")) args.optBoolean("autostart") else true
        val isMcp = args.optBoolean("isMcp", false)
        val mcpPath = args.optString("mcpPath", "/mcp").ifBlank { "/mcp" }
        val gsm = localSessionManager.guestServiceManager
        val id = "gsvc-${System.currentTimeMillis().toString(36)}"
        val spec = sh.haven.core.local.GuestServiceManager.GuestServiceSpec(
            id = id, label = label, command = command, port = port, autostart = autostart,
            isMcp = isMcp, mcpPath = mcpPath,
        )
        gsm.register(spec)
        invalidateGuestTools()
        return JSONObject().apply {
            put("id", id)
            put("activeDistroId", prootManager.activeDistroId)
            put("status", "registered")
            put("isMcp", isMcp)
            put("hint", "call start_guest_service to launch it now")
        }
    }

    private fun listGuestServices(): JSONObject {
        val gsm = localSessionManager.guestServiceManager
        val running = gsm.services.value
        val arr = JSONArray()
        gsm.registered().forEach { spec -> arr.put(guestServiceToJson(running[spec.id], spec)) }
        return JSONObject().apply {
            put("activeDistroId", prootManager.activeDistroId)
            put("count", arr.length())
            put("services", arr)
        }
    }

    private fun guestSpecOrThrow(id: String): sh.haven.core.local.GuestServiceManager.GuestServiceSpec =
        localSessionManager.guestServiceManager.registered().firstOrNull { it.id == id }
            ?: throw McpError(-32602, "Unknown guest service id: $id")

    private fun startGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val spec = guestSpecOrThrow(id)
        val gsm = localSessionManager.guestServiceManager
        gsm.start(id)
        invalidateGuestTools()
        // The MCP reverse tunnel re-homes to pick up the new service's port
        // via HavenApp's guest-service observer (no-op when no tunnel endpoint
        // is configured).
        return guestServiceToJson(gsm.services.value[id], spec).apply { put("status", "started") }
    }

    private fun stopGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        val spec = guestSpecOrThrow(id)
        localSessionManager.guestServiceManager.stop(id)
        invalidateGuestTools()
        return guestServiceToJson(localSessionManager.guestServiceManager.services.value[id], spec)
            .apply { put("status", "stopped") }
    }

    private fun unregisterGuestService(args: JSONObject): JSONObject {
        val id = args.optString("id").takeIf { it.isNotBlank() }
            ?: throw McpError(-32602, "id is required")
        guestSpecOrThrow(id)
        localSessionManager.guestServiceManager.unregister(id)
        invalidateGuestTools()
        return JSONObject().apply { put("id", id); put("status", "unregistered") }
    }

    private suspend fun startAudioBridge(): JSONObject {
        localSessionManager.audioBridge.start()
        return audioBridgeStatusJson()
    }

    private fun stopAudioBridge(): JSONObject {
        localSessionManager.audioBridge.stop()
        return audioBridgeStatusJson()
    }

    private fun audioBridgeStatusJson(): JSONObject {
        val s = localSessionManager.audioBridge.statusNow()
        return JSONObject().apply {
            put("state", s.state.name)
            put("port", s.port)
            put("bytesStreamed", s.bytesStreamed)
            s.error?.let { put("error", it) }
        }
    }

    private suspend fun getProotInstallLog(args: JSONObject): JSONObject {
        val distroId = args.optString("distroId").takeIf { it.isNotEmpty() }
        val sinceMs = args.optLong("sinceMs", 0L)
        val limit = args.optInt("limit", 100).coerceIn(1, 1000)
        val events = prootInstallLogRepository.querySince(
            distroId = distroId,
            sinceMs = sinceMs,
            limit = limit,
        )
        val arr = JSONArray().apply {
            events.forEach { put(installLogEventToJson(it)) }
        }
        return JSONObject().apply {
            put("count", events.size)
            distroId?.let { put("distroId", it) }
            put("sinceMs", sinceMs)
            put("events", arr)
        }
    }
}

/**
 * Consent gating metadata for a single tool, looked up by [McpServer]
 * before dispatching the call so destructive tools can prompt the user
 * with an action-specific summary.
 *
 * Read-only and tap-equivalent tools use [ConsentLevel.NEVER] and skip
 * the prompt entirely; the [summary] still has to be a function so the
 * registry shape stays uniform but is unused in that branch.
 */
internal data class ToolConsent(
    val level: ConsentLevel,
    val summary: (JSONObject) -> String,
)

private class ToolHandler(
    val description: String,
    val inputSchema: JSONObject,
    /** Consent level the dispatcher applies before invoking [invoke]. */
    val consentLevel: ConsentLevel = ConsentLevel.NEVER,
    /**
     * Builds the human-readable one-liner shown in the consent prompt
     * for non-NEVER tools. Default is just the tool name; per-tool
     * registrations should override with something specific so the user
     * understands what they're approving.
     */
    val summarise: (JSONObject) -> String = { "tool call" },
    private val invoke: suspend (JSONObject) -> JSONObject,
) {
    suspend fun handle(args: JSONObject): JSONObject = invoke(args)
}

/** JSON Schema for tools that take no arguments. */
private fun emptyObjectSchema(): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject())
}

/**
 * Dedicated notification channel for agent-posted notifications
 * (raise_notification). Kept separate from Haven's foreground / renewal
 * channels so the user can mute "agent test notifications" without
 * silencing real Haven notifications.
 */
private const val AGENT_NOTIFICATION_CHANNEL_ID = "agent.test.notifications"
private const val INSTALL_NOTIFICATION_CHANNEL_ID = "agent.install"

/** Upper bound on lines requested from logcat in a single read_logcat call. */
private const val MAX_LOGCAT_LINES = 5000

/** Upper bound on the response payload from a single read_logcat call (256 KiB). */
private const val MAX_LOGCAT_BYTES = 256 * 1024

/**
 * Cheap shell-metacharacter screen for read_logcat args that get
 * interpolated into a `sh -c` command line. Rejects the characters that
 * can start a new command, a process substitution, or a variable
 * expansion. Logcat filter expressions and timestamps don't legitimately
 * contain any of these.
 */
private fun String.containsShellMetacharacter(): Boolean =
    any { it == ';' || it == '|' || it == '&' || it == '$' || it == '`' || it == '\n' || it == '\r' }
