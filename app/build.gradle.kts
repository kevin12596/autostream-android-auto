plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kcliang.autostream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kcliang.autostream"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.4.14"
    }

    // Local builds use Android's local debug key. Official CI builds supply private signing secrets.
    val signingPath = System.getenv("AUTOSTREAM_KEYSTORE_PATH")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs.getByName("debug") {
            storeFile = file(signingPath)
            storeType = "pkcs12"
            storePassword = System.getenv("AUTOSTREAM_STORE_PASSWORD")
            keyAlias = System.getenv("AUTOSTREAM_KEY_ALIAS")
            keyPassword = System.getenv("AUTOSTREAM_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Versioned APK name, e.g. AutoStream-v0.4.14-build18.apk
    applicationVariants.all {
        val v = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "AutoStream-v${v.versionName}-build${v.versionCode}.apk"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
