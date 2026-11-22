/**
 * RPG Maps TV player: a full-screen player view for Fire TV and Android TV.
 *
 * It is a WebView pointed at the tablet's local-network server, plus the two
 * things a TV browser cannot do: find the tablet on its own (mDNS, advertised
 * by the tablet app's LanAdvertiser) and stay on screen without a screensaver
 * or any browser chrome.
 *
 * Deliberately no AndroidX and no Compose: it has to start quickly on a
 * 2016-era Fire TV stick, and the less it pulls in, the less can go wrong.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

base {
    archivesName.set("rpg-maps-tv-player")
}

android {
    namespace = "com.rpgmaps.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rpgmaps.player"
        // Fire OS 5 (Fire TV Stick 2nd gen) is Android 5.1, API 22.
        minSdk = 22
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Same committed debug key as the tablet app, for the same reason: every
    // CI build must be installable over the last one. See app/build.gradle.kts.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
