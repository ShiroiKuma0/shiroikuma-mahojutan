---
name: build-apk
description: Build the signed release APK of the shiroikuma 魔法絨毯 (Flying Carpet) Android fork, then deliver it automatically via the global /after-build skill (adb push if a phone is connected, else scp to skhw — no transfer prompt). Use whenever the user asks to build the app, build the APK, make a release build, or build and send to the phone — AND proactively (without being asked) after completing any code, resource, asset, icon, or version change to the Android app, so the user always has an up-to-date APK to test.
---

# Build the signed release APK and optionally send to phone

This is the **Android** app of the FlyingCarpet fork (`Android/FlyingCarpet/`). The desktop
(Tauri/Rust) app is forked and rebranded too, but is built by its own **build-deb** skill — this one
never touches it, and the two carry **independent** `+N` build counters. The app has **no native
code** — it is pure Kotlin, so there are no ABI splits: `assembleRelease` produces **one universal
APK**. The `arm64-v8a` in the filename is a naming convention (the target device), not an ABI variant.

## When to build — always, after any change

**Build automatically after completing any change to the Android app** (code, resources, assets,
icons, version bumps) — don't wait to be asked. Once the change is done and the working tree is in a
testable state, run the build (these Steps), copy to `~/tmp`, then deliver it via the global
**/after-build** skill. The goal is
that there is always a fresh, signed APK ready to install for testing. This does **not** relax the
hard rules below: building and delivery are automatic, but committing and pushing to git are
**never** automatic — they still require an explicit request ("Push"). Skip an
auto-build only when the change leaves the app uncompilable (mid-refactor) or touches nothing the APK
ships (e.g. only docs, skills, or `~/tmp` scratch files).

## Hard rules (same as everyday development — see CLAUDE.md)

- **Never `git commit`/`git push` unprompted**, and **never `adb install`.** Build, copy to `~/tmp`,
  then deliver the APK automatically via the global **/after-build** skill — it runs `/adb-check`
  (UNSANDBOXED) then `/adb-push` to `/sdcard/tmp/` if a phone is connected, otherwise `/scp` to
  `skhw:~/tmp/`, announcing the filename. No transfer prompt — never ask "scp or adb push?" or
  "is the phone connected?". The user installs from the phone's file manager.
- Always keep the unconditional `~/tmp/` copy so a missing/forgotten cable never costs the build.

## Steps

1. **Note the output filename.** Read the fork version from `Android/FlyingCarpet/app/build.gradle`:
   - `grep -E 'releaseVersionName|buildNumber' Android/FlyingCarpet/app/build.gradle`
   - The clean APK name is `shiroikuma-mahojutan_<releaseVersionName>+<buildNumber>_arm64-v8a.apk`
     (e.g. `shiroikuma-mahojutan_9.0.10+1_arm64-v8a.apk`). `versionCode` = `21 * 10000 + buildNumber`.
   - **If this is a new deliverable build** distinct from the last one the user kept, bump
     `buildNumber` (+1) in `build.gradle` first so the install lands as an upgrade. Iterating an
     unpushed build can reuse the current number (a same-code reinstall is fine). On an upstream
     rebase the number resets to 1 — see the `upstream-new-version` skill.

2. **Build** (from the repo root). `gradlew` ships without its exec bit in this repo, so invoke it
   via `sh`. Pin JDK 21 and the SDK; the `< /dev/null` guarantees it never blocks on stdin:
   ```bash
   cd Android/FlyingCarpet
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
   export ANDROID_HOME=/home/shiroikuma/android-sdk
   export ANDROID_SDK_ROOT="$ANDROID_HOME"
   export PATH="$JAVA_HOME/bin:$PATH"
   sh ./gradlew :app:assembleRelease --console=plain < /dev/null
   ```
   Confirm `BUILD SUCCESSFUL`. The Kotlin `w:` deprecation warnings are from upstream code and are
   normal. The raw artifact is `app/build/outputs/apk/release/shiroikuma-mahojutan_<ver>_arm64-v8a-release.apk`
   (AGP appends `-release`; the copy step below drops it).

3. **Copy to `~/tmp` under the clean name** (always, before any push):
   ```bash
   mkdir -p ~/tmp
   src=$(ls -1 app/build/outputs/apk/release/*.apk | head -1)
   ver=$(grep -oP 'releaseVersionName = "\K[^"]+' app/build.gradle)
   bn=$(grep -oP 'buildNumber = \K[0-9]+' app/build.gradle)
   cp "$src" ~/tmp/"shiroikuma-mahojutan_${ver}+${bn}_arm64-v8a.apk"
   ls -lh ~/tmp/"shiroikuma-mahojutan_${ver}+${bn}_arm64-v8a.apk"
   ```

4. **Verify** (optional but recommended) with the latest build-tools:
   - `aapt dump badging <apk> | grep -E "package:|application-label:"` → expect
     `shiroikuma.mahojutan`, `versionName='<ver>+<bn>'`, label `白い熊 魔法絨毯`.
   - `apksigner verify --print-certs <apk>` → `CN=shiroikuma mahojutan`.

5. **Deliver automatically via the global /after-build skill** — every build, no asking. After the
   signed APK is in `~/tmp/`, invoke **/after-build**: it runs `/adb-check` UNSANDBOXED (a sandboxed
   check falsely reports no device), then `/adb-push` to `/sdcard/tmp/` if a phone is connected,
   otherwise `/scp` to `skhw:~/tmp/`, and announces the filename that landed. Never prompt
   "scp or adb push?" or "is the phone connected?" — /after-build decides on its own. Never
   `adb install`; the user installs from the phone's file manager.

## Signing (prerequisite)

Release signing is non-interactive. `app/build.gradle` reads `keystore.properties` at the Gradle
project root (`Android/FlyingCarpet/keystore.properties`, **gitignored**), falling back to
`SIGNING_*` env vars. If neither is present the build is **unsigned** and the APK will not install.

`keystore.properties` points at the stable per-fork keystore:

```
keyAlias=mahojutan
keyPassword=mahojutan-shiroikuma
storeFile=/home/shiroikuma/.android-keystores/shiroikuma-mahojutan.jks
storePassword=mahojutan-shiroikuma
```

The keystore (`~/.android-keystores/shiroikuma-mahojutan.jks`, alias `mahojutan`) is stable so every
rebuild installs as an upgrade over the previous `shiroikuma.mahojutan` install. It lives outside the
repo and is never committed. To recreate it on a fresh machine:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/keytool -genkeypair -v \
  -keystore ~/.android-keystores/shiroikuma-mahojutan.jks \
  -alias mahojutan -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 -storepass mahojutan-shiroikuma -keypass mahojutan-shiroikuma \
  -dname "CN=shiroikuma mahojutan, OU=personal, O=shiroikuma, L=, ST=, C=JP"
```

## Coexistence

`applicationId shiroikuma.mahojutan` differs from upstream `dev.spiegl.flyingcarpet`, so this fork
installs **side-by-side** with the official Flying Carpet. The code `namespace` stays
`dev.spiegl.flyingcarpet` (R class / Kotlin package), so no source edits are needed. Do not try to
install over an official build signed with a different key — Android refuses.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
