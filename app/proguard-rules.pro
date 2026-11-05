# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
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

# ---- Release minification ----
# Extensions are loaded at runtime and link against app/library classes and
# bundled third-party libraries by name, so R8 must not rename or strip any API
# they may use. Shrinking/optimisation still applies to everything else (most
# notably Jetpack Compose, Coil and Media3 internals).
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable,RuntimeVisible*Annotations,AnnotationDefault

# App, library and shared modules (also covers NiceHttp, SafeFile, anime-db)
-keep class com.lagradost.** { *; }

# Libraries commonly used by extensions
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.fleeksoft.ksoup.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep class org.schabi.newpipe.** { *; }
-keep class org.conscrypt.** { *; }

# Views/preferences used by extension settings screens
-keep class !androidx.compose.**,androidx.** { *; }
-keep class com.google.android.material.** { *; }

# JNI-backed libraries (native code looks these up by name)
-keep class torrServer.** { *; }
-keep class go.** { *; }
-keep class io.github.anilbeesetti.nextlib.** { *; }

# JVM-only APIs referenced by Rhino/Jackson that don't exist on Android
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
