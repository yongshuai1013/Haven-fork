---
layout: default
title: Automation (Tasker / MacroDroid)
---

# Automation (Tasker / MacroDroid)

Drive Haven from your phone's automation apps. Haven ships a native Tasker /
Locale / MacroDroid **plugin action** — "Run command on a Haven server" — and
exposes the same capability over its local [MCP HTTP endpoint](../mcp-tools.md)
for hosts that prefer raw HTTP requests.

## Run command on a Haven server (plugin action)

A Locale-protocol plugin action, discoverable directly in Tasker's and
MacroDroid's "add action → plugin" list — there's no separate plugin APK, it's
built into Haven. Add the action, tap to configure, and set:

- **Haven server** — a dropdown of your saved SSH connections.
- **Command** — a shell command, run in the login shell. The host substitutes
  its own variables into it *before* it fires, so a one-liner like
  `cd .. && rm -rf openpilot && git clone https://github.com/{lv=user}/openpilot -b {lv=branch}`
  works with your MacroDroid/Tasker variables inline.
- **Wait until the command finishes** — hold the macro until the command exits
  (Tasker/MacroDroid's "wait for action"). Off = fire-and-forget.
- **Show the command in a live terminal** — bring Haven to the front and stream
  the command into a visible terminal tab so you can watch it run.

When the command finishes it posts a result notification (exit code + a snippet
of the output).

### Result variables

In **wait** mode the action returns the command's output to the host as local
variables, for use in later macro steps:

| Variable | Contents |
|---|---|
| `%hstdout` | the command's standard output |
| `%hstderr` | the command's standard error |
| `%hexit` | the exit code (`-1` if the connection failed) |

The action declares these variables to the host so they appear in its variable
list. Passback only happens in wait mode — a fire-and-forget host has already
moved on by the time the command finishes.

### Watching it run (live terminal)

Android's background-activity-launch rules don't let a plugin's broadcast
receiver bring an app to the foreground on its own, so "show the command in a
live terminal" posts a **"Watch <server>" notification**; tapping it foregrounds
Haven, connects (or reuses) the profile's terminal, and types the command in.
One tap, then you watch the output live.

### Firing it as a raw intent

The action also accepts its config as **flat intent extras**, so it works from a
generic "Send Intent" step (or `adb`) as well as the plugin flow — broadcast
`com.twofortyfouram.locale.intent.action.FIRE_SETTING` to
`sh.haven.app/.tasker.TaskerFireReceiver` with string extras
`sh.haven.tasker.PROFILE_ID`, `sh.haven.tasker.COMMAND`, and boolean extras
`sh.haven.tasker.BLOCK`, `sh.haven.tasker.OVERLAY`.

### How it runs

The command runs over SSH through Haven's headless exec path: it reuses a live
session for the profile if one is connected, otherwise it connects headlessly.
Headless connect needs a stored password or an unencrypted key — FIDO2/encrypted
keys, and port-knock / SPA / tunnel-routed profiles, must be connected
interactively in Haven at least once first, after which the reuse path serves
them. No extra setup beyond having the SSH connection saved.

## HTTP / MCP endpoint

The same "run a command on a server" capability is available over Haven's local
agent endpoint (`http://127.0.0.1:8730/mcp`) via the `run_command` tool — pair
once (an approve-on-device prompt mints a token), then drive it with a single
authenticated POST per action from MacroDroid's/Tasker's **HTTP Request** action.
See [Agent transport (MCP)](../mcp-tools.md).

---

[← All features](../FEATURES.md)
