# Plan — R8 Deobfuscation & Release Optimization (v25.0 / Build 25)

- **Goal:** Enable R8 code shrinking, obfuscation (`mapping.txt` / `proguard.map` generation), resource shrinking, and native debug symbol packaging for release builds (`versionCode 25` / `versionName "25.0"`) so Google Play Console automatically receives the deobfuscation file inside every release App Bundle (`.aab`).

## Success Criteria
- [x] `minifyEnabled true` and `shrinkResources true` enabled in `buildTypes.release` of `/app/build.gradle`.
- [x] `ndk { debugSymbolLevel = 'FULL' }` configured in `defaultConfig` of `/app/build.gradle`.
- [x] `/app/proguard-rules.pro` created with `-keepattributes SourceFile,LineNumberTable`, `-renamesourcefileattribute SourceFile`, and keep rules for Room, Activities/Services, ViewBinding, Kotlin Coroutines, OkHttp3/Okio, and Media3.
- [x] Version bumped to `versionCode = 25` and `versionName = "25.0"` for the next Google Play Console release, with XML fallback labels and unit test assertions synchronized.
- [x] Verified `:app:minifyReleaseWithR8` generates `app/build/outputs/mapping/release/mapping.txt` (30 MB) and `:app:testDebugUnitTest` + `compile_applet` succeed.

## Next Step
- Done — all criteria verified.
