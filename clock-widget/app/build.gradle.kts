plugins { id("com.android.application") }

// Committed signing key on purpose so each CI build installs as an update. Not a secret.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.vibecoded.clock"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.vibecoded.clock"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.$runNumber"
    }
    signingConfigs {
        create("release") {
            storeFile = file("clock.jks")
            storePassword = "vibecoded-clock"
            keyAlias = "clock"
            keyPassword = "vibecoded-clock"
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
