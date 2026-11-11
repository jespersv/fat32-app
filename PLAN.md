# fat32-app: Plan

## Goal
A small, sideloaded Android app (personal use, not published for now) that does one job:

> Plug a FAT32/FAT16 USB drive into my phone, pick file(s), copy them to the drive, then tell me when it is safe to unplug.

## Non-goals (for now)
- Play Store release, polished UI, onboarding, analytics
- File management on the drive (delete, rename, move)
- exFAT/NTFS support
- Copying from the USB drive to the phone

## Known facts
- Phone: Google Pixel 10 Pro, Android 17 (build CP3A.261005.005).
- PC: Windows, no Android Studio. Builds run from the command line or on GitHub Actions.
- Assumed, not yet checked: the drive does not show up in the phone's Files app.

## Design as built

Two routes to the drive, chosen automatically:

1. **Direct** (default). The app claims the USB device itself. libaums handles USB mass storage and the partition table; fat32-lib handles FAT12/16/32. Used when Android has not mounted the drive.
2. **Through Android**. If Android has mounted the drive, the app must not grab it underneath. The user picks a folder with Android's picker and the app writes through it. Android has to eject the drive before it is unplugged.

Copying, on both routes:
- Each file is written under a `.part` name, optionally read back and compared (CRC32), then renamed. An existing file is only replaced after the new copy is complete.
- On the direct route, refused before any data is written: files over 4 GiB minus 1 byte, and files larger than the free space. Through Android those limits are not known in advance, so such a copy fails part-way and the partial file is removed.
- Names are adjusted to FAT rules. On a name clash: skip, replace or keep both.
- The copy runs in a foreground service with a wake lock, with progress in a notification and a cancel button.
- Unplugging mid-copy fails the current file and stops the rest with a clear message.

"Safe to unplug" on the direct route means: all data, the directory and both allocation tables are written, nothing is cached, and the app keeps the USB device claimed so Android cannot mount it in the meantime.

## Decisions that differ from the first plan

| First plan | Built | Why |
|---|---|---|
| libaums for USB and FAT32 | libaums for USB only, fat32-lib for all FAT types | Testing showed libaums 0.10.0 updates only the first allocation table, which leaves a drive that disk checkers flag as damaged. It also has no FAT16 support. |
| Jetpack Compose, coroutines | Plain Android views, a worker thread | No Android toolchain was available while writing. Code without AndroidX could be compiled and checked locally; Compose could not. The screen is one simple page either way. |
| targetSdk = phone's API level | compileSdk/targetSdk 35, minSdk 33 | A toolchain version set known to work together (Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21). Raise it once the build is confirmed. |
| SAF first, libaums as fallback | Both, picked by whether Android mounted the drive | The phone's behaviour with the drive is not known yet. |

## Milestones

- [x] **M0 Project init.** Gradle project, wrapper, `.gitignore`, shared debug key, GitHub Actions build.
- [ ] **M1 On-device spike.** Not possible without the phone. Replaced for now by tests on FAT disk images. See the checklist below.
- [x] **M2 Core copy.** File and folder selection, streaming copy, progress, `.part` and rename.
- [x] **M3 Safe-unplug UX.** Status card, verification, detach handling, Storage settings shortcut.
- [x] **M4 Robustness.** Foreground service, cancel, name clashes, 4 GiB check, name rules.
- [x] **M5 (part).** Share files into the app from other apps.
- [ ] **First real build and device test.** Nothing has been built with Gradle or run on a phone yet.

## What has been verified

- All app code compiles against the API 35 SDK with libaums 0.10.0 and fat32-lib 0.6.5.
- 29 unit tests pass, including end-to-end copies onto FAT12, FAT16 and FAT32 images, with and without a partition table: full drive, unplug during copy, cancel, replace, stale FAT32 free-space hint, damaged allocation table, and "opening a drive writes nothing".
- Images created by a different FAT implementation (pyfatfs) were filled by the copy engine and then checked with an independent FAT checker: both allocation tables identical, every file's cluster chain matches its size, no lost clusters, FAT32 free count correct, all file contents identical to the source.

## Device test checklist (first run on the Pixel)

Use a drive with nothing important on it.

1. Plug in the drive. Does it appear in the Files app? (Answers the open question.)
2. Open the app. Does it ask for USB access and reach the green card with the right name, file system and free space?
3. Copy one small file. Unplug on green. Check the file on a PC and run `chkdsk X:` (should report no problems).
4. Copy many files at once, and one file of about 1 to 3 GB. Note the speed.
5. Try a file over 4 GB: it should be refused before copying.
6. Start a large copy and press Cancel: no leftover file.
7. Start a large copy and turn the screen off: it should keep going.
8. Unplug during a copy: the app should report it. Expect to run `chkdsk` on the drive afterwards.
9. If the drive is FAT16: repeat steps 2 and 3 with it.

## Open questions
1. Is the drive FAT32 or FAT16? Both are supported; it only matters for testing.
2. Does Android mount the drive? Decides which route is used day to day.
3. Does libaums work on Android 17 with this drive? Only the device test can tell.
