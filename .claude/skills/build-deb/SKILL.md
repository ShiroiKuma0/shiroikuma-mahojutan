---
name: build-deb
description: Build the rebranded amd64 .deb of the shiroikuma 魔法絨毯 (Flying Carpet) Tauri desktop app for 白い熊's Tuxedo OS machine, bumping the fork +N build number first, and copy it to ~/tmp. Use whenever the user asks to build the desktop app, the deb, or the Linux app — AND proactively (without being asked) after completing any change to the desktop app (Rust, frontend, icons, tauri.conf.json), so there is always a fresh .deb to install.
---

# Build the rebranded desktop `.deb` (amd64)

This is the **desktop** (Tauri/Rust) app of the FlyingCarpet fork (`Flying Carpet/` + `core/`),
rebranded exactly like the Android app. **Every build ships BOTH artifacts together** (hard rule,
白い熊 2026-07-23): whenever this skill builds the `.deb`, also run the **build-apk** skill for the
same `+N` — never deliver one artifact alone. The two artifacts share **one** `+N` build counter —
the same code always builds as the same `+N` on both (see Versioning below).

The `.deb` targets **this** machine (白い熊's Tuxedo OS host), so it is **never** `adb push`ed or
`scp`ed anywhere — copy it to `~/tmp/` and let 白い熊 install it locally. Do **not** run
`apt install` / `dpkg -i` yourself: installing is 白い熊's call, exactly as `adb install` is on Android.

## When to build — always, after any change

**Build automatically after completing any change to the desktop app** (Rust in `core/` or
`src-tauri/`, frontend in `Flying Carpet/src/`, icons, `tauri.conf.json`) — don't wait to be asked.
Building and copying to `~/tmp` are automatic; `git commit` / `git push` are **never** automatic and
still require an explicit "Push". Skip an auto-build only when the tree is mid-refactor and won't
compile, or the change ships nothing in either artifact (docs, skills, `~/tmp` scratch files).

**A build is always BOTH artifacts:** whatever triggered it (desktop-only change, Android-only
change), the same `+N` gets a `.deb` **and** an APK — after finishing the `.deb` here, run the
**build-apk** skill's steps for the same number.

## Versioning — ONE shared `+N` counter for APK and deb (hard rule)

The fork has a **single** `+N` build counter shared by both artifacts: **the same code must always
build as the same `+N` on the APK and the `.deb`.** The counter is stored in two places that must
always hold the same number:

- Android: `buildNumber` in `Android/FlyingCarpet/app/build.gradle` (a plain integer there —
  `build.gradle` zero-pads it into the `versionName` itself);
- desktop: the `+N` in the `version` field of `Flying Carpet/src-tauri/tauri.conf.json`, formatted
  `"<release>+<NNN>"` (e.g. `"9.0.10+026"`) — **written zero-padded to three digits**, since Tauri
  copies this string verbatim into the `.deb` filename.

**The `+N` is ALWAYS zero-padded to three digits** (hard rule, 白い熊 2026-08-01): `+026`, never
`+26`. Unpadded counters sort lexicographically wrong (`+10` before `+3`), burying the newest build.
dpkg compares digit runs numerically, so padding does not disturb upgrade ordering:
`9.0.10+026 > 9.0.10+25`. Builds up to `+25` predate the rule and keep their names.

Rules:

- `<release>` = the FlyingCarpet **release** version — the latest upstream `v*` tag (equals
  `core/Cargo.toml`), **not** whatever Android's `build.gradle` says.
- The counter **resets to 1 on each upstream rebase** and goes **+1 for every delivered build of
  either artifact**. Bump **both places together** *before* building — even if only one artifact is
  being built this time. Never reuse a number, and never overwrite an older `.deb` in `~/tmp` —
  the numbered files are meant to accumulate there (the sister repos do the same, e.g.
  `shiroikuma-jiyudoga_0.25.1+27_amd64.deb`).
- **Every delivered `+N` ships both artifacts** (hard rule, 白い熊 2026-07-23): the APK and the
  `.deb` are always built together as a pair — never one alone. Historical gaps predate this rule
  (no deb exists for `+2`…`+19`, no APK for `+21`). Equally never allowed: the two artifacts
  carrying different `+N` for the same code.
- Tauri feeds the `version` value into the `.deb` **filename**, its `Version:` control field, and the
  app's own title-bar version label (`getVersion()` in `customize.js`), so there is nothing else to
  edit on the desktop side.
- dpkg orders `9.0.10+026 > 9.0.10+25 > 9.0.10+1 > 9.0.10`, so each build installs cleanly as an
  upgrade (digit runs compare numerically, so `026` counts as 26).

Iterating on a build 白い熊 has not yet kept may reuse the current number; anything delivered gets its
own.

## Steps

1. **Bump the shared build number in BOTH places** (they must stay equal):
   ```bash
   grep -n '"version"' "Flying Carpet/src-tauri/tauri.conf.json"
   grep -nE 'buildNumber' Android/FlyingCarpet/app/build.gradle
   ```
   Edit `"version": "<release>+<NNN>"` → the next number, **zero-padded to three digits**, in
   `tauri.conf.json` **and** `buildNumber = <N>` → `<N+1>` in `build.gradle` (a bare integer there;
   the padding is applied by `String.format("%03d", buildNumber)`). Same number in both.

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
   cp target/release/bundle/deb/shiroikuma-mahojutan_<release>+<NNN>_amd64.deb ~/tmp/
   ls -lh ~/tmp/shiroikuma-mahojutan_<release>+<NNN>_amd64.deb
   ```

4. **Verify** (cheap, catches a broken rebrand):
   ```bash
   dpkg-deb -f <deb> Package Version     # → shiroikuma-mahojutan / <release>+<NNN>
   dpkg-deb -c <deb> | grep -E 'bin/|applications/'   # → usr/bin/shiroikuma-mahojutan + the .desktop
   ```
   The `.desktop` entry must read `Name=白い熊 魔法絨毯`.

5. **Announce** the filename that landed in `~/tmp` and the install command
   (`sudo apt install ~/tmp/shiroikuma-mahojutan_<release>+<NNN>_amd64.deb`). Never install it yourself,
   and never `adb push` / `scp` a `.deb` — it is for this machine only.

6. **Build the APK for the same `+N`** — every build ships both artifacts (hard rule): run the
   **build-apk** skill's steps (the counter is already bumped, so skip its bump step) so the paired
   APK also lands in `~/tmp/`.

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
