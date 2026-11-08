#!/bin/bash

# Universal Download Manager - APK Build Script
# This script will build a debug APK for testing

echo "=========================================="
echo "Universal Download Manager - APK Builder"
echo "=========================================="
echo ""

# Check if we're in the right directory
if [ ! -f "settings.gradle" ]; then
    echo "ERROR: Please run this script from the project root directory"
    echo "Current directory: $(pwd)"
    exit 1
fi

# Check for Java
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed or not in PATH"
    echo "Please install Java JDK 17 or later"
    exit 1
fi

echo "Java is installed: $(java -version 2>&1 | head -1)"
echo ""

# Make gradlew executable
chmod +x gradlew

# Build the APK
echo "Building debug APK..."
echo "This may take several minutes depending on your system"
echo ""

./gradlew assembleDebug

# Check if build succeeded
if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "Build Successful!"
    echo "=========================================="
    echo ""
    
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    
    if [ -f "$APK_PATH" ]; then
        echo "APK Location: $(pwd)/$APK_PATH"
        echo ""
        echo "APK Size: $(du -h "$APK_PATH" | cut -f1)"
        echo ""
        
        if command -v adb &> /dev/null; then
            echo "ADB is available - you can install the APK now"
            read -p "Install APK to connected device? (y/n) " -n 1 -r
            echo ""
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                echo "Installing APK..."
                adb install "$APK_PATH"
                if [ $? -eq 0 ]; then
                    echo "APK installed successfully!"
                else
                    echo "Installation failed - check if device is connected"
                fi
            fi
        else
            echo "ADB not found - you can manually copy the APK to your device"
        fi
        
        echo ""
        echo "To manually install:"
        echo "1. Copy the APK to your Android device"
        echo "2. Open the file on your device"
        echo "3. Tap 'Install'"
        echo ""
        
    else
        echo "APK not found at expected location"
        echo "Build may have failed - check the output above"
        exit 1
    fi
else
    echo ""
    echo "=========================================="
    echo "Build Failed"
    echo "=========================================="
    echo ""
    echo "Please check the error messages above"
    exit 1
fi
