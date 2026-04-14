// Pin a newer R8 than AGP 8.6.0 bundles. Stock R8 8.6.17 hits a
// ConcurrentModificationException on assembleRelease with our keep-rule set
// (org.videolan + androidx.media3.decoder.ffmpeg). 8.7.14 is the nearest
// stable release with the fix. buildscript{} must precede plugins{}.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:8.7.14")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics.plugin) apply false
}
