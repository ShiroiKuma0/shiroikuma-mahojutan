---
name: upstream-new-version
description: Rebase the shiroikuma 魔法絨毯 fork onto a new upstream release of spieglt/FlyingCarpet. Use when the user says a new upstream version is out, asks to update/sync to upstream, bump to the new Flying Carpet release, or rebase custom onto the latest upstream.
---

# Rebase the fork onto a new upstream release

This codifies the "new upstream version" half of the fork workflow: move `main` to the new upstream
release, replay our `custom` customizations on top, and produce a fresh `+1` build.

> **Never `git push` or `git commit` unprompted, and never `adb install`.** Same hard rules as
> everyday development (see CLAUDE.md). After the rebase + build you stop and let the user test; you
> only `git push` when they explicitly say **"Push"**.

## Background — how versioning works here

This fork is **Android-only** (the Tauri/Rust desktop app is not built or shipped here). The Android
app has no native code → one universal APK; `arm64-v8a` in the filename is a label, not an ABI split.

- **`versionName` tracks the FlyingCarpet *release* version** — the latest `v*` git tag, equal to the
  desktop `Cargo.toml` / `tauri.conf.json` version. **Upstream lets the Android `build.gradle`
  versionName lag the release** (it was a stale `9.0.8` at the `v9.0.10` release / Android
  `versionCode 21`), so **do not** read the version from Android's `build.gradle` — read it from the
  tag. Our `build.gradle` holds it in `releaseVersionName`.
- **`upstreamVersionCode`** in our `build.gradle` mirrors upstream's Android `defaultConfig.versionCode`.
- **`buildNumber`** is our fork increment. It **resets to `1`** on each new upstream version and bumps
  +1 per delivered build.
- Fork `versionName` = `"<releaseVersionName>+<buildNumber>"`; fork `versionCode` =
  `upstreamVersionCode * 10000 + buildNumber`. So when upstream's Android `versionCode` climbs, the new
  line's codes all exceed the previous line's, keeping sideloaded upgrades monotonic.

All three values live near the top of `Android/FlyingCarpet/app/build.gradle`.

## Steps

1. **Fetch upstream:**
   - `git fetch upstream --tags`
   - Identify the new release tag: `git tag --sort=-version:refname | head`. Read the new release
     version (it equals the tag minus the `v`, and equals upstream `core/Cargo.toml`):
     `git show <tag>:core/Cargo.toml | grep -m1 '^version'`.
   - Read upstream's new **Android** `versionCode` (it may still lag the release version):
     `git show <tag>:Android/FlyingCarpet/app/build.gradle | grep -E 'versionCode|versionName'`.

2. **Advance `main` to the new upstream release** (it mirrors upstream, no fork work lives there):
   - `git checkout main`
   - `git merge --ff-only upstream/main` (or `git reset --hard <tag>` to track an exact tag).

3. **Rebase `custom` onto the new `main`:**
   - `git checkout custom`
   - `git rebase main`
   - Resolve conflicts so **all** our customizations survive (see the table in step 5). The only
     conflict-prone files are `Android/FlyingCarpet/app/build.gradle` and
     `Android/FlyingCarpet/app/src/main/res/values/strings.xml`.

4. **Re-derive versioning in `Android/FlyingCarpet/app/build.gradle`:**
   - Set `releaseVersionName` to the **new release version** (from the `v*` tag, step 1) — not the
     value upstream put in Android's `build.gradle`.
   - Set `upstreamVersionCode` to the **new upstream Android `versionCode`** (step 1).
   - **Reset `buildNumber` to `1`.**
   - These lines conflict on every rebase (upstream changes the bare `versionCode`/`versionName` we
     replaced with the fork logic) — resolve by **re-deriving**, never by blindly keeping either side.

5. **Verify our customizations are intact** (after resolving the rebase):

   | What | Expected value | Where |
   | --- | --- | --- |
   | Installed app ID | `shiroikuma.mahojutan` | `app/build.gradle` → `defaultConfig.applicationId` |
   | Code namespace | `dev.spiegl.flyingcarpet` (unchanged from upstream) | `app/build.gradle` → `namespace` |
   | App launcher label | `白い熊 魔法絨毯` | `app_name` in `res/values/strings.xml` |
   | Fork version logic | `releaseVersionName` / `upstreamVersionCode` / `buildNumber` + `forkVersionName`/`forkVersionCode` | top of `app/build.gradle` |
   | APK base name | `setProperty("archivesBaseName", …)` | `app/build.gradle` `defaultConfig` |
   | Release signing | `signingConfigs.release` from `keystore.properties` / `SIGNING_*` + `buildTypes.release.signingConfig` | `app/build.gradle` |
   | `keystore.properties` gitignored | present in `Android/FlyingCarpet/.gitignore` | `.gitignore` |

   Sanity check the script still evaluates:
   `cd Android/FlyingCarpet && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/shiroikuma/android-sdk sh ./gradlew :app:tasks --console=plain < /dev/null` (or a `--dry-run` assemble).

6. **Build the new `+1`** via the **build-apk** skill, then deliver it via the global **/after-build**
   skill (no transfer prompt). This is the
   first build of the new upstream line (`<newVersion>+1`).

7. **Stop.** Let the user test. Commit/push only on their explicit **"Push"**. Because rebasing
   rewrites `custom`'s history, the push is `git push --force-with-lease origin custom`; `main` is a
   plain fast-forward (`git push origin main`).

## Notes

- Keep our changes a **small, legible layer** on top of upstream — prefer rebasing (linear history)
  over merging, so the customization set stays easy to audit and replay.
- If upstream restructures `build.gradle` (e.g. introduces its own `versionName`/`versionCode`
  variables, product flavors, or its own signing block), port our fork-version + signing layer into
  the new structure rather than forcing the old diff.
- `gradlew` ships without its exec bit in this repo — always invoke it as `sh ./gradlew` (the build
  skill does this).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
