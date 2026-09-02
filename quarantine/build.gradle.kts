plugins {
    id("chill-kotlin-library")
    alias(libs.plugins.kotlin.serialization) // test sources only: a real @Serializable class exercises the baked policy
}

dependencies {
    api(project(":policy"))
    implementation(project(":policy-painless"))
    implementation(libs.asm)

    testImplementation(project(":annotations"))
    testImplementation(libs.kotlinx.serialization.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

description = "Bytecode allowance verifier (ASM) with policy caching, build-time lambda verification, and shipped policies for common Kotlin libraries"

// ---- library policies baked at build time --------------------------------------------------------
// kotlin-stdlib comes off this module's own runtime classpath; other libraries are scan-only
// dependencies: needed to enumerate their API here, never a code dependency of the quarantine jar.

val policyScanClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false // the scanned jar only; its stdlib dependency is the one already on runtimeClasspath
    description = "Libraries scanned at build time to generate shipped chill policies"
}

dependencies {
    policyScanClasspath(libs.kotlinx.serialization.core)
}

val generateLibraryPolicies by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates the shipped META-INF/chill/policy/*.ctena library policies"
    dependsOn(tasks.compileKotlin)

    // NOTE: deliberately not sourceSets.main.runtimeClasspath - that would include
    // processResources output, which itself depends on this task's output.
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath.get(), policyScanClasspath)
    mainClass.set("dev.brikk.chill.quarantine.generator.buildtime.GenerateKotlinPoliciesKt")

    val outDir = layout.buildDirectory.dir("generated-policy")
    val kotlinxJar = policyScanClasspath.elements.map { files ->
        files.map { it.asFile }.single { it.name.startsWith("kotlinx-serialization-core") }
    }

    inputs.files(configurations.runtimeClasspath.get().filter { it.name.startsWith("kotlin-stdlib") })
    inputs.files(policyScanClasspath)
    outputs.dir(outDir)

    argumentProviders.add {
        listOf(outDir.get().asFile.absolutePath, kotlinxJar.get().absolutePath)
    }
}

sourceSets {
    main {
        resources.srcDir(generateLibraryPolicies)
    }
}
