import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics.plugin)
}

android {
    namespace = "com.ooustream.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ooustream.iptv"
        minSdk = 21
        targetSdk = 34
        versionCode = 53
        versionName = "3.5.8"

        // TMDB API key for poster quality fallback
        val localPropsFile = rootProject.file("local.properties")
        val tmdbKey = if (localPropsFile.exists()) {
            val props = Properties()
            FileInputStream(localPropsFile).use { props.load(it) }
            props.getProperty("TMDB_API_KEY", "")
        } else ""
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbKey\"")
    }

    signingConfigs {
        getByName("debug") {
            // Default debug keystore
        }
        create("release") {
            // Use debug keystore for sideloaded distribution
            // Replace with production keystore for Play Store
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.fragment.ktx)

    // Leanback
    implementation(libs.leanback)

    // ConstraintLayout (MultiView grid)
    implementation(libs.constraintlayout)

    // Material (mobile bottom nav, theme)
    implementation(libs.material)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.livedata)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Media3 ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.leanback)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)

    // FFmpeg audio decoder extension (DTS, AC3, EAC3 software decoding via ExoPlayer)
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")

    // libVLC — secondary player engine for codecs ExoPlayer can't decode
    // (HEVC Main 10 10-bit HDR on older MTK devices, rare container formats, etc.)
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // Image Loading
    implementation(libs.coil)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Security
    implementation(libs.security.crypto)

    // DataStore
    implementation(libs.datastore.preferences)

    // Shimmer (loading skeletons)
    implementation(libs.shimmer)

    // Palette (color extraction)
    implementation(libs.palette)

    // ZXing (QR code generation for MultiView upgrade)
    implementation(libs.zxing.core)

    // WorkManager
    implementation(libs.work.runtime)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.storage)

    // Core library desugaring (required by media3-ffmpeg-decoder)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
