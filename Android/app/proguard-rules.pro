# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Keep Retrofit interfaces
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep interface * { @retrofit2.http.* <methods>; }

# NewPipeExtractor uses Mozilla Rhino to run YouTube player JS (signature/cipher
# decryption) — Rhino does reflection-heavy bytecode generation that R8 breaks silently.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# The PoToken WebView bridge is called from JS via reflection (@JavascriptInterface);
# R8 renaming these methods in release builds breaks the bridge silently (JS calls a
# method that no longer exists, swallowed as a JS-side error with no Kotlin stack trace).
-keepclassmembers class com.rhamers.osse.youtube.WebViewPoTokenProvider$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Gson-(de)serialized model classes. Gson maps JSON keys to fields by reflection using
# the field's name (falling back to it even when @SerializedName is present, if the annotation
# were ever stripped) — without these rules R8 renames fields in release builds and every
# field silently deserializes to its default value. VideoSearchResult in particular has no
# @SerializedName annotations at all, so it depends entirely on its field names surviving.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keep class com.rhamers.osse.data.api.** { <fields>; }
-keep class com.rhamers.osse.youtube.VideoSearchResult { <fields>; }