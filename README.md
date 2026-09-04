<div align="center">

<img src="Android/FlyingCarpet/app/src/main/fc_logo-playstore.png" width="120" alt="白い熊 魔法絨毯 icon" />

# 白い熊 魔法絨毯

**Encrypted, peer-to-peer file transfer — over an ad hoc WiFi hotspot, or over a network you are already on. No cloud, no account.**

A fork of [Flying Carpet](https://github.com/spieglt/FlyingCarpet) with **major additions**: a full
yellow-on-black theme, an in-app *白い熊 魔法絨毯 UI* page that restyles every single surface, a live
transfer readout with speed and ETA, one-tap receiving into the directory you used last, one-zip
Export/Import of everything you have set, a headless backup surface that lets a sister app back
this one up — and put it back on a wiped phone — external font
support, a custom icon, a share-sheet target, and a rebranded Linux desktop build shipped as an
amd64 `.deb` — plus a **5 GHz Wi-Fi Direct hotspot** that transfers three times faster than stock,
Bluetooth password hand-off in Shared Network mode, a say-what-happens dialog when the other
device already has a file, and the Bluetooth work an EMUI phone needs to pair at all.

Installs **side-by-side** with the official Flying Carpet (app id `shiroikuma.mahojutan`, dpkg package
`shiroikuma-mahojutan`).

> **Version 10 is a breaking change.** The wire protocol moved to Noise, so a v10 device cannot
> transfer with a v9 one — update the app on *both* devices. v10 also adds **Shared Network mode**,
> for when both devices are already on the same WiFi or wired network and no hotspot is wanted —
> which this fork makes the **default**, since hotspot mode takes both devices off their network for
> the duration of the transfer.

**📥 Latest release: [`10.0.4+062`](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases/latest)** — [all releases & downloads »](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases)

</div>

---

## 🚀 A hotspot on 5 GHz — three times faster

Stock raises its hotspot with `LocalOnlyHotspot`, which **cannot be asked for a band**: the API that
takes a configuration is reserved for system apps, so an ordinary app gets whatever the framework
picks. On a Huawei Mate XT and a Galaxy Z Fold that is always **2.4 GHz, 20 MHz, 802.11n** — and
setting 5 GHz in the system hotspot settings changes nothing, because that governs a different
access point entirely.

This fork raises the hotspot as a **Wi-Fi Direct group owner** instead and asks for 5 GHz. A group
owner still presents as an ordinary WPA2 access point, so the other device joins it exactly as
before and the transfer protocol is untouched — it is only the way the access point is created that
changes. Measured between those two phones: **17 MB/s → 55 MB/s.**

The band is a request rather than an instruction, so the log names the band actually obtained, and
every way it can fail — refused, unreadable, or simply never arriving — falls back to the old
hotspot automatically.

**Desktop transfers ride the same group.** Stock has the Linux side host for a phone, and its access
point is a NetworkManager profile — which exposes a band and a channel but nothing for width, so it
comes up **20 MHz whatever band you ask for**. The phone hosts instead, and its group negotiates
**80 MHz**: measured between this phone and the desktop, **20.3 MB/s → 47.5 MB/s peak**.

---

## 🎨 Yellow on black, everywhere

The whole app is rebuilt around a high-contrast yellow-on-black look: yellow text, thin yellow borders
with rounded corners, black fills — on the title bar, the Send/Receive and peer-OS buttons, the file
picker, the transfer log, the Bluetooth switch, the progress bar, the About dialog, and the window
itself. Nothing is left half-styled: any property you haven't overridden falls back to the fork's own
default rather than to stock's blue-on-white.

---

## 🛠️ Customize UI — every surface, yours

A dedicated settings page (the *Customize UI* button under the logo) turns the entire interface into
something you can edit without touching code:

- **Per-element text** — rename any label, and set its colour, font, style and size.
- **Per-button colours** — every toggle button (Send, Receive, Hotspot, Shared Network, and each of
  the five peer-OS buttons) carries its *own* selected and unselected fill and text colour, border
  colour, border-width slider and corner-radius slider. No global "accent colour" compromise.
- **Live preview** — changes land on the real UI as you make them.
- **Reset anywhere** — per property, per group, or everything at once.
- **The settings page styles itself**, so you can theme the theming tool.

The page is laid out for scanning: a thin rule between top-level sections, a text-width underline
under every heading, and a fixed indentation ladder from section to element to control — the same
shape on the phone and on the desktop.

---

## 📊 A transfer you can actually read

Stock gives you a bar filling up and nothing else. Both apps now show, above it:

```
12.4 MB / 340 MB  ·  8.7 MB/s
1m 26s elapsed  ·  38s left
```

Two lines, because one wrapped anyway and broke wherever the glyphs happened to land. **Elapsed sits
beside remaining** — remaining answers "how much longer", elapsed answers "has this been going long
enough that something is wrong". The speed is a **rolling five-second rate**, not an average from the
first byte, so it reflects what is happening now and a stall is visible instead of averaged away;
every clock measures the data phase, so waiting on a dialogue never counts as transfer time.

There is also a **second bar for the whole transfer**, so sending a folder no longer means watching
the bar reset to zero twelve times with no idea how far along you are. The second row appears only
when there is more than one file.

And the log now **says which device is being waited on**. Choosing the files to send — or the
directory to receive into — arms this device and then goes quiet until the other one is armed too,
which is indistinguishable from a hang if you do not already know the other end is what is holding
things up. So a selection now prints the action to take over there, and says plainly that this
device is waiting for it.

---

## 📂 Receive where you received last

Receiving used to mean tapping through a directory picker every single time. Beside *Select directory*
there is now a button reading **Receive in “~/tmp”**, which starts listening in the directory you
picked last — no dialog. It survives restarts (Android takes a persistable grant for the tree Uri;
the desktop abbreviates your home directory to `~`).

---

## 📤 Send straight from the share sheet

The Android app answers `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for any type, so files picked
anywhere — a file manager, a gallery, another app — can be sent by choosing 白い熊 魔法絨毯 from the
share sheet. The selection arrives preloaded and the search for the receiving device starts on its
own, so a share is one tap rather than a launch, a mode, and a picker.

---

## 📡 Bluetooth, on a phone with a Japanese name

A BLE advertisement is 31 bytes, and after the flags and a 128-bit service UUID the device name gets
eight of them. Android measures that name with `String.length`, which counts UTF-16 units — so
`白い熊` looks like 3 and is really 9, the packet goes one byte over, and the controller rejects it.
EMUI reports that as advertise error 18 and the phone simply never appears. This fork counts bytes.

Two more, in the same area: the GATT server used to stop advertising on *any* incoming LE connection
— on EMUI a watch or a pair of earbuds is enough — so it went off the air seconds after starting;
it now waits until something reads one of its own characteristics. And a first `connectGatt()` that
comes back with status 133, Android's catch-all, is retried rather than ending the transfer in
silence. Joining the peer's hotspot is retried too, since the system picker gives up after one
empty scan.

Since v10 the rest of the Bluetooth stack — bonding, the Linux side, GATT lifecycle — is upstream's
own, and considerably better than what this fork carried against v9; it is used as-is.

---

## 📍 Nothing to do with where you are

Android hides *every* Bluetooth scan result while the phone's master Location switch is off — no
error, no failed callback, just a receiving phone that searches for ever while the sender advertises
a metre away. Granting the app location permission does not turn that switch on; they are different
things, which is exactly what makes it such a good disguise. This fork declares its Bluetooth scan
`neverForLocation` — a promise to the framework that a scan result is never used to work out where
the phone is — and stops asking for location at all on Android 13 and up, where the hotspot takes
`NEARBY_WIFI_DEVICES` instead. Sending a file has nothing to do with where you are, so the app no
longer asks. On older Androids, where the toggle really does rule, it says so plainly and offers
the setting rather than searching in silence.

---

## 🔑 The password, without the ceremony

With Bluetooth off, the password travels by eye — and the fork makes that a glance rather than a
dialog. The receiver shows a QR code **with the password printed underneath it** — scan it or read it, both
are on screen, and there is no dialog in the way. The sender opens **one dialog that scans and types
at the same time**: a live camera preview sitting directly above the password field, so there is
nothing to back out of to reach the keyboard. Decline the camera, or have none, and the same dialog
is simply the typing dialog.

---

## 🔗 Bluetooth in Shared Network mode, too

Upstream uses Bluetooth only to negotiate hotspot credentials, so in Shared Network mode the switch
is greyed out and the password has to be scanned or typed. Here it stays live in both modes and
becomes the toggle it looks like: **use Bluetooth**, or **scan the QR code / type the password**.
Nothing else changes — the receiving device generates the password either way; it simply hands it
over the BLE link instead of putting it on screen. A line under the switch says which of the two is
about to happen, and who will be doing the scanning.

Getting there meant making the handshake honest about its own time. Discovery no longer waits on
BlueZ to volunteer an event — it reads the adapter's device list every second and requires a live
signal, so a peer that is on the air is found in milliseconds instead of a minute, and a bonded
peer's stale cache entry is never mistaken for one. The probe that connects to a paired device to
re-read its services runs off to the side rather than inside the scan, and only for devices in
range: one switched-off headset used to eat a full minute of every scan. Every remaining wait ticks
in the log with the seconds counted off, on both apps, and a scan held by another program on the
computer — which owns the radio, and can stretch a one-second connection into a failed minute — is
named at the start rather than left to look like our own slowness.

And nothing removes a pairing behind your back any more: that "let's just pair again" reflex
deletes one half of a working bond, and the other device then refuses the next one.

---

## 🙋 “That file is already there” — asked, not decided

Stock decides a name collision on the *receiving* device and says nothing: an identical file is
skipped, a differing one is quietly saved as `(1) name`. Both are decisions made on the device
whose user is not the one watching the transfer. This fork stops and asks on the **sending** side —
**skip**, **overwrite**, or **rename** with a pre-filled `name (copy).ext` — and overwriting really
replaces the file that is there rather than leaving a `(1)` copy beside it.

Asked once, not once per file: tick **Apply to every remaining file** and the answer stands for the
rest of the transfer, so a folder the other device already has is one dialog rather than ten. A
repeated *rename* means “keep both” — each file is named for itself, `name (copy).ext`, since a name
that was typed once cannot be reused.

It needs fields stock does not know, so it is version-guarded: the fork announces itself on the
wire and only switches the exchange on when both ends are running it. A stock or Apple peer
transfers exactly as before and never sees the extra fields.

---

## 📶 Bluetooth that actually pairs

Everything between a Linux desktop and an EMUI phone that stood between “advertising” and a
transfer, found with an HCI trace and both devices' own logs: an advertisement over the 31-byte
budget, an extended advertising set that Android's default legacy-only scan cannot see, a scan
whose 512 ms window in every 5120 ms was an exact harmonic of BlueZ's 1280 ms advertising interval
(so a packet that fell in the gap fell in the gap **for ever**), a GATT read issued twice so the
second was refused because the first was in flight, and a pairing that could not complete because
the phone's stack will not present an incoming numeric comparison. The roles are negotiated now
instead of following send/receive: the phone takes the connecting side, because it is the one that
can insist on the LE transport — the desktop cannot, and a dual-transport bond otherwise sends it
down a classic-Bluetooth link that carries no GATT.

Every wait ticks with the seconds counted off, and the phone writes its transcript to
`Android/data/shiroikuma.mahojutan/files/logs/transcript.log`, because EMUI drops an app's logcat.

---

## 💾 Export / Import — everything, in one `.zip`

The first thing on the UI page is a real backup surface. Point it at a folder once; from then on it
tells you when the last backup was written and hands the whole configuration over in a single
timestamped archive — `shiroikuma-mahojutan_2026-07-25_23-44-06.zip`.

- **Pick what travels**: the main screen's look, the UI page's own look, and your imported font
  files are separate, independently selectable categories.
- **Import merges** rather than overwrites, key by key, so restoring a partial backup never wipes
  what it didn't cover, and importing the same file twice changes nothing the second time.
- **No backup folder set** is shown in red wherever it appears, so an unconfigured backup is
  impossible to overlook.
- The archive knows which app wrote it, so a phone backup is never poured into the desktop app by
  mistake — the two store their colours differently.

---

## 🤖 Backed up without lifting a finger — and restored onto a wiped phone

The Android app answers a headless export request, so an automation app can trigger its backup with
no screen and no tapping, and get back the path, the byte count and a human-readable size. Progress
comes back as **real counts, never a percentage**, naming the category being written so the caller
can light up the right row. A long export can be **stopped from where it was started**, and a
cancelled run leaves the backup folder exactly as it found it — the archive is written to a `.part`
file and renamed into place only once it is complete, so a half-finished backup can never be mistaken
for the newest one.

It also answers a second, stricter door: a companion app can take this app's data **and give it
back**, which is what makes a clean phone recoverable. That door never takes a path — the caller
opens the destination and passes a file descriptor, so the backup stays encrypted and checksummed by
the app that owns it — and it never trusts a name: the caller is checked by exact package, by the uid
the kernel reports, and against a **pinned signing certificate**. Restoring is only possible there,
never over the open door.

**No secret to paste.** The switch ships **on** and 「Use authorization token?」 ships **off**, because
a pasted token cannot survive the wipe this feature exists to recover from. Turn it on and a caller
must present the 24-byte token as well — generated on the phone, compared in constant time, and kept
in a file the backup itself never touches, so it can never leak into an archive. A token sent while
the switch is off is quietly ignored rather than refused, so nothing breaks when you change your mind.

---

## 🔤 Bring your own fonts

Every font menu ends with **Add external font…**. Pick a `.ttf`/`.otf` and it is stored with the app,
then offered — rendered in its own glyphs — in every font dropdown from then on.

---

## 🖥️ A real Linux desktop build

The desktop app is rebranded as thoroughly as the phone app and shipped as an **amd64 `.deb`**: dpkg
package and binary `shiroikuma-mahojutan`, launcher entry `白い熊 魔法絨毯`, the fork's yellow-traced
carpet icon, and the same yellow-on-black theme and *Customize UI* page as Android — a port of the
same catalog, not a lookalike. It installs alongside the official desktop build.

---

## 🖼️ A carpet of our own

Custom launcher icon: the 「魔法」 kanji on a traced flying carpet, yellow on black, regenerated across
every density (adaptive foreground, legacy launcher, round mask, store asset), plus a matching
yellow-traced icon set for the desktop app. The in-app logo is tinted with a luminance-preserving
colorize, so the carpet's line detail survives the recolour — while QR codes are never tinted, and
stay scannable.

---

## 📦 Predictable builds

Both artifacts are versioned `<upstream release>+<build number>`: the build number increases with
every delivered build and normally resets on each upstream rebase, so no two builds share a filename
and every install lands as a clean upgrade.

The reset has one condition. Android's `versionCode` is derived as `<upstream's own versionCode> ×
10000 + <build number>`, so a reset only stays monotonic while upstream's number climbs alongside the
release. When a release leaves it untouched — as `10.0.4` did, bumping only the Apple apps — the
counter **carries on instead of resetting**, since resetting would build a lower `versionCode` than
the one already installed and Android would refuse the APK as a downgrade. Upgrades winning comes
first; the tidy `+001` does not.

---

## Built on Flying Carpet

A fork of [Flying Carpet](https://github.com/spieglt/FlyingCarpet) by Theron Spiegl (app id
`shiroikuma.mahojutan`, so it coexists with the official build). All the hard parts — the Noise
encrypted transport, the authenticated discovery behind Shared Network mode, the ad hoc hotspot
setup, and the Bluetooth LE credential exchange that makes phone-to-laptop transfers work with no
network at all — are upstream's work, and this fork tracks its releases. The code remains under the
**GPL-3.0**.

## Building

```bash
git clone git@github.com:ShiroiKuma0/shiroikuma-mahojutan.git
cd shiroikuma-mahojutan

# Android (signed release APK; needs JDK 21 + the Android SDK)
cd Android/FlyingCarpet
sh ./gradlew :app:assembleRelease

# Linux desktop (amd64 .deb; needs `cargo install tauri-cli` and the webkit2gtk-4.1 dev libs)
cd ../..
cargo tauri build --bundles deb
```

Android release signing reads a gitignored `Android/FlyingCarpet/keystore.properties` (or `SIGNING_*`
environment variables); without either, the APK is unsigned and will not install.
