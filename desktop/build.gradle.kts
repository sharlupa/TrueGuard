import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.1"
    // плагин Android в том же загрузчике классов, что и плагин Kotlin (модуль :android)
    id("com.android.application") version "9.4.1" apply false
}

// gradle -PtargetOs=windows … — сборка интерфейса для переносной Windows-версии (package-windows.sh)
val targetWindows = (findProperty("targetOs") as String?) == "windows"
if (targetWindows) layout.buildDirectory.set(file("build-windows"))

group = "com.trueguard"
version = "1.2.4"

dependencies {
    implementation(if (targetWindows) compose.desktop.windows_x64 else compose.desktop.currentOs)
    // официальная Material 3 от JetBrains: внутри androidx material3 1.5.0-alpha22 с MD3 Expressive
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    // общий код для Linux, Windows и Android
    sourceSets["main"].kotlin.srcDir("src/shared/kotlin")
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

compose.desktop {
    application {
        mainClass = "com.trueguard.MainKt"
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED", "-Dawt.useSystemAAFontSettings=on", "-Dsun.java2d.uiScale.enabled=true")
        buildTypes.release.proguard {
            // ProGuard 7.8 из плагина не читает классы JDK 26
            version.set("7.10.0")
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "trueguard"
            packageVersion = "1.2.4"
            description = "TrueGuard — WDTT VPN"
            vendor = "TrueGuard"
            modules("java.instrument", "java.management", "jdk.unsupported")
            linux {
                iconFile.set(project.file("src/main/resources/icons/app256.png"))
                shortcut = true
                appCategory = "Network"
                menuGroup = "Network"
            }
        }
    }
}

tasks.test {
    // рендер экранов идёт без окна
    systemProperty("java.awt.headless", "true")
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
