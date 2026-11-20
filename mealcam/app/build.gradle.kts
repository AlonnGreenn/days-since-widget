plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// MealCam has all-files access, so unlike the other apps in this repo its signing key is NOT
// committed: CI restores it from the MEALCAM_KEYSTORE_BASE64 / MEALCAM_KEYSTORE_PASSWORD secrets.
// A backup of the key lives in the vault (_system/apps/mealcam-signing/). Without it: debug key.
val keystore = file("mealcam.p12")
val keyPass = System.getenv("MEALCAM_KEYSTORE_PASSWORD") ?: ""
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.vibecoded.mealcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vibecoded.mealcam"
        minSdk = 30 // all-files access
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        create("release") {
            if (keystore.exists()) {
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = keyPass
                keyAlias = "mealcam"
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
}
