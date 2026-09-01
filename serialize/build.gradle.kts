plugins {
    id("chill-kotlin-library")
}

dependencies {
    api(project(":quarantine"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

description = "Safe freeze/thaw serialization of Kotlin lambdas verified by chill quarantine"
