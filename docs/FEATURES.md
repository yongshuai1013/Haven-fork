---
layout: default
title: Features
---

# Features

Haven is one app for SSH/terminal, remote and local desktops, files and cloud
storage, email, on-device Linux, and an AI-agent bridge. Each area has its own
page below; the [landing page](index.md) has a short summary.

- **[Terminal](features/terminal.md)** — Mosh / Eternal Terminal / SSH, tmux-aware session restore, configurable keyboard toolbar, OSC 7/8/9/52/133/777 integration.
- **[Desktops](features/desktops.md)** — VNC (RFB 3.8 / VeNCrypt), RDP (IronRDP + EGFX), a GPU-accelerated native Wayland compositor, and a multi-distro local-desktop manager.
- **[Files & cloud storage](features/files-and-cloud.md)** — unified browser for SFTP/SCP, SMB, and 60+ cloud providers; cross-filesystem copy/move, editor and image tools, folder sync; plus on-device FFmpeg transcode, HLS streaming, and a DLNA server.
- **[Connections & networking](features/connections.md)** — host-key TOFU, port forwarding (-L/-R/-D/-J), SOCKS/HTTP/Tor proxies, per-app WireGuard & Tailscale tunnels, port knocking and fwknop SPA, and on-device SSH key management (incl. FIDO2/SK).
- **[Email & Mail Rules](features/email.md)** — read ProtonMail (bridge protocol) and any IMAP/SMTP mailbox; compose/reply/forward, multi-account, attachments; plus inbound automation with an approval queue.
- **[Local Linux on-device](features/local-linux.md)** — a real Linux userland via PRoot (no root): Alpine, Debian, Arch, or Void side-by-side, each with its native package manager.
- **[USB device forwarding](features/usb.md)** — broker an attached USB device through Android and re-expose it to the agent, into the Linux guest, or over USB/IP to a **remote host** (e.g. forward a phone-hosted YubiKey, touch on the phone); includes a device-class support matrix for what works where.
- **[Reticulum mesh](features/reticulum.md)** — rnsh shell, file transfer, and `-L`/`-D` port forwarding over Reticulum, pure Kotlin. The one transport that keeps working with no internet at all.
- **[Agent transport (MCP)](features/agent-mcp.md)** — an optional MCP server exposing ~130 consent-gated, audited tools, including ones that drive Haven's own UI for a self-hosting build → install → verify loop.
- **[Security & privacy](features/security.md)** — biometric lock, no telemetry, encrypted backup/restore (AES-256-GCM).

**Languages** — the UI is translated into 12 languages (English, Chinese, Spanish, Hindi, Arabic with RTL, Portuguese, Bengali, Russian, Japanese, Korean, French, German) and follows the device language.

---

[Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md) · [Backup file format](backup-format.md) · [Privacy policy](privacy-policy.html)
