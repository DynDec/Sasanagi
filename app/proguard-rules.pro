-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.sasayaki.player.** {
    *** Companion;
}
-keepclasseswithmembers class com.sasayaki.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
