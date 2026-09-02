package dev.brikk.chill.opensearch

import kotlinx.serialization.KSerializer

/**
 * A ready-to-run chill script: the frozen source plus the encoded params for this execution.
 * Use as an inline script: `lang = "chill"`, `source`, `params`.
 */
open class ChillScript<out R> internal constructor(
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
open class ChillScriptTemplate<P : Any, out R> internal constructor(
    val source: String,
    val paramsSerializer: KSerializer<P>,
) {
    fun withParams(params: P): ChillScript<R> = ChillScript(source, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/** Empty receiver for scripts whose OpenSearch inputs must all be declared as lambda parameters. */
object ChillBoundScript

class ChillBoundScore<P : Any, D : Any> internal constructor(
    source: String,
    params: Map<String, Any?>,
    private val boundParams: P,
    private val evaluator: ChillBoundScript.(P, D) -> Double,
) : ChillScript<Double>(source, params) {
    fun evaluate(doc: D): Double = evaluator(ChillBoundScript, boundParams, doc)
}

class ChillBoundScoreWithBaseScore<P : Any, D : Any> internal constructor(
    source: String,
    params: Map<String, Any?>,
    private val boundParams: P,
    private val evaluator: ChillBoundScript.(P, D, Double) -> Double,
) : ChillScript<Double>(source, params) {
    fun evaluate(doc: D, score: Double): Double = evaluator(ChillBoundScript, boundParams, doc, score)
}

class ChillBoundScoreTemplate<P : Any, D : Any> internal constructor(
    source: String,
    paramsSerializer: KSerializer<P>,
    private val evaluator: ChillBoundScript.(P, D) -> Double,
) : ChillScriptTemplate<P, Double>(source, paramsSerializer) {
    fun evaluate(params: P, doc: D): Double = evaluator(ChillBoundScript, params, doc)
}

class ChillBoundScoreWithBaseScoreTemplate<P : Any, D : Any> internal constructor(
    source: String,
    paramsSerializer: KSerializer<P>,
    private val evaluator: ChillBoundScript.(P, D, Double) -> Double,
) : ChillScriptTemplate<P, Double>(source, paramsSerializer) {
    fun evaluate(params: P, doc: D, score: Double): Double = evaluator(ChillBoundScript, params, doc, score)
}

/**
 * A reference to a stored chill script (registered from a [ChillScriptTemplate]) with a typed
 * params requirement.
 */
class ChillStoredScriptRef<P : Any>(
    val id: String,
    val paramsSerializer: KSerializer<P>,
) {
    fun withParams(params: P): ChillStoredScript =
        ChillStoredScript(id, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/** A stored-script invocation: id + encoded params. */
class ChillStoredScript(
    val id: String,
    val params: Map<String, Any?>,
)

fun <P : Any> storedChillScript(id: String, params: ParamTypeSlot<P>): ChillStoredScriptRef<P> =
    ChillStoredScriptRef(id, params.serializer)

fun storedChillScript(id: String): ChillStoredScript = ChillStoredScript(id, emptyMap())
