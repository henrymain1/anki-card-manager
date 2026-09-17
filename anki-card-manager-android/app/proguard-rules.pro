# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.borderless.ankicards.**$$serializer { *; }
-keepclassmembers class com.borderless.ankicards.** {
    *** Companion;
}
-keepclasseswithmembers class com.borderless.ankicards.** {
    kotlinx.serialization.KSerializer serializer(...);
}
