# Rules for the minified desktop build (`packageRelease*`).
#
# Everything here is either an optional dependency that is deliberately absent, or something
# resolved at runtime by name — which ProGuard cannot see and would otherwise strip.

# Optimisation is off, and this is not caution for its own sake: with it on, ProGuard rewrote
# okio's Okio__JvmOkioKt.source to return okio.Source where the signature says InputStreamSource,
# and the JVM rejected the class with "VerifyError: Bad return type" the first time DataStore read
# the settings file. The app started and then had no settings, with the failure on a background
# thread. Shrinking is where the size comes from anyway; optimisation added risk for very little.
-dontoptimize

# okio is what broke, and it is reached through DataStore's okio storage rather than directly.
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }

# OkHttp compiles against GraalVM, Conscrypt and BouncyCastle, none of which are shipped.
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# Ktor picks its engine through a ServiceLoader, so nothing references the class directly.
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class * implements io.ktor.client.engine.HttpClientEngineContainer
-keepnames class io.ktor.** { *; }
-dontwarn io.ktor.**

# kotlinx.serialization generates serializers reached through companions.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-keepclassmembers class me.pngwasi.plume.** {
    *** Companion;
}
-keepclasseswithmembers class me.pngwasi.plume.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class me.pngwasi.plume.data.**$$serializer { *; }

# JNA builds proxies over these interfaces and maps fields by name and declaration order.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.Structure { public *; }
-dontwarn java.awt.*

# Compose and Skia.
-dontwarn org.jetbrains.skiko.**
-keep class org.jetbrains.skiko.** { *; }
-dontwarn androidx.compose.**

# DataStore and okio.
-dontwarn okio.**
-dontwarn androidx.datastore.**
-keep class androidx.datastore.** { *; }

# Coroutines' debug agent and service loaders.
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
