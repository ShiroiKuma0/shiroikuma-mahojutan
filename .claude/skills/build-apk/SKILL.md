---
name: build-apk
description: Build the signed release APK of the shiroikuma 魔法絨毯 (Flying Carpet) Android fork, then always ask whether to push it to the connected phone via adb. Use whenever the user asks to build the app, build the APK, make a release build, or build and push to the phone.
---

# Build the signed release APK and optionally push to phone

This is the **Android** app of the FlyingCarpet fork (`Android/FlyingCarpet/`). The desktop
(Tauri/Rust) app is **not** part of this fork and is never built here. The app has **no native
code** — it is pure Kotlin, so there are no ABI splits: `assembleRelease` produces **one universal
APK**. The `arm64-v8a` in the filename is a naming convention (the target device), not an ABI variant.

## Hard rules (same as everyday development — see CLAUDE.md)

- **Never `git commit`/`git push` unprompted**, and **never `adb install`.** Build, copy to `~/tmp`,
  and only `adb push` to `/sdcard/tmp/` after asking. The user installs from the phone's file manager.
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

5. **Always ask** (via AskUserQuestion) whether to push the APK to the phone — every build, no
   assuming. Options: "Yes, push via adb" / "No, just build".

6. **If yes, push directly** (never `adb install`):
   - `adb devices` — confirm a device is connected.
   - `adb shell mkdir -p /sdcard/tmp`
   - `adb push ~/tmp/<apk name> /sdcard/tmp/<apk name>`
   - Verify: `adb shell ls -l /sdcard/tmp/<apk name>` (size matches the local file).
   - The user installs it from the phone's file manager.

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
