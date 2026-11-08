# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sasayaki (`com.sasayaki.player`) is a native Android audio player for local Japanese ASMR works. Its core job is making sense of how DLsite-style downloads are organized: version folders (SE有り/無し, 左右反転, mp3/wav), audio split into segments, nested category folders, and ZIP archives. Everything is read **in place** under all-files access (`MANAGE_EXTERNAL_STORAGE`). Audio, documents and images must never be copied or extracted into app storage; the only exception is downscaled cover thumbnails in `cacheDir/covers`.

Stack: Kotlin and Jetpack Compose (Material 3), Media3/ExoPlayer, Coil 3, kotlinx.serialization, DataStore. AGP 9 with built-in Kotlin, so there is no `kotlin-android` plugin. compileSdk 36, minSdk 26, Java 17 with core library desugaring. Dependency versions live in `gradle/libs.versions.toml`.

## Commands

```bash
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # R8-minified APK -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:compileDebugKotlin     # fast compile check
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "*ScanSampleTest*"   # single test
```

`ScanSampleTest` is the main tool for working on scanning heuristics. It runs the real scanner on a library folder (`SASAYAKI_SAMPLE`, default `C:/Users/User/Documents/ASMR`) and writes how every entry, version and track was interpreted to `SASAYAKI_OUT` (default `app/build/scan-dump.txt`). If the folder is missing, the test is skipped. After changing anything in `data/scan/`, rerun it and diff the dump against the previous one to catch regressions:

```bash
SASAYAKI_OUT="$TEMP/scan-dump.txt" ./gradlew :app:testDebugUnitTest --tests "*ScanSampleTest*"
```

`CoverSeedSampleTest` does the same for cover colors: it renders each sample cover next to the dark and light surface, accent and secondary colors picked from it, to `SASAYAKI_SEEDS_OUT` (default `app/build/cover-seeds.png`). Render it before and after changing `CoverSeed` and compare.

Release builds are signed with the key described in `keystore.properties` at the project root (git-ignored, like `*.jks`); without that file they fall back to the debug key. Don't read or print that file, it holds the key's passwords. Debug builds are a separate app, `com.sasayaki.player.debug` ("Sasayaki Debug"), so they install next to the release app `com.sasayaki.player` without touching its data.

R8 strips anything reached only by reflection. After adding serialized classes, libraries or anything instantiated by name (services, providers, Coil components), check the release build on the device as well.

Device testing uses the user's physical phone over adb, not an emulator. On Git Bash, set `MSYS_NO_PATHCONV=1` so device paths aren't rewritten. adb hangs when a directory is pushed to a destination with a Japanese name: push under an ASCII name, then rename it on the device.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the data model, the `Ref` file handle, ZIP and VFS internals, the scanning heuristics, playback, persistence and UI structure. Read it before changing scanning or file access, and keep it up to date when the architecture changes.

## UI writing

Assume the user knows common UI and design patterns. Don't add labels, captions or helper text that explain what a control does or what an element represents; the segmented progress bar needs no "Whole work" caption, for example. Name buttons and titles after the action, not the state or the implementation: "Add folder" is right, "Add folder (files are read in place, never copied)" is wrong.

## Gotchas

- Do not run Kotlin source containing regex backslashes through Python or shell heredoc string replacement. It silently turns `\\` into `\` and breaks the regexes in `Naming.kt` and `CoverPicker.kt`. Use the Edit tool for those files.
- MP3s in the sample library are CBR without Xing/LAME headers. The extractor has constant-bitrate seeking enabled. Seamless segment transitions depend on ExoPlayer loading the next playlist item in advance; there is no encoder-delay metadata to trim.

## Finishing a task

Once a task's changes work in the debug build on the phone, build the release APK and install it over the release app with `adb install -r`, so the phone always runs the latest release build. If the phone isn't reachable over adb, say so instead of skipping it silently.

After finishing and passing the necessary checks, commit the task's changes unless the user asks not to or to leave them uncommitted. Use Conventional Commits, with a closing keyword when implementing or fixing a GitHub issue.
