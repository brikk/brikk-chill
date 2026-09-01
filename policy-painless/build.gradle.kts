plugins {
    id("chill-kotlin-library")
}

dependencies {
    api(project(":policy"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The OpenSearch lang-painless jar is only used as the *source* of the whitelist
// .txt definition files at build time; it is not a code dependency.
val painlessWhitelists: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    painlessWhitelists(libs.opensearch.lang.painless)
}

val extractPainlessWhitelists by tasks.registering(Copy::class) {
    from(zipTree(painlessWhitelists.singleFile)) {
        include("org/opensearch/painless/spi/*.txt")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("painless-whitelists"))
}

val generatePainlessPolicy by tasks.registering(JavaExec::class) {
    dependsOn(tasks.compileKotlin, extractPainlessWhitelists)

    // NOTE: deliberately not sourceSets.main.runtimeClasspath - that would include
    // processResources output, which itself depends on this task's output.
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath.get())
    mainClass.set("dev.brikk.chill.policy.painless.generator.GeneratePolicyKt")

    val whitelistDir = layout.buildDirectory.dir("painless-whitelists")
    val plusDir = layout.projectDirectory.dir("src/main/painless-plus")
    val outDir = layout.buildDirectory.dir("generated-policy")

    inputs.dir(whitelistDir)
    inputs.dir(plusDir)
    outputs.dir(outDir)

    argumentProviders.add {
        listOf(
            whitelistDir.get().asFile.absolutePath,
            plusDir.asFile.absolutePath,
            outDir.get().asFile.absolutePath,
        )
    }
}

sourceSets {
    main {
        resources.srcDir(generatePainlessPolicy)
    }
}

description = "Base safe-JDK chill policy generated from OpenSearch Painless whitelists"
