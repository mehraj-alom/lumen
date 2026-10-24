// :core:store — the Room-backed state store.
//
// ADR 0002: this is the single exception to the `core/*` purity rule, because
// Room is an Android-only artifact. The persistence port (`StateStore`) and
// every DTO live in `:core:model`, which stays pure, so `:core:agent` — the
// loop — remains a plain JVM module with a fake store.
//
// This module may depend on `androidx.room`, `androidx.datastore`,
// `androidx.sqlite` and `net.zetetic` and nothing else outside `core/`.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.lumen.core.store"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The Room plugin wants the schema location in its own DSL, not as a KSP
// argument. The schema is committed, so the next migration has something to
// migrate from.
room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // ADR 0002: the only Android dependencies this module may have.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher)

    implementation(project(":core:model"))
    implementation(project(":core:sandbox"))

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.coroutines.test)
}
