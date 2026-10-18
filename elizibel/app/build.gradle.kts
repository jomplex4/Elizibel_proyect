plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.digitalminds.elizibel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.digitalminds.elizibel"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "2.1"
    }

    signingConfigs {
        // firma fija guardada en el repositorio: cada version nueva se instala encima de la anterior
        create("fija") {
            storeFile = file("elizibel.keystore")
            storePassword = "elizibel123"
            keyAlias = "elizibel"
            keyPassword = "elizibel123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // los OGG se leen directo del APK con MediaExtractor: no se comprimen
        noCompress += listOf("ogg")
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sin dependencias externas: solo el framework de Android.
