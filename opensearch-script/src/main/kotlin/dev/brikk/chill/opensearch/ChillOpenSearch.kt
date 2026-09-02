package dev.brikk.chill.opensearch

import dev.brikk.chill.policy.ALL_PACKAGE_ACCESS_TYPES
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.LibraryPolicies
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.serialize.Chill
import kotlin.reflect.KClass

/**
 * The shared chill configuration for OpenSearch scripting, used identically on the freezing
 * client and the verifying server plugin.
 */
object ChillOpenSearch {
    /** Script language name registered by the plugin. */
    const val LANGUAGE = ChillScript.LANG

    private val receiverClassName = ChillSearchScript::class.java.name
    private val boundReceiverClassName = ChillBoundScript::class.java.name

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

    private fun freeze(
        slots: List<ChillSlot>,
        lambda: Any,
        receiver: KClass<*> = ChillSearchScript::class,
        returnType: KClass<*> = Any::class,
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
            lambdaReturnType = returnType,
            slots = slots.map { Chill.SlotDescriptor(it.kind, it.boundClass.name) },
            shipClasses = shipClasses,
            lambda = lambda,
        )
    }

    /** A bound class ships with its nested classes: kotlinx generates `Companion` and `$serializer`. */
    private fun shipSet(clazz: Class<*>): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        fun visit(c: Class<*>) {
            if (result.add(c)) c.declaredClasses.forEach { visit(it) }
        }
        visit(clazz)
        return result.toList()
    }

    // ---- script(): one name, slot types pick the result kind ---------------------------------
    // paramOf  -> ChillScript (ready), paramType -> ChillScriptTemplate (reusable);
    // canonical slot order: params, doc, source, score. Score is optional and always last.

    fun <R> script(block: ChillSearchScript.() -> R): ChillScript<R> =
        ChillScript(freeze(emptyList(), block), emptyMap())

    fun <P : Any, R> script(p: ParamValueSlot<P>, block: ChillSearchScript.(P) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(p), block), ParamsCodec.encodeToMap(p.serializer, p.value))

    fun <P : Any, R> script(p: ParamTypeSlot<P>, block: ChillSearchScript.(P) -> R): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p), block), p.serializer)

    fun <D : Any, R> script(d: DocSlot<D>, block: ChillSearchScript.(D) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(d), block), emptyMap())

    fun <S : Any, R> script(s: SourceSlot<S>, block: ChillSearchScript.(S) -> R): ChillScript<R> =
        ChillScript(freeze(listOf(s), block), emptyMap())

    fun <P : Any, D : Any, R> script(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        block: ChillSearchScript.(P, D) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, d), block), ParamsCodec.encodeToMap(p.serializer, p.value))

    fun <P : Any, D : Any, R> script(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        block: ChillSearchScript.(P, D) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, d), block), p.serializer)

    fun <P : Any, S : Any, R> script(
        p: ParamValueSlot<P>,
        s: SourceSlot<S>,
        block: ChillSearchScript.(P, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, s), block), ParamsCodec.encodeToMap(p.serializer, p.value))

    fun <P : Any, S : Any, R> script(
        p: ParamTypeSlot<P>,
        s: SourceSlot<S>,
        block: ChillSearchScript.(P, S) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, s), block), p.serializer)

    fun <D : Any, S : Any, R> script(
        d: DocSlot<D>,
        s: SourceSlot<S>,
        block: ChillSearchScript.(D, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(d, s), block), emptyMap())

    fun <P : Any, D : Any, S : Any, R> script(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        s: SourceSlot<S>,
        block: ChillSearchScript.(P, D, S) -> R
    ): ChillScript<R> =
        ChillScript(freeze(listOf(p, d, s), block), ParamsCodec.encodeToMap(p.serializer, p.value))

    fun <P : Any, D : Any, S : Any, R> script(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        s: SourceSlot<S>,
        block: ChillSearchScript.(P, D, S) -> R
    ): ChillScriptTemplate<P, R> =
        ChillScriptTemplate(freeze(listOf(p, d, s), block), p.serializer)

    // ---- bound score programs ----------------------------------------------------------------

    fun <P : Any, D : Any> boundScore(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        block: ChillBoundScript.(P, D) -> Double,
    ): ChillBoundScore<P, D> = ChillBoundScore(
        freeze(listOf(p, d), block, ChillBoundScript::class, Double::class),
        ParamsCodec.encodeToMap(p.serializer, p.value),
        p.value,
        block,
    )

    fun <P : Any, D : Any> boundScore(
        p: ParamValueSlot<P>,
        d: DocSlot<D>,
        score: ScoreSlot,
        block: ChillBoundScript.(P, D, Double) -> Double,
    ): ChillBoundScoreWithBaseScore<P, D> = ChillBoundScoreWithBaseScore(
        freeze(listOf(p, d, score), block, ChillBoundScript::class, Double::class),
        ParamsCodec.encodeToMap(p.serializer, p.value),
        p.value,
        block,
    )

    fun <P : Any, D : Any> boundScore(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        block: ChillBoundScript.(P, D) -> Double,
    ): ChillBoundScoreTemplate<P, D> = ChillBoundScoreTemplate(
        freeze(listOf(p, d), block, ChillBoundScript::class, Double::class),
        p.serializer,
        block,
    )

    fun <P : Any, D : Any> boundScore(
        p: ParamTypeSlot<P>,
        d: DocSlot<D>,
        score: ScoreSlot,
        block: ChillBoundScript.(P, D, Double) -> Double,
    ): ChillBoundScoreWithBaseScoreTemplate<P, D> = ChillBoundScoreWithBaseScoreTemplate(
        freeze(listOf(p, d, score), block, ChillBoundScript::class, Double::class),
        p.serializer,
        block,
    )
}
