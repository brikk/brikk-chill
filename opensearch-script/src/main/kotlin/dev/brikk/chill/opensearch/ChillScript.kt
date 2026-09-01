package dev.brikk.chill.opensearch

import kotlinx.serialization.KSerializer

/**
 * A ready-to-run chill script: the frozen source plus the encoded params for this execution.
 * Use as an inline script: `lang = "chill"`, `source`, `params`.
 */
class ChillScript internal constructor(
    val source: String,
    val params: Map<String, Any?>,
) {
    companion object {
        const val LANG = "chill"
    }
}

/**
 * A reusable chill script: the frozen source with a declared-but-unsupplied params type. The only
 * way into a query is [withParams] - which also makes it the right shape to register as a stored
 * script (store the value-free source once, send params per query).
 */
class ChillScriptTemplate<P : Any> internal constructor(
    val source: String,
    val paramsSerializer: KSerializer<P>,
) {
    fun withParams(params: P): ChillScript = ChillScript(source, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/**
 * A reference to a stored chill script (registered from a [ChillScriptTemplate]) with a typed
 * params requirement.
 */
class ChillStoredScriptRef<P : Any>(
    val id: String,
    val paramsSerializer: KSerializer<P>,
) {
    fun withParams(params: P): ChillStoredScript = ChillStoredScript(id, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/** A stored-script invocation: id + encoded params. */
class ChillStoredScript(
    val id: String,
    val params: Map<String, Any?>,
)

fun <P : Any> storedChillScript(id: String, params: ParamTypeSlot<P>): ChillStoredScriptRef<P> =
    ChillStoredScriptRef(id, params.serializer)

fun storedChillScript(id: String): ChillStoredScript = ChillStoredScript(id, emptyMap())
