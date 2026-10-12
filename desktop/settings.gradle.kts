pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "trueguard"

// Android-версия: тот же интерфейс из src/shared, плюс VpnService и WebView для капчи.
// Подключается, только если есть Android SDK, — для сборки под Linux и Windows он не нужен.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }
if (androidSdk != null) include(":android")
