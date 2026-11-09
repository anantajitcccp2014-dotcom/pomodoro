plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.worktimer.pomo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.worktimer.pomo"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Постоянный ключ подписи: новые сборки ставятся поверх старых без потери статистики.
    signingConfigs {
        create("app") {
            storeFile = file("pomodoro.jks")
            storePassword = "pomodoro"
            keyAlias = "pomodoro"
            keyPassword = "pomodoro"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
