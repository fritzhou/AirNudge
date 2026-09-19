plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airnudge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airnudge.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    val releaseKeystore = providers.environmentVariable("AIRNUDGE_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("AIRNUDGE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("AIRNUDGE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("AIRNUDGE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    val cameraXVersion = "1.4.2"

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    testImplementation("junit:junit:4.13.2")
}

val handLandmarkerModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")

tasks.register("downloadHandLandmarkerModel") {
    description = "Downloads the official MediaPipe hand landmarker model"
    outputs.file(handLandmarkerModel)
    doLast {
        val modelFile = handLandmarkerModel.asFile
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val modelUrl = java.net.URI(
                "https://storage.googleapis.com/mediapipe-models/hand_landmarker/" +
                    "hand_landmarker/float16/1/hand_landmarker.task"
            ).toURL()
            val temporaryFile = modelFile.resolveSibling("${modelFile.name}.download")
            try {
                modelUrl.openStream().use { input ->
                    temporaryFile.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporaryFile.length() > 1_000_000) { "Downloaded hand model is unexpectedly small" }
                check(temporaryFile.renameTo(modelFile)) { "Could not install the hand landmarker model" }
            } finally {
                temporaryFile.delete()
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("downloadHandLandmarkerModel")
}
