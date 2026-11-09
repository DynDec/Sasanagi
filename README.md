# Sasayaki

**Sasayaki** is a native Android audio player built for local Japanese ASMR and voice works.

DLsite-style downloads rarely fit the folder-as-album model of standard music and audiobook players: a single work often ships with multiple versions (with/without sound effects, ear-swapped, MP3 alongside WAV), partial variant folders that only re-record specific tracks, nested category directories, accompanying scripts/illustrations, and ZIP archives with Shift-JIS or UTF-8 filenames. Sasayaki is designed to understand that structure out of the box and play everything **in place** without copying or extracting files into app storage.

---

## Goals

- **Zero-copy, in-place playback:** Read audio, illustrations, subtitles, and documents directly where they live on disk—including inside `.zip` archives—without duplicating gigabytes of media into app-private storage.
- **Structure-aware scanning:** Automatically turn messy download directories and archives into clean, unified work entries with organized versions, tracks, covers, and attachments.

---

## Features

### Library & Scanning
- **Smart Work Detection:** Distinguishes between category folders and individual works using structural heuristics and RJ codes (`RJxxxxxx`), grouping nested folders and matching archives into unified entries.
- **Version Organization:** Groups alternate takes (`本編`, `SEなし`, `左右反転`, `おまけ`/`特典`, etc.) and pairs lossless (`WAV`/`FLAC`) and lossy (`MP3`) copies of the same version.
- **Partial Version Filling:** Automatically completes partial alternate versions (for example, an ear-reversed or no-SFX folder that only replaces a subset of tracks) by borrowing the remaining tracks from the base version.
- **Clean Cover Selection:** Scores image filenames to automatically pick logo-free (`ロゴなし` / `イラストのみ`) illustrations over promotional banners or thumbnails, falling back to embedded audio artwork when needed, with manual per-work cover override support.
- **Search, Filter & Sort:** Filter works by category chips, search by title or RJ code (with NFKC normalization), and sort by title (natural sort), recently added, or recently played.

### Direct Archive (`.zip`) Support
- **Play Without Extracting:** Stream audio, view illustrations, and read scripts directly from ZIP archives (including self-extracting archives and ZIP64).
- **Japanese Encoding Support:** Custom central-directory parser supporting UTF-8 flags, `0x7075` Unicode path extra fields, and Shift-JIS (`windows-31j`) filename fallbacks.
- **External Viewer Bridge:** Opens PDFs and other bundled documents inside ZIPs in external viewer apps via a proxy file descriptor (`ContentProvider`) without extracting them to disk.

### Playback & Audio
- **Gapless Segment Queue:** Plays multi-track versions as a continuous queue with preloaded transitions and constant-bitrate seeking support.
- **Live Version Switching:** Switch between versions (e.g., `SEあり` ↔ `SEなし` or `WAV` ↔ `MP3`) mid-listen while preserving your exact track and timestamp whenever the recordings match.

### Illustrations, Scripts & Theme
- **Built-in Gallery & Reader:** Browse bundled illustrations in a zoomable viewer and read bundled `.txt` / `.md` scripts and readmes directly in the app.
- **Material 3 & Cover-Tinted Palette:** Supports Dark, Light, and System themes, Android 12+ Dynamic Color, custom primary/secondary color pickers, and dynamic **Cover Tint** that extracts a perceptually balanced CIE Lab palette from the current work's cover art.

---

## Requirements & Permissions

- **Android 8.0 (API 26)** or higher.
- **All Files Access (`MANAGE_EXTERNAL_STORAGE`):** Required so Sasayaki can scan library folders and seek directly inside large audio files and ZIP archives in place without copying them into app storage.

---

## Building

```bash
# Build debug APK (installs side-by-side as com.sasayaki.player.debug)
./gradlew :app:assembleDebug

# Build R8-minified release APK
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:testDebugUnitTest
```

For details on the internal design, VFS/ZIP handling, scanning heuristics, and playback architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).
