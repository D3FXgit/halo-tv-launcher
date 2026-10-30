import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.halo.launcher"
    compileSdk = 35

    defaultConfig {
        // Different from the source package, so a release build can sit next to a dev build.
        applicationId = "app.halo.tv"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // The Gemini API key is entered by each user in the setup wizard (never built into the APK).
        // Public project page, sent as contact in the User-Agent of picture downloads (Wikimedia
        // asks for one). Set halo.projectUrl in gradle.properties (or -Phalo.projectUrl=...).
        val projectUrl = providers.gradleProperty("halo.projectUrl").getOrElse("").replace("\"", "")
        buildConfigField("String", "PROJECT_URL", "\"$projectUrl\"")
    }

    // Optional release signing (see docs/DEVELOPMENT.md → "Release signing"). Provide either a
    // git-ignored keystore.properties file in the project root (storeFile, storePassword, keyAlias,
    // keyPassword) or the environment variables HALO_KEYSTORE_FILE, HALO_KEYSTORE_PASSWORD,
    // HALO_KEY_ALIAS and HALO_KEY_PASSWORD (used by CI). Without them the release APK is signed
    // with the local debug key, which is fine for personal sideloading.
    val signingProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(prop: String, env: String): String? =
        (signingProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    val releaseStoreFile = signingValue("storeFile", "HALO_KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    val releaseStorePassword = signingValue("storePassword", "HALO_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "HALO_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "HALO_KEY_PASSWORD") ?: releaseStorePassword

    signingConfigs {
        if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Compose is several times slower in debuggable, unshrunk builds, so the
        // "debug" build (what Android Studio's Run button installs) is optimised too.
        debug {
            // Enables the screenshot/test hooks used by tv.gradle.kts (tvTour). Off in release.
            buildConfigField("boolean", "DEV_HOOKS", "true")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            buildConfigField("boolean", "DEV_HOOKS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Your own release key when configured (above), else the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.profileinstaller)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.palette)
    implementation(libs.zxing.core)
}
