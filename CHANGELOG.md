# Changelog

All notable changes this fork makes on top of stock
[Flying Carpet](https://github.com/spieglt/FlyingCarpet). Versions are
`<upstream release>+<fork build number>`; the build number resets on each upstream rebase and
increases with every delivered build. The Android and desktop artifacts share one counter — the
same code always builds as the same `+N` on both, though not every `+N` ships both artifacts.

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
