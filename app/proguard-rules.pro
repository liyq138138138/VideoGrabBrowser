# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep our data classes that are serialized
-keep class com.videograb.browser.DetectedVideo { *; }
-keep class com.videograb.browser.VideoType { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep WebView JavaScript interface
-keepclassmembers class com.videograb.browser.MainActivity {
    @android.webkit.JavascriptInterface <methods>;
}
