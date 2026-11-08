@echo off
REM Universal Download Manager - APK Build Script for Windows
REM This script will build a debug APK for testing

echo ==========================================
 echo Universal Download Manager - APK Builder
echo ==========================================
echo.

REM Check if we're in the right directory
if not exist settings.gradle (
    echo ERROR: Please run this script from the project root directory
    echo Current directory: %cd%
    pause
    exit /b 1
)

REM Check for Java
java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java is not installed or not in PATH
    echo Please install Java JDK 17 or later
    pause
    exit /b 1
)

echo Java is installed
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    echo   Version: %%v
)
echo.

call gradlew.bat assembleDebug

REM Check if build succeeded
if errorlevel 1 (
    echo.
    echo ==========================================
    echo Build Failed
    echo ==========================================
    echo.
    echo Please check the error messages above
    pause
    exit /b 1
)

echo.
echo ==========================================
echo Build Successful!
echo ==========================================
echo.

set APK_PATH=app\build\outputs\apk\debug\app-debug.apk

if exist %APK_PATH% (
    echo APK Location: %cd%\%APK_PATH%
    echo.
    
    REM Get file size
    for %%F in (%APK_PATH%) do set FILESIZE=%%~zF
    echo APK Size: %FILESIZE% bytes
    echo.
    
    REM Check if ADB is available
    where adb >nul 2>&1
    if errorlevel 1 (
        echo ADB not found - you can manually copy the APK to your device
        echo.
        echo To manually install:
        echo 1. Copy the APK to your Android device
        echo 2. Open the file on your device
        echo 3. Tap 'Install'
        echo.
    ) else (
        echo ADB is available - you can install the APK now
        set /p choice=Install APK to connected device? (y/n): 
        if /i "%choice%"=="y" (
            echo Installing APK...
            adb install %APK_PATH%
            if errorlevel 1 (
                echo Installation failed - check if device is connected
            ) else (
                echo APK installed successfully!
            )
        )
    )
    
    pause
) else (
    echo APK not found at expected location
    echo Build may have failed - check the output above
    pause
    exit /b 1
)
