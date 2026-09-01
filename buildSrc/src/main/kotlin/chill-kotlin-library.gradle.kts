import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
}

// group/version come from the root gradle.properties (version overridable with -Pversion=X.Y.Z)

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ---- publishing -------------------------------------------------------------------------------

publishing {
    repositories {
        // keyless SNAPSHOT flow: main pushes publish here (Central Portal snapshots repo)
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials {
                username = System.getenv("KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME")
                    ?: System.getenv("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME")
                password = System.getenv("KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD")
                    ?: System.getenv("KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD")
            }
        }
        // release flow: all modules publish into one shared staging dir, which release.yml zips
        // into a Central Portal deployment bundle and uploads with publishingType=AUTOMATIC
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// the gradle-plugin module gets its publications from java-gradle-plugin (main + plugin markers);
// plain library modules publish the java component themselves. Deferred: this convention is
// applied before java-gradle-plugin in that module's plugins block.
afterEvaluate {
    if (!pluginManager.hasPlugin("java-gradle-plugin")) {
        publishing.publications.register<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    // marker publications (dev.brikk.chill.gradle.plugin) must keep their artifactId; everything
    // else publishes as chill-<module>
    if (!artifactId.endsWith("gradle.plugin")) {
        artifactId = "chill-${project.name}"
    }
    pom {
        name.set("brikk-chill ${project.name}")
        description.set(providers.provider { project.description ?: "brikk-chill: safe quarantine verification and serialization of JVM classes and Kotlin lambdas" })
        url.set("https://github.com/brikk/brikk-chill")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer { name.set("Jayson Minard") }
            developer { name.set("Sortdev SRL") }
        }
        scm {
            connection.set("scm:git:https://github.com/brikk/brikk-chill.git")
            developerConnection.set("scm:git:git@github.com:brikk/brikk-chill.git")
            url.set("https://github.com/brikk/brikk-chill")
        }
    }
}

// sign only when a key is present (releases); the SNAPSHOT flow stays keyless
val signingKey: String? = System.getenv("KOTLIN_TOOLCHAIN_SIGNING_KEY")
if (!signingKey.isNullOrBlank()) {
    signing {
        val passphrase = System.getenv("KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE")
            ?: System.getenv("KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE")
        useInMemoryPgpKeys(signingKey, passphrase)
        sign(publishing.publications)
    }
}

// refuse to publish a non-SNAPSHOT to the snapshots repo and vice versa
tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")
        if (repository.name == "centralSnapshots" && !isSnapshot) {
            throw GradleException("Version ${project.version} is not a -SNAPSHOT; use the release flow")
        }
        if (repository.name == "staging" && isSnapshot) {
            throw GradleException("Refusing to stage a -SNAPSHOT (${project.version}) for a Central release")
        }
    }
}
