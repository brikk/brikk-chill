plugins {
    id("chill-kotlin-library")
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures` // RankParams / ArticleDoc shared by the unit and integration suites
}

dependencies {
    api(project(":opensearch-script"))
    compileOnly(libs.opensearch.server)

    testFixturesImplementation(project(":opensearch-script"))
    testImplementation(testFixtures(project(":opensearch-plugin")))
    testImplementation(libs.opensearch.server)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// the fixtures are for this build's own suites; keep them out of the published component
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

description = "OpenSearch plugin executing chill-verified serialized Kotlin lambdas as scripts (score/filter/field contexts)"

// ---- OpenSearch plugin zip ---------------------------------------------------------------------
// Layout: plugin-descriptor.properties + plugin-security.policy + all runtime jars at the zip root.
// The descriptor's opensearch.version must exactly match the target server version.

val opensearchVersion = libs.versions.opensearch.asProvider().get()

val pluginDescriptor = tasks.register("pluginDescriptor") {
    val outFile = layout.buildDirectory.file("plugin-descriptor/plugin-descriptor.properties")
    val ver = project.version.toString()
    inputs.property("version", ver)
    inputs.property("opensearchVersion", opensearchVersion)
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                description=Chill-verified serialized Kotlin lambda scripts (score/filter/field)
                version=$ver
                name=chill-script
                classname=dev.brikk.chill.opensearch.plugin.ChillScriptPlugin
                java.version=21
                opensearch.version=$opensearchVersion
                """.trimIndent() + "\n",
            )
        }
    }
}

val pluginZip = tasks.register<Zip>("pluginZip") {
    group = "distribution"
    description = "Assembles the installable OpenSearch plugin zip"
    archiveBaseName.set("chill-script")
    archiveVersion.set("${project.version}-os-$opensearchVersion")

    from(tasks.jar)
    from(configurations.runtimeClasspath)
    from(pluginDescriptor)
    from(layout.projectDirectory.file("src/main/plugin-metadata/plugin-security.policy"))
}

tasks.named("assemble") { dependsOn(pluginZip) }

publishing.publications.withType<MavenPublication>().configureEach {
    artifact(pluginZip) { classifier = "os-$opensearchVersion" }
}

val pluginTestRepository = layout.buildDirectory.dir("plugin-test-repository")
publishing.repositories.maven {
    name = "pluginTest"
    url = uri(pluginTestRepository)
}

tasks.test {
    dependsOn("publishMavenPublicationToPluginTestRepository")
    inputs.file(pluginZip.flatMap { it.archiveFile })
    inputs.dir(pluginTestRepository)
    systemProperty("chill.plugin.zip", pluginZip.get().archiveFile.get().asFile.absolutePath)
    systemProperty("chill.plugin.version", project.version.toString())
    systemProperty("chill.opensearch.version", opensearchVersion)
    systemProperty("chill.plugin.publication", pluginTestRepository.get().dir(
        "${project.group.toString().replace('.', '/')}/chill-opensearch-plugin/${project.version}",
    ).asFile.absolutePath)
}

// ---- integration tests: real OpenSearch (Testcontainers) with the plugin installed --------------

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "integrationTestImplementation"(libs.testcontainers.junit)
    "integrationTestImplementation"(libs.opensearch.java)
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs the chill plugin against a real OpenSearch node (requires Docker)"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()

    dependsOn(pluginZip)
    inputs.file(pluginZip.flatMap { it.archiveFile }).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("chill.plugin.zip", pluginZip.get().archiveFile.get().asFile.absolutePath)
    systemProperty("chill.opensearch.version", opensearchVersion)

    onlyIf("Docker is available") {
        try {
            ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}

val packagingTest = sourceSets.create("packagingTest")
dependencies {
    add(packagingTest.implementationConfigurationName, gradleTestKit())
    add(packagingTest.implementationConfigurationName, libs.junit.jupiter)
    add(packagingTest.runtimeOnlyConfigurationName, libs.junit.platform.launcher)
}

val packagingTestTask = tasks.register<Test>("packagingTest") {
    group = "verification"
    description = "Checks ZIP-only changes rerun real-node integration tests in an isolated project copy"
    testClassesDirs = packagingTest.output.classesDirs
    classpath = packagingTest.runtimeClasspath
    useJUnitPlatform()
    inputs.files(rootProject.fileTree(rootProject.projectDir) {
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**", ".idea/**")
    }).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("chill.project.root", rootProject.projectDir.absolutePath)
    shouldRunAfter(integrationTestTask)
    onlyIf("Docker is available") {
        try {
            ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}

tasks.named("check") { dependsOn(integrationTestTask, packagingTestTask) }

// Local synthetic benchmarks only; not part of the published plugin or ordinary test runs.
val benchmark = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations["api"], configurations["implementation"])
val releasedBenchmark = configurations.create("releasedBenchmark") { isCanBeConsumed = false }
dependencies {
    for (configuration in listOf(benchmark.implementationConfigurationName, releasedBenchmark.name)) {
        add(configuration, libs.opensearch.server)
        add(configuration, libs.opensearch.lang.painless)
        add(configuration, libs.jmh.core)
        add(configuration, libs.mockito.core)
    }
    add(releasedBenchmark.name, "dev.brikk.chill:chill-opensearch-plugin:0.1.0")
    add(benchmark.annotationProcessorConfigurationName, libs.jmh.generator)
}
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs local JMH scoring benchmarks against synthetic Lucene doc values (no cluster)"
    dependsOn(tasks.named(benchmark.classesTaskName))
    classpath = if (providers.gradleProperty("benchmarkReleased").isPresent) files(benchmark.output, releasedBenchmark) else benchmark.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("benchmarkJava").orElse("25").get()))
    })
    args("ScoringBenchmark", "-p", "binding=painless,painless_cached,direct,bound", "-p", "shape=narrow,wide12", "-p", "access=all", "-p", "missingPercent=0", "-prof", "gc", "-foe", "true")
}
