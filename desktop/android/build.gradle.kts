plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.trueguard.client"
    compileSdk = 37
    // NDK — только компилятор C для cgo ядра (GOOS=android)
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.trueguard.client"
        minSdk = 29
        targetSdk = 35
        versionCode = 124
        versionName = "1.2.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets["main"].apply {
        // общий код интерфейса и логики — тот же, что у Linux и Windows
        kotlin.srcDirs("src/main/kotlin", "../src/shared/kotlin")
        jniLibs.srcDir(layout.buildDirectory.dir("generated/coreLibs").get().asFile)
    }

    // ядро — исполняемый файл libwdtt.so: распаковывается в nativeLibraryDir, откуда его можно запускать
    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // подпись отладочным ключом — для своей установки; для публикации нужен свой ключ
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

// Ядро wdtt-client из ../linux-client, собранное под GOOS=android через NDK, как в оригинальном APK.
// Со статическим GOOS=linux ядро на телефоне не работает: Go не видит корневые сертификаты Android
// (HTTPS к VK падает), а перебор интерфейсов через netlink запрещён приложениям с Android 11
// (pion/turn не создаёт клиента). Под android это обходят crypto/x509 и библиотека anet.
val coreSrc = rootProject.file("../linux-client")
val ndkBin = androidComponents.sdkComponents.ndkDirectory.map { it.dir("toolchains/llvm/prebuilt/linux-x86_64/bin") }
val buildCore by tasks.registering {
    val out = layout.buildDirectory.dir("generated/coreLibs")
    val clangDir = ndkBin
    inputs.files(fileTree(coreSrc) { include("*.go", "go.mod", "go.sum") })
    outputs.dir(out)
    doLast {
        val api = 29 // = minSdk
        // ядро встраивает сервер для вкладки «Деплой» — на чистой копии его сначала нужно собрать
        if (!File(coreSrc, "deploy_assets/wdtt-server").exists()) {
            check(ProcessBuilder("sh", "build-server.sh").directory(coreSrc).inheritIO().start().waitFor() == 0) { "сборка wdtt-server не удалась" }
        }
        // ABI → GOARCH, GOARM, компилятор NDK
        val targets = mapOf(
            "arm64-v8a" to Triple("arm64", "", "aarch64-linux-android$api-clang"),
            "armeabi-v7a" to Triple("arm", "7", "armv7a-linux-androideabi$api-clang"),
            "x86_64" to Triple("amd64", "", "x86_64-linux-android$api-clang"),
        )
        for ((abi, t) in targets) {
            val dst = out.get().dir(abi).asFile.apply { mkdirs() }
            // anet обращается к внутренностям net через linkname — новый компоновщик Go это проверяет
            val pb = ProcessBuilder("go", "build", "-trimpath", "-ldflags=-s -w -checklinkname=0", "-o", File(dst, "libwdtt.so").path, ".")
                .directory(coreSrc).inheritIO()
            pb.environment().putAll(
                mapOf(
                    "CGO_ENABLED" to "1", "GOOS" to "android", "GOARCH" to t.first, "GOARM" to t.second,
                    "CC" to clangDir.get().file(t.third).asFile.path,
                )
            )
            check(pb.start().waitFor() == 0) { "go build для $abi не удался" }
        }
    }
}
tasks.named("preBuild") { dependsOn(buildCore) }
