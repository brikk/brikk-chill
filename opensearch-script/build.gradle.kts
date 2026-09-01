plugins {
    id("chill-kotlin-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":serialize"))
    api(libs.kotlinx.serialization.core)
    api(libs.kotlinx.serialization.json)

    // client extensions only; consumers bring their own opensearch-java
    compileOnly(libs.opensearch.java)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

description = "Client-side chill OpenSearch scripting: typed slot bindings (kotlinx.serialization), script/template/stored-script types, opensearch-java extensions, and the shared script receiver"
