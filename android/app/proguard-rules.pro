# kotlinx.serialization генерирует сериализаторы, R8 их не видит.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class uz.smartsale.agent.data.net.** {
    *** Companion;
}
-keepclasseswithmembers class uz.smartsale.agent.data.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
