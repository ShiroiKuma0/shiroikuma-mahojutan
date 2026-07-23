# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## This is a rebranded fork (read first)

This is the **shiroikuma personal fork** of [spieglt/FlyingCarpet](https://github.com/spieglt/FlyingCarpet),
following the same model as the other `shiroikuma-*` sister repos. **Both apps are forked and rebranded**:
the Android app (APK for the phone) and the Tauri/Rust Linux desktop app (amd64 `.deb` for 白い熊's Tuxedo
OS machine — this host). Each upstream release yields both artifacts — see the upstream-new-version skill.

| Item | Value |
|------|-------|
| Upstream | `spieglt/FlyingCarpet` (remote `upstream`, HTTPS, fetch-only) |
| Fork | `git@github.com:ShiroiKuma0/shiroikuma-mahojutan` (remote `origin`, push here) |
| Installed app ID | `shiroikuma.mahojutan` (was `dev.spiegl.flyingcarpet`) |
| Code namespace (unchanged) | `dev.spiegl.flyingcarpet` (R class / Kotlin package — no source edits) |
| App launcher label | `白い熊 魔法絨毯` (`app_name` in `Android/FlyingCarpet/app/src/main/res/values/strings.xml`) |
| APK filename | `shiroikuma-mahojutan_<release>+<buildNumber>_arm64-v8a.apk` (e.g. `shiroikuma-mahojutan_9.0.10+1_arm64-v8a.apk`) |
| Desktop `.deb` | `shiroikuma-mahojutan_<release>+<buildNumber>_amd64.deb` (e.g. `shiroikuma-mahojutan_9.0.10+1_amd64.deb`) — `cargo tauri build --bundles deb` from the repo root, copied to `~/tmp` (installed locally with apt, no adb/scp) |
| Desktop dpkg package / binary | `shiroikuma-mahojutan` (`productName` / `mainBinaryName` in `Flying Carpet/src-tauri/tauri.conf.json`) → `/usr/bin/shiroikuma-mahojutan` |
| Desktop app name | `白い熊 魔法絨毯` — launcher entry (`src-tauri/shiroikuma-mahojutan.desktop` template), window `title` in `tauri.conf.json`, `<title>`/`<h1>` in `Flying Carpet/src/index.html` |
| Desktop icon | the yellow-traced carpet (yellow line drawing on black, source commit `191ab66`), full set regenerated via `cargo tauri icon` into `Flying Carpet/src-tauri/icons/` |
| Desktop fork UI | yellow-on-black theme + “Customize UI” page — the desktop port of the Android fork UI (`Appearance.kt`/`Settings.kt` model): `Flying Carpet/src/customize.js` + `customize.css`, hooked into `index.html`/`main.js` (About dialog, start-button labels, logo tint); settings persist in localStorage key `shiroikuma_ui`, unset = fork default |
| Desktop build tool | `cargo install tauri-cli` (v2); webkit2gtk-4.1/gtk/soup dev libs already installed on this host |
| Keystore | `~/.android-keystores/shiroikuma-mahojutan.jks` (alias `mahojutan`); creds in gitignored `Android/FlyingCarpet/keystore.properties` |
| Build JDK / SDK | JDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), SDK `/home/shiroikuma/android-sdk` |

### Branch model

- `main` — mirrors `upstream/main`, **fast-forward only**, never carries our changes.
- `custom` — carries all fork customizations, **rebased onto each new upstream release**. Develop here.

### Versioning

**Both artifacts** — APK and `.deb` — are versioned `"<release>+<buildNumber>"`, where `<release>` is the
FlyingCarpet **release** version (the latest `v*` tag / desktop `Cargo.toml`), **not** the stale value
upstream leaves in Android's `build.gradle` (e.g. Android said `9.0.8` at the `v9.0.10` release).
`buildNumber` resets to `1` on each upstream rebase and goes **+1 for every delivered build** — never
reuse a number, never overwrite an older artifact in `~/tmp`.

- **Android** (`Android/FlyingCarpet/app/build.gradle`, three values at the top): `versionName` =
  `"<releaseVersionName>+<buildNumber>"`; `versionCode` = `<upstream Android versionCode> * 10000 +
  <buildNumber>` (currently `21 * 10000 + 19 = 210019`).
- **Desktop** (`Flying Carpet/src-tauri/tauri.conf.json`, the `version` field — the single source of
  truth): `"<release>+<buildNumber>"`, e.g. `"9.0.10+1"`. Tauri feeds it straight into the `.deb`
  filename and its `Version:` control field, and the app's title-bar version label reads it back via
  `getVersion()`. dpkg orders `9.0.10+2 > 9.0.10+1`, so each build installs as an upgrade.
- The **two counters are independent**: an APK build bumps only Android's `buildNumber`, a `.deb` build
  only the desktop `version`. Android is at `+19`, the desktop line started at `+1` (2026-07-22).

### Skills (authoritative for project specifics)

- `.claude/skills/build-apk` — build the signed release APK, copy to `~/tmp`, optionally `adb push`.
- `.claude/skills/build-deb` — bump the desktop `+N`, build the amd64 `.deb`, copy to `~/tmp`.
- `.claude/skills/upstream-new-version` — rebase `custom` onto a new upstream release + re-derive versions,
  then build both artifacts: the Android `+1` APK and the rebranded desktop amd64 `.deb`.

### Hard rules

- **Never `git commit` / `git push` unprompted; never `adb install`.** Build, copy to `~/tmp`, and only
  `adb push` to `/sdcard/tmp/` after asking — the user installs from the phone's file manager and tests
  before anything is committed. **Always ask the adb-push question as an explicit yes/no prompt via the
  `AskUserQuestion` tool** (a Yes/No choice), never as plain prose — and wait for the answer before pushing.
  Push (`--force-with-lease origin custom`, since rebases rewrite history) only on an explicit **"Push"**.

---

## What this is

Flying Carpet: encrypted, peer-to-peer file transfer between Android, iOS, Linux, macOS, and Windows over an ad hoc WiFi hotspot — no shared network or internet required. Bluetooth LE (added in v9) is used optionally to negotiate WiFi credentials before the transfer. This repo contains the **desktop** (Linux/Windows) and **Android** implementations. The iOS/macOS (Swift) codebase is **not public** and lives elsewhere; the Go predecessor was rewritten in Rust.

## Repository layout

This is a Cargo workspace (`Cargo.toml` at root) plus an independent Android Gradle project.

- `core/` — `flying-carpet-core` crate. The shared, platform-agnostic transfer engine for **desktop only** (Linux + Windows). This is where most logic lives.
- `Flying Carpet/src-tauri/` — `flying-carpet` crate, the Tauri v2 desktop app (thin wrapper exposing `core` to the GUI via `#[tauri::command]`).
- `Flying Carpet/src/` — desktop frontend: plain HTML/CSS/vanilla JS (`main.js`), no build step, no framework. Talks to Rust via `window.__TAURI__`. The fork adds `customize.js`/`customize.css` (yellow-on-black theme + the Customize UI page, mirroring the Android fork UI).
- `Android/FlyingCarpet/` — standalone Kotlin/Android app (see "Android is independent" below).
- `fastlane/` — F-Droid/Play Store metadata only.

## Build, run, test

### Desktop (Rust + Tauri)
Run all commands from the **repo root** (the workspace root).

```bash
cargo tauri dev          # run dev build of the desktop app (needs `cargo install tauri-cli`)
cargo tauri build        # build release artifacts (.AppImage/.deb on Linux, .msi/.exe on Windows)
cargo build -p flying-carpet-core    # compile just the core crate
cargo test  -p flying-carpet-core    # run core unit tests
cargo test  -p flying-carpet-core utils::tests::size_readable   # run a single test
```

There is no JS build step or `beforeDevCommand` — Tauri serves `Flying Carpet/src/` directly (`frontendDist` in `tauri.conf.json`). Edit `main.js` and reload.

Linux build needs system libs (webkit2gtk, libsoup, gdk, etc.) — see the Ubuntu example in `README.md`. The platform-specific `core` module is selected at compile time, so building on Linux compiles `core/src/linux/*` and on Windows compiles `core/src/windows/*` — **you cannot exercise the Windows networking/BLE code from a Linux host and vice versa.**

### Android
```bash
cd Android/FlyingCarpet
./gradlew assembleDebug     # build debug APK
./gradlew test              # JVM unit tests
./gradlew connectedAndroidTest   # instrumented tests (needs device/emulator)
```
Requires Android 10 / API 29+ (uses the `LocalOnlyHotspot` API).

## Architecture

### Desktop core: the transfer state machine
`core/src/lib.rs` is the entry point. `start_transfer()` drives the whole flow and is generic over a `UI` trait — the **only** coupling between the engine and any frontend. Implement `UI` (5 methods: `output`, `show_progress_bar`, `update_progress_bar`, `enable_ui`, `show_pin`) to host the core anywhere. The Tauri app's impl is `GUI` in `Flying Carpet/src-tauri/src/main.rs`, which forwards each call as an emitted window event that `main.js` listens for.

Transfer flow in `start_transfer()`:
1. (Optional) `bluetooth::negotiate_bluetooth()` — exchange peer OS + SSID + password over BLE GATT.
2. `network::connect_to_peer()` — one side **hosts** a hotspot, the other **joins**. Returns a `PeerResource` (`WifiClient(gateway_ip)` for the joiner, `WindowsHotspot`/`LinuxHotspot` for the host).
3. `start_tcp()` — TCP on **port 3290**. Host binds/listens; client connects to the gateway IP.
4. `confirm_version()` then `confirm_mode()` — handshake over the socket. Versions are compatible if `peer_version >= 8` (`utils::is_compatible`); mismatched send/receive selections error out.
5. `sending::send_file()` / `receiving::receive_file()` per file.

`Transfer` (in `lib.rs`) is the shared mutable state held in Tauri's managed state: the cancel `JoinHandle`, the active hotspot, the SSID, and a BLE-pairing mpsc channel. `clean_up_transfer()` tears down the TCP stream and hotspot afterward.

### Wire protocol (must stay identical across all 5 platforms)
Because Android and Swift reimplement this independently, **any change here is a cross-platform breaking change**:
- Files are sent in 1 MB chunks (`CHUNKSIZE` in `lib.rs`). Each chunk: a 12-byte AES-GCM nonce prepended to the ciphertext, length-prefixed with a `u64`. A chunk length of `0` signals end-of-file.
- Encryption: **AES-256-GCM**. The key is `SHA-256(password)` (see the README FAQ for why it's not a PBKDF) and the hotspot SSID is `flyingCarpet_<first 2 key bytes as hex>` (`utils::get_key_and_ssid`).
- Multi-file: sender writes file count as `u64`, then per file: filename length + filename + size, then a "do you already have this?" exchange (receiver hashes its copy; transfer is skipped if SHA-256 matches).
- All integers on the wire are `u64` via tokio's `write_u64`/`read_u64`.
- BLE: fixed GATT UUIDs (`SERVICE_UUID` + OS/SSID/Password characteristics) defined identically in `core/src/{linux,windows}/bluetooth.rs`. The **sender** acts as the BLE peripheral/GATT server (see README footnote on the macOS↔Linux pairing limitation).

### Platform abstraction
`core/src/lib.rs` uses `#[cfg_attr(target_os = ..., path = ...)]` to map `mod network` and `mod bluetooth` to either `core/src/linux/*` or `core/src/windows/*`. **Both platform implementations must expose the same function signatures** (e.g. `connect_to_peer`, `stop_hotspot`, `get_wifi_interfaces`, `negotiate_bluetooth`, `check_support`) — `lib.rs`, `sending.rs`, and `receiving.rs` are platform-neutral and call into them. Each platform splits BLE into `central.rs` (client/GATT-client role) and `peripheral.rs` (server role). Linux uses `bluer` (BlueZ) and shells out to `nmcli` for WiFi; Windows uses `windows-rs` (WiFi Direct / WinSock / WlanAPI) and `wifidirect-legacy-ap`.

### Errors
Everything in `core` returns `Result<_, FCError>` (`core/src/error.rs`), a single string-wrapping error type with `From` impls for the common error kinds. Use the `fc_error("msg")` helper to construct one. The Tauri commands convert these to `Option<String>` for JS (null = success).

### Android is independent
`Android/` does **not** use the Rust `core` via FFI/JNI — it's a **full Kotlin reimplementation** of the same wire + BLE protocol (`Send.kt`, `Receive.kt`, `Bluetooth.kt`, `MainViewModel.kt`). When you change the protocol, encryption, or BLE UUIDs in `core`, you must mirror the change here (and the change should also be reflected in the non-public Swift codebase). Comments in `Send.kt`/`Receive.kt` explicitly note where the Kotlin mirrors the Rust/Swift behavior.

## Versioning gotcha
The version number lives in **four** places and they must be bumped together: `core/Cargo.toml`, `Flying Carpet/src-tauri/Cargo.toml`, `Flying Carpet/src-tauri/tauri.conf.json`, and `MAJOR_VERSION` in `core/src/lib.rs` (this last one is the on-the-wire compatibility number — only the major version is checked). Android has its own `versionCode`/`versionName` in `Android/FlyingCarpet/app/build.gradle`. The release commit convention is `version X.Y.Z: <summary>`.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
