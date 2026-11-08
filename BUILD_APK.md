# Building the APK - Step by Step Guide

## Overview

This guide will walk you through building a debug APK for the Universal Download Manager app.

## Prerequisites

### 1. Install Java JDK 17+
- Windows: https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
- macOS: `brew install openjdk@17`
- Linux: `sudo apt install openjdk-17-jdk`

### 2. Install Android Studio
- Download: https://developer.android.com/studio

## Building

### Using Android Studio
1. Open the project
2. Wait for Gradle sync
3. Click "Build APK"
4. Find APK at: `app/build/outputs/apk/debug/app-debug.apk`

### Using Command Line
```bash
cd DownloadManagerApp
chmod +x gradlew
./gradlew assembleDebug
```

## Troubleshooting
- **Gradle sync failed**: Update plugin version in `app/build.gradle`
- **SDK not found**: Run `sdkmanager "platforms;android-34"`
- **Java version**: Use JDK 17+
- **Out of memory**: Add `org.gradle.jvmargs=-Xmx2048m` to `gradle.properties`
