// LUMEN — console module (process :ui).
// The Compose console and the AccessibilityService both live here so that
// the accessibility service stays in the process the user can enable
// (ARCH.md §1, §6).

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.lumen.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lumen"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // One APK per ABI, plus a universal one. The arm64-v8a APK is the one
    // to install on a modern phone; x86_64 is for the emulator. The
    // universal APK is the fallback when you are not sure.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // The runtime is a library whose service is merged into this application,
    // so the console and the agent share a uid and can bind to each other.
    implementation(project(":agent"))

    implementation(project(":core:model"))
    implementation(project(":core:action"))
    implementation(project(":core:grounding"))
    implementation(project(":core:policy"))
    implementation(project(":core:quota"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:store"))
    implementation(project(":core:brain"))
    implementation(project(":core:protocol"))
    implementation(project(":core:agent"))

    implementation(project(":platform:accessibility"))
    implementation(project(":platform:capture"))
    implementation(project(":platform:virtualdisplay"))
    implementation(project(":platform:idle"))
    implementation(project(":platform:overlay"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.truth)
}
