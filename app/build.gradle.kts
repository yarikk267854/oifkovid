import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videosaver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videosaver.app"
        // Динамические цвета (Material You) работают с Android 12+,
        // на более старых версиях используется запасная палитра.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Python + ffmpeg внутри весят много, поэтому только реальные устройства.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Личный ключ подписи: благодаря ему новые сборки ставятся поверх старых без удаления.
    // Для публикации в Google Play создайте свой ключ и не храните его в репозитории.
    signingConfigs {
        create("personal") {
            storeFile = file("keystore/personal.jks")
            storePassword = "videosaver"
            keyAlias = "videosaver"
            keyPassword = "videosaver"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // yt-dlp запускает бинарники Python/ffmpeg из распакованных нативных библиотек.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // yt-dlp + ffmpeg для Android (поддерживаемый форк, Maven Central)
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    // Compose + Material 3
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")

    // Превью видео
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
