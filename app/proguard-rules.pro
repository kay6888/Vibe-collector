# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization keeps generated serializers referenced only reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.vibecollector.** {
    *** Companion;
}
-keepclasseswithmembers class com.vibecollector.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vibecollector.**$$serializer { *; }

# Services and receivers are resolved from the manifest by name.
-keep class com.vibecollector.MainActivity { *; }
-keep class com.vibecollector.BootReceiver { *; }
-keep class com.vibecollector.capture.ChatAccessibilityService { *; }
-keep class com.vibecollector.overlay.BubbleService { *; }
-keep class com.vibecollector.notify.CaptureActionReceiver { *; }
