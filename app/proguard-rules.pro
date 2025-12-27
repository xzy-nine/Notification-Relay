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

# Keep all Activity classes (required for Android system to find them via reflection)
-keep public class * extends android.app.Activity {
    public <init>(...);
    public void *(...);
}

# Keep all Service classes (required for Android system to find them via reflection)
-keep public class * extends android.app.Service {
    public <init>(...);
    public void *(...);
}

# Keep all BroadcastReceiver classes
-keep public class * extends android.content.BroadcastReceiver {
    public <init>(...);
    public void *(...);
}

# Keep all ContentProvider classes
-keep public class * extends android.content.ContentProvider {
    public <init>(...);
    public void *(...);
}

# Keep Application class
-keep public class * extends android.app.Application {
    public <init>(...);
    public void *(...);
}

# Keep DeviceConnectionManager fields accessed via reflection
-keep class com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager {
    private java.util.Map authenticatedDevices;
    private java.util.Map deviceInfoCache;
    private java.util.Set rejectedDevices;
    private java.util.Set heartbeatedDevices;
    private java.util.Map deviceLastSeen;
    private java.util.Map heartbeatJobs;
    private java.lang.String uuid;
    private java.lang.String localPublicKey;
    private java.lang.String localPrivateKey;
    private int listenPort;
    private boolean udpDiscoveryEnabled;
}

# Keep data classes used by DeviceConnectionManager
-keep class com.xzyht.notifyrelay.feature.device.service.DeviceInfo { *; }
-keep class com.xzyht.notifyrelay.feature.device.service.AuthInfo { *; }

# Keep Compose related classes
-keep class androidx.compose.** { *; }
-keep class top.yukonga.miuix.kmp.** { *; }

# Keep data classes and their constructors
-keep class com.xzyht.notifyrelay.feature.device.model.** {
    *;
}

# Keep utility classes
-keep class com.xzyht.notifyrelay.common.** {
    *;
}

# Keep core classes
-keep class com.xzyht.notifyrelay.common.core.** {
    *;
}

# Keep enums
-keep class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Gson serialization classes
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep jmdns classes
-keep class javax.jmdns.** { *; }
-keep class org.jmdns.** { *; }

# Keep Kotlin coroutines
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Keep BuildConfig
-keep class com.xzyht.notifyrelay.BuildConfig { *; }

# SLF4J logging framework (used by jmdns)
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-keep class org.slf4j.impl.StaticLoggerBinder { *; }