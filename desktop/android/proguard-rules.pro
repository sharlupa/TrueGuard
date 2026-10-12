# kotlinx.serialization: сериализаторы ищутся по имени
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# мост JavaScript → Kotlin для капчи в WebView
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
