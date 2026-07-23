---
name: build-deb
description: Build the rebranded amd64 .deb of the shiroikuma 魔法絨毯 (Flying Carpet) Tauri desktop app for 白い熊's Tuxedo OS machine, bumping the fork +N build number first, and copy it to ~/tmp. Use whenever the user asks to build the desktop app, the deb, or the Linux app — AND proactively (without being asked) after completing any change to the desktop app (Rust, frontend, icons, tauri.conf.json), so there is always a fresh .deb to install.
---

# Build the rebranded desktop `.deb` (amd64)

This is the **desktop** (Tauri/Rust) app of the FlyingCarpet fork (`Flying Carpet/` + `core/`),
rebranded exactly like the Android app. The Android APK is built by the separate **build-apk** skill;
the two artifacts carry **independent** `+N` build counters.

The `.deb` targets **this** machine (白い熊's Tuxedo OS host), so it is **never** `adb push`ed or
`scp`ed anywhere — copy it to `~/tmp/` and let 白い熊 install it locally. Do **not** run
`apt install` / `dpkg -i` yourself: installing is 白い熊's call, exactly as `adb install` is on Android.

## When to build — always, after any change

**Build automatically after completing any change to the desktop app** (Rust in `core/` or
`src-tauri/`, frontend in `Flying Carpet/src/`, icons, `tauri.conf.json`) — don't wait to be asked.
Building and copying to `~/tmp` are automatic; `git commit` / `git push` are **never** automatic and
still require an explicit "Push". Skip an auto-build only when the tree is mid-refactor and won't
compile, or the change ships nothing in the `.deb` (docs, skills, Android-only files).

## Versioning — every build is `+1` (hard rule)

The desktop version lives in **one** place: the `version` field of
`Flying Carpet/src-tauri/tauri.conf.json`, formatted `"<release>+<buildNumber>"` (e.g. `"9.0.10+1"`).

- `<release>` = the FlyingCarpet **release** version — the latest upstream `v*` tag (equals
  `core/Cargo.toml`), **not** whatever Android's `build.gradle` says.
- `<buildNumber>` **resets to 1 on each upstream rebase** and goes **+1 for every delivered build**.
  Bump it *before* building. Never reuse a number, and never overwrite an older `.deb` in `~/tmp` —
  the numbered files are meant to accumulate there (the sister repos do the same, e.g.
  `shiroikuma-jiyudoga_0.25.1+27_amd64.deb`).
- Tauri feeds this one value into the `.deb` **filename**, its `Version:` control field, and the app's
  own title-bar version label (`getVersion()` in `customize.js`), so there is nothing else to edit.
- dpkg orders `9.0.10+2 > 9.0.10+1 > 9.0.10`, so each build installs cleanly as an upgrade.

Iterating on a build 白い熊 has not yet kept may reuse the current number; anything delivered gets its
own.

## Steps

1. **Bump the build number** in `Flying Carpet/src-tauri/tauri.conf.json`:
   ```bash
   grep -n '"version"' "Flying Carpet/src-tauri/tauri.conf.json"
   ```
   Edit `"version": "<release>+<N>"` → `"<release>+<N+1>"`.

2. **Build** from the **repo root** (the Cargo workspace root). Needs `cargo install tauri-cli` (v2)
   once; the webkit2gtk-4.1 / gtk / soup dev libs are already installed on this host:
   ```bash
   cargo tauri build --bundles deb
   ```
   Only the `deb` bundle — never `--bundles all` (the AppImage/rpm targets are not shipped here).
   A `__TAURI_BUNDLE_TYPE variable not found` warning is harmless (it only affects Tauri's unused
   self-updater plugin).

3. **Copy to `~/tmp`** under the name Tauri already produced — **no renaming**:
   ```bash
   cp target/release/bundle/deb/shiroikuma-mahojutan_<release>+<N>_amd64.deb ~/tmp/
   ls -lh ~/tmp/shiroikuma-mahojutan_<release>+<N>_amd64.deb
   ```

4. **Verify** (cheap, catches a broken rebrand):
   ```bash
   dpkg-deb -f <deb> Package Version     # → shiroikuma-mahojutan / <release>+<N>
   dpkg-deb -c <deb> | grep -E 'bin/|applications/'   # → usr/bin/shiroikuma-mahojutan + the .desktop
   ```
   The `.desktop` entry must read `Name=白い熊 魔法絨毯`.

5. **Announce** the filename that landed in `~/tmp` and the install command
   (`sudo apt install ~/tmp/shiroikuma-mahojutan_<release>+<N>_amd64.deb`). Never install it yourself,
   and never `adb push` / `scp` a `.deb` — it is for this machine only.

## Visual check (optional, no desktop interference)

The app can be exercised headlessly to confirm the fork UI still renders — this never steals focus
from 白い熊's session:

```bash
Xvfb :99 -screen 0 1000x1200x24 &
env -u WAYLAND_DISPLAY GDK_BACKEND=x11 DISPLAY=:99 target/release/shiroikuma-mahojutan &
sleep 8
DISPLAY=:99 import -window "$(DISPLAY=:99 xdotool search --name '白い熊' | head -1)" shot.png
```

`GDK_BACKEND=x11` and unsetting `WAYLAND_DISPLAY` are required — otherwise the app opens on 白い熊's
real Wayland session instead of the virtual display.

## What the rebrand covers (keep all of it working)

| What | Value | Where |
| --- | --- | --- |
| dpkg package + binary | `shiroikuma-mahojutan` | `productName` / `mainBinaryName` in `tauri.conf.json` |
| Launcher name | `白い熊 魔法絨毯` | `src-tauri/shiroikuma-mahojutan.desktop` (via `bundle.linux.deb.desktopTemplate`) |
| Window title + heading | `白い熊 魔法絨毯` | `tauri.conf.json` window `title`; `<title>`/`<h1>` in `src/index.html` |
| Icon | yellow-traced carpet | `src-tauri/icons/*` (regenerate with `cargo tauri icon <512px png>`) |
| Fork UI | yellow-on-black theme + “Customize UI” page | `src/customize.js`, `src/customize.css`, hooks in `index.html` / `main.js` |

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
