import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing details live outside the build file so the key never lands in
// version control; without the file, release simply falls back to unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    // Unit tests run on the JVM, where android.util.Log only exists as a stub;
    // the engine logs through it, so it answers with defaults there.
    testOptions { unitTests.isReturnDefaultValues = true }

    namespace = "io.github.mhammaddawudi.freeport"
    compileSdk = 34
    // The SDK on this machine has platform 34; 35 is not needed by anything here.
    buildToolsVersion = "34.0.0"

    defaultConfig {
        // Phones only: x86_64 is for emulators and would add ~27 MB to the universal apk.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        applicationId = "io.github.mhammaddawudi.freeport"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "3.1.0"
        // Where edges measured by people on the same operator are shared
        // (server/edge-board). Empty turns sharing off; each phone still
        // learns its own fast edges per operator.
        val edgeBoardUrl = (project.findProperty("edgeBoardUrl") as String?).orEmpty().trimEnd('/')
        buildConfigField("String", "EDGE_BOARD_URL", "\"$edgeBoardUrl\"")
    }

    // One apk per phone architecture plus a universal one. The per-ABI apks
    // are what most people should install — they are less than half the size.
    // The universal apk carries both phone ABIs (arm64-v8a, armeabi-v7a). All of them share one versionCode, so any of them can be
    // installed over any other.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val store = keystoreProperties.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // AGP drops the v1 (JAR) signature when minSdk >= 24. Some
                // third-party installers and file managers still check it,
                // so sign with every scheme.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // The JNI bridge is only compiled when the cpp sources are wired up. It is
    // guarded so the app builds and runs on the mock core before anyone has
    // cross-compiled the Rust side.
    if (project.hasProperty("warpNative")) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
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

    buildFeatures {
        compose = true
        // The home screen shows versionName, so people can tell which build they run.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract the libraries at install time. Loading them straight
            // from the apk would require every .so to sit on a 16 KB boundary
            // inside the zip, which build-tools 34's zipalign cannot do — and
            // on a device with 16 KB pages the core would silently fail to
            // load, dropping the whole app back to the mock.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // QR codes for the iPhone export. Encoder only, no camera code.
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
