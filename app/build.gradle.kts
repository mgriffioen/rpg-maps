plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * The player view (`/receiver/index.html`) has to exist in two places at once:
 * bundled in the APK for the local-network transport, and hosted at a public
 * HTTPS URL for the Chromecast receiver. Rather than keep two copies in sync by
 * hand -- a guaranteed source of "the TV is showing an old version" bugs -- the
 * repository-root copy is the only one, and the build copies it into assets.
 *
 * GitHub Pages serves the same file straight from the repository root.
 */
val generatedAssets: Provider<Directory> = layout.buildDirectory.dir("generated/receiverAssets")

val copyReceiverAssets by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("receiver"))
    into(generatedAssets.map { it.dir("receiver") })
}

android {
    namespace = "com.rpgmaps.tabletop"
    compileSdk = 36

    sourceSets.named("main") {
        assets.srcDir(generatedAssets)
    }

    defaultConfig {
        applicationId = "com.rpgmaps.tabletop"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Cast application ID for the custom receiver. "CC1AD845" is
        // Google's Default Media Receiver, which is only good enough to prove
        // the tablet can see the Chromecast -- it cannot render fog of war.
        // Replace it with your own ID from the Cast Developer Console once you
        // have published the receiver page (see docs/CASTING.md).
        resValue("string", "cast_app_id", "CC1AD845")
    }

    /**
     * A debug key that lives in the repository, so every build signs the same.
     *
     * Without this, AGP creates `~/.android/debug.keystore` on demand and each
     * machine gets its own. A CI runner starts with an empty home directory,
     * so *every* build produced a differently-signed APK, and Android refuses
     * to update an app whose signing key changed: the only way to install a
     * new build was to uninstall the old one, taking the map library and all
     * the fog with it.
     *
     * Committing a private key is normally wrong. This one is the standard
     * Android debug key in everything but bytes: the password is `android`,
     * it is written here in plain text, and it signs nothing that is
     * distributed. It exists to make one tablet's installs upgradable. A
     * release key would be kept out of the repository -- but this app has no
     * release channel to protect.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Every variant build runs preBuild, so the receiver page is always in place
// before assets are merged.
tasks.named("preBuild") {
    dependsOn(copyReceiverAssets)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)

    implementation(libs.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
