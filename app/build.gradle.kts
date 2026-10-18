plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fellowabhi.chargebrake"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fellowabhi.chargebrake"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    dependenciesInfo { includeInApk = false; includeInBundle = false }
    testOptions { unitTests.all { it.testLogging { showStandardStreams = true; events("passed", "failed") } } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
