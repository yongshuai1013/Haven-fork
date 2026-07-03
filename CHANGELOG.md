# Changelog

Release notes for Haven, newest first. Each `## vX.Y.Z` section is the body of
the corresponding GitHub Release; a release can't ship without its section
(enforced by `scripts/check-changelog.sh` in CI). The GitHub "Full Changelog"
compare link is appended automatically — don't add it here.

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
