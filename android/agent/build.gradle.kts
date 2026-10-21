// LUMEN — agent runtime module (process :agent).
// The agent runtime is deliberately a separate process from the console (ARCH.md §1):
// the user can kill the console at any time without interrupting a run.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.lumen.agent"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    // The agent is a LIBRARY, not a second application. The runtime service
    // is declared here with `android:process=":agent"` and its manifest is
    // merged into the one application, so the console and the runtime share
    // a uid.
    //
    // Two application modules would produce two apks with two uids, and the
    // agent could not bind to the non-exported accessibility service, which
    // is the whole reason the bridge exists.

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
