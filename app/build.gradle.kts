plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bearinmind.equalizer314"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bearinmind.equalizer314"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "0.0.13-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Note: on AGP 8.3+, also set `vcsInfo { include = false }` here for
            // F-Droid reproducibility. Current AGP 8.2.0 doesn't embed VCS info
            // by default, so no disabling is needed yet.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }

    // F-Droid rejects APKs containing the "Dependency metadata" extra signing
    // block that AGP 8.1+ embeds by default. Disable both APK and AAB variants.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // RecyclerView 1.2.0+ — needed for ViewHolder.bindingAdapterPosition.
    // Used to be pulled in transitively by androidx.media3; declaring
    // explicitly now that the media3 deps are gone.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // LocalBroadcastManager (for MediaNotificationListener → SessionDetector communication)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // (Removed unused androidx.media3-exoplayer/ui/session dependencies.
    // They were an artefact of an earlier capture-based design that
    // never shipped. The current implementation uses Android's native
    // DynamicsProcessing API and EnvironmentalReverb directly, no
    // ExoPlayer involvement. Removing them also strips the transitively-
    // added android.permission.ACCESS_NETWORK_STATE — "View network
    // connections" on the Play Store / settings UI — which was the
    // only thing that permission was there for.)

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// F-Droid reproducible builds: disable baseline profile generation. The
// output of baseline.prof is non-deterministic across machines and would
// cause F-Droid's build reproducibility check to fail.
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

