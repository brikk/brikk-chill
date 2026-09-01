plugins {
    id("chill-kotlin-library")
}

dependencies {
    api(project(":policy"))
    implementation(project(":policy-painless"))
    implementation(libs.asm)

    testImplementation(project(":annotations"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

description = "Bytecode allowance verifier (ASM) with policy caching and build-time lambda verification"
