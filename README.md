# fat32-app (USB Copy)

A small Android app for one job: plug a FAT16 or FAT32 USB drive into the phone, pick files, copy them to the drive, and be told when it is safe to unplug.

Personal, sideloaded tool. Not published. See [PLAN.md](PLAN.md) for the goal, the design decisions and what is still open.

## Status

The code is complete for the plan's milestones M0 to M4, plus the share-sheet target from M5.

- Checked: all Kotlin code compiles against the Android 15 (API 35) SDK and both libraries. The copy engine and the FAT layer pass 29 tests, and files written to FAT12, FAT16 and FAT32 disk images were verified with an independent FAT checker and a second FAT implementation.
- Not checked yet: the Gradle build itself and anything on a real phone (USB access, screens, notifications). The first build and the first run on the Pixel are the next step. Use a drive whose contents you can afford to lose for the first tests.

## Get the APK

### Option A: let GitHub build it (nothing to install on the PC)

1. Commit and push this repo.
2. On GitHub open the **Actions** tab, then the latest **Build APK** run.
3. Download the artifact `usb-copy-debug-apk` and unzip it to get `app-debug.apk`.

### Option B: build on Windows from the command line

1. Install JDK 17: `winget install EclipseAdoptium.Temurin.17.JDK`
2. Download "Command line tools only" from <https://developer.android.com/studio#command-line-tools-only> and unzip so that `C:\Android\cmdline-tools\latest\bin\sdkmanager.bat` exists.
3. Set the SDK location and install what the build needs:
   ```
   setx ANDROID_HOME C:\Android
   C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   C:\Android\cmdline-tools\latest\bin\sdkmanager.bat --licenses
   ```
4. In a new terminal, in this folder: `gradlew.bat assembleDebug`
5. The APK is `app\build\outputs\apk\debug\app-debug.apk`. Tests: `gradlew.bat testDebugUnitTest`.

Use JDK 17 for this toolchain (Gradle 8.9, Android Gradle plugin 8.7.3, Kotlin 2.0.21).

## Install on the phone

Copy `app-debug.apk` to the phone (cable, Drive, or a download) and open it. Android asks once to allow installs from that source.

With `adb` and wireless debugging you can instead run `adb install -r app-debug.apk`. Wireless is the practical way, because the phone's USB port is needed for the drive.

All builds are signed with the debug key in `keystore/`, so a new build installs over the old one.

## Use

On the Pixel 10 Pro, Android mounts the USB drive itself, so this is the normal flow:

1. Plug in the drive and open **USB Copy**. The card says **USB drive found**.
2. **Pick folder with Android**. The picker opens on the USB drive; choose or create the target folder.
3. **Add files** (the picker starts in the phone's storage, then in the folder you last added from), or share files into the app from another app.
4. **Copy**. The card turns red and says not to unplug.
5. When it is done, eject the drive in Android (the USB notification or Storage settings), then unplug.
6. Next drive: plug it in, **Pick folder with Android** (opens at the new drive's root), **Copy**.

The file list, "verify" and the name-clash choice are kept after copying and when the app is closed, so the same files can go to drive after drive. Remove single files with ✕, or all with **Clear list**. Files shared into the app from another app only stay until the app restarts; add them with **Add files** to keep them.

If Android does not mount a drive, the app handles it itself: allow USB access when asked, and unplug when the card is green and says **Safe to unplug**. The same can be forced for a mounted drive under Diagnostics, "Let this app handle the drive itself (advanced)", if copying through Android does not work.

## Diagnostics

At the bottom of the screen, **Diagnostics** opens the app's log: drive detection, USB permission, partition and FAT details, per-file speed, and every error with its stack trace. Crashes are recorded too.

- **Inspect drive (read only)** logs everything about the connected drive and measures read speed, without writing. A good first step with a new stick.
- **Share log** sends a text file (app log, phone details, and the app's own system log) to any app, for example email or Drive.
- **Verbose** logs every USB transfer. Only switch it on while chasing a problem.

With adb connected, `adb logcat -s USBCopy` shows the same lines live.

### adb over Wi-Fi

1. `winget install -e --id Google.PlatformTools`, then open a new terminal.
2. On the phone: Settings, About phone, tap Build number 7 times. Then Settings, System, Developer options, Wireless debugging: on. Phone and PC on the same Wi-Fi.
3. Once: tap "Pair device with pairing code", run `adb pair <ip>:<port>` with that dialog's address and enter the code.
4. Each session: `adb connect <ip>:<port>` with the address on the Wireless debugging screen (a different port), then `adb devices`.
5. `adb install -r app-debug.apk` to update the app.

## How "safe to unplug" works

There are two routes, because an app cannot unmount a drive that Android has mounted.

| | Direct (default) | Through Android |
|---|---|---|
| When | Android has not mounted the drive | Android has mounted the drive |
| Who handles the file system | The app (libaums for USB, fat32-lib for FAT) | Android |
| Safe to unplug | When the card is green: everything is written and nothing is cached | Only after ejecting the drive in Android |

On the direct route each file is written, optionally read back and compared, and then the directory and allocation table are written. Nothing is kept in memory afterwards, so an idle drive is always safe to unplug. The app keeps hold of the USB device until it is unplugged, which stops Android from mounting it in the meantime.

## Limits and known risks

- FAT12, FAT16 and FAT32 only. No exFAT or NTFS.
- One file can be at most 4 GiB minus 1 byte (a FAT limit). On the direct route larger files are refused before copying starts; through Android the copy fails when it reaches the limit.
- Unplugging during a copy can leave the file system inconsistent, as on any computer. The app then refuses the drive until it is repaired (`chkdsk` on Windows, `fsck.fat` on Linux). The app also refuses drives whose two allocation tables already differ.
- Very large FAT32 volumes with small clusters need a lot of memory, because the allocation table is held in memory. The app refuses such a volume with a clear message.
- File dates on the drive are the time of copying, not the original dates.
- libaums ships native code built for 4 KB memory pages. If the phone runs with 16 KB pages, Android may show a compatibility notice. Normal copying does not need that code; only USB error recovery does.
- Drives that use USB Attached SCSI only, or card readers with several slots, are untested.

## Project layout

```
app/src/main/java/com/jespersv/fat32app/
  core/     Copy engine, FAT name rules, boot sector probe. Plain Kotlin, no Android.
  usb/      USB drive access: libaums for the device, fat32-lib for FAT.
  saf/      The "through Android" route and reading the picked files.
  Session.kt       App state and drive detection.
  CopyService.kt   Foreground service that runs the copy.
  MainActivity.kt  The single screen.
app/src/test/      Unit tests, including end-to-end tests on in-memory FAT images.
.github/workflows/build.yml   Builds and tests on every push.
```

## Libraries

- [libaums](https://github.com/magnusja/libaums) 0.10.0, Apache 2.0: USB mass storage access.
- [fat32-lib](https://github.com/waldheinz/fat32-lib) 0.6.5, LGPL 2.1: FAT file system. Kept as source in `fat32-lib/` with one fix for drives with 64 KiB clusters (see `fat32-lib/README.md`).
