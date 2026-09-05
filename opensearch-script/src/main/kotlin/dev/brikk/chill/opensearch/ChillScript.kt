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
    open fun withParams(params: P): ChillScript<R> = ChillScript(source, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/**
 * Empty receiver for *bound* scripts: every OpenSearch input the lambda uses must be declared as
 * a slot parameter, which is what lets the identical lambda run locally via `evaluate`.
 */
object ChillBound

/**
 * A ready-to-run bound script. [evaluate] is the same lambda that was frozen, with any
 * `paramOf` value already applied, typed by the remaining slots in order: for
 * `bound(paramOf(p), docType<D>(), scoreType())` it is `(D, Double) -> R`. What the node computes
 * for a hit is what `evaluate` computes for the same document locally, with two caveats that
 * come from OpenSearch rather than chill:
 *
 *  - **Scores are float32.** A score-context result is stored by Lucene as a `float`, and
 *    `hit.score()` is that float as it appears in the response JSON. `evaluate` returns the full
 *    `double`; compare with [asIndexScore] (or a relative tolerance of ~1e-7) rather than for equality.
 *  - **Doc values are sorted.** Multi-valued doc values arrive sorted (keywords also
 *    de-duplicated), so a `List<T>` bound from `docType` sees sorted values while a locally
 *    constructed instance holds `_source` order; bind from `sourceType` when order matters, or
 *    construct local instances from the same sorted view.
 */
class ChillBoundScript<out R, out E : Function<R>> internal constructor(
    source: String,
    params: Map<String, Any?>,
    val evaluate: E,
) : ChillScript<R>(source, params)

/**
 * The value a client reads back as `_score` for a score-context script that returned this number.
 * Lucene keeps scores as `float`, and the response JSON carries that float in its shortest decimal
 * form (`300.14285`, not the widened `300.1428527832031`), which the client parses as a double. So
 * the round trip is float rounding followed by shortest-decimal re-parsing; this reproduces it
 * exactly, letting a local `evaluate` result be compared to `hit.score()` for equality.
 */
fun Number.asIndexScore(): Double = toDouble().toFloat().toString().toDouble()

/**
 * A reusable bound script with a declared params type. [evaluate] takes the params first
 * (`(P, D, Double) -> R`); [withParams] fixes them and yields a ready [ChillBoundScript] whose
 * `evaluate` omits them; [stored] keeps the evaluator alongside a stored-script reference.
 */
class ChillBoundTemplate<P : Any, out R, out E : Function<R>, out B : Function<R>> internal constructor(
    source: String,
    paramsSerializer: KSerializer<P>,
    val evaluate: E,
    private val bind: (P) -> B,
) : ChillScriptTemplate<P, R>(source, paramsSerializer) {
    override fun withParams(params: P): ChillBoundScript<R, B> =
        ChillBoundScript(source, ParamsCodec.encodeToMap(paramsSerializer, params), bind(params))

    /** Reference this template as the stored script [id] (register it with `putChillScript`). */
    fun stored(id: String): ChillStoredBoundRef<P, R, E, B> = ChillStoredBoundRef(id, paramsSerializer, evaluate, bind)
}

/**
 * A reference to a stored chill script (registered from a [ChillScriptTemplate]) with a typed
 * params requirement.
 */
open class ChillStoredScriptRef<P : Any>(
    val id: String,
    val paramsSerializer: KSerializer<P>,
) {
    open fun withParams(params: P): ChillStoredScript =
        ChillStoredScript(id, ParamsCodec.encodeToMap(paramsSerializer, params))
}

/** A stored-script invocation: id + encoded params. */
open class ChillStoredScript(
    val id: String,
    val params: Map<String, Any?>,
)

/** A stored reference to a bound template: typed params, and the evaluator travels with it. */
class ChillStoredBoundRef<P : Any, out R, out E : Function<R>, out B : Function<R>> internal constructor(
    id: String,
    paramsSerializer: KSerializer<P>,
    val evaluate: E,
    private val bind: (P) -> B,
) : ChillStoredScriptRef<P>(id, paramsSerializer) {
    override fun withParams(params: P): ChillStoredBoundScript<R, B> =
        ChillStoredBoundScript(id, ParamsCodec.encodeToMap(paramsSerializer, params), bind(params))
}

/** A stored bound invocation: id + params for the node, `evaluate` for the same math locally. */
class ChillStoredBoundScript<out R, out B : Function<R>> internal constructor(
    id: String,
    params: Map<String, Any?>,
    val evaluate: B,
) : ChillStoredScript(id, params)

fun <P : Any> storedChillScript(id: String, params: ParamTypeSlot<P>): ChillStoredScriptRef<P> =
    ChillStoredScriptRef(id, params.serializer)

fun storedChillScript(id: String): ChillStoredScript = ChillStoredScript(id, emptyMap())
