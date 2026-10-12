# kotlinx.serialization и корутины Swing подхватываются через рефлексию/ServiceLoader
-keep class kotlinx.coroutines.swing.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
-dontnote **
