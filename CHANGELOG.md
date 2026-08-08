# Changelog

All notable changes this fork makes on top of stock
[Flying Carpet](https://github.com/spieglt/FlyingCarpet). Versions are
`<upstream release>+<fork build number>`; the build number resets on each upstream rebase and
increases with every delivered build. The Android and desktop artifacts share one counter — the
same code always builds as the same `+N` on both, and since `+22` every delivered `+N` ships both
artifacts as a pair.

## 10.0.3+013 — 2026-08-08

Bluetooth becomes usable in Shared Network mode — and, along the way, usable at all: the handshake
that took a minute and a half now takes a second, and it stopped destroying pairings to get there.

### Bluetooth carries the password in Shared Network mode
- **The switch is live in both connection modes**, on both apps, and is the toggle it always looked
  like: *use Bluetooth*, or *scan the QR code / type the password*. Upstream greys it out outside
  hotspot mode, because BLE is only used there to negotiate hotspot credentials.
- Who owns the password does not change. The **receiver generates it** either way — the same side
  that would otherwise display it — and hands it over the BLE link instead of putting it on screen.
  The SSID still travels, derived from the password as everywhere else, and is simply unused.
- One rule decides which side supplies the credentials, `we_supply_credentials()` in `core`, read by
  both BLE roles and all three platform implementations: whoever hosts the hotspot in hotspot mode,
  the receiver in shared network mode.
- The switch belongs to you now: changing connection mode never flips it, and the choice survives a
  restart (localStorage on the desktop, the fork's settings store on Android). Only an unavailable
  radio overrides it, and that disables the switch rather than pretending it is off.
- **A line under the switch says what will happen**: with Bluetooth off, that the receiving device
  will show a QR code and password when the transfer starts and the sending device will scan or type
  it — worded for whichever side you are. Themeable like everything else, from the new *Line under
  the Bluetooth switch* element. Portrait only on Android; the landscape layout has no room under
  the switch.

### Discovery: from a minute and a half to milliseconds
- **The scan waited on BlueZ events.** `DeviceAdded` fires for a device BlueZ has never seen; for one
  it already knows — a bonded phone — the only thing that can wake the loop is a property change, and
  a bonded peer's UUID list never changes, so what it was really waiting for was an RSSI update on
  BlueZ's own schedule. It now sweeps the adapter's device list every second as well.
- **Both paths require a live signal.** An RSSI means BlueZ is hearing that device now, so a bonded
  peer's stale cache entry — which carries our service UUID from the last transfer — can no longer be
  mistaken for a peer that is on the air. Chasing one cost 22 seconds a round, twice per attempt.
- **The paired-device probe left the scan loop.** It used to be awaited inline, aimed at every paired
  device BlueZ knew of, in range or not: a switched-off headset spent 15 seconds timing out on the LE
  link socket and another 47 in BlueZ's connect, and for that whole minute neither discovery events
  nor the sweep could be processed. It now runs as its own task, only for devices being heard, capped
  at 20 seconds, and reports back over a channel.
- A paired device written off by one failed probe is retried after 30 seconds instead of never.

### Pairing stops breaking itself
- **The retry ladder no longer removes the pairing.** Its second rung used to unpair "and pair
  again" — the one-sided unpairing the fork's own field guide forbids. Measured: it deleted a bond
  that had worked half an hour earlier, and the next round's fresh pairing request reached a phone
  that still held its half, which dropped its bond and reported the pairing as failed. It now says
  what to do if failures persist — remove the pairing on *both* devices — and leaves the bond alone.
- A connect that hits our timeout is cancelled before the retry, so the retry stops coming straight
  back with `Operation already in progress` and wasting its turn.
- `ensure_le_link`'s socket timeout is 5 seconds rather than 15. It is a best-effort nudge toward the
  LE bearer and the code carries on regardless; when the radio is busy it can only ever time out.
- The connect itself is three 15-second attempts instead of one open-ended call that BlueZ abandons
  after 44 seconds in a single silent block.
- The log records which answer the passkey dialog was given, so a peer's refusal can be told apart
  from a dialog dismissed on this side.

### Nothing waits in silence
- `utils::with_progress` ticks every three seconds with the elapsed time through every wait that used
  to be quiet: the LE pairing socket, the connect, the GATT service read, the probe, and each wait
  for the peer's writes. Covered by a unit test on virtual time.
- The scan's own five-second round now reaches the app — "Still looking for the other device over
  Bluetooth... (15s, 7 devices in range)" — and it announces the peer's address when it finds it.
- Android has the same ticker while advertising, scanning, connecting, and waiting for the password,
  stopping the moment the peer touches one of our characteristics.
- Both sides say, once, why nothing may be happening yet: that the other device has to have started
  its transfer too.
- **A discovery held by another program on the computer is called out by name at the start.** It owns
  the radio — a classic-Bluetooth inquiry runs 10.24 seconds at a time, back to back, and an LE
  connection cannot be established under it — and it is not something this app can stop.

### Android: back on the air after a failed contact
- The sending phone comes off the air at the peer's first read or write, which with MITM-encrypted
  characteristics is exactly the access that triggers pairing and can fail. Nothing put it back: it
  sat with a live GATT server, an open transfer and an empty advertisement list while the other
  device rescanned for something that no longer existed. It now re-advertises when the peer
  disconnects before the exchange is done, and says so.
- The GATT callbacks' narration follows the connection mode: no more "it is setting up its hotspot"
  while waiting for a password that arrives over Bluetooth.

### The password dialog wears the fork's dress
- Upstream's dialog card was hardcoded white, so the fork's yellow text sat on white, with Bootstrap
  grey/green buttons. It is now the same black card, 2 px yellow border and 16 px radius as the
  Export/Import dialogs, with ArcaneChat pills — including the focus state, the caret and the network
  interface dropdown's arrow, all of which Bootstrap repaints on its own.
- It is customizable: a new **Password dialog** section on the UI page covers the question text, the
  password box, the buttons and the card's own background and border. The old *Password box* element
  moved into it, keeping its stored keys, so an existing backup still applies.

### Cancelling says so
- Android resets the per-file progress bar on cancel — its two neighbours were being cleared and it
  was not, so it stayed frozen at whatever fraction it had reached — and writes "Transfer cancelled."
  to the log, from the Cancel button rather than from the teardown every finished transfer passes
  through.

### Packaging
- `tokio`'s `test-util` feature as a dev-dependency of `core`, so tests can assert on ten seconds of
  waiting without waiting ten seconds.

## 10.0.3+005 — 2026-08-06

The first pass over v10's new surfaces: the controls upstream added are now in the fork's colours,
shared network is the default, and getting a password from one device to the other takes a glance
rather than a dialog.

### The connection-type row is themed
- **"Select connection type", "Hotspot" and "Shared Network" were still stock.** They arrived with
  v10 and the fork's Appearance layer had never been told about them, so on a yellow-on-black screen
  the label came out white and the selected button Material purple. All three are registered now, so
  they also show up in the 白い熊 魔法絨毯 UI page like everything else: the buttons under
  **Main page** with their own selected/unselected fill and text colours, border colour and
  border-width / corner-radius sliders, the label under **Step instructions**. Nothing new had to be
  styled — the fork's existing toggle default is yellow-on-black with a yellow border, inverting to
  black-on-yellow when selected, which is exactly the intended look.
- The desktop's connection row had the same three unthemed elements and gets the same treatment.
- Fixed on the desktop while there: `modeInstruction.text` still defaulted to v9's "Select Mode" and
  was overwriting v10's "Select File Mode" at runtime — ambiguous now that there are two mode rows.

### Shared Network is the default mode
- Hotspot mode turns the laptop's WiFi card into an access point and takes the phone off the network
  to join it, so both devices lose their connection for the duration. Shared network leaves both
  where they are. **The Bluetooth switch therefore starts disabled**: BLE exists only to negotiate
  hotspot credentials, and every platform greys it out in this mode. Switching to Hotspot re-enables
  it.
- On the desktop the default is set in markup and fires no change event, so the Bluetooth switch is
  brought in line once on a fresh load; without that it stayed enabled in a mode that never uses it.

### A shared network transfer died the moment it found its peer
- **`Transfer error: lateinit property peer has not been initialized`**, immediately after
  `Discovered peer at …`. `startTCP()` began by asking `isHosting()` which end it was, only to log
  "Listening on port …" or "Connecting to …" — fork code from the talkative-log work, written when
  hotspot was the only mode. `isHosting()` reads `peer`, which is set from the peer-OS buttons, and
  shared network mode hides those, so the read threw and ended the transfer. The announcement is
  scoped to hotspot mode now; shared network already names both ends for itself.
- `isHosting()` returns false for an unset peer instead of throwing, so a single unguarded call can
  never take a transfer down this way again.

### Passing the password over
- **The receiver prints the password under its own QR code** and no longer opens a modal to say it.
  Both ways of passing it over are on screen at once, with nothing to dismiss first. The caption is
  drawn into the bitmap rather than added to the layout, because the QR takes over the logo's
  ImageView and the *白い熊 魔法絨毯 UI* button sits directly beneath it; it lies on the quiet zone's
  white and never overlaps a module, so the code scans exactly as before.
- **The sender gets one dialog that scans and types at once.** The camera preview is embedded above
  the password field, in the fork's own black-and-yellow chrome, instead of zxing's full-screen
  capture activity — which had to be backed out of before the keyboard could be reached. A scan
  fills the field and starts the transfer; typing and OK does the same.
- Embedding the preview means requesting `CAMERA` ourselves, which the full-screen activity used to
  do. Declined, or on a device without a camera, the dialog is simply the typing dialog and the hint
  says so — the transfer is not cancelled. The camera is released in `onPause` and on every path out
  of the dialog.
- **The shared-network QR was being tinted yellow.** That ImageView normally holds the fork's logo,
  which is tinted, and the colour filter outlives the drawable — the hotspot path cleared it, the
  shared network path never did. It was rendering yellow-on-black and probably would not scan at
  all, which is a good reason scanning had not been the obvious route before now.

## 10.0.3+001 — 2026-08-06

Rebased onto upstream **Flying Carpet 10.0.3** (113 commits since 9.0.10). Upstream's v10 is a
breaking release, and it reworked several of the areas this fork had been patching, so this entry
records what the fork now adds, what it handed back, and what changed underneath.

### Upstream v10, in brief — read this before transferring
- **The wire protocol is now Noise** (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`), replacing the
  per-chunk AES-256-GCM. The PSK is PBKDF2-HMAC-SHA256 over the password at 600 000 iterations, and
  the plaintext version/mode preamble is bound into the Noise prologue. **A v10 device refuses a v9
  peer**, with a version-mismatch message rather than a failure — update every device you transfer
  between.
- **Shared Network mode**: when both devices are already on the same network (WiFi *or* wired), no
  hotspot is created. The receiver mints a one-time password, shows it with a QR code, and the two
  find each other over authenticated UDP discovery. Bluetooth is deliberately not used in this mode.
- Passwords are single-use and CSPRNG-generated, never chosen and never remembered; the receiver is
  always the TCP server and Noise responder in both modes.
- Android now targets SDK 37 and requests `ACCESS_LOCAL_NETWORK` (Android 17 blocks local-network
  traffic by default at that target); the toolchain moved to Gradle 9.6.1 and AGP 9.3.1.

### Bluetooth — the fork's Linux layer handed back to upstream
- **Bonds are no longer cleared after a transfer** (`CLEAR_BOND_AFTER_TRANSFER = false`). Against v9
  this fork removed the pairing on *both* sides, because a bonded peer was unfindable from Linux —
  BlueZ stops announcing a paired device, and its identity record does not carry our service UUID.
  Upstream v10 settled that the other way: it never removes a bond on cleanup, since a one-sided
  removal leaves the peer holding keys the other end has forgotten, and it fixed the discovery half
  properly by verifying the service over a connection instead of trusting the cached UUID list.
  Keeping the fork's behaviour on one side only would have recreated exactly the asymmetry v10
  removed, so it is off, and the two sides agree again.
- **The fork's Linux BLE work is dropped in favour of upstream's**: the candidate scan-and-reject
  loop, the bounded per-candidate attempt, the LE-only discovery, and the bond clearing. Upstream's
  replacement forces the LE bearer for bonded peers, invalidates stale GATT caches, disconnects on
  every exit path, and is documented in `docs/bluetooth-field-guide.md`.
- Also dropped as redundant: starting the hotspot during the handshake (`hotspot_is_up`), the fork's
  stale-hotspot sweep (upstream has `cleanup_stale_connections`, filtered by connection type), and
  the Linux BLE logging, which upstream did itself.

### Bluetooth — what the fork still carries
- **The advertised device name is measured in UTF-8 bytes, not `String.length`.** `白い熊` is 3
  UTF-16 units and 9 bytes; the extra byte overflows the 31-byte advertisement and the controller
  rejects the whole packet, which EMUI surfaces as advertise error 18 (HCI 0x12, invalid parameters)
  and which looks from the other device like nothing is there at all.
- **The GATT server stays on the air** until something reads or writes one of *our* characteristics,
  instead of stopping on any incoming LE connection — on EMUI a watch or earbuds connecting was
  enough to take the sender off the air seconds after it started advertising.
- **A refused connection is retried**: GATT status 133 on a first `connectGatt()` is common and
  usually transient, so it is retried (3 attempts, 800 ms apart) rather than ending the transfer in
  silence.
- **A read that fails with insufficient authentication or encryption is not a failure** — it is the
  expected answer to the first read of an `ENCRYPTED_MITM` characteristic, so it waits for the bond
  and asks again once the stack reports `BOND_BONDED`. The pending read is cleared once it lands, so
  a later bond cannot re-issue a read that has already been answered.
- Joining the peer's hotspot is retried up to four times, since the system picker gives up after a
  single empty scan.

### Location — still nothing to do with where you are
- `BLUETOOTH_SCAN` keeps its `neverForLocation` disavowal, `ACCESS_FINE_LOCATION` and
  `ACCESS_COARSE_LOCATION` stay capped at `maxSdkVersion="32"`, and the Location gate still explains
  itself on older Androids instead of scanning into silence. Upstream's new `ACCESS_LOCAL_NETWORK`
  declaration is kept alongside them, ungated, as SDK 37 requires.

### Fork features carried forward unchanged
- The yellow-on-black theme and the *白い熊 魔法絨毯 UI* page on both apps; Export / Import as one
  timestamped `.zip` with per-category selection and merging import; the token-gated automation
  export; external font support; the traced-carpet icon set; the share-sheet target; one-tap receive
  into the last directory; `autoconnect no` on both nmcli profiles so a hotspot profile can never
  outlive the app.
- **The two-bar transfer readout** is re-plumbed onto v10's signatures: `send_file`/`receive_file`
  are now generic over the stream and carry the peer-relative name, so `Totals` threads through
  those instead of the old key-and-prefix arguments.

### Packaging
- Versions re-derived: `releaseVersionName` 10.0.3, `upstreamVersionCode` 24, `buildNumber` reset to
  1 — `versionCode` 240001, both artifacts `10.0.3+001`. At this release the upstream sources
  disagree (the tag reads 10.0.3, the desktop crates still read 10.0.1); the tag is the release, and
  using it keeps one version string across the APK and the `.deb`.
- `archivesBaseName` moved to the `base { archivesName }` extension — AGP 9 removed it from
  `defaultConfig`, and the build failed outright on the old form.
- Upstream's `rpm` bundle target inherits the fork's desktop template, so it is rebranded too.

### Fixes found while rebasing
- `MainActivity` had two `onResume` overrides after the replay — the fork's appearance pass and
  upstream's Bluetooth-permission recovery — which is a compile error; they are now one.
- `stopAdvertisingForPeer()` null-checks the adapter, which can vanish if Bluetooth is switched off
  mid-flight.
- Both test `UI` implementations gained the fork's two extra trait methods and the transfer test
  threads a `Totals`, so upstream's cross-platform Noise and discovery known-answer vectors still
  run: 29 core tests pass.

## 9.0.10+071 — 2026-08-04

Built on upstream **Flying Carpet 9.0.10**. With `+070` the receiver found the peer with Location
off and a transfer completed at 84.88 mbps — and then a later attempt stopped one step further
along, having found the device and stopped scanning.

### Bluetooth — a refused connection is now retried
- **GATT status 133 ended the transfer silently.** The receiver's log read `Called connectGatt()`,
  then `onClientConnectionState() - status=133 connected=false`, then nothing. 133 is Android's
  catch-all GATT error and it lands on a first connect often enough not to mean much — typically
  when the connect goes out in the same breath as stopping the scan, before the controller has
  finished with the radio. The next attempt usually lands.
- **There was no next attempt.** `onConnectionStateChange()` looked only at whether the new state
  was `CONNECTED`; the other branch wrote one line to logcat, ignored `status` entirely and
  returned. Scanning had already been switched off by that point, so nothing was left that could
  move: the app sat on “Stopped scanning” while the sender went on advertising — it only comes off
  the air once a peer touches one of its characteristics.
- **A failed connect is now told apart from an ordinary disconnect.** `bluetoothGatt` is set only
  on `CONNECTED` and cleared by `closeGatt()`, so a disconnect with it still null and a non-zero
  status means we never got in. On that the client is closed and the connection asked for again
  after 800 ms, up to three times, each attempt named in the log.
- **A transfer that cannot start no longer looks busy for ever.** When the retries are exhausted
  the error is named and the transfer reset, so the UI unlocks and the button can be pressed again.
  This deliberately avoids `bluetoothFailed()`, which switches the Bluetooth toggle off — the right
  answer when Bluetooth itself will not work, the wrong one when a single connection attempt did
  not land.

## 9.0.10+070 — 2026-08-04

Built on upstream **Flying Carpet 9.0.10**. A send from the Huawei Mate XT to a Samsung phone left
the receiver sitting on “Scanning for Bluetooth peripherals…” indefinitely. The sender was
advertising correctly throughout — a scan from the desktop's own Bluetooth found it at −58 dBm,
service UUID and all — and the receiver's scan filter was registered with the framework. It had
simply been handed nothing since the moment it started.

### Bluetooth — scanning with the Location switch off
- **Android withholds every BLE scan result while the device's master Location toggle is off.**
  There is no error: `startScan()` returns success, `onScanFailed()` is never called, and results
  simply never arrive — indistinguishable from “the other device is not there”. Granting the app
  `ACCESS_FINE_LOCATION` does *not* turn that toggle on; the permission and the switch are
  different things, and the permission prompt appearing at startup makes it look as though the
  requirement has been met.
- **The guard for this existed but the receive path skipped it.** `locationEnabledForScanning()`
  was written for exactly this failure — log line, dialog and an “Open settings” button — and wired
  into `beginTransferWithSelection()`, which covers the share-intent and one-tap-last-folder
  routes. The folder picker's callback carried a *copy* of that function's body minus the check,
  and picking the destination folder is how a receive normally starts, so the one entry point that
  actually scans was the one entry point without the guard. It now calls
  `beginTransferWithSelection()` like every other route.
- **`BLUETOOTH_SCAN` is now declared `neverForLocation`** — the assertion that a scan result is
  never used to derive the phone's physical location, which takes scan results out from under the
  Location toggle entirely on Android 12 and up. The documented cost is that some beacon formats
  are filtered from results; the app looks for a plain 128-bit service UUID, which is not one of
  them.
- **Location permission is gone above Android 12L.** It was only ever wanted for WiFi here, and
  `startLocalOnlyHotspot()` takes `NEARBY_WIFI_DEVICES` instead from API 33 — so
  `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` now carry `maxSdkVersion="32"`, and the app
  asks for no location permission at all on a modern phone.
- **`NEARBY_WIFI_DEVICES` is requested at startup.** It was declared in the manifest but never
  requested, so it sat ungranted and `startHotspot()` would have prompted for it mid-transfer,
  after the Bluetooth handshake was already under way. It now joins the other permissions at
  launch.
- **The Location dialog is scoped to Android 11 and below**, where the toggle genuinely still
  governs scan results; above that it would have blocked transfers that work. When Location happens
  to be off there, one line in the log says it is not needed — so if a vendor build ever ignored
  the disavowal, the symptom arrives with its own explanation instead of as silence.

## 9.0.10+067 — 2026-08-04

Built on upstream **Flying Carpet 9.0.10**. Android-to-Android transfers now complete: the
Bluetooth handshake between two phones used to stall with both sides sitting on “Bluetooth
connection released”, and every fault behind it failed in silence. Six builds of diagnosis
(`+061`…`+067`), each of which is below.

### Bluetooth — the handshake between two Android phones
- **The central opened a second GATT client.** The scan callback was changed to call
  `connectGatt()` directly, but the bond receiver's own `connectGatt()` — left over from when the
  scan callback only called `createBond()` — was never removed, so both fired. `bluetoothGatt` was
  then set by whichever connection changed state last and the characteristics by whichever
  discovered services last; when those were different instances, `readCharacteristic()` could not
  find the characteristic in its own handle map, returned `false`, and never called back. The
  handshake stopped there with nothing in the log for 26 seconds until the link timed out. The bond
  handler now resumes the pending read on the connection it already has.
- **A second GATT *server* could be opened**, putting two copies of the service in the GATT
  database while `sendResponse()` only ever answers through the current one — so a read landing on
  the orphaned copy got no reply at all. The server is now closed before another is opened.
- **`MainActivity` is `singleTask`.** Without a launch mode, a share from a file manager started a
  second Activity instance, with its own `ViewModel`, its own `Bluetooth` object and therefore its
  own `openGattServer()` — the actual source of the second server. The existing `onNewIntent()`
  handler was written for exactly this and could never fire.
- **`readCharacteristic()` and `writeCharacteristic()` had their results discarded.** Both refuse
  requests by returning a failure and never calling back, so an unchecked call stalled the
  handshake in total silence. Both are now checked, retried once, and reported. Writing our OS back
  is the step that triggers `connectToPeer()`, so a lost write stranded both sides.
- **`onCharacteristicRead()` ignored `status`.** A failed read still delivers a value — an empty
  one — so a failure was passed on as though the peer ran an OS called `""`. Insufficient
  authentication/encryption is the *expected* first answer for the `ENCRYPTED_MITM`
  characteristics: it now waits for the bond and asks again.
- **GATT clients are `close()`d, not merely dropped.** Dropping the reference leaks the client
  interface registration; ours stayed registered long after a transfer ended.
- **The bond receiver registers once**, against the application rather than the Activity, since it
  belongs to the ViewModel and outlives any one Activity instance.
- **Pairing is announced once.** The stack emits `BOND_BONDING` twice for a single pairing; echoing
  every transition made the log read as though the phones had paired two separate times.
- **Our own `clearBond()` is no longer reported as a failure.** The `BOND_NONE` raised by the
  deliberate post-transfer bond removal was announced as “Pairing did not complete” one line after
  “Cleared pairing with peer” had reported the same event as a success.

### Transfer — hotspot and teardown
- **The one-tap “receive in the last folder” button never armed the transfer.** The start button
  sets `transferIsRunning` and the share-target path sets it; the fork's own one-tap button set the
  directory and mode and went. The hotspot's `onStarted` callback reads that flag as “the user
  cancelled”, so every one-tap receive handed the access point back 110 ms after the framework
  granted it — through the one branch that returned without printing anything. It now arms the
  transfer and locks orientation exactly as the start button does, and that branch says what it is
  doing.
- **`transferFinished` was a one-way latch.** `finishTransfer` posted `true` and nothing ever posted
  `false`; LiveData is sticky, so every observer registered afterwards was immediately handed
  `true` — and the Activity re-registers on each recreation, which a fold or a permission dialog is
  enough to cause. That redelivery called `cleanUpTransfer()` in the middle of the *next* transfer.
- **The joining phone never got its WiFi back.** Nothing ever called
  `unregisterNetworkCallback()`. While a `WifiNetworkSpecifier` request is registered the framework
  deliberately holds the device on that network, so the sender stayed on the peer's hotspot after
  the transfer instead of returning to its own WiFi. The retry path made it worse, building another
  callback and registering another request without releasing the last — leaking one per attempt for
  the life of the process, against a platform ceiling of about a hundred. The callback is now kept
  and released, before each retry and at the end of every transfer once the sockets are closed.
- **The one-tap receive button stopped disappearing after a completed transfer.** `toggleUI()`
  refreshed it first, but that decides visibility partly from the start button's, which `toggleUI()`
  only updates ten lines later — so it read the value left over from the transfer that had just
  ended.

### Diagnostics
- **The on-screen log is mirrored to logcat** (tag `FlyingCarpet`). The sending side says everything
  interesting through `outputText`, and with it going only to a `LiveData`, half of a stalled
  handshake could only be read off a photograph of the screen. This is what made the remaining
  faults findable.

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
