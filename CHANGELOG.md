# Changelog

Release notes for Haven, newest first. Each `## vX.Y.Z` section is the body of
the corresponding GitHub Release; a release can't ship without its section
(enforced by `scripts/check-changelog.sh` in CI). The GitHub "Full Changelog"
compare link is appended automatically — don't add it here.

## v5.68.38

🧰 **Dependency and toolchain updates** — no user-facing changes, just keeping the build current and secure: Kotlin 2.4.0 (with its matching KSP), OkHttp 5.4.0, the RDP transport's uniffi bindings to 0.32, and sha2 0.11 in the RDP native library. The RDP native library (`librdp_transport.so`) was rebuilt for all three ABIs against the updated bindings, and the whole app was re-verified green (build, unit tests, lint). If your RDP connections behaved before, they behave the same now.

## v5.68.37

🗂️ **Reopening a workspace is reliable now** — restoring a saved workspace with several terminals no longer stalls behind the session picker or stacks duplicate tabs. Each host comes up once and its tmux/zellij/screen sessions attach over that single connection, with all hosts brought up in parallel — so one slow or password-prompting connection no longer holds up the rest of the workspace. Relaunching a workspace you've already opened reuses what's live instead of duplicating tabs, and a session that has since been closed on the host is reattached (and noted as recreated) rather than coming back empty. Workspaces saved before the session-name update heal themselves: the first successful restore pins each tab's session, so the next restore is exact.

🏷️ **Save current** lists each open session as `‹host› tmux ‹name›` (host, session manager, session name) instead of an opaque id, so you can tell which session each row is before you save it.

## v5.68.36

🗂️ **Workspaces reopen your tmux sessions automatically** — restoring a saved workspace now reattaches each terminal to the exact tmux/zellij/screen session it was on, instead of dropping you into the session picker for every tab. Haven records each tab's session when you **Save current** and, on restore, dials the connection and attaches straight to that session by name — so a workspace with four terminals comes back as your four sessions with no prompting. Re-save any workspace made before this update once, so its tabs pick up their session names.

## v5.68.35

🔑 **The Keys tab is always available** (#360) — key and identity management no longer hides on a fresh install. Previously the Keys tab appeared only once you had a key, a step-ca CA, or an SSH connection, so a first-run user who prefers to add keys *before* their first connection had to find key management buried in Settings. It's now always present, like the Desktop and Files tabs.

## v5.68.34

🪪 **New: reusable SSH identities** (#360) — a named identity bundles a username, an optional password, and an optional SSH key, entered once and assignable anywhere. Manage identities on the Keys tab (**Add identity**); assign one per host from the new **Identity** picker in the connection editor's Authentication section, or per group via long-press → **Set identity** — hosts inherit the group's identity unless they override it, including an explicit "use this connection's own credentials" opt-out. The chosen identity's credentials are applied at connect time, so nothing about how you connect changes. Fully opt-in: existing per-host logins keep working untouched. Translated into all 12 UI languages.

🔧 **Fixed: tapping an identity-only host now connects** (#360) — a connection whose username comes from an assigned identity (so its own username is blank) was diverted into the password prompt, whose Connect button stayed disabled until you typed a username — trapping the connection. Tapping such a host now connects straight away, applying the identity's username, key, and password. Verified on-device: an identity-only host authenticates with the identity's key.

🔧 **Fixed: reopening a saved workspace now restores the connection** — launching a workspace from a cold start (nothing connected yet) silently failed to bring up its SSH terminals: each tab needs a live session to attach to, and none was dialled, so it no-opped. The launcher now dials a workspace's SSH hosts that aren't up, waits for the connection, then opens the terminals on it — so "one tap reopens them all" works from a fresh launch, not only when the host was already connected.

## v5.68.32

🖥️ **New: Custom command (X11) desktop** (#361) — termux-x11-style custom sessions for the Linux VM. The Desktops view gains a **Custom command (X11)** entry: Haven still runs the X server and the VNC display, but the session command is yours — e.g. `dbus-launch startxfce4` — instead of a fixed catalog desktop. It installs only what it needs (~15 MB: X server, dbus, xterm); your WM/DE comes from the distro's own package manager. Edit the command any time from the desktop row — changes apply on the next start, no reinstall — and a blank command routes into the editor instead of launching an empty screen. The usual X11 constraint applies: software rendering, so desktops that require a GL compositor (GNOME Shell, KWin) still won't start (#261).

## v5.68.31

🔄 **New: automatic backup push** (#359) — the encrypted backup can now keep itself current on your remote. Turn on **Push automatically** under Settings → Backup → Sync to a remote: Haven re-pushes the encrypted backup a couple of minutes after settings change (a burst of edits collapses into one push), plus a daily catch-up, with each result recorded in the connection audit log. Enabling asks for your backup password once and stores it encrypted on the device so background pushes can run without prompting — the dialog says exactly that, and turning auto-push off deletes it. Push-only by design: restoring (Pull) stays a manual action, so automatic sync can't silently overwrite your local config. SFTP destinations connect on demand and suit background sync best; SMB/rclone destinations still need to be connected. Translated into all 12 UI languages.

## v5.68.30

📜 **Fixed: scrolling fought tmux/nano/vim and painted stale history over them** (#255) — with a full-screen app (tmux without `mouse on`, nano, vim, less) on screen, a one-finger swipe scrolled Haven's local scrollback — which for these apps is the frozen pre-app history, so the display showed old content and rubber-banded on every redraw. Swipes over a full-screen app now send arrow keys to the app itself (as Termux does), so tmux, nano, vim, and less scroll their own content smoothly. Normal shell scrollback, two-finger local scroll, long-press selection, and `mouse on` behaviour are unchanged; profiles that disable the alternate screen keep local scrolling.

## v5.68.29

📟 **New: "Agent log" button on the ongoing notification** (#239) — while the agent (MCP) endpoint is running, the persistent notification carries an **Agent log** action that jumps straight to the Agent Activity screen, including from a cold start or from behind the biometric lock. The expanded notification also now lists each session on its own line, so the MCP status line (`running <tool>…` / last error) is no longer truncated behind other connection names.

## v5.68.28

🖥️ **Fixed: tmux session manager failed on hosts addressed by IP** (#358) — sessions are auto-named after `user@host`, so an IP host produced a name like `user-10.0.0.5`; tmux treats `.` and `:` as pane separators and refused to attach, and the connection closed with the misleading *"Shell closed — is your session manager installed on this host?"* error. Dots and colons in session names are now replaced with dashes (`user-10-0-0-5`). Thanks @Panthaaaa for the precise diagnosis.

## v5.68.27

🛟 **Fixed: restoring a backup could leave the app crashing on launch** — if your backup was taken after you'd changed the terminal background opacity, mail font size, or app-window scale, restoring it corrupted that setting and the app then crash-looped on every open, recoverable only by clearing all app data. Restore now keeps those settings the right type, **and this update self-heals an install already stuck this way** — just update and reopen; your connections and keys are intact. (Affected the existing Restore, not only the new remote sync below.)

☁️ **New: sync your encrypted backup to a connection you already have** (#323) — Settings → Backup → "Sync to a remote": pick an existing SFTP/SMB/rclone connection and a file path, then Push (encrypt + upload) or Pull (download + restore). Same AES-256-GCM encryption as the file backup — the remote never sees your config in the clear. Manual push/pull for now; connect the destination first.

## v5.68.26

🔧 **Build/CI reliability** — raised the Gradle build heap so the release build stops intermittently failing while packaging the 32-bit ARM app (a bundletool out-of-memory that wasted release runs on reruns) (#356). No app-facing change.

## v5.68.25

🔎 **The AI-assistant screen-reader tool worked only in debug builds — now it works in the shipped app** — `dump_haven_ui`, which lets a paired assistant read Haven's on-screen controls, returned "No Compose view in the foreground window" in every release build (the code optimiser renamed the classes it looks for). It never worked for anyone running a real build. Fixed, with a CI guard so it can't silently break again.

👁️ **An assistant can now see approval sheets and dialogs, not just the main screen** (#355) — the consent/pairing sheet renders in its own window that the screen-reader tool couldn't see, so the app's most safety-critical prompt was invisible to an assistant (it could be waiting on your approval without being able to tell you what for). It's now readable, tagged by which window it belongs to. Strictly read-only: an assistant can see the sheet but cannot tap it — approval stays with you, on the device.

## v5.68.24

📁 **Uploading a folder with sub-folders to an SMB share now works** (#273) — v5.68.23 fixed this for SFTP and missed the identical bug in the SMB path, where the first file inside a sub-folder failed because its parent directory was never created. (Local and cloud/rclone destinations were never affected.)

🛡️ **An agent can now see that its action is waiting for your approval, instead of guessing** (#355) — since v5.68.22 a request that arrives while Haven is in the background waits for you rather than being denied outright, but nothing told the AI assistant that: a waiting request and a refused one looked identical from its side, so it would give up or retry pointlessly. A new read-only tool lets it observe the approval queue — what's being asked, by which client, and since when. It cannot answer a prompt: only you can, on the device. This also fixes the waiting request being invisible internally, which is what made the old behaviour indistinguishable.

## v5.68.23

📁 **Uploading a folder that contains sub-folders now works** (#273) — it didn't. Folder upload asked the destination to create each file's parent directory one level at a time, so the very first file inside a sub-folder failed ("No such file") because its grandparent didn't exist yet, and the whole upload aborted. Only completely flat folders ever uploaded successfully. Parent directories are now created properly, once per directory. Found by testing a real Termux folder on a phone rather than trusting the code comment, which claimed this already worked.

⏱️ **Measured: the v5.68.22 folder-scan speed-up, on a real Termux folder** — 400 files: 1.6 s → 0.16 s. 4,000 files: **14.4 s → 0.29 s**. The old scan cost grew with every file; the new one barely moves. If a folder upload used to sit on a blank screen for half a minute, that was roughly 8,000 files' worth of waiting.

## v5.68.22

🛡️ **An agent action that arrives while Haven is in the background now waits for you instead of being denied on the spot** (#337) — previously, if an AI agent called a tool needing your approval while you were in another app (even one Haven itself had launched, like the system installer), the call failed immediately with "denied", and nothing you'd done said no. Now the call holds, the heads-up notification tells you it's waiting, and opening Haven shows the approval sheet for that *same, still-live* call — tap Allow and it proceeds. It's still denied automatically if you never answer, and it can never be approved without you: Haven does not become a silent automation channel. Device-verified end to end, along with the pairing-loop guards from v5.68.21 (a spamming client can't stack duplicate prompts, gets a two-minute cooldown after a Deny, is rate-limited across renamed retries, and can be silenced with one tap on **Block**).

📁 **Uploading a folder from another app's storage is dramatically faster and no longer looks hung** (#273) — picking a folder from, say, Termux could sit on a blank screen for half a minute even for a few megabytes: Haven asked that app for each file's name, type and size in separate cross-process queries, roughly three round trips per file. Each folder is now read in a single query, and the scan shows "Scanning… N files" while it works, so a slow source looks busy rather than dead. It can also be cancelled mid-scan now. (Measured against a simulated provider — if you hit this with a real Termux folder, please say whether it's fixed for you.)

👻 **New "Hide from recent apps" toggle** (Settings → Advanced, off by default) (#239) — hides Haven's card in the recents screen while sessions keep running; reopen from the app icon.

🔧 **Dependency and CI maintenance** — appcompat 1.7.1, hilt 2.60.1, datastore 1.2.1, smbj 0.14.0, xz 1.12, androidx.browser 1.10.0, hilt-work 1.4.0, mockk 1.14.11, and rustls 0.23.41 (the RDP native library is rebuilt for all three architectures). Two CI flake sources removed.

## v5.68.21

🖥️ **Boot a full QEMU system VM and drive it from Haven's own viewer** (#326) — alongside the proot desktops there's now a "System VM" manager (Desktop → Manage): import a raw/qcow2 x86_64 disk image, and Haven boots it with `qemu-system-x86_64` inside the active proot, real kernel and all, with the display on a loopback VNC port rendered by the existing VNC viewer — keyboard input included. The agent gets the same lifecycle over MCP (system-VM `list/import/start/stop/delete` verbs). Honest caveats, by design: unrooted Android has no `/dev/kvm`, so the VM runs under TCG emulation — around two minutes to a login prompt and a visibly slow framebuffer (fine for a boot-once console or light desktop; heavy desktops will drag) — it needs a distro whose qemu ships VNC support (Debian's does; Alpine's stripped build doesn't), one VM at a time, and x86_64 guests only so far. This is what #325's qemu-user can't do: a real foreign-arch kernel with its own block/net stack, not just translated binaries.

🛡️ **A misbehaving MCP client can no longer trap you in a pairing loop** (#337) — a client re-sending a stale token in a retry loop used to raise a fresh "Pair?" sheet per attempt (tap Pair, it asks again — four times in a row in the reproduced case) while real consent prompts starved behind them. Retries for a name already on screen now join that sheet instead of stacking duplicates, an explicit Deny silences that client's re-asks for two minutes, at most three pairing sheets can be raised per minute across *all* client names (the reproduced spammer rotated its name per call), and the pairing sheet gains a one-tap **Block** button that silences the client for the session (undo via Settings → Forget remembered allows). The consent sheet also now says when more requests are waiting behind the visible one, so a second client's prompt isn't mistaken for the action you just triggered. Block is translated in all 11 languages.

🔧 **Agent-created VNC/RDP/SPICE connections land on the right port** (#353) — `create_connection` advertised `vncPort`/`rdpPort`/`spicePort` arguments but only honoured the generic `port`, so e.g. a SPICE profile aimed at 5930 silently landed on 5900.

📦 **Debian 13 (Trixie) — the current Debian stable — joins the distro picker** (#253) — offered alongside Debian 12 (Bookworm), which stays for anyone who wants oldstable. Same proot-distro tarball lineage as Bookworm, all three architectures (arm64/x86_64/armv7), checksums verified by download against proot-distro's published values. The mirror picker works on it unchanged.

## v5.68.20

🖥️ **Foreign-architecture Linux distros are now offered in the distro picker** (#325) — the "＋ Add another distro" list only ever showed distros built for your phone's own CPU, so running, say, an x86_64 Debian on an arm64 phone meant knowing to hand-type a rootfs URL into Import rootfs. Every built-in distro now also appears as an emulated variant for each foreign architecture this build can run ("＋ Debian 12 (Bookworm) x86_64 — emulated, slower"), installed through the same path as before (the download is arch-detected and transparently run under qemu-user). It's discovery, not a new mechanism — and it's clearly labelled "emulated, slower" so the speed trade-off is obvious. Verified on an arm64 device: all five built-ins offered as x86_64 variants.

🔌 **Opening a USB drive with several attached now lets you pick which one** (#287) — "Open USB drive…" assumed a single drive and errored out ("pass deviceName") when more than one mass-storage device was plugged in; only the agent (MCP) path could choose. It now shows a short picker listing the attached drives. Opening a single drive, or none, is unchanged.

## v5.68.19

⌨️ **Local shells now track bracketed-paste and mouse modes** (#336) — an agent-opened local shell had no DECSET scan at all, and a local shell adopted into the Terminal UI got dead stub flows installed over the working ones, so `bracketPasteMode` read false forever. Two consequences fixed: `send_to_agent` now bracket-pastes multi-line messages to a REPL running in a local shell (verified on-device against bash 5.2 in the Alpine proot — a two-line message lands as one submitted paste, and drops back to plain input when the REPL exits), and an adopted local tab's own paste-wrapping now follows the live stream instead of never wrapping.

## v5.68.18

🔒 **Remote-desktop connections now pin the server's TLS certificate on first use** — a security review of every connection type found the VNC (VeNCrypt/X509) and RDP TLS paths accepted *any* certificate, leaving both open to man-in-the-middle interception. Both now record the certificate the first time you connect and refuse a changed one until you explicitly accept it — the same trust-on-first-use model as SSH host keys, sharing one trust store. From the same review: silent SSH connects (background and agent paths) fail closed on an unknown host key instead of proceeding; mail accounts configured for TLS now *require* STARTTLS rather than silently falling back when the server doesn't offer it; VNC remote→local clipboard sync is opt-in (default off), so a compromised server can't quietly read what lands on the phone's clipboard; backup encryption moved from 100k to 600k PBKDF2 iterations under a versioned envelope; rootfs archive extraction guards against zip-slip path traversal and deletes symlink-safely; and the terminal's native JNI layer got bounds/overflow hardening. The new host-key and clipboard strings are translated in all 11 languages.

🤖 **`send_to_agent` no longer garbles messages to plain shells** — it always wrapped the message in bracketed-paste markers, so a target that never enabled bracketed paste (a plain busybox/dash shell) received the markers as literal text and mangled the command. It now wraps only when the target has actually turned bracketed paste on (#226).

🔌 **The agent (MCP) endpoint now self-heals and no longer stalls on non-ASCII** — two fixes to the agent transport that the SSH terminal beside it already got right. The endpoint's accept loop is a background thread that the OS can kill when it trims the app; nothing was restarting it, so a still-up carrier could sit in front of a dead endpoint until you toggled the setting. It now revives on the same triggers the SSH sessions use (return-to-foreground and network-available). Separately, the request parser counted characters against a byte length, so any tool call carrying a multibyte character (an emoji, CJK, an accented letter) stalled until a 70-second timeout — it now reads the body by byte length and returns instantly. Both were reproduced and fixed on-device.

🔒 **Agent endpoint security hardening** — a staged review of the agent (MCP) transport, bringing it to the same bar as the SSH stack (`docs/design/mcp-backbone.md`). The request parser now caps body and header size (an oversized `Content-Length` could exhaust memory) and rejects cross-origin browser requests to the loopback endpoint. Beyond that, three changes tighten *who* an agent client is and *what* it may do:

- **Pairing tokens.** A paired client is now identified by a 256-bit secret it was issued when you approved it — not by the name it claims. It sends that token on every request; only its SHA-256 is stored on the device. A client that presents no token (or a wrong one) is treated as new and must be approved again. Approving a client on the phone is the one and only way in.
- **Un-pairing takes effect immediately.** Removing a client (from the tool or Settings → Agent endpoint → Paired clients) now revokes its token on the spot, not just after the next app restart.
- **Tunnelled traffic is never treated as on-device.** The "trust local clients" option applies only to genuine on-device clients; a client reaching the endpoint through a reverse tunnel still has to be approved, even though it arrives on `127.0.0.1`. Loopback trust is also **off by default** now.

Each of these was verified on-device against the live endpoint. Existing clients re-approve once on first connect after updating; configure your client (or the failover proxy) to send the token it's issued to stay paired across reconnects.

📖 **The agent's full tool surface is documented** — `docs/mcp-tools.md` lists every tool an agent can call, its arguments, and exactly when Haven asks for your approval (every call / once per session / never), generated from the live code so it can't drift. Under the hood the transport was re-architected into a shared protocol module and per-domain tool providers, with no change to how any tool behaves.

## v5.68.17

🖼️ **Agent-presented web pages, images and PDFs get a fullscreen view and clearer window controls** — pages the AI opens on screen now run with JavaScript and DOM storage enabled (previously many rendered as a blank white view), and the window chrome matches Haven's other windowed apps: an explicit ✕ closes the window (the old top-bar affordance read as "minimise"), and web/image content has a fullscreen toggle beside it that opens an immersive view with an exit control, rather than overlaying the content.

## v5.68.16

🖥️ **RDP to Linux xrdp servers now renders — previously a blank screen** — two long-standing gaps found by smoke-testing against a modern EGFX-capable xrdp: Haven never registered the DisplayControl virtual channel (xrdp aborts *all* channel processing when it's refused, so no frame ever arrived), and xrdp's Planar-codec tiles — which it uses for the greeter and much session content — were silently ignored. Both fixed: DisplayControl is registered and answered with the session's monitor layout, and Planar tiles decode through the RDP 6.0 bitmap decoder. Verified host-side (framebuffer dump) and on-device: the xrdp login greeter renders pixel-perfect. Windows RDP is unaffected.

⌨️ **Keyboard works immediately when switching Desktop → Terminal** — the terminal only claimed the keyboard on first composition, but adjacent screens stay composed while swiping, so returning to the terminal showed the keyboard while the keys still went to the desktop tab until you switched away and back again. The terminal now re-claims input every time its screen becomes active.

✂️ **Selection handles follow reading order** — after a backward drag (right-to-left or bottom-to-top), the *start* handle now sits on the top-left-most character as a left-to-right reader expects, instead of wherever the finger first went down. (The copied text was always correct; only the handles were swapped.)

🔧 **Toolchain and dependency refresh** — Android Gradle Plugin 9.2.1 + compileSdk 37 (unlocking hilt 2.59.2 and lifecycle 2.11), ironrdp 0.9-era stack (session 0.10, connector 0.9, input 0.6, new egfx crate), Robolectric 4.16.1, tailscale 1.100. No user-visible behaviour change expected from these beyond the fixes above.

## v5.68.15

🎯 **Pinch-zoom no longer breaks touch→text mapping in the terminal** — the gesture handler kept the pre-zoom character metrics captured in its closure (its `pointerInput` never restarts on a font change), so after any pinch-zoom every tap, long-press selection, handle drag and forwarded mouse click mapped through the old cell size while the screen drew at the new one: selecting a line grabbed the wrong text, worsening away from the top-left. Metrics are now read through `State` inside the handler, so touch mapping always matches what's rendered. Reported live while trying to copy a command after zooming out. (A crash reported around the same interaction is still under investigation — a stack trace is needed; if you hit it, logcat output on the issue tracker helps.)

🌍 **110 untranslated strings localised in all 11 languages** — everything added since the last i18n pass had shipped English-only: the desktop options/custom mounts/rootfs-import/USB-drive screens, rclone config import, deeplink connect confirmations, age encrypt/decrypt actions and identities, audio bridge and prompt-character settings, and the two-finger gesture accessibility descriptions.

📦 **Dependency refresh** — jsch 2.28.3, JavaMail 1.6.8, Navigation 2.9.8, tink 1.22.0, Compose BOM 2026.06, biometric alpha07, ironrdp-cliprdr 0.6 + ironrdp-tls 0.2.1, tailscale 1.100.0, and the GitHub Actions pins (checkout v7, artifact v7/v8, gh-release v3).

## v5.68.14

Follow-ups to the v5.68.13 rootfs-import work (#328) and the self-update path (#331), both found while verifying on-device.

📦 **Imported proot-built rootfs: long symlink targets no longer truncated** (#328) — v5.68.13 flattened the `.l2s.` link2symlink artifacts a Termux/proot-built rootfs carries, but the import's tar reader only handled GNU long *names* (type `L`), not long *link targets* (type `K`). Every Termux `.l2s.` target is an absolute path well over 100 characters, so it was read from the truncated 100-byte header field, lost its `.l2s.` basename, and slipped past the flattening. Type `K` is now parsed like `L`; a GNU-format tar with a 105-char link target was verified to import to a clean tree on-device. (Long link targets in the rarer PAX tar format are still unhandled — use GNU `tar`, which is the default for `tar(1)` and busybox.)

📥 **Truncated APK downloads are rejected, not staged** (#331) — a dropped connection mid-download produced a partial APK that still started with the zip magic bytes, so it passed the sanity check and was handed to the installer, which failed on-device with "problem parsing the package" — while the install had already reported success. Downloads (URL and backend) are now checked against the advertised length; a short read is deleted and surfaced as a failure with the byte counts, so it's retryable rather than a mystery.

## v5.68.13

Agent↔agent turn primitives over MCP, plus terminal-agent plumbing and port-forward fixes found while verifying them on-device (#226).

🤖 **New MCP tools: `await_turn` / `read_last_turn` / `isAgentRepl`** — turn-based conversation with whatever runs in a terminal session: block until the session is idle at a prompt (OSC 133 segments when the shell emits them, Claude-Code-aware screen heuristics otherwise — including NUL-padded fresh screens, stale shell-integration rows left under a running REPL, and ASCII spinner frames), then read the last completed turn (semantic command output, or the last bulleted block scraped above the REPL's input box with dividers and tmux status lines stripped). `list_sessions` marks which session is an agent REPL. Device-verified against plain busybox shells, an OSC 133-integrated prompt, and a live Claude Code session (#226).

🔧 **Agent-opened shells: the agent's view stays live** — `feed_terminal_output` injected into the UI tab's emulator instead of the one the agent tools read (silent no-op), and any Terminal-tab rebuild silently disconnected the agent emulator from the PTY (frozen snapshots while raw scrollback kept flowing). The agent tee now lives beside the scrollback ring and survives tab adoption and reattach.

🔌 **Local port forwards: honest activation and TIME_WAIT-proof rebind** — re-binding a `-L` forward right after a bulk transfer through it could fail against TIME_WAIT sockets (jsch binds without SO_REUSEADDR) yet still be reported active, leaving a listed-but-dead tunnel. Binds now retry "already in use" for ~2.3s, and `add_port_forward` reports `activated:false` with the reason when the bind genuinely failed (the rule stays saved for the next connect).

📦 **Imported rootfs: proot build artifacts flattened** (#328) — a rootfs built under a proot (Termux proot-distro, or a Haven guest) carries `.l2s.` symlink chains wherever dpkg hard-linked its database backups, with absolute paths of the *build* system baked in (e.g. `/data/data/com.termux/…`). Imported verbatim those links dangle — the root of the "`dpkg-divert: error creating new backup file … Operation not permitted`" reports. Import now materializes each such link from its sibling payload, so debootstrap/proot-built tarballs come in clean.

## v5.68.12

Keyboard fixes for SwiftKey and the compose overlay, plus a usable prompt on Void (#298, #253).

⌨️ **SwiftKey: lines no longer concatenate with the previous command** — SwiftKey keeps its internal composition context across Enter, so after one executed command the next word could reach the shell prefixed with the dead line (`nasls`, `naslsls` — with the old text visibly still in SwiftKey's suggestion bar). Haven now restarts the IME session at the Enter line boundary in Standard keyboard mode, the canonical signal for the keyboard to drop its prediction context. Regression-checked on-device with SwiftKey, Gboard and HeliBoard (no flicker or lost keystrokes); the failing configuration itself is reporter-specific, so confirmation on #298 is still open.

📝 **Compose (中) overlay no longer hides earlier words while you type the next** — the accumulated buffer and the keyboard's in-flight word were drawn at the same spot, so keyboards that compose every word (HeliBoard) appeared to erase the previous word until Enter. Both now render as one run. Device-verified with HeliBoard.

🐧 **Void Linux: usable shell prompt out of the box** — the seeded `/root/.profile` used bash-only prompt syntax, which Void's dash shell printed literally (`\[\033[32m\]\u@proot…`) with no line editing (arrow keys echoing `^[[H`). New installs/imports get a shell-aware prompt, and if bash is installed (`xbps-install -S bash`) the login shell hands over to it automatically (#253).

## v5.68.11

Mosh startup failures now show their real error instead of the install guide (#297).

📡 **Mosh: the real `mosh-server` error is finally surfaced** — when `mosh-server` was installed but refused to start (most commonly the server's UTF-8 locale isn't generated), Haven still showed the "mosh isn't installed" setup guide. The v5.60.7 fix for this defeated itself: its missing-binary check matched the phrase "No such file" anywhere in the output — and the locale error's own text contains exactly that phrase, so the most common failure was re-classified as "not installed" and the detailed error never appeared. The check now only matches output lines that actually name `mosh-server`, so a locale failure shows the real message (including the `locale-gen` hint). The reporter's exact error output is now a regression test.

## v5.68.10

Two fixes: compose (中) mode swallowed Enter, and imported rootfs lost hard-linked files.

⌨️ **Compose (中) mode: Enter now executes the line** — with the compose overlay active, Enter committed the buffered text but silently dropped the newline (and with an empty buffer, dropped the keypress entirely), so composed lines echoed concatenated on one prompt and never ran. Enter now submits the committed text like a normal keystroke. Device-verified with Gboard and HeliBoard: `ls` ⏎ executes every time. (Same #298 symptom family as v5.68.8's Standard-mode fix, different layer.)

📦 **Imported rootfs tarballs: hard-linked files no longer vanish** — in a wrapped tarball (the shape of every proot-distro-style import), hard-link entries pointed at an unstripped archive path, so the linked file was silently missing from the extracted rootfs; hard links that did extract came out with the app's umask instead of the archive's mode (losing exec bits on linked binaries). Both fixed (#328); the v5.68.5 mode restore itself was also verified exact on-device. If a previously imported rootfs behaves oddly, re-import it.

## v5.68.9

Agent-driven APK installs no longer time out on slow links (#331).

📲 **`install_apk_from_url` now stages in the background with pollable progress** — downloading a large APK over a slow link (e.g. updating Haven remotely while traveling) held the MCP request open for the whole transfer, so the call timed out while the install silently continued, leaving the agent blind. The tool now validates the URL synchronously, then downloads and installs in the background, returning `{pending, staging}` immediately. Both install tools (`install_apk_from_url`, `install_apk_from_backend`) publish a live `activeInstall` snapshot in `get_app_info` — phase (connecting / downloading / installing) plus bytes transferred — and the terminal outcome lands in `lastInstall`, including download failures that were previously invisible. Device-verified over a WireGuard-jump travel network, including a real mid-download network abort surfacing as a clean pollable error.

## v5.68.8

Fixes lines being lost or concatenated on Enter with the Standard keyboard (#298).

⌨️ **Enter now reliably executes the line in Standard keyboard mode** — with a composing keyboard (HeliBoard, swipe/gesture input, and others) in Standard mode, pressing Enter could fail to submit the typed line: the shell either executed an empty line while the text stayed stuck in the composition (Enter "only drew a new prompt"), or received the text with the Enter silently dropped — so successive commands concatenated on one prompt (`ls` ⏎ `ls` ⏎ `exit` arrived as `lslsexit`). Two distinct holes fixed: an accepted composition that was never sent to the shell, and newlines being stripped from keyboards that submit the line as a single commit (which also broke multi-line clipboard paste). Device-verified before/after with HeliBoard: the same key sequence that produced `lslsexit` now executes three lines and exits the shell. Known remaining gap: in the opt-in 中 compose-overlay mode, Enter can still be swallowed — tracked separately.

## v5.68.7

Optional: run Android's own native binaries inside a Local Linux shell (#304).

📦 **New "Expose Android system to guest" toggle (off by default)** — when enabled (Desktop → Manage → Options, or MCP `bind_android_system`), Haven binds Android's read-only `/system`, `/vendor`, `/apex`, `/product`, `/system_ext` and `/odm` into the proot guest, so the local Linux environment can run Android's own native tools — e.g. `/system/bin/getprop`, `toybox`. Left off by default because it exposes device and vendor internals; the partitions are mounted read-only, so the guest can't modify them. Device-verified: with the toggle on, `getprop ro.product.model` and `toybox uname` run inside the guest. (`/linkerconfig` isn't bound — it's SELinux-blocked for the app and unnecessary; Android's linker falls back to a default and still resolves its libraries.)

## v5.68.6

Shows your device model as the terminal "Host" line in neofetch/fastfetch (#304).

📟 **`neofetch`/`fastfetch` now show your device model instead of a blank Host** — inside a Local Linux (proot) shell these tools couldn't determine the hardware "Host", so the line came out empty: under proot there's no DMI, the device-tree model node is SELinux-unreadable, and `getprop` isn't reachable. Haven now seeds the device model (from `Build.MANUFACTURER`/`Build.MODEL`) where both tools look — `/tmp/sysinfo/model` for neofetch, and a bind over `/sys/firmware/devicetree/base/model` for fastfetch — across every proot launch path (terminal, one-shot, and desktop). Device-verified: Host now reads e.g. `OnePlus CPH2655`. (The separate request to bind Android's `/system`/`/vendor` into the guest is not included — it carries real SELinux/cross-device risk and needs a concrete use-case first.)

## v5.68.5

Fixes rootfs file permissions being lost on extraction (#328).

🔧 **Custom rootfs imports and distro installs now keep their file permissions** — Haven's tarball extractor was applying the Android app's restrictive umask (0077) instead of the permissions stored in the tar, so extracted directories came out `0700` and files `0600` regardless of the archive. On a custom-imported rootfs this could leave `/var/lib/dpkg` under-permissioned and make `apt`/`dpkg` fail with `error creating new backup file … Operation not permitted`. The extractor now restores each entry's exact mode from the tar. Re-import an affected rootfs to pick up correct permissions; an existing install can be repaired with `chmod 755 /var /var/lib /var/lib/dpkg`.

## v5.68.4

Two fixes: SSH hardware-key auth ordering, and a Native X11 desktop teardown crash.

🔑 **"Any hardware key" no longer prompts ahead of a primary software key (SSH)** — with a software key as your primary auth method and "Any hardware key" as a secondary fallback (and 2+ hardware keys enrolled), Haven asked you to present a hardware key during connect setup — *before* it even tried the primary software key that would have logged you in. It now defers that prompt: the software key is offered first, and the hardware-key pool is only exercised if it fails. When "Any hardware key" is your primary/only method, the present-your-key prompt is unchanged.

🖥️ **Native X11 (labwc) desktop no longer crashes the app on teardown** — stopping or restarting the on-device GPU desktop could crash Haven with a native use-after-free in the Wayland compositor (`liblabwc_android.so`) as it tore down. Fixed by clearing a dangling scene-node reference the moment the surface is destroyed. (Separate from backgrounding, which keeps the compositor alive.)

## v5.68.3

Fixes duplicated terminal input on the default keyboard for some IMEs (#298).

⌨️ **Typed input no longer doubles in the terminal (Secure keyboard mode)** — with the default (Secure) keyboard, a keyboard that *composes* words as you type — swipe/gesture typing, Samsung Keyboard, and some third-party IMEs — could send each word to the shell twice: you'd type `ls` and the shell would receive `lsls`. The duplicated characters also left the prompt non-empty, so Ctrl+D wouldn't exit the shell. The word is now sent once, reconciling only autocorrect/trailing-space differences. The earlier #298 fix covered only Standard keyboard mode; Secure — the default — had a separate gap. Verified with new regression tests; on-device confirmation for specific keyboards is still welcome.

## v5.68.2

Build-only fix so Haven builds from source on F-Droid (#327). No app-facing change from v5.68.1.

🔧 **Fixes the from-source FFmpeg build on F-Droid** — `build-ffmpeg/build.sh`'s SDK-CMake auto-detection ran `ls .../cmake/3.31.* .../cmake/3.22.*` and, when only one version is installed (as on F-Droid's buildserver, which installs just CMake 3.31.x), `ls` exits non-zero and `set -euo pipefail` silently aborted the whole FFmpeg build. This only affected builds that compile FFmpeg from source (F-Droid / reproducible builds); the GitHub release APKs ship the committed `.so` and were never affected, so v5.68.2's binaries are equivalent to v5.68.1's.

## v5.68.1

Brings the nested-Wayland desktops to 32-bit ARM (#327).

🖥️ **Nested-Wayland desktops (Sway, Cage, Hyprland, niri) now work on armv7** — the `libhaven_wayvnc_shim.so` capture-fallback shim (which runs inside the distro alongside `wayvnc`) is now cross-built for `armeabi-v7a` too, so those compositors render instead of grey-screening on the 32-bit build. Combined with the already-working VNC desktops (Openbox, Xfce4), the only local desktop still arm64-only on armv7 is the on-device GPU compositor (*Native X11 (GPU)*, which needs `liblabwc_android.so`). Not yet tested on real 32-bit hardware.

## v5.68.0

Adds a 32-bit ARM (armv7) build so Haven runs on older / 32-bit-only Android devices (#327).

📱 **New `armeabi-v7a` (armv7) flavor** — Haven previously shipped only `arm64-v8a` and `x86_64`, so 32-bit-only devices couldn't install it. There's now an `armv7` build with the full 32-bit native stack: PRoot + loader, the Go bridge (`libgojni` — WireGuard/MCP, rclone, mail), the Rust RDP and SPICE transports, the terminal (`termlib`), the OCR/scan libs (tesseract/leptonica), and FFmpeg (media conversion — now built for `x86_64` too, not just arm64). All five proot distros (Alpine, Debian, Ubuntu, Arch Linux ARM, Void) have armv7 rootfs images wired in. Local Linux desktops **over VNC (Openbox, Xfce4)** work on armv7 too — the X server and desktop run inside the distro, so they need no arm64-only libs. What's **arm64-only** on armv7 (same as the x86_64 build) is the on-device GPU compositor (the *Native X11 (GPU)* desktop — `liblabwc_android.so` + virgl + Xwayland) and the nested-Wayland compositors (Sway, Cage — their VNC shim isn't built for 32-bit in this release); remote desktops (RDP/VNC/SPICE) and everything else work. The x86_64 and app-level paths are verified in an emulator (app launches, proot/`apt` run, FFmpeg encodes H.264); not yet tested on real 32-bit hardware.

## v5.67.2

Fixes undeletable files inside proot distros (#329).

🧹 **A broken `link2symlink` stub no longer becomes un-removable** — PRoot's `--link2symlink` extension represents hard links as hidden `.l2s.` symlink chains. If those hidden backing files went missing (an interrupted `dpkg` run, or a rootfs copied/tarred without the `.l2s.*` files), the leftover stub could no longer be `stat`'d — so `ls`, `rm`, and `find` all failed on it with `Operation not permitted`, and `rm -rf` of any directory containing one aborted with "Directory not empty". PRoot now fails soft on a broken chain: the stub behaves like an ordinary dangling symlink and can be removed normally. Verified on host and on-device (arm64): a stub whose `.l2s.` files were deleted now `lstat`s cleanly (was EPERM) and `rm` removes it.

## v5.67.1

Candidate fix for `apt`/`dpkg` failing inside proot distros with a backup-file permission error (#328, #324) — pending confirmation from the reporter.

📦 **PRoot prefers a real hard link for `dpkg`'s `-old` database backups** — `dpkg` backs up its database files (`/var/lib/dpkg/status`, `/var/lib/dpkg/diversions`) by hard-linking them to a `-old` copy, which surfaced for one reporter as `dpkg-divert: error: error creating new backup file '/var/lib/dpkg/diversions-old': Operation not permitted`. PRoot's bundled `--link2symlink` extension was rewriting *every* hard link into its symlink-emulation unconditionally; PRoot now attempts a real hard link first and only falls back to that emulation when the underlying `link()` fails. Where a real hard link succeeds, `dpkg`'s backups match a normal Linux system (no `.l2s.` artifacts). **Caveat:** on the test device (OnePlus, Android 15) native hard links are denied to the app by the platform, so the change falls back to the existing emulation there and has no observable effect — the reporter's failure could not be reproduced, so this is not yet confirmed to fix it. The earlier #324 rootfs-import hardening (v5.66.3) was based on a wrong diagnosis (a directory at a dpkg DB path fails earlier with "Is a directory", not this backup error); it was left in place as harmless hardening. The change is verified to not regress `apt`/`dpkg` (host + on-device).

## v5.67.0

Encrypted (LUKS), writable, and multi-drive USB support (#287); MCP reconnects reliably; five reliability fixes found along the way.

🔐 **Encrypted (LUKS) drives now unlock** — a locked partition (previously reported read-only-locked with no way in) can be unlocked with its passphrase and mounted, against the drive's already-running helper VM — no reboot needed.

✏️ **Writable mounts** — "Open USB drive (writable)" mounts read-write instead of read-only, for edits and repairs. Every write flushes immediately (no write-back cache to lose to an unexpected kill), and closing a drive explicitly syncs and unmounts before the VM shuts down.

🗄️ **Multiple drives at once** — open more than one USB drive concurrently (up to a phone-resource limit), each in its own helper VM.

🧰 **Recovery/forensics toolset** — the helper Linux now bundles `testdisk`/`photorec`, `gdisk`/`sgdisk`, `parted`, `smartmontools`, and `ddrescue`, usable from a terminal into the drive's VM.

🔌 **MCP reconnects reliably without a force-stop** — MCP used to carry its tunnel over its own separately-authenticated background SSH session, whose headless auth can't use FIDO2 keys and would retry a doomed login forever once its other keys stopped working. MCP now rides your already-connected interactive SSH session instead — a Settings status row and `get_app_info`'s new `mcpCarriers` field show which route is actually carrying it.

🔧 **Five reliability fixes found during testing**: a keep-alive against Android suspending an idle USB device mid-session (which used to block all further enumeration until a physical replug), de-duplicated "USB: …" bookmarks by the drive's serial number instead of piling up a new one on every replug, a mount fallback that only exists for ext4/xfs was being tried on every filesystem (wasting the one fallback a non-ext4/xfs stick gets), raw USB transfers weren't synchronized against each other and could silently truncate above ~16KB — surfacing as the device resetting mid-write, most visibly formatting a LUKS header — and unlocking a LUKS partition could report success even when the mount had actually failed, because the guest's terminal echoes a command back as it's typed and that echo alone could satisfy the "done" check before the command had run; unlocking now verifies the real mount state before reporting back.

Drivable over MCP (`unlock_usb_drive_partition`, `open_usb_drive`'s `writable` param, `list_usb_drives`' `vms[]` array). Updated guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md).

## v5.66.3

Hardens the custom rootfs importer against malformed tarballs (#324).

🛡️ **Rootfs import handles a path reused with the wrong type** — Haven's tarball extractor (used for both built-in distro downloads and "Import rootfs…") now clears a path first if a later tar entry reuses it as a different type than an earlier one (e.g. a leftover directory placeholder later replaced by a regular file). Previously this could silently no-op or throw instead of letting the later entry win, potentially leaving an extracted file as the wrong type. Found while investigating a report of `dpkg` failing with "Operation not permitted" creating `/var/lib/dpkg/status-old` on a custom-imported rootfs — proot's hardlink emulation returns exactly that error when asked to link a directory instead of a file. Not yet confirmed as the root cause of that specific report (still waiting on the reporter to check whether `/var/lib/dpkg/status` is a directory on their rootfs), but it's a real correctness gap in the importer either way. Covered by 5 new unit tests.

## v5.66.2

Fixes importing a rootfs from a plain-HTTP LAN mirror (#284).

🌐 **Import a custom rootfs from a self-hosted HTTP mirror** — importing from an `http://` URL on your local network (a home-lab package/rootfs mirror without TLS) failed with "Cleartext HTTP traffic ... not permitted". Android blocks plain HTTP by default, and the declarative allowlist can only name specific domains, not an arbitrary LAN IP. Haven now falls back to a direct download for this one explicit, user-typed URL when that happens — every other network request in the app is unaffected. Verified against a real local HTTP mirror serving a real Alpine rootfs, end to end (download → extract → a working guest shell).

## v5.66.1

USB-drive connections reopen with a tap, and a CI fix.

🔖 **"USB: …" connections reopen with a tap** — a USB-drive connection used to go dead as soon as its little Linux VM stopped (eject, phone sleep, app restart), leaving a bookmark that just failed. Tap it again (with the drive still plugged in) and Haven now reboots the VM and reconnects automatically — no need to go back through "Open USB drive…" each time. If the drive isn't plugged in, Haven tells you to plug it back in instead of failing silently.

🔧 **Fixed a broken CI check** — a set of unit tests hadn't been updated for a recent internal change and were failing to compile, which had started blocking the automated test run on `main`. Fixed; no user-facing change.

## v5.66.0

The "Open USB drive" feature (#287), made faster and more reliable from a round of testing.

💾 **The USB helper Linux is kept, so later opens are quick** — the small on-device Linux that reads your drive is now **set up once and kept**, instead of rebuilt on every open. The **first** open still takes a few minutes (it downloads and installs the helper); every open after that skips straight to booting it — much quicker. A new **"Delete USB helper Linux"** option (**Desktop → Manage**) reclaims the ~280 MB if you want it back (it rebuilds itself, once, next time).

📂 **The drive opens in Files by itself** — when a drive is ready, Haven now switches to **Files** and lands on its contents (under `/mnt`, e.g. `/mnt/sda1`), instead of leaving you to find it.

🔌 **High-speed drives now mount reliably** — the USB/IP layer was reporting **every** exported device as *full-speed* (an old assumption from FIDO keys). A high-speed flash drive imported as full-speed makes the VM mis-read it, so it sometimes wouldn't appear. Haven now reports the drive's **real speed**, and **waits for the drive to actually enumerate** rather than assuming a fixed time — so a slower phone or a bigger/slower drive still mounts.

Drivable over MCP (adds `delete_usb_appliance`; `list_usb_drives` now reports `applianceProvisioned`). Updated guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md).

## v5.65.0

A new way to read USB drives the phone can't open, a fuller email tool surface, and a USB/IP fix.

💾 **Read USB drives the phone can't open (#287)** — plug in a USB flash drive or SSD and open its files, **even Linux-formatted (ext4/GPT) or other drives Android can't read itself**. Just go to **Desktop → Manage → "Open USB drive…"** — no setting to enable first; the open is a deliberate, read-only action. Haven hands the drive to a small on-device Linux virtual machine (which *does* have the kernel drivers a phone lacks), mounts it **read-only**, and surfaces the files as an ordinary **"USB: …" connection** — so the normal file browser, a terminal into the drive, and the MCP file tools all work unchanged. Because an un-rooted phone has no hardware virtualisation, the VM is emulated and **slow** (it's for pulling files off a drive, not a daily-driver desktop) — a live progress line shows what it's doing while it boots. Drivable over MCP (`open_usb_drive` / `list_usb_drives` / `close_usb_drive`). New guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md). *Read-only, one drive at a time, no encrypted (LUKS) drives yet; webcams/microphones still can't pass.*

📧 **More email tools for the agent (MCP)** — the IMAP/Gmail mail surface is filled out: `search_mail` (server-side IMAP SEARCH), `save_mail_draft`, `create_mail_folder` / `delete_mail_folder`, and `modify_mail_message` (mark read/unread, flag, move, copy/apply-label, delete). `send_mail` now threads replies correctly (`In-Reply-To`/`References`), `list_mail_messages` paginates and reads Cc, and a dropped IMAP idle socket reconnects instead of erroring. Device-verified on Gmail.

🔑 **USB/IP re-export fix** — exporting a phone USB device over USB/IP (to a remote host *or* the new on-device VM), stopping, and re-exporting within one app session failed with "Address already in use". The server now releases its port cleanly on stop, so re-export works.

## v5.63.0

More reporter-requested local-Linux-desktop control, a bring-your-own-rootfs path, and a security-key fix.

🔗 **Custom mounts (#301)** — **Desktop → Manage → Custom mounts** lets you expose extra Android paths inside a distro's guest, on top of the system mounts. Per-distro, read-write, any path (so a work-profile user can mount `/storage/emulated/9/…`, or share a folder between two distros). Picks up in the interactive shell, desktop sessions, and `run_in_proot`. Also drivable over MCP (`get_custom_binds` / `set_custom_binds`).

📦 **Import a custom rootfs (#284)** — **Desktop → Manage → Import rootfs…** brings your own rootfs tarball (`.tar.gz` or `.tar.xz`) — a proot-distro image, a `docker export`, or a second copy of a distro you already have. It's extracted and registered as a first-class distro (appears in the picker, `set_active_distro`, desktop installs) and used as-is (no packages forced). Also drivable over MCP (`import_distro`).

🧬 **Multiple instances of a distro (#302)** — falls out of the import path: give the import a new id and you get a second, isolated Ubuntu/Debian/… alongside your working one (clean vs. tinkered, per-project, testing). Each instance is a full rootfs.

🔑 **Security keys ignore non-FIDO USB devices** — FIDO2 auth no longer breaks when a USB audio dongle (or any non-FIDO USB device) is attached. Haven now matches only a real CTAPHID interface, so an audio dongle's volume-button HID is ignored, and a USB device that turns out to hold no usable key falls through to your NFC key instead of failing the connection.

## v5.62.0

Three reporter-requested additions for the local Linux desktop and the connect flow.

🔌 **Remap low ports (#300)** — a new toggle in **Desktop → Manage** lets guest services bind privileged ports (below 1024). With it on, a service on port N inside the guest becomes reachable at **N+2000** (e.g. `80` → `2080`), working around Android blocking the app from binding low ports directly. Off by default; it affects every privileged port, including a guest sshd.

🗂️ **Share device storage toggle (#301)** — also in **Desktop → Manage**, and on by default. Turn it off to stop a local session mounting your shared storage (`/storage` and `/sdcard`) into the guest, keeping your photos and downloads hidden from the local Linux environment.

🔗 **`haven://connect` deep link (#305)** — a new link to launch a connection, e.g. `haven://connect?host=<h>&user=<u>&port=<p>&transport=mosh&session=<s>`. If it matches one saved connection it asks for confirmation, then connects and attaches the named session; otherwise it opens the New Connection form pre-filled. Nothing is connected or saved without a tap (links carry no credentials). Useful for a self-hosted dashboard that drops you straight into a host or tmux session in one tap.

## v5.61.1

Fixes and refinements to the SPICE viewer and desktop gestures, from the first round of testing.

🎨 **SPICE colours corrected** — red and blue were swapped on-device; SPICE frames now render with correct colours. (Known issue still under investigation: colours can briefly flip during video streaming.)

👌 **Two-finger desktop gestures** — viewport control now lives on two fingers across SPICE, RDP and VNC: pinch to zoom, drag to pan the view **or** scroll the remote (toggle in the viewer toolbar), and a two-finger tap for a middle click. The previous three-finger gesture didn't work on OnePlus/OxygenOS, which intercepts three-finger touches system-wide before they reach the app.

🔑 **Security-key SSH tunnels** — a SPICE, VNC, RDP or SMB connection tunnelled through a jump host set to **"Any hardware key"** now fires the FIDO touch prompt instead of falling back to a password prompt.

⌨️ **Standard keyboard (#298)** — characters composed by an IME / gesture typing now flush to the shell when you press Enter.

The SPICE decoder is now developed in its own repository — [GlassOnTin/spice-kotlin](https://github.com/GlassOnTin/spice-kotlin) (AGPL-3.0) — and pulled into Haven as a submodule.

## v5.61.0

🖥️ **SPICE remote desktop (#286)** — a native SPICE client for QEMU/KVM and libvirt VMs, alongside the existing VNC and RDP viewers. The display channel decodes the SPICE image codecs (raw, **LZ**, **GLZ**, **ZLIB-GLZ**, and **QUIC**) plus the image cache, server draw operations, hardware-cursor shape/position, and multiple display surfaces. Input covers keyboard, absolute pointer, mouse buttons and the scroll wheel; the viewer shares the VNC/RDP gestures (two-finger pinch-zoom and pan). SSH tunnelling is supported. Add SPICE connections in the Connections tab or via the agent (MCP). Decoding is verified pixel-correct against QEMU and Windows Server 2025 guests.

🔑 **"Any hardware key" authentication** — a new authentication method that lets a connection authenticate with **any** enrolled FIDO2/security key (touch whichever one is present), instead of pinning to a single key. Edit Connection → Authentication methods → add **Any hardware key (FIDO)**.

🔒 **Listing several keys now requires _all_ of them** — adding more than one key to a connection's authentication methods now means "present every listed key" (AND), not "any one of them". Use **Any hardware key** for the either-of behaviour. (A true "must present both" challenge also depends on the server's `AuthenticationMethods publickey,publickey`.)

Also: the FIDO security-key path is now honoured on **jump hosts** (`-J`); a heads-up notification appears while a connect waits on a security-key touch; and a dark-theme readability sweep across the VNC/RDP/SPICE viewer toolbars, SSH key names, and a couple of black-on-dark text spots.

## v5.60.7

Two terminal fixes.

**Standard keyboard mode (#298):** a toolbar Ctrl/Alt tapped before a typed character could get stuck, so Ctrl+D didn't work and Enter was sent as Ctrl+Enter (showing up as garbage like `^[[13;5u`, breaking zsh's first-run wizard). Control combos now reach the shell immediately and the modifier is consumed. Secure mode (the default) was never affected.

**Local proot shell (#299):** bash/zsh process substitution — `diff <(cmd) <(cmd)` and `echo <(cmd)` — failed with "write error: Broken pipe". Fixed `/dev/fd` handling in the bundled proot.

## v5.60.6

🔑 **Security-key SSH reconnect no longer crashes** — a FIDO2/YubiKey (SK) SSH profile that re-authenticated after a dropped connection could crash with a `NullPointerException`: the auto-reconnect path rebuilt the SSH client without carrying over the security-key authenticator. Reconnect now re-authenticates correctly. As a safety net, any other connect path missing the authenticator now fails with a clear, diagnosable error instead of a bare crash.

## v5.60.5

🔎 **Local shell exits are now diagnosable (#294)** — a local Linux (proot) shell that exits immediately, e.g. a session manager like tmux/zellij that won't start, now records its exit code and last output in the connection log (Settings → View connection log) instead of vanishing without a trace.

**For agents/MCP:** `set_preference` / `get_preference` gain `connection_logging_enabled`, so connection logging can be toggled over MCP to diagnose a failing connection (enable → reproduce → `get_connection_log`).

## v5.60.4

🪟 **Terminal background transparency** — a new background-opacity setting (global, with a per-connection override). Set it below 100% and your device wallpaper shows through the terminal and every other Haven screen instead of a solid background.

🔐 **Fix #296** — post-quantum SSH key exchange (ML-KEM / `mlkem768x25519-sha256`) stopped working after the previous update's jsch bump, so connections to servers that require PQ KEX failed with "Algorithm negotiation fail". Restored by updating the bundled cryptography library (BouncyCastle → 1.84).

## v5.60.3

☁️ **Fix #295** — the rclone cloud-remote editor no longer pre-fills option fields with a bogus `[]` default, and **Save** is now gated on running **Configure** first, so a remote can't be saved half-configured.

## v5.60.2

Cleanup of the MCP / agent settings screen.

## v5.60.1

🔑 **Security-key SSH + a new multiplexer tab no longer hangs** — opening a second tmux/screen session on a FIDO2/YubiKey (SK) profile re-dialed and re-authenticated instead of reusing the live connection, which crashed (NPE) and then hung because the touch prompt never surfaced on the Terminal screen. The new tab now reuses the already-authenticated connection (no second touch), and the FIDO touch/PIN prompt is shown on the Terminal screen when a connect does need it.

⌨️ The extra keyboard rows now stay visible in fullscreen.

## v5.60.0

🪟 **tmux/screen session restore over SSH (#290)** — two fixes for profiles that attach to a multiplexer:

- The terminal emulator is now created at connect time, so a multiplexer's startup probes (DA2 / XTVERSION) are answered live instead of leaking escape sequences like `0c)\` onto your prompt.
- A remembered key passphrase is now stored against the key, not the host — so it's correctly reused on the session-picker path instead of being dropped.

**For agents/MCP:** the tmux/screen session picker can be observed and answered over MCP, and `create_connection` accepts a `sessionManager` field.
