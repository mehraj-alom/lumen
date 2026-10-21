// LUMEN — agent runtime module (process :agent).
// The agent runtime is deliberately a separate process from the console (ARCH.md §1):
// the user can kill the console at any time without interrupting a run.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.lumen.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lumen.agent"
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

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.lifecycle.service)

    implementation(project(":core:model"))
    implementation(project(":core:action"))
    implementation(project(":core:grounding"))
    implementation(project(":core:policy"))
    implementation(project(":core:quota"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:protocol"))
    implementation(project(":core:brain"))
    implementation(project(":core:agent"))
    implementation(project(":core:store"))

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
}
