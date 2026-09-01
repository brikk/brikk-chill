package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import org.opensearch.common.settings.Settings
import org.opensearch.plugins.Plugin
import org.opensearch.plugins.ScriptPlugin
import org.opensearch.script.ScriptContext
import org.opensearch.script.ScriptEngine

/**
 * Registers the `chill` script language: inline script sources are chill-frozen Kotlin lambdas
 * (`chill~~<base64>`), verified against the server's quarantine policy before any class is
 * defined, then executed for the score / filter / field contexts.
 */
class ChillScriptPlugin : Plugin(), ScriptPlugin {

    init {
        // force the (one-time) policy construction at plugin load rather than first compile
        ChillOpenSearch.chill
    }

    override fun getScriptEngine(settings: Settings, contexts: Collection<ScriptContext<*>>): ScriptEngine {
        return ChillScriptEngine()
    }
}
