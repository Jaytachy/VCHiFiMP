# ---- JNI -------------------------------------------------------------------
# Native methods are resolved by name from C, so the classes and member names
# must survive obfuscation.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class dev.jay.usbdirect.UsbAudioEngine { *; }
-keep class dev.jay.usbdirect.NativeResampler { *; }

# ---- Android entry points --------------------------------------------------
-keep class dev.jay.usbdirect.PlaybackService { *; }
-keep class dev.jay.usbdirect.MainActivity { *; }
-keep class dev.jay.usbdirect.PlaybackService$LocalBinder { *; }

# MediaSession callbacks are invoked reflectively by the framework.
-keepclassmembers class * extends android.media.session.MediaSession$Callback {
    public *;
}

# ---- Kotlin / enums --------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
