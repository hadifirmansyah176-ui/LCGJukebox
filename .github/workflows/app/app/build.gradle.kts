plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hadi.lcgjukebox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hadi.lcgjukebox"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    dependencies {
        implementation("androidx.media:media:1.7.0")
        implementation("androidx.core:core-ktx:1.12.0")
    }

