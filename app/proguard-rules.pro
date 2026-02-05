# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JAudioTagger classes
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Ignore missing AWT classes (JAudioTagger optional dependency, not used on Android)
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**

# ===== Release Optimizations =====

# Remove Log calls in release build
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ===== Room Database =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ===== Kotlin =====
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

