# "Send Folder" behavior across the five platforms

Audited and fixed 2026-07-24, branch `shared-network` (both repos).

## The rule, as of now

**Selecting a folder recreates that folder inside the destination the receiver chose, with
its contents inside. Selecting individual files puts those files loose in the
destination.** All five platforms behave this way.

Implemented uniformly as: **every top-level selection is named relative to its own parent
directory.** That single rule produces both behaviors — a selected folder's own name becomes
the first path component, while a selected file's parent is stripped down to the bare
filename — and it degrades sensibly for mixed and multi-directory selections.

The wire format is unchanged: one relative filename string per file, `/`-separated, as
before. This was never a protocol question — receivers just `mkdir -p` whatever parent
components arrive. It was entirely a sender-side choice of which prefix to strip.

## What it used to be

macOS was the only platform that created the folder; the other four dumped the contents.

| Platform | Was | Now |
|---|---|---|
| **macOS** | creates the folder | unchanged — this was the model |
| **iOS** | dumps contents | creates the folder |
| **Android** | dumps contents (and broke against desktop, below) | creates the folder |
| **Windows** | dumps contents | creates the folder |
| **Linux** | dumps contents | creates the folder |

## Where each platform implements it

**macOS** — `Apple/shared/Transfer.swift`, `handleFileSelection`:
`sendDir = urls[0].deletingLastPathComponent()`, with `sendFolder = true` hardcoded. Unchanged.

**iOS** — same function, iOS branch. Was `sendDir = urls[0]` (the folder itself); now
`urls[0].deletingLastPathComponent()`, matching macOS. Both platforms share the prefix-strip
in `Send.swift:112-119`, so they now agree.

**Android** — `Utilities.kt` `getFilesInDir(dir, pathSoFar)`, seeded from `MainActivity.kt`.
Was seeded `""`; now seeded with `dir.name`.

**Windows / Linux** — `core/src/utils.rs` `expand_selection()`, called from the Tauri
`expand_files` command (`Flying Carpet/src-tauri/src/main.rs`). Each selected root is
expanded and its files named against the root's parent. The result — `Vec<SendFile>`,
`{path, name}` — flows through `Mode::Send` to `sending::send_file`, which now just writes
the name it was given.

Moving the naming decision to selection time is what made the desktop side correct: the old
code tried to recover a prefix at send time from a flat list of absolute paths, by which
point the information about *what the user actually picked* was gone.

## Bugs fixed along the way

### 1. Android → Windows/Linux failed outright for folders with sub-folders

`getFilesInDir` built nested paths as `pathSoFar + '/' + name` from a seed of `""`, so the
first level produced `"/sub"` and Android sent **`/sub/a.jpg`** — with a leading slash.

Receivers disagreed about that slash. Android (`sanitizeRelativeFilename`) and Apple
(`safeDestinationURL`) both skip empty components and coped. The Rust receiver
(`receiving.rs` `sanitize_relative_filename`) sees `Component::RootDir` and errors with
`Received invalid filename path: /sub/a.jpg`, aborting the transfer.

Worth recording that the hardening in `5debeda` did not cause this — it exposed it. Before
that commit the Rust receiver did `full_path.push("/sub/a.jpg")`, and pushing an absolute
path **replaces** the buffer, so those files were written outside the chosen destination
entirely. Turning a silent path escape into a loud error was correct; the defect was always
on the Android side.

Fixed by seeding with the folder name (which makes `pathSoFar` non-empty) *and* by guarding
the join so it can never emit a leading separator regardless of seed. Covered by
`names_are_relative_and_slash_separated`, which asserts the Rust receiver's own sanitizer
accepts everything the Rust sender produces.

### 2. Desktop lost a directory level, or aborted, depending on the folder's shape

The old prefix was "the parent with the fewest components", ties broken by walk order:

- `P` with ≥1 file directly inside → prefix `P`. Contents dumped (the old intended behavior).
- `P` containing only `P/s1` → prefix became `P/s1`, silently dropping `s1` from every path.
- `P` containing only `P/s1` and `P/s2` → prefix locked to whichever was walked first, and
  every file under the sibling failed `strip_prefix`, killing the transfer with
  `Error sending file: Strip prefix error`.

The same abort hit drag-and-drop of two folders at once, or of files from two different
directories — which the deleted comment in `lib.rs` had half-acknowledged. Resolving each
selection against its own parent removes the shared-prefix concept entirely, so all of these
now work. Covered by `folder_of_only_subdirectories_keeps_full_structure` and
`selections_from_different_directories_coexist`.

### 3. Empty file list panicked the desktop sender

`start_transfer` indexed `files[0]` to seed the prefix. JavaScript's `if (!selectedFiles)`
check passes an empty array through (`[]` is truthy), so an empty selection reached the core
and panicked the transfer task. `Mode::Send` now rejects an empty list with a message, and
the frontend reports empty selections before starting.

### 4. Short write of the file hash

Unrelated to folders, found by clippy while working here: `sending.rs` used `stream.write()`
for the 32-byte "do you already have this file" hash. A partial write would have sent a
truncated hash and left the remainder to be read as the next protocol field. Now `write_all`.

### 5. Help text described a gesture that mostly doesn't exist

> "(To send a folder, drag it onto the window instead of clicking 'Start Transfer'.)"

Copy-pasted into the desktop app, Android, and macOS. Android has no drag-and-drop (it has a
Send Folder checkbox), and macOS has no drop handler either — its `NSOpenPanel` sets
`canChooseDirectories = true`, so you just pick the folder. Each platform's text now
describes what that platform actually supports, and all four (plus iOS's storyboard help)
state that a sent folder is recreated on the receiving end.

## Tests

`core/src/utils.rs` `selection_tests` — six cases over a real temp tree, run by `cargo test`:

| Test | Guards |
|---|---|
| `selected_folder_keeps_its_own_name` | the headline behavior |
| `selected_files_are_flat` | ordinary file sends didn't regress into folders |
| `folder_of_only_subdirectories_keeps_full_structure` | bug 2 |
| `selections_from_different_directories_coexist` | bug 2's abort cases |
| `names_are_relative_and_slash_separated` | bug 1, asserted against the real receiver sanitizer |
| `nonexistent_selection_is_skipped` | unreadable paths don't abort a whole transfer |

`transfer_tests::end_to_end_encrypted_transfer` now sends under `album/photo.bin` and asserts
the file arrives at `recv/album/photo.bin`, so directory recreation is covered end to end
through the real Noise stack.

Kotlin and Swift are covered by the Tier 6 hardware rows in `docs/v10-release-test-plan.md` —
neither has a unit-test seam over its platform file APIs (SAF `DocumentFile`,
`NSFileCoordinator`).

## Cross-repo note

The iOS/macOS half of this lives in `Apple/` (`shared/Transfer.swift`,
`macOS/…/AppDelegate.swift`, `iOS/…/Main.storyboard`) and must land with the changes here.
No wire-format bytes changed, so this is not a protocol break and needs no version bump —
but shipping one repo without the other would restore exactly the user-visible inconsistency
this removes.

## Fork addition: folders arriving through the share sheet (2026-09-06)

Android only. A folder shared to the app from a file manager used to be armed as if it were a
file and then killed the transfer partway through — `open(2)` on a directory with `O_RDONLY`
succeeds on Linux, so `ContentResolver.openInputStream()` returned a healthy-looking stream
and the first `read()` in `Send.kt` failed with `EISDIR`, after the size had already been
announced. 白い熊 had to fall back to picking the same folder again through "Directory to
send".

`handleShareIntent()` now classifies each shared URI before opening anything
(`sharedUriIsDirectory()`, `Utilities.kt`) and expands the folders
(`expandSharedDirectory()`), seeding `filePaths` with the folder's own name — so a shared
folder produces exactly the selection the folder picker would have produced, and the rule at
the top of this document holds for it unchanged.

Three URI shapes have to be handled, because senders do not agree on one:

| Shape | How it is expanded |
|---|---|
| SAF **tree** URI | the grant covers the children — `DocumentFile.fromTreeUri()` + `getFilesInDir()` |
| SAF **document** URI of `MIME_TYPE_DIR` | the grant covers that one document only; the id is rebuilt with `buildTreeDocumentUri()` and tried, then the filesystem fallback below |
| `file://` | walked with `java.io.File` via `getFilesInRawDir()`, each file wrapped in `DocumentFile.fromFile()` |
| a plain **FileProvider** URI | neither of the above: settled by `fstat`-ing the descriptor, and the path recovered by `readlink`-ing `/proc/self/fd/<n>` |

That last row is why detection cannot rest on the mime type. A file manager sharing through an
ordinary `FileProvider` answers every cheap question wrongly: `getType()` guesses a mime from
the name and returns something perfectly ordinary (白い熊, 2026-09-07: Total Commander offered a
folder named `... 1080p.10bit` as a 3.44KB file), the URI is not a document URI so it carries no
document id to resolve, and `openFile()` hands over a real directory descriptor without
complaint. `probeDescriptor()` therefore asks the kernel — `S_ISDIR` on the `fstat` of the
descriptor the provider itself opened — which no provider can misreport, and recovers the path
from `/proc/self/fd`, which a FileProvider URI has no other way to give up.

The filesystem fallback maps a document id (`primary:Download/foo`) to a real path and is
legitimate here only because the fork already holds `MANAGE_EXTERNAL_STORAGE` for the
Export / Import page. It is depth-limited against symlink loops. A folder held by a cloud app
has no path to walk and cannot be expanded by either route; that case says so plainly instead
of arming a transfer that would die at the first read.

Mixed shares (files *and* folders in one share) work as a single selection — `addSharedFile()`
appends to `files`, `fileStreams` and `filePaths` together, because `MainViewModel` pairs the
three by position.

"Directory to send" still opens the picker unconditionally (白い熊, 2026-09-06), so there is
always a way to choose a different folder than the one that was shared.
