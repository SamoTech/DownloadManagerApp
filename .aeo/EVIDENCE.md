# Evidence Ledger

| Criterion | Check (exact command or action) | Result (what you saw) | Status |
|---|---|---|---|
| Full applet build compilation | `compile_applet` | `Build succeeded - the applet is compiled` | pass |
| All 31 unit tests including `phase12_threadsEngine_ssrDataSjs_shortcodeToPk_graphQlRelay_andFakeMp3Prevention` | `gradle :app:testDebugUnitTest --console=plain` & inspect `TEST-com.example.downloadmanager.DownloadManagerCoreVerificationTest.xml` | `tests="31" skipped="0" failures="0" errors="0"`, `BUILD SUCCESSFUL` | pass |
| Version 28.0 sync & untouched `applicationId` / `namespace` | `grep -rnE "versionCode|applicationId|v28\.0|Version 28\.0" app/build.gradle app/src/main/res/` | `versionCode = 28`, `versionName = "28.0"`, `applicationId = "com.samotech.Lucadownloadmanager"`, `namespace = 'com.example.downloadmanager'`, layouts/preferences updated to v28.0 | pass |
