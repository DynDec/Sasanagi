# Architecture

Sasayaki is a single-module Android app (`:app`, package `com.sasayaki.player`). This document describes how the pieces fit together. Build commands and working conventions are in [CLAUDE.md](CLAUDE.md).

## Constraint: everything is read in place

Works are read where they are, under all-files access (`MANAGE_EXTERNAL_STORAGE`). Audio, documents and illustrations are never copied or extracted into app storage. That includes files inside ZIP archives. The only media-derived data the app stores is downscaled cover thumbnails in `cacheDir/covers`. Most of the design below follows from this rule.

## Overview

```
 library roots (folders, ZIPs)
          │
          ▼
 data/vfs      VNode tree over folders and ZIPs       ◄── Ref: handle to any file
          │                                                (plain or ZIP entry)
          ▼
 data/scan     LibraryScanner → EntryAnalyzer          heuristics: works, versions,
          │    (Naming, CoverPicker)                   tracks, covers, subtitles
          ▼
 data          LibraryRepository (library.json)
               ProgressRepository, DurationRepository, SettingsRepository, CoverCache
          │
          ├──────────────► ui/       Compose screens (read StateFlows)
          │                  │
          ▼                  ▼
 playback      PlaybackEngine (one ExoPlayer) ◄── PlaybackService (MediaSession, notification)
                 └─ RoutingDataSource → ArchiveDataSource (szip://) / DefaultDataSource (file://)
```

The app-wide singletons live in `AppGraph` (`SasayakiApp.kt`). They are created once in `Application.onCreate`, and the engine is created lazily on first use.

## Data model (`data/Models.kt`)

- **`Entry`**: one work. It has a title, an RJ code, a category path (the folders above it) and its source folders or ZIPs. It also holds its versions, cover, images and docs.
- **`Version`**: one way of listening to the work, such as 本編, 効果音なし, 左右反転 or the WAV copy. It holds an ordered list of tracks.
  - A *filled* version (`baseLabel != null`) re-records only some tracks. The rest are borrowed from a base version and marked `Track.fromBase`.
- **`Track`**: one audio file, i.e. one segment. It has a display title, format, a track key (e.g. `4`, `4r`), a normalized core name and an optional subtitle `Ref`.
- **`Ref`**: the handle to any file. It is either a plain path, or a ZIP path plus an entry name. For ZIP entries it caches the local-header offset, compression method and sizes, so an entry opens without re-reading the archive index. `Ref.cacheKey` identifies the file in the duration and thumbnail caches.

The scan result is a `Library` serialized to `filesDir/library.json`. Entry ids are `"<root>::<relative path>"`.

## File access

Everything that reads a file goes through `Ref`, so ZIP and folder content behave the same:

| Consumer | Mechanism |
|---|---|
| Scanning, text, images | `Vfs.openStream`, `Vfs.readBytes` |
| Metadata (durations, embedded art) | `MediaMetadataRetriever` with `Vfs.mediaDataSource` for ZIP entries |
| Playback | `RefUris` maps a `Ref` to `file://` or `szip://…`. `RoutingDataSource` sends `szip://` to `ArchiveDataSource` and everything else to `DefaultDataSource`. |
| Other apps (PDF scripts, etc.) | `RefProvider` (non-exported, per-URI grants) returns plain files directly. ZIP entries are served through `StorageManager.openProxyFileDescriptor`, so the viewer reads straight from the archive. |

### ZIP support (`data/vfs/ZipIndex.kt`)

`ZipIndex` is a custom central-directory parser. `java.util.zip` isn't used because downloads need:
- ZIP64;
- the UTF-8 flag and the `0x7075` unicode-path extra field;
- a fallback from strict UTF-8 to windows-31j/Shift-JIS for entry names;
- self-extracting archives, which have a base offset.

`ZipEntryReader` reads one entry in place:
- **stored** entries (method 0, typical for audio) have full random access;
- **deflated** entries (method 8) use a forward-only `Inflater`, which restarts from the beginning on a backward seek.

Encrypted entries and ZIPs nested inside ZIPs are skipped.

`Vfs.fromFolder` and `Vfs.fromZip` build a `VNode` tree. ZIPs found inside a work's folder are mounted as subdirectories, and junk files (`__MACOSX`, `._*`, dotfiles, `Thumbs.db`) are ignored.

## Scanning (`data/scan/`)

These heuristics were tuned against a real library of DLsite-style downloads. `ScanSampleTest` dumps the scanner's view of a library so a change can be compared before and after (see CLAUDE.md).

1. **`LibraryScanner`** walks each library root and decides, folder by folder, whether it is a category or a work (`isEntryFolder`). For example:
   - audio directly inside a folder makes it a work;
   - an RJ-code name makes it a work;
   - a folder of archives with no other content is a category;
   - a folder whose subfolders all look structural (`mp3`, `wav`, `SE無し`, numbered parts) is a work.

   Each ZIP and each loose audio file also becomes a work. Afterwards, a folder and a ZIP of the same work in the same category (matched by title or RJ code) are merged, and the folder copy is preferred.
2. **`EntryAnalyzer`** turns one work's `VNode` into versions:
   - It unwraps single-folder wrappers and collects the RJ code.
   - It groups audio by directory. A directory becomes a version; its label comes from the path, with format folders (`mp3`, `wav`) and leading numbers dropped.
   - Several variants in one folder (e.g. `_SE付き` / `_音声のみ` suffixes) are split into separate versions only when every tag/format group lines up with another group. Otherwise, files that merely carry tags (bonus tracks, `【…】` titles) would be split up.
   - It fills partial versions, as described in the data model.
   - It sorts versions: 本編 first, neutral sections next, おまけ/特典 last. Within that order, the root comes first, then path order, then untagged before tagged.
   - It matches subtitles (LRC/VTT/SRT, including `x.mp3.vtt`) to tracks, first by directory and stem, then by stem anywhere in the work.
3. **`Naming`** holds the string logic shared by both:
   - NFKC normalization;
   - natural sort that treats full-width digits as digits;
   - removing leading track numbers and building track keys;
   - parsing variant tags into a core name plus tags;
   - version labels, section rank, RJ codes.
4. **`CoverPicker`** scores image filenames to pick the plain, logo-free illustration:
   - higher: ロゴなし, 文字無し, イラストのみ, 壁紙, and an image whose sibling is a `…ロゴあり` copy;
   - lower: アイコン, キャラデザ, スマホ, thumbnails, files under 60 KB.

   If there is no image, the cover falls back to the art embedded in the first audio file.

## Playback (`playback/`)

- **`PlaybackEngine`** owns the app's single ExoPlayer. Both the UI and the media session use it, always on the main thread.
  - One version becomes one playlist, with one media item per track. ExoPlayer loads the next item in advance, so segment changes are seamless. The sample MP3s are CBR without Xing/LAME headers, so constant-bitrate seeking is enabled and there is no encoder delay to trim.
  - Audio attributes are `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH` with focus handling: the player pauses on interruptions instead of lowering its volume. It also pauses when headphones are unplugged.
  - It exposes `EngineState` (entry, version, index, play state, speed, volume boost, repeat, sleep timer) as a `StateFlow`.
  - Volume boost (0 to +9 dB) uses a `LoudnessEnhancer` on the player's audio session, recreated when the session ID changes. It is not persisted, like speed.
  - Segment-aware operations:
    - `skipBy` crosses into neighbouring tracks;
    - `seekGlobal` seeks on the whole-work timeline, using durations from `DurationRepository`;
    - `switchVersion` keeps the current track and position when the target has the same recording (SE on/off, WAV/MP3, filled tracks). Candidates come from the key, the core name and the index, and need durations within 3% (or 5 s), or a core-name match while durations are unknown. Otherwise the target resumes where it was last left (`Progress.versions`).
  - It saves progress every 5 s and on pause or track change. Resuming rewinds a few seconds after a long pause. Durations the player reports are recorded back into `DurationRepository`.
  - Artwork is put into `MediaMetadata` as **bytes**, because System UI cannot read `file://` URIs in the app's private cache.
- **`PlaybackService`** is a `MediaSessionService` that wraps the engine's player. It provides:
  - the media notification and lock-screen controls;
  - `onPlaybackResumption`, which rebuilds the last session from saved progress;
  - background playback after the task is swiped away, as long as something is playing.

  `MainActivity` binds a `MediaController` while visible, only to keep the service alive. The UI calls the engine directly.
- **Sleep timer**: either a countdown with a 30-second volume fade, or a stop at the end of the current track (`pauseAtEndOfMediaItems`).

## Persistence

| Store | Backing | Notes |
|---|---|---|
| `SettingsRepository` | DataStore Preferences | Read synchronously once at start-up so the first frame uses the right theme. Cover overrides are stored as JSON. |
| `LibraryRepository` | `library.json` | Rescanned on every launch in the background, while the previous index stays visible. |
| `ProgressRepository` | `progress.json` | Per-entry version, track index, position, fraction and a finished flag, plus the last spot in each version. Saved with a debounce. |
| `DurationRepository` | `durations.json` | Track durations keyed by `Ref.cacheKey`. Probed when a work is opened, and updated by the player. |
| `CoverCache` | `cacheDir/covers/*.jpg` | Downscaled JPEGs, served to Coil 3 by `CoverFetcher` and `CoverKeyer` for the `Cover` model. |

The JSON files are written by `JsonStore`, which writes to a temp file and then renames it.

## UI (`ui/`)

- Jetpack Compose with Material 3, dark theme by default (`ui/theme`). User-picked accent and secondary colors (`ui/ColorPicker.kt`) replace the four primary and four secondary roles respectively of whichever scheme is active, including dynamic color. `Tones` derives them from the picked color at Material's tones (L\* 80/20/30/90 in dark, 40/100/90/10 in light), using CIE Lab hue with chroma reduced to fit sRGB.
- There is no navigation library. `MainActivity` keeps a stack of `Route`s (Library, Entry, Settings). The full-screen player, the mini player and the folder picker are overlays drawn on top of it.
- Screens read repository and engine `StateFlow`s directly; there are no view models. Playback position is polled while a screen is visible (`rememberPosition`).
- `FolderPicker` browses storage volumes directly, which the all-files access permission allows.
