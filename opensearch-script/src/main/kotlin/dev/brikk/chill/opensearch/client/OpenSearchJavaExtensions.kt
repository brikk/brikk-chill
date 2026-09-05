package dev.brikk.chill.opensearch.client

import dev.brikk.chill.opensearch.ChillScript
import dev.brikk.chill.opensearch.ChillScriptTemplate
import dev.brikk.chill.opensearch.ChillStoredScript
import jakarta.json.JsonValue
import org.opensearch.client.json.JsonData
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.Script
import org.opensearch.client.opensearch._types.ScriptLanguage
import org.opensearch.client.opensearch._types.StoredScript
import org.opensearch.client.opensearch._types.query_dsl.ScriptQuery
import org.opensearch.client.opensearch._types.query_dsl.ScriptScoreFunction
import org.opensearch.client.opensearch._types.query_dsl.ScriptScoreQuery
import org.opensearch.client.opensearch.core.PutScriptResponse
import org.opensearch.client.opensearch.core.SearchRequest

/**
 * opensearch-java client integration. This file only links when opensearch-java is on the
 * consumer's classpath (the dependency is compileOnly here).
 */

private val chillLanguage: ScriptLanguage = ScriptLanguage.of { it.custom(ChillScript.LANG) }

/**
 * An explicit `null` param is sent as JSON `null`, not dropped: dropping it would make the server
 * apply the class default instead of the null the caller chose (`ParamsCodec` only emits keys the
 * params class actually set, so every key here is intentional).
 */
private fun Map<String, Any?>.toJsonData(): Map<String, JsonData> =
    mapValues { (_, v) -> if (v == null) JsonData.of(JsonValue.NULL) else JsonData.of(v) }

/** Inline script form: lang "chill", frozen source, encoded params. */
fun ChillScript<*>.toScript(): Script = Script.of { s ->
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

// ---- typed query construction: the result type picks the contexts a script may enter ------------
// A ChillScript<Boolean> cannot be passed where a score is needed, and vice versa; the node enforces
// the same rule at compile from the type recorded in the payload, this just moves it to the IDE.

/** `script_score` query: the script must produce a number. */
fun ScriptScoreQuery.Builder.chill(script: ChillScript<Number>): ScriptScoreQuery.Builder = script(script.toScript())

/** `function_score` script function: the script must produce a number. */
fun ScriptScoreFunction.Builder.chill(script: ChillScript<Number>): ScriptScoreFunction.Builder = script(script.toScript())

/** `script` query (filter): the script must produce a boolean. */
fun ScriptQuery.Builder.chill(script: ChillScript<Boolean>): ScriptQuery.Builder = script(script.toScript())

/** `script_fields` entry: any result type. */
fun SearchRequest.Builder.chillScriptField(name: String, script: ChillScript<*>): SearchRequest.Builder =
    scriptFields(name) { it.script(script.toScript()) }

/** Stored variants. Stored scripts carry no client-side result type; the node still checks at compile. */
fun ScriptScoreQuery.Builder.chill(script: ChillStoredScript): ScriptScoreQuery.Builder = script(script.toScript())
fun ScriptScoreFunction.Builder.chill(script: ChillStoredScript): ScriptScoreFunction.Builder = script(script.toScript())
fun ScriptQuery.Builder.chill(script: ChillStoredScript): ScriptQuery.Builder = script(script.toScript())
fun SearchRequest.Builder.chillScriptField(name: String, script: ChillStoredScript): SearchRequest.Builder =
    scriptFields(name) { it.script(script.toScript()) }

/** Registers a reusable template as a stored script (`PUT _scripts/{id}`); verified at store time. */
fun OpenSearchClient.putChillScript(id: String, template: ChillScriptTemplate<*, *>): PutScriptResponse =
    putScript { req ->
        req.id(id).script(
            StoredScript.of { s -> s.lang(chillLanguage).source(template.source) },
        )
    }
