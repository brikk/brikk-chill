plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "brikk-chill"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "annotations",
    "policy",
    "policy-painless",
    "quarantine",
    "serialize",
    "gradle-plugin",
    "opensearch-script",
    "opensearch-plugin",
)
