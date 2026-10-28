plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    // Room writes the database layout here at build time. These files are kept in git
    // (they hold no user data) so every future version can write a safe migration.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

android {
    namespace = "com.heybuddy"
    // compileSdk only decides which APIs the code can see; current AndroidX libraries need 37.
    // targetSdk (below) stays at 36 until the owner approves moving it.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.heybuddy"
        // Android 11+ gives clean biometric-bound Keystore APIs (spec section 2).
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Only real phones' CPU types (Task 2.1). x86 copies are only for emulators and would add
            // about 22 MB of OCR and database engine for nothing.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // FRAME CHECKS ONLY, never shipped (Task 1.10m). Like release (not debuggable, so Compose
        // runs at real speed), but signed with the debug key and not shrunk, so the instrumented
        // motion script can drive it. Compose must be measured like this: a debug build is several
        // times slower and gives misleading frame numbers.
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Tests normally run against the debug app; `-PmotionProfiling` runs them against "profiling".
    testBuildType = if (project.hasProperty("motionProfiling")) "profiling" else "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // The migration test (Task 1.10p) builds a version-1 database from app/schemas/.../1.json.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    lint {
        // Hey Buddy is a phone app (spec section 2); the CPU filter above leaves out the x86_64 copy
        // that only Chromebooks and emulators use, on purpose (Task 2.1).
        disable += "ChromeOsAbiSupport"
    }

    buildFeatures {
        compose = true
        // SafeLog reads BuildConfig.DEBUG so it can stay silent in release builds.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.tink.android)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.mlkit.text.recognition.devanagari)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
