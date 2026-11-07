# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sasayaki (`com.sasayaki.player`) is a native Android audio player for local Japanese ASMR works. Its core job is making sense of how DLsite-style downloads are organized: version folders (SE有り/無し, 左右反転, mp3/wav), audio split into segments, nested category folders, and ZIP archives. Everything is read **in place** under all-files access (`MANAGE_EXTERNAL_STORAGE`). Audio, documents and images must never be copied or extracted into app storage; the only exception is downscaled cover thumbnails in `cacheDir/covers`.

Stack: Kotlin and Jetpack Compose (Material 3), Media3/ExoPlayer, Coil 3, kotlinx.serialization, DataStore. AGP 9 with built-in Kotlin, so there is no `kotlin-android` plugin. compileSdk 36, minSdk 26, Java 17 with core library desugaring. Dependency versions live in `gradle/libs.versions.toml`.

## Commands

```bash
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin     # fast compile check
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:testDebugUnitTest --tests "*ScanSampleTest*"   # single test
```

`ScanSampleTest` is the main tool for working on scanning heuristics. It runs the real scanner on a library folder (`SASAYAKI_SAMPLE`, default `C:/Users/User/Documents/ASMR`) and writes how every entry, version and track was interpreted to `SASAYAKI_OUT` (default `app/build/scan-dump.txt`). If the folder is missing, the test is skipped. After changing anything in `data/scan/`, rerun it and diff the dump against the previous one to catch regressions:

```bash
SASAYAKI_OUT="$TEMP/scan-dump.txt" ./gradlew :app:testDebugUnitTest --tests "*ScanSampleTest*"
```

Device testing uses the user's physical phone over adb, not an emulator. On Git Bash, set `MSYS_NO_PATHCONV=1` so device paths aren't rewritten. adb hangs when a directory is pushed to a destination with a Japanese name: push under an ASCII name, then rename it on the device.

## Architecture

**Data flow.** `LibraryScanner` produces a list of `Entry` objects (a work), each holding `Version`s that hold `Track`s. The list is saved to `filesDir/library.json`. The UI shows it, and `PlaybackEngine` turns one `Version` into an ExoPlayer playlist.

**`Ref` is the universal file handle** (`data/Models.kt`). It is either a plain path, or a ZIP path plus an entry name with cached local-header offset, compression method and sizes. All reading goes through `Ref`, so ZIP and folder content behave the same:
- `Vfs.openStream` and `Vfs.openReader` read the bytes.
- `RefUris` and `ArchiveDataSource` handle playback; ZIP entries use `szip://` URIs, plain files use `file://`.
- `RefProvider` (a ContentProvider) shares files with other apps. ZIP entries are served through `StorageManager.openProxyFileDescriptor`, so the viewer reads straight from the archive.
- `Ref.cacheKey` keys the duration cache and the thumbnails.

**`data/vfs/`** builds a `VNode` tree over a folder or a ZIP. Archives found inside a work's folder are mounted as directories. `ZipIndex` is a custom central-directory parser, not `java.util.zip`, for these reasons:
- it handles ZIP64, the UTF-8 flag, the 0x7075 unicode-path field, and a fallback to Shift-JIS/windows-31j for names;
- `ZipEntryReader` gives random access to stored entries;
- deflated entries are read with a forward-only Inflater that restarts on backward seeks.

**`data/scan/`** holds the heuristics. They were tuned against the real sample library, so check the dump after any change.
- `LibraryScanner` walks the library roots and decides whether each folder is a category or a work (`isEntryFolder`). It merges a folder and a ZIP of the same work (matched by title or RJ code), preferring the folder copy.
- `EntryAnalyzer`:
  - groups audio files into versions;
  - splits a single folder into variants only when every tag/format group lines up with another;
  - completes partial variants (e.g. a 効果音なし folder with only 2 tracks) with tracks from the main version, marked `fromBase` / `baseLabel`;
  - computes version labels and sort order (本編 first, おまけ/特典 last);
  - matches subtitle files (LRC/VTT/SRT) to tracks.
- `Naming` has the string logic: NFKC normalization, natural sort with full-width digits, removing leading track numbers, track keys, parsing variant tags, RJ codes.
- `CoverPicker` scores image filenames to prefer the plain (logo-free) illustration: ロゴなし, イラストのみ and 壁紙 score up; アイコン, スマホ and small files score down.

**Playback.** `PlaybackEngine` (a singleton in `AppGraph`, used on the main thread) owns the one ExoPlayer, which `PlaybackService` (a `MediaSessionService`) wraps in a MediaSession. The UI calls the engine directly. `MainActivity` binds a `MediaController` only to keep the service alive. The engine:
- exposes `EngineState` as a StateFlow;
- handles skipping across segment boundaries, seeking on the whole-work timeline, and switching versions while keeping the position (matched by track key, then core name, then index);
- runs the sleep timer, saves progress every 5 s, and records durations reported by the player;
- serves media resumption through `resumptionItems()`.

Artwork goes into MediaMetadata as **bytes**, not a file URI, because System UI can't read the app's private cache.

**State and persistence** (`AppGraph` in `SasayakiApp.kt`):
- `SettingsRepository`: DataStore.
- `LibraryRepository`, `ProgressRepository` (per-entry resume position) and `DurationRepository`: JSON files written by `JsonStore` (atomic save, debounced).
- `CoverCache` makes downscaled JPEGs, served to Coil by `CoverFetcher` and `CoverKeyer` for the `Cover` model.

**UI** (`ui/`). There is no navigation library. `MainActivity` keeps a stack of `Route` objects, with the full-screen player and the folder picker as overlays on top. Screens read the repository StateFlows directly, and the theme is dark by default.

## UI writing

Assume the user knows common UI and design patterns. Don't add labels, captions or helper text that explain what a control does or what an element represents; the segmented progress bar needs no "Whole work" caption, for example. Name buttons and titles after the action, not the state or the implementation: "Add folder" is right, "Add folder (files are read in place, never copied)" is wrong.

## Gotchas

- Do not run Kotlin source containing regex backslashes through Python or shell heredoc string replacement. It silently turns `\\` into `\` and breaks the regexes in `Naming.kt` and `CoverPicker.kt`. Use the Edit tool for those files.
- MP3s in the sample library are CBR without Xing/LAME headers. The extractor has constant-bitrate seeking enabled. Seamless segment transitions depend on ExoPlayer loading the next playlist item in advance; there is no encoder-delay metadata to trim.
