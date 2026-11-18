# Plan — Threads Extraction Engine Upgrade & v28.0 Release (Build 28)

- **Goal:** Implement a 3-stage browser-emulated Threads (`threads.com` / `threads.net`) media extractor with SSR `data-sjs` JSON hydration, GraphQL relay fallback, fake/unrelated `.mp3` elimination, and update the app version to `versionCode = 28` (`v28.0`).

## Success Criteria
- [x] `UrlClassifier` canonicalizes Threads URLs to `threads.com` and strictly requires `/post/<id>` or `/t/<id>` paths (rejecting bare profile URLs).
- [x] SSR `<script type="application/json" data-sjs>` extraction isolates the requested post (`code == postId` / `pk == targetPk`), extracts ranked `video_versions`, `carousel_media`, voice `audio.audio_src` (`.m4a`), and `video_dash_manifest` XML representations using a zero-dependency pure-Kotlin JSON parser.
- [x] GraphQL Relay fallback queries `/graphql/query` and `/api/graphql` using `X-IG-App-ID`, `X-ASBD-ID`, dynamically extracted `LSD`/`doc_id`/`csrftoken`, and custom-alphabet Base64 `shortcodeToMediaPk()` decoding.
- [x] Fake/unrelated `.mp3` prevention blocks `extractGenericOrWebVideoInfo` & partial-range HTML fallback on Threads/social video URLs, unescapes `\/` before CDN regex scanning, and filters `static.cdninstagram.com`, `static.*.fbcdn.net`, `/rsrc.php/`, and static `.mp3` UI assets.
- [x] Version bumped to `versionCode = 28` / `versionName = "28.0"` (`com.samotech.Lucadownloadmanager` preserved) across Gradle, layouts, preferences, and unit test assertions.
- [x] All 31 unit tests (including `phase12_threadsEngine_ssrDataSjs_shortcodeToPk_graphQlRelay_andFakeMp3Prevention`) and `compile_applet` pass cleanly.

## Next Step
- Done — all criteria verified.
