# Moshi
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes *Annotation*, InnerClasses
-keep class ru.asop.terminal.network.models.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
