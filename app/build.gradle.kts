plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// Load keystore properties if exists
val keystorePropertiesFile = rootProject.file("app/keystore/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    try {
        keystoreProperties.load(keystorePropertiesFile.inputStream())
    } catch (e: Exception) {
        println("Warning: could not load keystore.properties: ${e.message}")
    }
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
            // This keystore is public for CI - generated via openssl as PKCS12, works for debug/release
            val ksFile = rootProject.file("app/keystore/tradescanner.jks")
            val ksFileAlt = file("keystore/tradescanner.jks")
            val actualFile = when {
                ksFile.exists() -> ksFile
                ksFileAlt.exists() -> ksFileAlt
                else -> null
            }

            if (actualFile != null) {
                storeFile = actualFile
                storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD") ?: "tradescanner"
                keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS") ?: "tradescanner"
                keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD") ?: "tradescanner"
                // Let Gradle auto-detect store type (JKS or PKCS12) - our openssl file is PKCS12 but named .jks
                // No explicit storeType needed; removing fixes both JKS and PKCS12 compatibility
                println("SigningConfig release: using keystore ${actualFile.absolutePath}")
            } else {
                println("Warning: release keystore not found at app/keystore/tradescanner.jks - release build will fallback to debug signing")
                // Don't set storeFile - let buildTypes handle fallback
            }
        }
        // Debug uses default debug keystore
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Disabled for first release to avoid R8 issues; enable later
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing if keystore exists, otherwise debug for local development
            val ksFile = rootProject.file("app/keystore/tradescanner.jks")
            val ksFileAlt = file("keystore/tradescanner.jks")
            val hasKeystore = ksFile.exists() || ksFileAlt.exists() || keystorePropertiesFile.exists()

            signingConfig = if (hasKeystore) {
                // Check if release config has storeFile set
                val releaseCfg = signingConfigs.getByName("release")
                if (releaseCfg.storeFile != null && releaseCfg.storeFile!!.exists()) {
                    releaseCfg
                } else {
                    println("Release keystore file missing, using debug signing for release build")
                    signingConfigs.getByName("debug")
                }
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
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            // Keep all native libs for OpenCV if added later
            useLegacyPackaging = false
        }
    }
    // Ensure lint doesn't break release
    lint {
        abortOnError = false
        checkReleaseBuilds = false
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

    // Coroutines - including play-services for Task.await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // MLKit Text Recognition for OCR price extraction
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // OpenCV - OPTIONAL: App has pure Kotlin fallback, so this is commented out by default to guarantee build
    // If you want better template matching accuracy, uncomment and ensure internet for Maven:
    // implementation("com.quickbirdstudios:opencv:4.12.0")
    // org.opencv is loaded via reflection in TemplateMatcher, so app works without it

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
