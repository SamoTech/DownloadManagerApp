# Luca Download Manager (`com.samotech.Lucadownloadmanager`)

[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/SamoTech/DownloadManagerApp/blob/main/LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024--36)-green.svg)](https://developer.android.com/)
[![JDK](https://img.shields.io/badge/JDK-21-orange.svg)](https://openjdk.org/)

An Android download manager for direct HTTP/HTTPS files, BitTorrent/Magnet transfers, public audio streams, and public video/HLS streams, focused on transfer integrity, path containment, and truthful source reporting.

---

## Core Capabilities

### 1. Direct HTTP/HTTPS Segmented & Resumable Transfers
- **RFC 9110 Range Validation**: Multi-segment parallel transfers require `HTTP 206 Partial Content` with a matching `Content-Range` header (`start-end/total`) and exact byte count per segment.
- **Automatic Single-Stream Fallback**: If a server ignores `Range` and returns `HTTP 200 OK`, parallel segments are aborted and the download falls back cleanly to a single stream.
- **Validator-Backed Resume**: Persists `ETag` and `Last-Modified` validators in Room (`v2`), uses `If-Range` on resume, and handles `416 Range Not Satisfiable` safely.
- **Per-Job Staging & Collision-Safe Finalization**: Writes to `.filename.part.<id>` and promotes to the final filename only after byte-length and optional SHA-256 verification.

### 2. BitTorrent & Magnet Engine (BEP-3 / BEP-9 / BEP-15 / BEP-19)
- **Strict Path Containment**: All torrent metadata paths are validated via `TorrentPathSafety` against parent traversal (`..`), absolute paths, backslashes, NUL/control characters, symlinks, and duplicate normalized paths.
- **Piece SHA-1 & Length Verification**: Multi-file and single-file transfers are staged per job and verified against declared file lengths and piece SHA-1 hashes across file boundaries before promotion.
- **Truthful Swarm Metrics**: Displays measured tracker/peer counts (`0` when measured zero, `Unavailable` when unmeasured) without synthetic floors.
- **Per-File Lifecycle States**: Tracks `UNSELECTED`, `QUEUED`, `PARTIAL`, `VERIFIED`, `UNVERIFIED_COMPLETE`, and `FAILED` states per file.

### 3. Audio & Video Stream Downloading
- **Strict Host Classification**: Uses `UrlClassifier` on parsed URL hosts to prevent lookalike domain or query-string spoofing.
- **Public Audio Support**: Direct audio files (`MP3`, `FLAC`, `M4A`, `OGG`, `WAV`, `OPUS`), SoundCloud, YouTube/YouTube Music, Bandcamp, Audiomack, Mixcloud, Jamendo, and Internet Archive.
- **Honest Format & Provenance Reporting**: Never labels an `M4A` or `MP3` stream as `FLAC`. Spotify links download the public 30-second preview (explicitly labeled `30s Preview`) unless the user explicitly enables **Allow Alternate-Source Lookup (YouTube)**, which verifies title, artist, and duration before matching.
- **Public Video & HLS Support**: Supports YouTube, TikTok, Instagram, X/Twitter, Facebook, Reddit, Vimeo, Dailymotion, Archive.org, and non-encrypted VOD HLS (`.m3u8`) playlists (including master variant selection, `#EXT-X-MAP`, and `#EXT-X-BYTERANGE`).
- **Unsupported Sources**: Live HLS streams, encrypted HLS (`#EXT-X-KEY` with `METHOD != NONE`), Twitch, and DRM-protected subscription services (Apple Music, Deezer, Tidal, Netflix, Disney+, Prime Video) are explicitly reported as unsupported.

### 4. Background Queue, Network Policy & Android 15+ Compliance
- **Queue & Concurrency Control**: Enforces `max_concurrent_downloads` and `use_mobile_data` (Wi-Fi-only policy pauses/defers active transfers when switching to metered networks).
- **Serialized Worker Cancellation**: Awaits coroutine termination (`stopAndAwait`) before state transitions and guards progress updates with `WHERE id = :id AND status = 'DOWNLOADING'`.
- **Android 15 (`targetSdk 36`) Lifecycle**: Uses `WorkManager` (`DownloadRecoveryWorker`) on `BOOT_COMPLETED` instead of launching a `dataSync` foreground service from boot, and handles `Service.onTimeout`.

---

## Privacy & Third-Party Resolver Disclosure

- Direct HTTP/HTTPS, HLS, and BitTorrent transfers connect directly from the device to the target hosts, trackers, and peers.
- When **Allow Third-Party Social Video Resolvers** is enabled in Settings (default: on), social video URLs (TikTok, X/Twitter, Instagram) may be queried against public resolver endpoints (`tikwm.com`, `vxtwitter.com`, `fxtwitter.com`, `ddinstagram.com`) when direct page extraction is restricted. This can be disabled in Settings.
- When **Allow Alternate-Source Lookup (YouTube)** is enabled by the user (default: off), track metadata (title and artist) may be searched on YouTube to find a verified full-length match.

---

## Build Prerequisites & Verification

- **JDK**: Java 21
- **Android SDK**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`
- **Gradle Wrapper**: Gradle `9.3.1` with pinned SHA-256 distribution checksum in `gradle/wrapper/gradle-wrapper.properties`

```bash
# Build Debug APK
./gradlew assembleDebug

# Run Unit & Regression Test Suite
./gradlew :app:testDebugUnitTest

# Run Android Lint
./gradlew :app:lintDebug
```
