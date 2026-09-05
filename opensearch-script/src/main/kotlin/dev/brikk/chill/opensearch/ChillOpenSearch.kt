package dev.brikk.chill.opensearch

import dev.brikk.chill.policy.ALL_PACKAGE_ACCESS_TYPES
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.LibraryPolicies
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.ShipClosure
import dev.brikk.chill.serialize.Chill
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

/**
 * The shared chill configuration for OpenSearch scripting, used identically on the freezing
 * client and the verifying server plugin.
 */
object ChillOpenSearch {
    /** Script language name registered by the plugin. */
    const val LANGUAGE = ChillScript.LANG

    private val receiverClassName = ChillSearchScript::class.java.name
    private val boundReceiverClassName = ChillBound::class.java.name

    /**
     * Allowances for the script receiver: instance methods and property reads, plus ref-only of
     * Class/reflect.Type so reified helpers can pass `T::class.java` without enabling reflection.
     * Static methods included: Kotlin default-argument calls compile to synthetic static
     * `name$default` bridges on the receiver class.
     */
    val receiverPolicies: Set<String> = listOf(
        PolicyAllowance.ClassLevel.ClassAccess(receiverClassName, setOf(AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassAccess(boundReceiverClassName, setOf(AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassMethodAccess(
            receiverClassName,
            "*",
            "*",
            setOf(AccessTypes.call_Class_Instance_Method, AccessTypes.call_Class_Static_Method)
        ),
        PolicyAllowance.ClassLevel.ClassPropertyAccess(
            receiverClassName,
            "*",
            "*",
            setOf(AccessTypes.read_Class_Instance_Property)
        ),
        PolicyAllowance.ClassLevel.ClassAccess(
            java.lang.reflect.Type::class.java.name,
            setOf(AccessTypes.ref_Class_Instance)
        ),
        PolicyAllowance.ClassLevel.ClassAccess(Class::class.java.name, setOf(AccessTypes.ref_Class)),
    ).toPolicy().toSet()

    /**
     * Pure-computation kotlin-stdlib packages allowed wholesale for script bodies. The generated
     * stdlib policy black-lists a whole facade class when any single member fails verification,
     * which knocks out everyday helpers (map/filter/toRegex/...); these packages contain no IO,
     * reflection, or process surface.
     */
    val scriptKotlinPackagePolicies: Set<String> = listOf(
        "kotlin.collections",
        "kotlin.text",
        "kotlin.sequences",
        "kotlin.ranges",
        "kotlin.comparisons",
    ).map { PolicyAllowance.PackageAccess(it, ALL_PACKAGE_ACCESS_TYPES, requireSealed = false) }
        .toPolicy().toSet()

    /**
     * Types a bound doc class may carry beyond primitives/String/collections: date doc values
     * arrive as [java.time.ZonedDateTime].
     */
    val docValueTypePolicies: Set<String> = listOf(
        PolicyAllowance.ClassLevel.ClassAccess(
            java.time.ZonedDateTime::class.java.name,
            setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance)
        ),
    ).toPolicy().toSet()

    /**
     * The full script policy. Library policies (kotlin-stdlib, kotlinx-serialization-core) are the
     * build-time generated ones shipped in the quarantine jar - see [LibraryPolicies] for how to
     * override them for library versions this build has not seen. Nothing is scanned at runtime,
     * on either side.
     */
    val quarantine: Quarantine by lazy {
        Quarantine(
            Quarantine.painlessPlusKotlinFullPolicy +
                    LibraryPolicies.kotlinxSerializationCore +
                    receiverPolicies +
                    scriptKotlinPackagePolicies +
                    docValueTypePolicies,
        )
    }

    val chill: Chill by lazy { Chill(quarantine) }

    // ---- freeze -----------------------------------------------------------------------------

    /**
     * [returnType] is the lambda's reified `R`, recorded in the payload so the node can reject a
     * script whose result cannot serve its context (a `String` in a score query) at compile time
     * rather than on the first document. Boxed primitives are recorded as their wrapper class.
     */
    @PublishedApi
    internal fun freeze(
        slots: List<ChillSlot>,
        lambda: Any,
        returnType: KClass<*>,
        receiver: KClass<*> = ChillSearchScript::class,
    ): String {
        require(slots.map { it.kind }
            .toSet().size == slots.size) { "Each slot kind may be bound at most once: ${slots.map { it.kind }}" }
        require(slots.indexOfFirst { it.kind == ChillSlot.KIND_SCORE }.let { it == -1 || it == slots.lastIndex }) {
            "scoreType() must be the final slot"
        }
        val shipClasses =
            slots.filterNot { it.kind == ChillSlot.KIND_SCORE }.flatMap { shipSet(it.boundClass) }.distinct()
        return chill.serializeFunctionToBase64(
            lambdaReceiver = receiver,
            lambdaReturnType = returnType.javaObjectType.kotlin,
            slots = slots.map { Chill.SlotDescriptor(it.kind, it.boundClass.name) },
            shipClasses = shipClasses,
            lambda = lambda,
        )
    }

    /**
     * A bound class ships with everything of the user's that it needs and the policy does not
     * already cover: its nested classes (kotlinx generates `Companion` and `$serializer`), and
     * transitively any enum, nested `@Serializable` type, or helper its bytecode references.
     */
    @PublishedApi
    internal fun shipSet(clazz: Class<*>): List<Class<*>> = ShipClosure(quarantine).compute(clazz)

    // ---- script(): one name, slot types pick the result kind ---------------------------------
    // paramOf  -> ChillScript (ready), paramType -> ChillScriptTemplate (reusable);
    // canonical slot order: params, doc, source, score. Score is optional and always last.

    inline fun <reified R> script(noinline block: ChillSearchScript.() -> R): ChillScript<R> =
        ChillScript(freeze(emptyList(), block, R::class), emptyMap())

    inline fun <P : Any, reified R> script(p: ParamValueSlot<P>, noinline block: ChillSearchScript.(P) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(p), block, R::class), ParamsCodec.encodeToMap(p.serializer, p.value))

    inline fun <P : Any, reified R> script(p: ParamTypeSlot<P>, noinline block: ChillSearchScript.(P) -> R): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p), block, R::class), p.serializer)

    inline fun <D : Any, reified R> script(d: DocSlot<D>, noinline block: ChillSearchScript.(D) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(d), block, R::class), emptyMap())

    inline fun <S : Any, reified R> script(s: SourceSlot<S>, noinline block: ChillSearchScript.(S) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(s), block, R::class), emptyMap())

    inline fun <P : Any, D : Any, reified R> script(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        noinline block: ChillSearchScript.(P, D) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, d), block, R::class), ParamsCodec.encodeToMap(p.serializer, p.value))

    inline fun <P : Any, D : Any, reified R> script(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        noinline block: ChillSearchScript.(P, D) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, d), block, R::class), p.serializer)

    inline fun <P : Any, S : Any, reified R> script(
        p: ParamValueSlot<P>,
        s: SourceSlot<S>,
        noinline block: ChillSearchScript.(P, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, s), block, R::class), ParamsCodec.encodeToMap(p.serializer, p.value))

    inline fun <P : Any, S : Any, reified R> script(
        p: ParamTypeSlot<P>,
        s: SourceSlot<S>,
        noinline block: ChillSearchScript.(P, S) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, s), block, R::class), p.serializer)

    inline fun <D : Any, S : Any, reified R> script(
        d: DocSlot<D>,
        s: SourceSlot<S>,
        noinline block: ChillSearchScript.(D, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(d, s), block, R::class), emptyMap())

    inline fun <P : Any, D : Any, S : Any, reified R> script(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        s: SourceSlot<S>,
        noinline block: ChillSearchScript.(P, D, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, d, s), block, R::class), ParamsCodec.encodeToMap(p.serializer, p.value))

    inline fun <P : Any, D : Any, S : Any, reified R> script(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        s: SourceSlot<S>,
        noinline block: ChillSearchScript.(P, D, S) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, d, s), block, R::class), p.serializer)

    // ---- bound(): same slots as script(), empty receiver, evaluator kept for local execution ------
    // Ready (paramOf / no params) -> ChillBoundScript<R, E>, E = (remaining slots) -> R.
    // Template (paramType)         -> ChillBoundTemplate<P, R, E, B>, E = (P, slots) -> R, B = (slots) -> R.

    inline fun <reified R> bound(noinline block: ChillBound.() -> R): ChillBoundScript<R, () -> R> =
        ChillBoundScript(freeze(listOf(), block, R::class, ChillBound::class), emptyMap(), { block(ChillBound) })

    inline fun <reified R> bound(score: ScoreSlot, noinline block: ChillBound.(Double) -> R): ChillBoundScript<R, (Double) -> R> =
        ChillBoundScript(freeze(listOf(score), block, R::class, ChillBound::class), emptyMap(), { score -> block(ChillBound, score) })

    inline fun <P : Any, reified R> bound(p: ParamValueSlot<P>, noinline block: ChillBound.(P) -> R): ChillBoundScript<R, () -> R> =
        ChillBoundScript(freeze(listOf(p), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { block(ChillBound, p.value) })

    inline fun <P : Any, reified R> bound(p: ParamTypeSlot<P>, noinline block: ChillBound.(P) -> R): ChillBoundTemplate<P, R, (P) -> R, () -> R> =
        ChillBoundTemplate(freeze(listOf(p), block, R::class, ChillBound::class), p.serializer, { params -> block(ChillBound, params) }, { params -> { block(ChillBound, params) } })

    inline fun <P : Any, reified R> bound(p: ParamValueSlot<P>, score: ScoreSlot, noinline block: ChillBound.(P, Double) -> R): ChillBoundScript<R, (Double) -> R> =
        ChillBoundScript(freeze(listOf(p, score), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { score -> block(ChillBound, p.value, score) })

    inline fun <P : Any, reified R> bound(p: ParamTypeSlot<P>, score: ScoreSlot, noinline block: ChillBound.(P, Double) -> R): ChillBoundTemplate<P, R, (P, Double) -> R, (Double) -> R> =
        ChillBoundTemplate(freeze(listOf(p, score), block, R::class, ChillBound::class), p.serializer, { params, score -> block(ChillBound, params, score) }, { params -> { score -> block(ChillBound, params, score) } })

    inline fun <D : Any, reified R> bound(d: DocSlot<D>, noinline block: ChillBound.(D) -> R): ChillBoundScript<R, (D) -> R> =
        ChillBoundScript(freeze(listOf(d), block, R::class, ChillBound::class), emptyMap(), { doc -> block(ChillBound, doc) })

    inline fun <D : Any, reified R> bound(d: DocSlot<D>, score: ScoreSlot, noinline block: ChillBound.(D, Double) -> R): ChillBoundScript<R, (D, Double) -> R> =
        ChillBoundScript(freeze(listOf(d, score), block, R::class, ChillBound::class), emptyMap(), { doc, score -> block(ChillBound, doc, score) })

    inline fun <S : Any, reified R> bound(s: SourceSlot<S>, noinline block: ChillBound.(S) -> R): ChillBoundScript<R, (S) -> R> =
        ChillBoundScript(freeze(listOf(s), block, R::class, ChillBound::class), emptyMap(), { source -> block(ChillBound, source) })

    inline fun <S : Any, reified R> bound(s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(S, Double) -> R): ChillBoundScript<R, (S, Double) -> R> =
        ChillBoundScript(freeze(listOf(s, score), block, R::class, ChillBound::class), emptyMap(), { source, score -> block(ChillBound, source, score) })

    inline fun <P : Any, D : Any, reified R> bound(p: ParamValueSlot<P>, d: DocSlot<D>, noinline block: ChillBound.(P, D) -> R): ChillBoundScript<R, (D) -> R> =
        ChillBoundScript(freeze(listOf(p, d), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { doc -> block(ChillBound, p.value, doc) })

    inline fun <P : Any, D : Any, reified R> bound(p: ParamTypeSlot<P>, d: DocSlot<D>, noinline block: ChillBound.(P, D) -> R): ChillBoundTemplate<P, R, (P, D) -> R, (D) -> R> =
        ChillBoundTemplate(freeze(listOf(p, d), block, R::class, ChillBound::class), p.serializer, { params, doc -> block(ChillBound, params, doc) }, { params -> { doc -> block(ChillBound, params, doc) } })

    inline fun <P : Any, D : Any, reified R> bound(p: ParamValueSlot<P>, d: DocSlot<D>, score: ScoreSlot, noinline block: ChillBound.(P, D, Double) -> R): ChillBoundScript<R, (D, Double) -> R> =
        ChillBoundScript(freeze(listOf(p, d, score), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { doc, score -> block(ChillBound, p.value, doc, score) })

    inline fun <P : Any, D : Any, reified R> bound(p: ParamTypeSlot<P>, d: DocSlot<D>, score: ScoreSlot, noinline block: ChillBound.(P, D, Double) -> R): ChillBoundTemplate<P, R, (P, D, Double) -> R, (D, Double) -> R> =
        ChillBoundTemplate(freeze(listOf(p, d, score), block, R::class, ChillBound::class), p.serializer, { params, doc, score -> block(ChillBound, params, doc, score) }, { params -> { doc, score -> block(ChillBound, params, doc, score) } })

    inline fun <P : Any, S : Any, reified R> bound(p: ParamValueSlot<P>, s: SourceSlot<S>, noinline block: ChillBound.(P, S) -> R): ChillBoundScript<R, (S) -> R> =
        ChillBoundScript(freeze(listOf(p, s), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { source -> block(ChillBound, p.value, source) })

    inline fun <P : Any, S : Any, reified R> bound(p: ParamTypeSlot<P>, s: SourceSlot<S>, noinline block: ChillBound.(P, S) -> R): ChillBoundTemplate<P, R, (P, S) -> R, (S) -> R> =
        ChillBoundTemplate(freeze(listOf(p, s), block, R::class, ChillBound::class), p.serializer, { params, source -> block(ChillBound, params, source) }, { params -> { source -> block(ChillBound, params, source) } })

    inline fun <P : Any, S : Any, reified R> bound(p: ParamValueSlot<P>, s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(P, S, Double) -> R): ChillBoundScript<R, (S, Double) -> R> =
        ChillBoundScript(freeze(listOf(p, s, score), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { source, score -> block(ChillBound, p.value, source, score) })

    inline fun <P : Any, S : Any, reified R> bound(p: ParamTypeSlot<P>, s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(P, S, Double) -> R): ChillBoundTemplate<P, R, (P, S, Double) -> R, (S, Double) -> R> =
        ChillBoundTemplate(freeze(listOf(p, s, score), block, R::class, ChillBound::class), p.serializer, { params, source, score -> block(ChillBound, params, source, score) }, { params -> { source, score -> block(ChillBound, params, source, score) } })

    inline fun <D : Any, S : Any, reified R> bound(d: DocSlot<D>, s: SourceSlot<S>, noinline block: ChillBound.(D, S) -> R): ChillBoundScript<R, (D, S) -> R> =
        ChillBoundScript(freeze(listOf(d, s), block, R::class, ChillBound::class), emptyMap(), { doc, source -> block(ChillBound, doc, source) })

    inline fun <D : Any, S : Any, reified R> bound(d: DocSlot<D>, s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(D, S, Double) -> R): ChillBoundScript<R, (D, S, Double) -> R> =
        ChillBoundScript(freeze(listOf(d, s, score), block, R::class, ChillBound::class), emptyMap(), { doc, source, score -> block(ChillBound, doc, source, score) })

    inline fun <P : Any, D : Any, S : Any, reified R> bound(p: ParamValueSlot<P>, d: DocSlot<D>, s: SourceSlot<S>, noinline block: ChillBound.(P, D, S) -> R): ChillBoundScript<R, (D, S) -> R> =
        ChillBoundScript(freeze(listOf(p, d, s), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { doc, source -> block(ChillBound, p.value, doc, source) })

    inline fun <P : Any, D : Any, S : Any, reified R> bound(p: ParamTypeSlot<P>, d: DocSlot<D>, s: SourceSlot<S>, noinline block: ChillBound.(P, D, S) -> R): ChillBoundTemplate<P, R, (P, D, S) -> R, (D, S) -> R> =
        ChillBoundTemplate(freeze(listOf(p, d, s), block, R::class, ChillBound::class), p.serializer, { params, doc, source -> block(ChillBound, params, doc, source) }, { params -> { doc, source -> block(ChillBound, params, doc, source) } })

    inline fun <P : Any, D : Any, S : Any, reified R> bound(p: ParamValueSlot<P>, d: DocSlot<D>, s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(P, D, S, Double) -> R): ChillBoundScript<R, (D, S, Double) -> R> =
        ChillBoundScript(freeze(listOf(p, d, s, score), block, R::class, ChillBound::class), ParamsCodec.encodeToMap(p.serializer, p.value), { doc, source, score -> block(ChillBound, p.value, doc, source, score) })

    inline fun <P : Any, D : Any, S : Any, reified R> bound(p: ParamTypeSlot<P>, d: DocSlot<D>, s: SourceSlot<S>, score: ScoreSlot, noinline block: ChillBound.(P, D, S, Double) -> R): ChillBoundTemplate<P, R, (P, D, S, Double) -> R, (D, S, Double) -> R> =
        ChillBoundTemplate(freeze(listOf(p, d, s, score), block, R::class, ChillBound::class), p.serializer, { params, doc, source, score -> block(ChillBound, params, doc, source, score) }, { params -> { doc, source, score -> block(ChillBound, params, doc, source, score) } })
}
