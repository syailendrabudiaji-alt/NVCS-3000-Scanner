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

compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlinOptions {
    jvmTarget = "17"
}

}

dependencies {
// Android camera
val cameraXVersion = "1.4.2"

implementation("androidx.camera:camera-core:$cameraXVersion")
implementation("androidx.camera:camera-camera2:$cameraXVersion")
implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
implementation("androidx.camera:camera-view:$cameraXVersion")

// On-device machine-learning inference
implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")

}