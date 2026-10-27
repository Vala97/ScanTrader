# Release minify. Library AARs also ship their own consumer rules.
# Moshi and kotlinx.serialization are not on the classpath.

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions

# Gson models (Scryfall, TCGdex, CardTrader, local set cache).
-keep class com.scantrader.app.network.** { *; }
-keep class com.scantrader.app.data.catalog.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room entities and the generated database.
-keep class com.scantrader.app.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Retrofit interfaces.
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ONNX Runtime (Milo). JNI looks up these classes by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit text recognition. The AAR rules cover most of this; keep the public API.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-dontwarn com.google.mlkit.**

# CameraX pulls in optional classes that are not packaged.
-dontwarn com.google.devtools.build.android.desugar.runtime.**
