# Keep kotlinx.serialization runtime + generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclasseswithmembers class **$$serializer {
    *** descriptor;
}
-keepclassmembers class no.fyhn.uvindex.** {
    *** Companion;
}
-keepclasseswithmembers class no.fyhn.uvindex.** {
    kotlinx.serialization.KSerializer serializer(...);
}
