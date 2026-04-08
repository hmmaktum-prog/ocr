# Fix SEC-14: Use official OkHttp recommended rules instead of wildcard keep
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# Fix SEC-13: Only keep what is actually needed — not the entire package
# Sealed class result variants must survive minification (used in when-expressions)
-keep class com.example.ocr.LlamaServerManager$StartResult { *; }
-keep class com.example.ocr.LlamaServerManager$StartResult$* { *; }

# General Android rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
