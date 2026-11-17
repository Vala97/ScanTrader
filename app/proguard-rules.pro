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

# Camera bind uses the public API. PreviewView.setController installs
# PreviewView.getViewPort() into CameraX's UseCaseGroup. Do not reflect into
# CameraController: R8 renames private fields such as mViewPort, and that
# failure used to leave ImageAnalysis unbound.
-keepclassmembers class androidx.camera.view.CameraController {
    public void setEnabledUseCases(int);
    public void setImageAnalysisAnalyzer(java.util.concurrent.Executor, androidx.camera.core.ImageAnalysis$Analyzer);
    public boolean isImageAnalysisEnabled();
}
-keepclassmembers class androidx.camera.view.LifecycleCameraController {
    public void bindToLifecycle(androidx.lifecycle.LifecycleOwner);
}

# Gson TypeToken under R8 full mode: keep generic signatures of TypeToken subclasses
# (e.g. object : TypeToken<List<...>>() {} in CachingTcgdexApi).
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.scantrader.app.vision.** { *; }
-dontwarn com.google.gson.**
