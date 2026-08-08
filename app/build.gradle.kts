plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Load keystore properties if exists for local build, otherwise use debug config fallback
val keystorePropertiesFile = rootProject.file("app/keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "dev.tradescanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tradescanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile") ?: "keystore/tradescanner.jks")
                storePassword = keystoreProperties.getProperty("storePassword") ?: "tradescanner"
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: "tradescanner"
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: "tradescanner"
            } else {
                // Fallback for CI with env variables or debug keystore path
                // If no keystore, build will use debug signing for development
                // CI workflow generates keystore.properties
                val storeFilePath = System.getenv("KEYSTORE_FILE") ?: "keystore/tradescanner.jks"
                storeFile = file(storeFilePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tradescanner"
                keyAlias = System.getenv("KEY_ALIAS") ?: "tradescanner"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "tradescanner"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing if keystore exists, otherwise debug
            signingConfig = if (file("keystore/tradescanner.jks").exists() || keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // MLKit Text Recognition for OCR price extraction
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // OpenCV - using quickbirdstudios version which bundles native libs via Maven
    // If you prefer pure Java fallback, the app has TemplateMatcher without OpenCV as well
    implementation("com.quickbirdstudios:opencv:4.8.0")

    // For image processing fallback
    implementation("androidx.camera:camera-core:1.3.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
