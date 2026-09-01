package dev.brikk.chill.opensearch.client

import dev.brikk.chill.opensearch.ChillScript
import dev.brikk.chill.opensearch.ChillScriptTemplate
import dev.brikk.chill.opensearch.ChillStoredScript
import org.opensearch.client.json.JsonData
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.Script
import org.opensearch.client.opensearch._types.ScriptLanguage
import org.opensearch.client.opensearch._types.StoredScript
import org.opensearch.client.opensearch.core.PutScriptResponse

/**
 * opensearch-java client integration. This file only links when opensearch-java is on the
 * consumer's classpath (the dependency is compileOnly here).
 */

private val chillLanguage: ScriptLanguage = ScriptLanguage.of { it.custom(ChillScript.LANG) }

private fun Map<String, Any?>.toJsonData(): Map<String, JsonData> =
    entries.mapNotNull { (k, v) -> v?.let { k to JsonData.of(it) } }.toMap()

/** Inline script form: lang "chill", frozen source, encoded params. */
fun ChillScript.toScript(): Script = Script.of { s ->
    s.inline { i ->
        i.lang(chillLanguage)
            .source(source)
            .params(params.toJsonData())
    }
}

/** Stored-script invocation form: id + encoded params. */
fun ChillStoredScript.toScript(): Script = Script.of { s ->
    s.stored { st ->
        st.id(id).params(params.toJsonData())
    }
}

/** Registers a reusable template as a stored script (`PUT _scripts/{id}`); verified at store time. */
fun OpenSearchClient.putChillScript(id: String, template: ChillScriptTemplate<*>): PutScriptResponse =
    putScript { req ->
        req.id(id).script(
            StoredScript.of { s -> s.lang(chillLanguage).source(template.source) },
        )
    }
