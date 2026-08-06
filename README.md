<div align="center">

<img src="Android/FlyingCarpet/app/src/main/fc_logo-playstore.png" width="120" alt="白い熊 魔法絨毯 icon" />

# 白い熊 魔法絨毯

**Encrypted, peer-to-peer file transfer — over an ad hoc WiFi hotspot, or over a network you are already on. No cloud, no account.**

A fork of [Flying Carpet](https://github.com/spieglt/FlyingCarpet) with **major additions**: a full
yellow-on-black theme, an in-app *白い熊 魔法絨毯 UI* page that restyles every single surface, a live
transfer readout with speed and ETA, one-tap receiving into the directory you used last, one-zip
Export/Import of everything you have set, a token-gated hook for headless backups, external font
support, a custom icon, a share-sheet target, and a rebranded Linux desktop build shipped as an
amd64 `.deb` — plus the Bluetooth fixes an EMUI phone with a Japanese device name needs.

Installs **side-by-side** with the official Flying Carpet (app id `shiroikuma.mahojutan`, dpkg package
`shiroikuma-mahojutan`).

> **Version 10 is a breaking change.** The wire protocol moved to Noise, so a v10 device cannot
> transfer with a v9 one — update the app on *both* devices. v10 also adds **Shared Network mode**,
> for when both devices are already on the same WiFi or wired network and no hotspot is wanted.

**📥 Latest release: [`10.0.3+001`](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases/latest)** — [all releases & downloads »](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases)

</div>

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
- **Per-button colours** — every toggle button (Send, Receive, and each of the five peer-OS buttons)
  carries its *own* selected and unselected fill and text colour, border colour, border-width slider
  and corner-radius slider. No global "accent colour" compromise.
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
12.4 MB / 340 MB  ·  8.7 MB/s  ·  38s left
File 3 of 12  ·  512 MB / 2.10 GB  ·  8.7 MB/s  ·  3m 04s left
```

— and a **second bar for the whole transfer**, so sending a folder no longer means watching the bar
reset to zero twelve times with no idea how far along you are. The second row appears only when there
is more than one file.

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

## 🤖 Backed up without lifting a finger

The Android app answers a token-gated intent, so an automation app can trigger its export headlessly
— no screen, no tapping — and get back the path, the byte count and a human-readable size. Progress
comes back as real counts, never a percentage. It stays completely closed until you turn the switch
on: the master switch defaults to **off**, the 24-byte token is generated on the phone, compared in
constant time, and lives in a file the backup itself never touches, so it can never leak into an
archive.

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

Both artifacts are versioned `<upstream release>+<build number>`: the build number resets on every
upstream rebase and increases with every delivered build, so no two builds share a filename and every
install lands as a clean upgrade.

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
