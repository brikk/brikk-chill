package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.policy.ChillPolicyLoader
import dev.brikk.chill.quarantine.LibraryPolicies
import org.apache.logging.log4j.LogManager
import org.opensearch.common.settings.Settings
import org.opensearch.plugins.Plugin
import org.opensearch.plugins.ScriptPlugin
import org.opensearch.script.ScriptContext
import org.opensearch.script.ScriptEngine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Registers the `chill` script language: inline script sources are chill-frozen Kotlin lambdas
 * (`chill~~<base64>`), verified against the server's quarantine policy before any class is
 * defined, then executed for the score / filter / field contexts.
 *
 * Policy override: the shipped library policies can be replaced per name by dropping a
 * `<name>.ctena` into this plugin's config directory (`config/chill-script/`), e.g.
 * `kotlin-stdlib.ctena` regenerated against a different stdlib version. See [LibraryPolicies].
 */
class ChillScriptPlugin(@Suppress("UNUSED_PARAMETER") settings: Settings, configPath: Path) : Plugin(), ScriptPlugin {

    private val log = LogManager.getLogger(ChillScriptPlugin::class.java)

    init {
        if (Files.isDirectory(configPath)) {
            ChillPolicyLoader.overrideDirectory = configPath
            listOf(LibraryPolicies.KOTLIN_STDLIB, LibraryPolicies.KOTLINX_SERIALIZATION_CORE).forEach { name ->
                ChillPolicyLoader.overrideFileFor(name)?.let { log.info("chill policy [{}] overridden from {}", name, it) }
            }
        }
        // force the (one-time) policy load at plugin load rather than first compile
        ChillOpenSearch.chill
    }

    override fun getScriptEngine(settings: Settings, contexts: Collection<ScriptContext<*>>): ScriptEngine {
        return ChillScriptEngine()
    }
}
