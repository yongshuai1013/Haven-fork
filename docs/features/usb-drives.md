---
layout: default
title: Reading USB drives
---

# Reading USB drives (flash drives, SSDs, memory cards)

**The short version:** plug a USB drive into your phone, and Haven can show you
its files — even drives formatted for Linux that your phone normally can't read.
Tap once, wait a minute, and the drive's folders appear in Haven's file browser
like any other connection.

This page assumes no background knowledge. If you just want the steps, jump to
[How to use it](#how-to-use-it). If you're curious *why* a phone struggles with
some drives, read [Why this is hard](#why-this-is-hard) at the end.

## The problem this solves

You plug a USB flash drive or portable SSD into your phone (with a USB-C or
USB-OTG adapter). Sometimes the phone shows the files straight away — great. But
often it shows **nothing**, even though the drive is fine and full of files.

That usually means the drive is formatted in a way your phone's built-in file
support doesn't understand. The most common cases:

- The drive was **formatted on a Linux PC** (a format called *ext4*). Android
  can read the simple formats cameras and Windows use (FAT32, exFAT), but not the
  Linux ones.
- The drive is **partitioned** in a layout (called *GPT*) the phone won't open.
- It's an **internal disk pulled out of a computer**, a Raspberry Pi SD card, a
  NAS disk, and so on.

In all of these, the files are perfectly readable — your phone just doesn't have
the right "driver" built in, and on a normal (un-rooted) phone you can't add one.

## What Haven does

Haven runs a **tiny, throwaway Linux computer inside the app** (a *virtual
machine*) that *does* have those drivers. It hands the USB drive to that little
Linux, which reads the drive and shares the files back to Haven. To you it just
looks like a new connection named after your drive — open it in **Files** and
browse, copy things off, or open a terminal into it.

Nothing is written to your drive (it's mounted **read-only** by default), nothing
leaves your phone, and the little Linux is deleted as soon as you eject the drive.

> You don't need to know anything about virtual machines to use this — that's the
> whole point. The word "QEMU" you might see elsewhere is just the engine doing
> the work behind the scenes.

## How to use it

**1. Turn it on once.** Go to **Settings** and switch on **"Open USB drives in a
VM."** It's off by default because it's heavier than Haven's normal USB handling
(see [trade-offs](#trade-offs-and-limits)).

**2. Plug in the drive.** Connect your USB drive to the phone with a USB-C or OTG
adapter. (If nothing happens at all, try a different adapter or cable — a flaky
adapter is the most common reason a drive isn't detected.)

**3. Open it.** Go to the **Desktop** tab → **Manage** → the menu next to the
distro picker → **"Open USB drive…"**. Haven shows *"Opening the USB drive in a
Linux VM — this takes a minute…"*

**4. Wait.** The first time, this takes a few minutes (it downloads a small Linux
image once and sets things up; later opens are quicker). A **live progress line**
right there under Manage shows what it's doing — *"Downloading the Linux image…",
"Booting Linux — the slow step…", "Setting up the VM and mounting your drive…"* —
so you can see it's working, not stuck. You'll get a notification when it's ready:
*"USB drive mounted — open 'USB Drive' in Files."*

**5. Browse.** Switch to the **Files** (or **Connections**) tab and open the new
**"USB: …"** connection. Your drive's partitions are under `/mnt` (e.g.
`/mnt/sda1`). Copy files off, preview them, or open a terminal into the drive.

**6. Eject when done.** Back in **Desktop → Manage**, the same menu now says
**"Eject USB drive"** — tap it to shut the little Linux down and free the drive.
(It also closes by itself if you don't.)

## What works, and what doesn't

**Works:**

- **Linux drives** — ext4, and most partition layouts including GPT.
- **Ordinary drives** — FAT32 / exFAT work here too (though your phone can
  usually read those directly without this feature).
- A full **terminal into the drive**, if you want to poke around with Linux
  commands — it comes free with the connection.

**Doesn't work (yet, or ever):**

- **Encrypted drives (LUKS)** — not supported yet; planned.
- **Writing to the drive** — it's read-only for now, so you can't accidentally
  corrupt it. (Writable access is planned as an explicit opt-in.)
- **Webcams and USB microphones** — these can *never* work through any of Haven's
  USB features, due to a hard limit in Android's USB support (see
  [USB device forwarding](usb.md) for the details).
- **More than one drive at a time** — eject one before opening another.

## Trade-offs and limits

Because your phone isn't "rooted," the little Linux can't use the phone's CPU
directly — it has to be *emulated*, which is **slow**. That's fine for what this
feature is for: **getting files off a drive**. It is *not* meant to be a fast,
everyday Linux desktop. Expect the first open to take a few minutes and file
copies to be unhurried.

If all you need is a normal Linux *desktop* on your phone (not a USB drive),
Haven has a much faster option that doesn't involve a virtual machine — see
[Local Linux on-device](local-linux.md).

## For the agent (MCP)

If you drive Haven with the built-in AI-agent bridge, three tools cover this,
gated on the same **"Open USB drives in a VM"** setting:

- `open_usb_drive` — start the VM for an attached drive (returns immediately;
  the boot continues in the background).
- `list_usb_drives` — list attached USB drives and the VM's progress. While it's
  booting, `vm.stage` carries a human-readable line ("Booting Linux…", etc.) you
  can relay; poll until `vm.phase: ready`, which includes the loopback
  **`profileId`** and the mounted paths.
- `close_usb_drive` — eject and tear everything down.

Once it's ready, browse the drive with the normal file tools (`list_directory`,
`serve_file`) using the returned `profileId`, exactly like any SFTP connection.

## Why this is hard

*(Optional — skip this unless you're curious.)*

Reading a drive like an ext4 disk needs a piece of software called a **kernel
driver**, which only a real operating-system **kernel** can load. A normal phone
app — including Haven's lightweight "Linux on the phone" mode
([proot](local-linux.md)) — has no kernel of its own, so it can never grow that
ability, no matter what you install.

A **virtual machine** is different: it's a complete, real (if pretend) computer,
*with its own kernel*. So it can load the driver and read the drive. The catch is
that running a whole computer-inside-a-computer is expensive, which is why it's
slow on a phone.

Haven already knew how to lend a phone's USB device to a *real* Linux machine over
the network (the [USB/IP feature](usb.md#to-a-remote-host-over-usbip)). This
feature reuses exactly that — except the "real Linux machine" is the little one
running on your own phone, a few inches from the drive. Same plumbing, no network.

Originally requested in
[issue #287](https://github.com/GlassHaven/Haven/issues/287).

---

[← All features](../FEATURES.md) · [USB device forwarding](usb.md) ·
[Local Linux on-device](local-linux.md)
