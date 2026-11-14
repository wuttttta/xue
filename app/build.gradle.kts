plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "lol.varie.xue"
    compileSdk = 35

    defaultConfig {
        applicationId = "lol.varie.xue"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // Phones only: keeps the OCR library's native code to the two ARM builds.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // A fixed key from GitHub secrets, when present, so each new build installs over the last one.
    // Without it the build uses the runner's throwaway debug key (uninstall before each update).
    val ksFile = System.getenv("XUE_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) {
            create("fixed") {
                storeFile = file(ksFile)
                storeType = "pkcs12"
                storePassword = System.getenv("XUE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XUE_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "xue"
                keyPassword = System.getenv("XUE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksFile != null) signingConfig = signingConfigs.getByName("fixed")
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
    // OCR mode: on-device Chinese text recognition through Google Play services. The model isn't in the APK:
    // Play services downloads it once, when OCR is first turned on, and keeps it (offline after that).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.2")
}
