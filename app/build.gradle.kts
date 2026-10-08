plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ramim.homedragon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ramim.homedragon"
        minSdk = 29
        targetSdk = 36
        versionCode = 35
        versionName = "2.25"
    }

    // Fixed debug key so every new build installs over the previous one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release: signed with the private upload key when the build has it (GitHub Secrets, see PLAY_STORE.md).
        // Without those secrets it falls back to the fixed debug key, so test builds still install over each other.
        create("release") {
            val ksPath = System.getenv("UPLOAD_KEYSTORE_FILE")
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            } else {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
