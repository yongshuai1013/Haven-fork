# Changelog

Release notes for Haven, newest first. Each `## vX.Y.Z` section is the body of
the corresponding GitHub Release; a release can't ship without its section
(enforced by `scripts/check-changelog.sh` in CI). The GitHub "Full Changelog"
compare link is appended automatically — don't add it here.

## v5.65.0

A new way to read USB drives the phone can't open, a fuller email tool surface, and a USB/IP fix.

💾 **Read USB drives the phone can't open (#287)** — plug in a USB flash drive or SSD and open its files, **even Linux-formatted (ext4/GPT) or other drives Android can't read itself**. Turn on **Settings → "Open USB drives in a VM"**, then **Desktop → Manage → "Open USB drive…"**. Haven hands the drive to a small on-device Linux virtual machine (which *does* have the kernel drivers a phone lacks), mounts it **read-only**, and surfaces the files as an ordinary **"USB: …" connection** — so the normal file browser, a terminal into the drive, and the MCP file tools all work unchanged. Because an un-rooted phone has no hardware virtualisation, the VM is emulated and **slow** (it's for pulling files off a drive, not a daily-driver desktop) — a live progress line shows what it's doing while it boots. Drivable over MCP (`open_usb_drive` / `list_usb_drives` / `close_usb_drive`). New guide: [Reading USB drives](https://github.com/GlassHaven/Haven/blob/main/docs/features/usb-drives.md). *Read-only, one drive at a time, no encrypted (LUKS) drives yet; webcams/microphones still can't pass.*

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
