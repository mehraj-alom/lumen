// The native inference runtime.
//
// This is where the native build lives, because `core/*` is pure Kotlin and
// cannot host an Android native build (ADR 0002). The Kotlin side of the
// bridge is `LlamaNative`, which implements the `NativeInference` port that
// `core:brain` defines — so the agent never sees the native library.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.lumen.platform.inference"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35

        // The native inference runtime is opt-in. The default build ships no
        // native code and no model weights, and the agent is fully functional
        // without them (decision D2).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(project(":core:model"))
    implementation(project(":core:brain"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}
