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
    implementation(project(":core:grounding"))
    implementation(project(":core:action"))
    implementation(project(":core:policy"))
    implementation(project(":core:brain"))
    implementation(project(":core:protocol"))

    testFixturesApi(project(":core:model"))
    testFixturesApi(project(":core:action"))
    testFixturesApi(project(":core:brain"))
    testFixturesImplementation(libs.kotlin.stdlib)
    testFixturesImplementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":core:agent")))
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(testFixtures(project(":core:brain")))
    testImplementation(testFixtures(project(":core:grounding")))
}
