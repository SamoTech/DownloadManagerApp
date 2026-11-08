# APK Build Guide - Universal Download Manager

## Quick Answer

You now have everything needed to build the APK:

1. Install Java JDK 17+
2. Install Android SDK
3. Open project in Android Studio OR use command line
4. Run `./gradlew assembleDebug`
5. Find APK at `app/build/outputs/apk/debug/app-debug.apk`

## Fastest Way to Build the APK

### If you have Android Studio (Recommended)
1. Open the project in Android Studio
2. Click "Build APK" (green Android icon with down arrow)
3. Wait for build (~2-5 minutes)
4. Find your APK at: `app/build/outputs/apk/debug/app-debug.apk`

### If you prefer Command Line
- Windows: `build_apk.bat`
- macOS/Linux: `./build_apk.sh`

## Timeline
- Install Java JDK: 2-5 min
- Install Android Studio: 5-10 min
- First Build: 3-10 min
- Total: 10-25 min
