package dev.brikk.chill.gradle

import dev.brikk.chill.quarantine.KotlinxSerializationSupportPolicies
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.generator.buildtime.LibraryPolicyWriter
import dev.brikk.chill.quarantine.generator.jarfile.JarAllowancesGenerator
import dev.brikk.chill.quarantine.generator.jarfile.KotlinStdlibPolicyGenerator
import dev.brikk.chill.quarantine.generator.jarfile.KotlinxSerializationPolicyGenerator
import dev.brikk.chill.quarantine.generator.jarfile.ScanMode
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URLClassLoader

/**
 * Generates one named library policy from the configured jars into `<outputDirectory>/<name>.ctena`.
 *
 * Classes are loaded through a [URLClassLoader] whose parent is the platform loader, so the scan
 * sees exactly the configured files and not the Gradle daemon's (or this plugin's) own copies of
 * kotlin-stdlib or anything else.
 */
@CacheableTask
abstract class ChillGeneratePolicyTask : DefaultTask() {

    @get:Input
    abstract val policyName: Property<String>

    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val scanMode: Property<String>

    @get:Input
    abstract val packages: SetProperty<String>

    @get:Input
    abstract val excludePackages: SetProperty<String>

    @get:Input
    abstract val excludeClasses: SetProperty<String>

    @get:Input
    abstract val supportPolicies: SetProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val name = policyName.get()
        val jarFiles = jars.files.filter { it.isFile }.sortedBy { it.name }
        if (jarFiles.isEmpty()) throw GradleException("chill policy '$name': no jar files configured in `jars`")

        val loaderFiles = (jarFiles + classpath.files.filter { it.exists() }).distinct()
        val urls = loaderFiles.map { it.toURI().toURL() }.toTypedArray()

        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val (generator, support) = generatorFor(name, jarFiles, loader)
            val written = try {
                LibraryPolicyWriter.write(
                    name = name,
                    generator = generator,
                    outputDir = outputDirectory.get().asFile,
                    supportLines = support + supportPolicies.get(),
                    supportDescription = "support allowances",
                )
            } catch (ex: NoClassDefFoundError) {
                throw GradleException(
                    "chill policy '$name': class ${ex.message} could not be loaded while scanning; " +
                        "add the jars' dependencies to `classpath` (e.g. configurations.runtimeClasspath)",
                    ex,
                )
            }
            logger.lifecycle(
                "[chill] policy '$name': ${written.generatedLines} generated + ${written.supportLines} support lines " +
                    "from ${jarFiles.joinToString { it.name }} -> ${written.file}",
            )
        }
    }

    private fun generatorFor(name: String, jarFiles: List<File>, loader: ClassLoader): Pair<JarAllowancesGenerator, Set<String>> =
        when (val p = profile.get()) {
            ChillPolicySpec.PROFILE_KOTLIN_STDLIB ->
                KotlinStdlibPolicyGenerator(jarFiles, loader) to emptySet()

            ChillPolicySpec.PROFILE_KOTLINX_SERIALIZATION_CORE ->
                KotlinxSerializationPolicyGenerator(jarFiles, loader) to KotlinxSerializationSupportPolicies.policy

            ChillPolicySpec.PROFILE_CUSTOM -> {
                val mode = when (val m = scanMode.get().lowercase()) {
                    "safe" -> ScanMode.SAFE
                    "all" -> ScanMode.ALL
                    else -> throw GradleException("chill policy '$name': unknown scanMode '$m' (expected 'safe' or 'all')")
                }
                JarAllowancesGenerator(
                    jarFiles = jarFiles.map { it.path },
                    scanMode = mode,
                    preFilterPackageWhiteList = packages.get().toList(),
                    postFilterPackageWhiteList = packages.get().toList(),
                    postFilterPackageBlackList = excludePackages.get().toList(),
                    postFilterClassBlackList = excludeClasses.get(),
                    verifier = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy),
                    useClassLoader = loader,
                ) to emptySet()
            }

            else -> throw GradleException("chill policy '$name': unknown profile '$p'")
        }
}
