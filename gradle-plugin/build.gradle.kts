plugins {
    id("chill-kotlin-library")
    `java-gradle-plugin`
}

dependencies {
    api(project(":quarantine"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        register("chill") {
            id = "dev.brikk.chill"
            implementationClass = "dev.brikk.chill.gradle.ChillPlugin"
            displayName = "Chill lambda verification"
            description = "Build-time security verification of @ChillLambda / @JvmSerializableLambda classes against a Chill quarantine policy"
        }
    }
}

description = "Gradle plugin verifying serializable Kotlin lambdas against a chill policy at build time"
