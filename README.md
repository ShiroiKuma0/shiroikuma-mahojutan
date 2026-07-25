<div align="center">

<img src="Android/FlyingCarpet/app/src/main/fc_logo-playstore.png" width="120" alt="白い熊 魔法絨毯 icon" />

# 白い熊 魔法絨毯

**Encrypted, peer-to-peer file transfer over an ad hoc WiFi hotspot — no shared network, no cloud, no account.**

A fork of [Flying Carpet](https://github.com/spieglt/FlyingCarpet) with **major additions**: a full
yellow-on-black theme, an in-app *Customize UI* page that restyles every single surface, one-zip
Export/Import of everything you have set, a token-gated hook for headless backups, external font
support, a custom icon, and a rebranded Linux desktop build shipped as an amd64 `.deb`.

Installs **side-by-side** with the official Flying Carpet (app id `shiroikuma.mahojutan`, dpkg package
`shiroikuma-mahojutan`).

**📥 Latest release: [`9.0.10+24`](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases/latest)** — [all releases & downloads »](https://github.com/ShiroiKuma0/shiroikuma-mahojutan/releases)

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
`shiroikuma.mahojutan`, so it coexists with the official build). All the hard parts — the AES-256-GCM
wire protocol, the ad hoc hotspot setup, and the Bluetooth LE credential exchange that make
phone-to-laptop transfers work without any shared network — are upstream's work, and this fork tracks
its releases. The code remains under the **GPL-3.0**.

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
