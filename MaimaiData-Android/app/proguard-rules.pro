-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.sorrowrain.maimaidata.**$$serializer { *; }
-keep class dev.sorrowrain.maimaidata.models.LXNSUtageNotesSerializer { *; }
-keepclassmembers class dev.sorrowrain.maimaidata.** {
    *** Companion;
}
-keepclasseswithmembers class dev.sorrowrain.maimaidata.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum dev.sorrowrain.maimaidata.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
