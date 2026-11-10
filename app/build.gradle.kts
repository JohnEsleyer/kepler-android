plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Export the Room schema so the database contract (notably the composite
// primary key of the `pages` table) is reviewable and assertable in tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.esper.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.johnesleyer.esper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "3.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // A production keystore is supplied through the environment (or
            // Gradle properties) so no secret is ever committed to the repo.
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: project.findProperty("RELEASE_STORE_FILE") as? String
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: project.findProperty("RELEASE_STORE_PASSWORD") as? String ?: ""
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: project.findProperty("RELEASE_KEY_ALIAS") as? String ?: ""
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: ""
            } else {
                // Fall back to the local debug keystore so `assembleRelease`
                // still produces a signed APK that `adb install` can put on a
                // device for verification. Replace with a real keystore
                // (KEYSTORE_PATH / RELEASE_STORE_FILE) for distribution.
                val debugKeystore = signingConfigs.findByName("debug")
                val debugStoreFile = debugKeystore?.storeFile
                    ?: file("${System.getProperty("user.home")}/.android/debug.keystore")
                storeFile = debugStoreFile
                storePassword = debugKeystore?.storePassword ?: "android"
                keyAlias = debugKeystore?.keyAlias ?: "androiddebugkey"
                keyPassword = debugKeystore?.keyPassword ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)

    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.google.gson)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
}
