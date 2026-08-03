# Changelog

All notable changes this fork makes on top of stock
[Flying Carpet](https://github.com/spieglt/FlyingCarpet). Versions are
`<upstream release>+<fork build number>`; the build number resets on each upstream rebase and
increases with every delivered build. The Android and desktop artifacts share one counter — the
same code always builds as the same `+N` on both, and since `+22` every delivered `+N` ships both
artifacts as a pair.

## 9.0.10+052 — 2026-08-03

Built on upstream **Flying Carpet 9.0.10**. Bluetooth transfers between the Android app and the
Linux desktop app now work end to end, and a transfer tells you what it is doing while it runs.

### Bluetooth — Android
- Advertising failed outright with EMUI error 18 (HCI `0x12`, invalid command parameters). The
  advertisement included the adapter name, guarded by `name.length <= 8`, which counts UTF-16 units:
  “白い熊” is 3 of those but **9 UTF-8 bytes**, overflowing the 31-byte packet by one. Measured in
  bytes now, and the log says when the name is dropped.
- The GATT server stopped advertising on *any* incoming LE connection. On EMUI that callback fires
  for links unrelated to this app — a watch, earbuds, a system service — taking the sender off the
  air seconds after it started. It now stops only once something reads or writes one of our own
  characteristics, which only the real peer does.
- Advertise and scan failures are reported by name instead of a bare integer, and say what to do
  next rather than silently ending the transfer.
- The hotspot join is retried automatically: the system network picker gives up after one empty
  scan, which is the “no device found, hit retry” everyone runs into. A WiFi scan is also requested
  before the network request, so the framework has fresh results to match against.
- The pairing is cleared on both sides after a transfer, in step with the desktop.

### Bluetooth — Linux desktop
- `scan()` returned the first device BlueZ announced, including devices it merely knows about — a
  paired headset in the room was enough to end a transfer. It now requires our service UUID, prefers
  whatever is actually on the air (by RSSI), and remembers what it has rejected.
- A wrong candidate is no longer fatal: each gets a bounded attempt covering both the characteristic
  lookup and the handshake, generous enough for a passkey to be typed on both devices.
- Discovery is LE-only. With `Auto`, BlueZ merges the peer's BR/EDR record into the same device and
  `Connect()` brings up classic profiles instead of an ATT link, after which every read fails with
  “Not connected”.
- Reading a characteristic's properties that early returns “No such property 'MTU'” — a diagnostic
  print that was aborting the whole handshake. Diagnostics can no longer fail a transfer.
- The device is removed after each transfer, so every transfer pairs afresh. Reusing the bond was
  tried twice and cannot work: BlueZ stops announcing a paired peer during discovery, and its
  identity record does not carry our service UUID, so a bonded peer is unfindable.
- The hotspot is brought up **during** the handshake, before the peer is told the credentials, so it
  is beaconing by the time the peer looks. It used to be created after the handshake returned,
  leaving the peer scanning for an SSID that did not exist yet.

### Transfer progress — both apps
- A readout above the bar: bytes sent of total, current speed, and ETA.
- A **second bar and second line for the whole transfer**, so multi-file transfers show overall
  position instead of restarting at zero for every file. Shown only when there is more than one file.
- Sending is byte-exact (all sizes known up front); receiving is weighted by file count and reports
  bytes received, because the wire protocol carries filename + size per file and a grand total would
  break compatibility with the other platforms.
- Redraws throttled to 4 Hz.
- The desktop cleared neither line at the end of a transfer, leaving stale figures on screen.

### Receiving directory — both apps
- A second button beside *Select directory* reads **Receive in “~/tmp”** and starts listening in the
  directory picked last time, with no dialog. Android persists the tree Uri with a persistable grant
  (without which it would be a dead link after a restart); the desktop stores the path and
  abbreviates `$HOME` to `~`.
- “Select Folder” is now “Select directory”, in normal capitalization — Android was shouting it via
  Material's default `allCaps` — and the desktop button no longer spans the full width.

### UI & wording
- The *Customize UI* pill is **白い熊 魔法絨毯 UI** on both apps.
- The desktop page uses the same Kanji logo as Android instead of the stock Flying Carpet icon.
- “Device disconnected” is now “Bluetooth connection released”: it is the normal handover to WiFi,
  and it read like a failure.

### Packaging
- The shared build counter is **zero-padded to three digits** (`+052`, never `+52`) in the
  versionName, both artifact filenames, and the release tag, so builds sort in build order. The
  `versionCode` keeps the plain integer. Builds up to `+25` predate the rule and keep their names.

## 9.0.10+24 — 2026-07-25

Built on upstream **Flying Carpet 9.0.10**. This release gives both apps a real backup surface and
brings their settings pages to one shared house style.

**Artifacts**

- Android: `shiroikuma-mahojutan_9.0.10+24_arm64-v8a.apk` (app id `shiroikuma.mahojutan`,
  versionCode `210024`, signed with the fork keystore, universal — the app has no native code).
- Linux desktop: `shiroikuma-mahojutan_9.0.10+24_amd64.deb` (dpkg package and binary
  `shiroikuma-mahojutan`).

### Major features

- **Export / Import, on both apps** — the first section of the UI page. A settable backup folder,
  the newest backup in it reported whenever the page or the panel opens, per-category checkboxes
  under a *Select all*, and a button bar with *Cancel* alone on the left and *Import* / *Export*
  grouped on the right, all as round pills.
- **One archive per export** — a single timestamped
  `shiroikuma-mahojutan_<yyyy-MM-dd_HH-mm-ss>.zip` holding a `manifest.json` plus one entry per
  category. Nothing is written beside it and nothing is split across files.
- **Three independently selectable categories** — the main screen's appearance, the UI page's own
  appearance, and the imported font files (the real `.ttf`/`.otf` bytes, not just their names).
- **Import merges per key** rather than replacing wholesale, so restoring a backup never destroys
  settings it did not cover, and re-importing the same file is idempotent. Categories the archive
  does not carry are skipped silently.
- **Cross-platform guard** — both apps write the same file name, so the manifest records which one
  produced the archive and an import of the other kind is refused with a message instead of
  corrupting settings (an Android colour is an ARGB integer, a desktop one is `#rrggbb`).
- **Automation export (Android)** — the sister-app state-export contract, so an external automation
  app can back this one up headlessly: `EXPORT_STATE` and `LIST_CATEGORIES` intents, gated by a
  master switch that defaults to **off** and a token that must also match. The reply is a fresh
  broadcast carrying the absolute path, the real byte count, a human-readable size and the category
  count; failures reply with a specific reason (`automation disabled`, `bad token`, `no-directory`,
  `no-storage-access`, `unknown category in items: …`). Progress broadcasts carry real counts and a
  unit, never a percentage, throttled to one per 500 ms, with the final one always sent. The export
  runs off the main thread under `goAsync()`, and exactly one terminal reply is ever sent.
- **The token never travels** — it lives in its own preferences file, which the backup engine does
  not read, so it cannot end up inside an archive. It is 24 `SecureRandom` bytes, generated on first
  read, compared in constant time, shown abbreviated, copied whole on tap, and regenerable.

### UI and theming

- Both settings pages are restyled to one house look: a full-width hairline marking the boundary
  between top-level sections, a **text-width** underline under every heading rather than a
  full-width rule, and a fixed indentation ladder — section, element, control — replacing the
  earlier very deep single indent.
- The backup folder is shown in **red** everywhere it is unset: the UI page's summary line, the
  panel's status line, and the folder box's own border.
- Export and import finish in a black, yellow-bordered dialog. Acknowledging a **successful** one
  closes the whole chain — the dialog, the panel beneath it, and the UI page — while a failure
  closes only the dialog, leaving the panel open so the problem can be fixed on the spot. The import
  dialog additionally offers *Restart now*.
- Folder entry, the folder browser and the backup chooser are all fork-drawn black-and-yellow
  surfaces rather than platform-default dialogs.

### Desktop app

- The backup engine runs in the frontend, because the desktop settings live in `localStorage` where
  the Rust side cannot see them; Rust supplies only what a webview cannot do — read a file, write a
  file, list a folder, restart the app.
- Archive payloads cross the IPC bridge base64-encoded instead of as a JSON array of numbers, with a
  small hand-written codec on the Rust side, so no new crate is pulled in for it.
- The ZIP is written and read directly (stored entries, own CRC-32), again with no added dependency.

### Fixes and behaviour

- The desktop QR code is drawn inside a yellow quiet zone with yellow light modules, so it is
  black-on-yellow with the 4-module margin the format requires — previously it was drawn straight
  onto the black window with no quiet zone at all. The container is reset when the logo returns.

### Packaging

- Every delivered build now ships **both** artifacts for the same `+N`; the two build skills each
  finish by running the other, which is what earlier gaps in the `.deb` and APK lines came from.

## 9.0.10+20 — 2026-07-23

Built on upstream **Flying Carpet 9.0.10**. This release adds the desktop app to the fork: it is now
rebranded and themed just like the Android app, and ships as an amd64 `.deb`.

**Artifacts**

- Android: `shiroikuma-mahojutan_9.0.10+20_arm64-v8a.apk` (app id `shiroikuma.mahojutan`,
  versionCode `210020`, signed with the fork keystore, universal — the app has no native code).
- Linux desktop: `shiroikuma-mahojutan_9.0.10+20_amd64.deb` (dpkg package and binary
  `shiroikuma-mahojutan`, first build of the desktop line — same `+N` as the APK, from the shared
  build counter).

### Major features

- **Customize UI page** — an in-app settings screen (opened from the *Customize UI* button under the
  logo) that restyles every surface of the app: per-element label text, text colour, font family,
  font style and text size, each with a live preview.
- **Per-element colours and geometry** — every toggle button (Send, Receive, and the Android / iOS /
  Linux / macOS / Windows peer buttons) has its own selected and unselected fill colour, selected and
  unselected text colour, border colour, border-width slider and corner-radius slider, configured in
  that button's own block. The Select Files button, Cancel button, output log box, Send Folder
  checkbox, Bluetooth switch, progress bar, About dialog and window background are configurable the
  same way.
- **External fonts** — every font dropdown ends with *Add external font…*, which copies a picked
  `.ttf`/`.otf` into app storage and then offers it, rendered in its own glyphs, in every font menu.
- **Desktop app forked and shipped** — the Tauri/Rust Linux app is rebranded and carries a port of
  the same theme and Customize UI engine, delivered as an amd64 `.deb`.

### UI and theming

- Yellow-on-black defaults everywhere: yellow text, yellow 1 dp borders, black backgrounds, 10 dp
  rounded corners — overridable per property, with border width and corner radius as sliders that
  start at zero, so a border can be removed entirely.
- Settings distinguish "unset → fork default" from an explicit `0`, so a fresh install is fully
  themed rather than half-styled.
- The settings page is organised into logical sections (Main page, Title bar, Step instructions, Peer
  OS buttons, Send Folder checkbox, Cancel button, Transfer output, Window, About page, and the
  settings page itself), folding each element's text, colours and borders into one block.
- The settings page is itself themeable and uses a yellow-on-black dark theme so its native controls
  stay legible; Done / Reset / Apply are styled buttons and are configurable too.
- Per-group *Reset to default* buttons throughout, plus a global *Reset all to defaults*.
- The About dialog is themeable (background, title, body) and defaults to yellow on black.
- The app logo is tinted with a luminance-preserving colorize, which keeps the carpet's "FC" line
  detail visible instead of flattening it into a solid shape; QR codes are never tinted so they stay
  scannable.
- The main title reads 白い熊 魔法絨毯, and the version label shows the full fork version
  (e.g. `9.0.10+20`) with no "Version" prefix.
- *Customize UI* is a button under the logo, right-justified, in both orientations; landscape moves
  the version and About links onto the top row.
- Deeper indentation on the settings page (controls at 144 dp, sub-items at 72 dp) for scannability.
- Settings persist in `SharedPreferences` (store `shiroikuma_ui`) and are applied in
  `MainActivity.onResume`.

### Desktop app (Linux)

- dpkg package and `/usr/bin` binary renamed to `shiroikuma-mahojutan`; installs side-by-side with the
  official desktop build.
- Launcher entry, window title and in-app heading all read 白い熊 魔法絨毯, via a dedicated
  `.desktop` template wired in through `bundle.linux.deb.desktopTemplate`.
- Full icon set regenerated from the fork's yellow-traced carpet.
- Yellow-on-black theme and the Customize UI page ported from the Android engine, with the same
  catalog, the same "unset inherits the fork default" semantics, and live application; settings
  persist in `localStorage` under `shiroikuma_ui`.
- External fonts supported on the desktop too, stored with the app and registered as web fonts.
- The About box moves from a native alert to a themeable in-page dialog; the version label reads the
  fork version straight from the app.
- Build: `cargo tauri build --bundles deb` from the repo root produces the `.deb`; its filename, dpkg
  `Version:` field and in-app version label all come from one value in `tauri.conf.json`.

### Icon and branding

- Custom launcher icon: the 「魔法」 kanji on a traced flying carpet, yellow on black, replacing the
  stock logo and the stock green-Android legacy placeholder.
- Regenerated across all densities: adaptive-icon foreground, legacy `ic_launcher`, round mask, and
  the store asset; a dedicated black adaptive background is used so the night-theme asset stays
  untouched.
- Matching yellow-traced icon set for the desktop app.
- Launcher label 白い熊 魔法絨毯.

### Packaging and versioning

- `applicationId shiroikuma.mahojutan` while the code namespace stays `dev.spiegl.flyingcarpet`, so
  the fork installs alongside the official app with no source edits.
- Fork versioning: `versionName = "<release>+<buildNumber>"`, where the release tracks the upstream
  `v*` tag rather than the value upstream leaves in the Android `build.gradle` (which lagged at
  `9.0.8` at the `v9.0.10` release); `versionCode = <upstream Android versionCode> * 10000 +
  <buildNumber>`, keeping sideloaded upgrades monotonic across upstream bumps.
- The desktop `.deb` carries the same `+N` as the APK — one build counter is shared across both
  artifacts, so the same code always builds as the same version; dpkg orders
  `9.0.10+20 > 9.0.10+1 > 9.0.10`, so each build installs as an upgrade.
- Every build gets a unique filename — build numbers are never reused and older artifacts are never
  overwritten.
- Non-interactive release signing from a gitignored `keystore.properties`, with `SIGNING_*`
  environment variables as a fallback; clean APK filenames via `archivesBaseName`.
- Upstream's `FUNDING.yml` removed — the fork does not inherit upstream funding links.

### Repository

- `CLAUDE.md` documents the fork model, branch strategy (`main` mirrors upstream fast-forward only,
  `custom` carries the fork and is rebased onto each release), versioning and hard rules.
- Repo skills automate the recurring work: `build-apk`, `build-deb`, and `upstream-new-version`
  (rebase onto a new upstream release, re-derive both version lines, rebuild both artifacts).
- Commits in this repository carry no AI-assistant attribution trailers.
