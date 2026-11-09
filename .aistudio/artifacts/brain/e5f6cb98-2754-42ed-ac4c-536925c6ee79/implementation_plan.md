# Implementation Plan: Pure Real-Stream Engine & Zero-Fallback Architecture

Eliminate all bundled sample media asset fallbacks across Video, Music, Torrent/Magnet, and Direct download engines, ensuring that every download fetches the real network resource or accurately reports the specific network/codec error, while guaranteeing that the media player strictly plays the downloaded file.

## User Review & Critical Decisions

> [!IMPORTANT]
> **User-Confirmed Clarification (Phase 1 Answers):**
> 1. **No Bundled Asset Substitution on Error**: When a download experiences an HTTP error (403, 404), timeout, unextractable DRM, or unconnectable torrent swarm, the app **must never** substitute bundled sample audio or video (`R.raw.luca_*`). It must transition the download to `FAILED` with an informative error message and let the user retry or view details.
> 2. **Real Network Payload Only**:
>    - **Video Downloads**: Download the exact progressive/adaptive stream from the requested URL. Mux video+audio using `MediaMuxer` when adaptive formats are selected. If the stream cannot be extracted, mark `FAILED`.
>    - **Music Downloads**: Download the exact audio stream (MP3/M4A/FLAC/OGG/WAV) from the requested URL. Remux DASH M4A into standard M4A containers for playback. If the page is a closed SPA without extractable audio, mark `FAILED`.
>    - **Torrent & Magnet Downloads**: Download pieces strictly via WebSeeds (BEP-19) or TCP Peer Wire Protocol (BEP-3). If no unchoked peers or WebSeeds respond, mark `FAILED` instead of materializing sample media.
>    - **Media Player**: Play the exact file on disk without synthesizing fallback audio/video.

## 1. Overview & Core Concept

Luca Download Manager operates on four distinct protocols:
1. **Direct HTTP/HTTPS Multi-Segment**: Range-based segmented downloading using OkHttp.
2. **Audio/Music Engine**: Audio extraction from YouTube/YouTube Music, Bandcamp, SoundCloud, and direct audio streams, with non-fragmented container remuxing.
3. **Video Engine**: Multi-quality progressive and adaptive stream downloading with hardware-accelerated MPEG-4 muxing.
4. **BitTorrent / Magnet Engine**: Magnet URI metadata parsing, WebSeed downloading, and TCP peer wire protocol data transfer.

Currently, several download completion catch blocks and `MediaAssetHelper.ensureValidMediaFile` silently write `R.raw.luca_*` files when a network stream encounters an error. This creates the illusion that "it downloads the wrong file". Removing these asset substitutions restores 100% fidelity to the actual downloaded files.

## 2. User Experience & Visual Design

- **Clear Error Attribution in Download List**:
  - Download cards display precise failure causes (e.g., `HTTP 403 Forbidden: Video stream restricted`, `Torrent swarm: 0 reachable peers`, `Connection timed out`) rather than pretending to succeed with a sample file.
  - The **Retry** button re-attempts the download using refreshed headers or alternate progressive format tags.
- **Media Player Experience**:
  - Displays actual file duration, codec details (`MediaExtractor`), and video frame rendering.
  - If a file is incomplete or corrupted, shows a clear snackbar error (`Corrupted or unreadable media container`) rather than silently playing an unrelated sample.

## 3. Key Product Decisions & Trade-Offs

```
┌────────────────────────────────────────────────────────────────────────┐
│                        USER INPUT (URL / MAGNET)                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
┌──────────────────────┐                          ┌──────────────────────┐
│ Direct / Web / Video │                          │   Magnet / Torrent   │
└──────────┬───────────┘                          └──────────┬───────────┘
           │                                                 │
   [Resolve Streams]                                  [Query WebSeeds]
   [Http Range / Mux]                                 [Query TCP Peers]
           │                                                 │
     ┌─────┴─────┐                                     ┌─────┴─────┐
     ▼           ▼                                     ▼           ▼
 [Success]   [Failure]                             [Success]   [Failure]
     │           │                                     │           │
 [Write File] [Set Status: FAILED]                 [Write File][Set Status: FAILED]
 [Progress 100%] [Show Exact Reason]               [Progress 100%] [Show Exact Reason]
```

- **Remove `writeVerifiedMediaAssetToFile` from download flows**:
  - *Chosen Approach*: Strip out all `MediaAssetHelper.writeVerifiedMediaAssetToFile` invocations in `DownloadManager`, `VideoDownloadManager`, `MusicDownloadManager`, `TorrentDownloadManager`, and `MediaPlayerActivity`.
  - *Why*: User explicitly confirmed that failed downloads should fail honestly with network errors instead of replacing user content with sample media.
- **Enhanced YouTube & Generic Stream Extraction**:
  - *Chosen Approach*: Expand InnerTube client fallback parameters (WEB, ANDROID_VR, TVHTML5, IOS) in `YouTubeExtractor` with updated client version signatures to prevent bot detection and 403 throttling.
- **Strict Torrent Download Completion**:
  - *Chosen Approach*: Only mark torrent downloads `COMPLETED` when real payload bytes are received from WebSeeds or TCP peers. When no swarm data is delivered, update status to `FAILED: No reachable peers or WebSeeds`.

## 4. Technical Architecture & File Modifications

### 1. `MediaAssetHelper.kt`
- Remove fallback file writing from `ensureValidMediaFile`.
- Keep container inspection, `detectActualMimeType`, `remuxAudioToStandardM4a`, and `muxMp4VideoAndM4aAudio`.
- Keep `resolvePlayableFile` strictly scoped to the download item's own file and torrent subfolder.

### 2. `VideoDownloadManager.kt`
- In `downloadVideo`: Remove `writeVerifiedMediaAssetToFile` fallback.
- If both adaptive muxing and progressive streams fail, record the exact exception message and transition to `DownloadStatus.FAILED`.
- Strengthen YouTube and direct video stream extraction with automatic format fallback (muxed 18/22 first, then adaptive mp4+m4a).

### 3. `MusicDownloadManager.kt`
- In `downloadMusic`: Remove `writeVerifiedMediaAssetToFile` fallback.
- Stream direct audio files, remux YouTube DASH M4A streams to standard M4A containers, and fail cleanly with an informative error if the remote page does not expose an audio stream.

### 4. `TorrentDownloadManager.kt`
- In `downloadTorrent`: Remove `fulfillSwarmFiles` mock generation.
- Download pieces strictly from WebSeeds (BEP-19) or TCP peers (BEP-3). If neither transfers the requested files, set `status = DownloadStatus.FAILED` with `errorMessage = "No active peers or WebSeeds available in swarm"`.

### 5. `DownloadManager.kt`
- In `downloadFile`: Remove `writeVerifiedMediaAssetToFile` fallback in the network error catch block; let network exceptions throw directly into the error handler.

### 6. `MediaPlayerActivity.kt`
- Remove fallback asset synthesis on playback error; show an informative Toast/error state if the file cannot be decoded.

### 7. Unit & Regression Tests (`DownloadManagerCoreVerificationTest.kt`)
- Add unit tests asserting that download managers do not overwrite files with sample assets upon HTTP 404/network errors.
- Run `gradle :app:testDebugUnitTest` and `compile_applet` to verify clean builds and test passes.
