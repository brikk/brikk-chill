plugins {
    id("chill-kotlin-library")
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

description = "Chill policy model, string format, and loader"
