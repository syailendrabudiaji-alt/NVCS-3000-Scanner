
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nvcs3000.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nvcs3000.scanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
