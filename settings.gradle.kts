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
