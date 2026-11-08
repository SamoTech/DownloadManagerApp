# Universal Download Manager - Android App

[![GitHub license](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/SamoTech/DownloadManagerApp/blob/main/LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B-green.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.8%2B-orange.svg)](https://kotlinlang.org/)

A **comprehensive Android download manager** that supports downloading **ALL file types from ANY website**, including **torrents and videos** from popular platforms.

---

## 🚀 Features

### 📥 Core Download Functionality
- ✅ **Direct Downloads** - Download files from any URL (HTTP/HTTPS)
- ✅ **Resume Support** - Continue interrupted downloads
- ✅ **Progress Tracking** - Real-time speed, ETA, and percentage
- ✅ **Queue Management** - Multiple simultaneous downloads (up to 4)
- ✅ **Background Processing** - Downloads continue when app is closed

### 🎭 Torrent Support
- ✅ **Magnet Links** - Full magnet URI support
- ✅ **Torrent Files** - Download from .torrent files
- ✅ **Peer Management** - Track peers, seeds, and speeds
- ✅ **File Selection** - Choose specific files from multi-file torrents

### 🎥 Video Download Support
- ✅ **YouTube** - All qualities (144p to 4K)
- ✅ **Vimeo** - Full video support
- ✅ **Facebook** - Videos from posts and pages
- ✅ **Instagram** - Videos, reels, and IGTV
- ✅ **Twitter/X** - Videos from tweets
- ✅ **TikTok** - TikTok video downloads
- ✅ **Quality Selection** - Choose video quality and format
- ✅ **Audio Extraction** - Extract MP3 from videos

### 🎨 Modern UI
- ✅ **Material Design 3** - Beautiful, responsive interface
- ✅ **Dark Mode** - Automatic theme switching
- ✅ **Tab Navigation** - Easy switching between download types
- ✅ **Notifications** - Download status updates
- ✅ **Search & Filter** - Find downloads quickly

### 📁 File Management
- ✅ **Open/Share/Delete** - Full file management
- ✅ **Storage Options** - Internal, SD card, or custom locations
- ✅ **File Details** - View comprehensive file information

### ⚙️ Settings
- ✅ **Download Limits** - Control concurrent downloads
- ✅ **Network Settings** - WiFi-only, mobile data options
- ✅ **Torrent Settings** - Upload limits, connection settings
- ✅ **Video Settings** - Default quality and format
- ✅ **Appearance** - Theme and language options

---

## 📸 Screenshots

*(Add your screenshots here)*

---

## 🛠️ Technical Stack

| Component | Technology |
|-----------|------------|
| **Language** | Kotlin 1.8+ |
| **Architecture** | MVVM + Repository Pattern |
| **Database** | Room (SQLite ORM) |
| **Networking** | OkHttp + Retrofit |
| **Torrent** | libtorrent4j + jlibtorrent |
| **UI** | Material Components 3 |
| **Concurrency** | Kotlin Coroutines |
| **Background** | Foreground Service + WorkManager |

---

## 📋 Requirements

- **Minimum SDK:** 24 (Android 7.0 Nougat)
- **Target SDK:** 34 (Android 14)
- **Java:** 8+
- **Android Studio:** Latest version

---

## 📥 Installation

### Build APK

```bash
# Clone the repository
git clone https://github.com/SamoTech/DownloadManagerApp.git
cd DownloadManagerApp

# Build debug APK
./gradlew assembleDebug

# APK will be in: app/build/outputs/apk/debug/app-debug.apk
```

### Using Build Scripts

```bash
# Linux/macOS
./build_apk.sh

# Windows
build_apk.bat
```

---

## 🚀 Usage

### Start a Download
1. Open the app
2. Tap **+** button
3. Select download type (Direct, Torrent, or Video)
4. Enter URL
5. Configure options (filename, location, quality)
6. Tap **Start Download**

### Manage Downloads
- **Pause/Resume** - Tap the pause/play button
- **Cancel** - Stop and remove download
- **Retry** - Restart failed downloads
- **Delete** - Remove from list and storage
- **Open** - Open downloaded file
- **Share** - Share with other apps

---

## 📂 Project Structure

```
DownloadManagerApp/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/downloadmanager/
│       │   ├── activity/          # UI Activities
│       │   ├── adapter/           # RecyclerView Adapters
│       │   ├── manager/           # Download Managers
│       │   ├── model/             # Data Models & Database
│       │   ├── service/           # Background Services
│       │   └── utils/             # Utility Classes
│       └── res/                   # Resources (layouts, drawables, etc.)
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew & gradlew.bat
├── PROJECT_SUMMARY.md
├── QUICK_START.md
├── BUILD_APK.md
└── APK_BUILD_GUIDE.md
```

---

## 📜 Documentation

- [Project Summary](PROJECT_SUMMARY.md) - Complete feature list and technical details
- [Quick Start Guide](QUICK_START.md) - User guide for the app
- [Build APK Guide](BUILD_APK.md) - Detailed build instructions
- [APK Build Guide](APK_BUILD_GUIDE.md) - Quick reference

---

## 🤝 Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📜 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

---

## 🆘 Support

For support and feedback:
- Open an issue on [GitHub Issues](https://github.com/SamoTech/DownloadManagerApp/issues)
- Check the [documentation](PROJECT_SUMMARY.md)

---

## ⚠️ Important Notes

- **Torrent downloads** require libtorrent4j library
- **YouTube extraction** may need periodic updates
- **Video downloads** depend on platform APIs
- **Legal responsibility** - Users must ensure they have rights to download content
- **Storage permissions** - Required for saving files
- **Battery optimization** - Exclude app for best performance

---

**⭐ Star this repository if you find it useful!**

[![GitHub stars](https://img.shields.io/github/stars/SamoTech/DownloadManagerApp?style=social)](https://github.com/SamoTech/DownloadManagerApp/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/SamoTech/DownloadManagerApp?style=social)](https://github.com/SamoTech/DownloadManagerApp/network)
