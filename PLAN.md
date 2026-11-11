# fat32-app: Plan

## Goal
A small, sideloaded Android app (personal use, not published for now) that does one job:

> Plug a FAT32/FAT16 USB drive into my phone, pick file(s), copy them to the drive, then tell me when it is safe to unplug.

## Non-goals (for now)
- Play Store release, polished UI, onboarding, analytics
- File browsing/management on the drive (delete, rename, move)
- exFAT/NTFS support (FAT16/FAT32 only)
- Copying *from* the USB to the phone (easy to add later)

## Key technical question: who mounts the drive?
Two possible approaches. Which one applies depends on the phone, so Phase 0 decides.

| | A. Storage Access Framework (SAF) | B. libaums (raw USB mass storage) |
|---|---|---|
| How | Android mounts the drive itself; app writes through `DocumentFile` / `ContentResolver` | App talks to the USB device directly via `UsbManager` and parses FAT itself ([libaums](https://github.com/magnusja/libaums)) |
| Pros | No USB permission handling, tiny code, no FAT parsing, works with any filesystem the OS supports | Works even if the OS does not mount the drive; full control over flushing |
| Cons | Cannot unmount; "safe to remove" is best effort (see below) | FAT16/32 only; OS may also try to mount, causing conflicts; more code to maintain; slower |
| When | **Default, try first** | Fallback if SAF does not see the drive |

Decision: build with **SAF first**. Only fall back to libaums if Phase 0 shows the phone does not expose the drive.

## "Safely disconnect": what is actually possible
Apps cannot unmount a volume on stock Android (that is a system/privileged action). What the app *can* do:
1. Write each file via a `ParcelFileDescriptor`, call `FileDescriptor.sync()` (fsync) and close it, so data is flushed to the device, not just the page cache.
2. Verify after copy (size match at minimum, optional checksum read-back).
3. Show a clear "Done, flushed. Safe to eject" state, with a button that opens system Storage settings where the user taps Eject.
(With libaums the app owns the FS, so `flush()` + close gives the same guarantee without relying on the OS.)

## Design
- **Language/UI:** Kotlin, Jetpack Compose, single activity, single screen.
- **minSdk 26 / targetSdk current stable.** Personal device, so minSdk can be set to my phone's version.
- **Flow:**
  1. Tap *Select destination* -> `ACTION_OPEN_DOCUMENT_TREE` -> choose a folder on the USB drive. Persist the URI permission (`takePersistableUriPermission`) so later runs skip this if the drive is the same.
  2. Tap *Select files* -> `OpenMultipleDocuments`.
  3. Tap *Copy* -> background copy with per-file + total progress, cancel button.
  4. On finish: fsync, verify, show "Safe to eject".
- **Copy engine:** a plain Kotlin class (`Copier`) with streaming copy (1 MiB buffer), coroutine-based, progress as `Flow`. No UI dependencies so it is unit-testable.
- **Long copies:** run in a foreground service (or `WorkManager` expedited) so the copy survives screen-off; notification shows progress.
- **Detect unplug:** listen for `ACTION_MEDIA_EJECT` / `UsbManager.ACTION_USB_DEVICE_DETACHED` and `StorageVolume` state; fail the copy cleanly and say which files were incomplete.
- **FAT constraints to handle explicitly:**
  - Max file size 4 GiB - 1 byte: refuse up front with a clear message.
  - Filename restrictions on FAT (`" * / : < > ? \ |`) and long-name quirks: sanitize or warn.
  - FAT has 2 s timestamp resolution; ignore for this app.
  - Name collisions: ask / auto-suffix / skip (default: skip, report).
- **Failure handling:** write to `name.part`, rename on success, so an interrupted copy never looks like a finished file.

## Milestones
- **M0: Project init.** Gradle Kotlin DSL project, Compose, app id `com.jespersv.fat32app` (change if you prefer), `.gitignore`, CI-less. Builds and installs on phone via adb.
- **M1: Phase 0 spike.** Minimal app that opens the document-tree picker. Plug in the drive and check: does the drive appear as a root? Can I create a file in it? Result decides SAF vs libaums.
- **M2: Core copy.** Select files + destination, streaming copy, progress, `.part` rename, fsync.
- **M3: Safe-eject UX.** Verify step, "safe to eject" screen, eject-settings shortcut, detach handling.
- **M4: Robustness.** Foreground service, cancel, collisions, 4 GiB check, filename sanitizing, large-file test.
- **M5 (optional):** share-sheet target so I can share files into the app from other apps; libaums fallback if needed; copy USB -> phone.

## Testing
- Unit tests for `Copier` (against temp dirs / fake streams), name sanitizer, size-limit check.
- Manual device checklist: small file, many files, ~3 GB file, >4 GiB rejected, unplug mid-copy, copy then eject, re-plug and verify contents on a PC.

## Open questions
1. Phone model and Android version? (affects minSdk and how USB storage is exposed)
2. Does the phone already show the drive in the Files app when plugged in? If yes, SAF is almost certainly enough.
3. Typical file sizes/counts? (decides whether foreground service is needed in M2 or M4)
4. Any preferred app name / package id?
5. Is Android Studio installed on the PC, or should the project be buildable from the command line (Gradle wrapper + adb)?

## Dev setup (to confirm at M0)
- JDK 17, Android SDK (platform + build-tools), Gradle wrapper committed
- Install: `./gradlew installDebug` with USB debugging or wireless adb
