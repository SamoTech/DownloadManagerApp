# Universal Download Manager - Android App

## Overview

This is a comprehensive Android download manager application that supports downloading all file types from any website, including torrents and videos.

## Features

### Core Download Functionality
- Direct downloads from URLs
- Resume interrupted downloads
- Progress tracking (speed, ETA, percentage)
- Queue management for multiple downloads
- Background processing

### Torrent Support
- Magnet URI downloads
- .torrent file downloads
- Peer management
- File selection from multi-file torrents

### Video Download Support
- YouTube
- Vimeo
- Facebook, Instagram, Twitter/X, TikTok
- Quality selection
- Audio extraction

### User Interface
- Material Design 3
- Tab navigation
- Detailed download views
- Notifications
- Dark mode support

## Project Structure

DownloadManagerApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/downloadmanager/
│   │   │   ├── activity/ (5 files)
│   │   │   ├── adapter/ (1 file)
│   │   │   ├── manager/ (3 files)
│   │   │   ├── model/ (6 files)
│   │   │   ├── service/ (1 file)
│   │   │   └── utils/ (3 files)
│   │   └── res/ (44 files)
│   └── build.gradle
├── settings.gradle
├── build.gradle
├── gradle.properties
└── gradlew, gradlew.bat

## Technical Implementation

- MVVM Architecture
- Room Database
- LiveData
- Coroutines
- Foreground Service
- Broadcast Receiver

## License

MIT License
