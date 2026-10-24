# 保留 kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.gtool.**$$serializer { *; }
-keepclassmembers class com.gtool.** { *** Companion; }
-keepclasseswithmembers class com.gtool.** { kotlinx.serialization.KSerializer serializer(...); }

# 保留 Compose 运行时必要信息
-keep class androidx.compose.runtime.** { *; }
