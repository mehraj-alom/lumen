plugins {
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(project(":core:model"))

    // The fixture source set is its own compilation: it does not inherit
    // `implementation`, and its public API mentions `:core:model` types, so
    // those go in as `api` for the modules that consume the fixtures.
    testFixturesApi(project(":core:model"))
    testFixturesImplementation(libs.kotlin.stdlib)
    testFixturesImplementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
}
