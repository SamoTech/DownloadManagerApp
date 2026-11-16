# ==============================================================================
# R8 / ProGuard Deobfuscation & Keep Rules for Luca Download Manager
# Automatically embeds BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map
# into the Release Android App Bundle (.aab) for Google Play Console Crash/ANR symbolication.
# ==============================================================================

# Preserve line numbers and source file attributes for Play Console stack trace deobfuscation
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# Keep Room Database implementation, Entities, DAOs, and TypeConverters
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.downloadmanager.model.** { *; }

# Keep Android Activity, Service, BroadcastReceiver, and ViewBinding classes
-keep class com.example.downloadmanager.activity.** { *; }
-keep class com.example.downloadmanager.service.** { *; }
-keep class com.example.downloadmanager.databinding.** { *; }

# Keep Kotlin Coroutines & Metadata
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# OkHttp3 & Okio rules
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# AndroidX Media3 / ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
