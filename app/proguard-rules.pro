# kotlinx.serialization keeps its generated serializers via companion objects.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class me.pngwasi.plume.** {
    *** Companion;
}
-keepclasseswithmembers class me.pngwasi.plume.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio ship references to optional platform APIs.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink resolves key managers reflectively.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
