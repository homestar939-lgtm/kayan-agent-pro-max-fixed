# Kayan X ProGuard rules

# Keep JNI entry points — names must match C function signatures
-keepclasseswithmembernames class com.kayan.x.engine.LlamaEngine {
    native <methods>;
}

# Gson models
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.kayan.x.model.** { *; }

# Timber
-dontwarn org.jetbrains.annotations.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }

# Compose — already handled by AGP Compose rules but added for safety
-keep class androidx.compose.** { *; }
