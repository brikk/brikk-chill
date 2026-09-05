package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillBound
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.ChillSlot
import dev.brikk.chill.opensearch.DocValuesCodec
import dev.brikk.chill.opensearch.ParamsCodec
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.limits.ChillExecutionLimitError
import dev.brikk.chill.quarantine.limits.ExecutionBudget
import dev.brikk.chill.quarantine.limits.ExecutionLimitInstrumenter
import dev.brikk.chill.quarantine.limits.LimitedCharSequence
import dev.brikk.chill.serialize.Chill
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.IndexSearcher
import org.opensearch.script.FieldScript
import org.opensearch.script.FilterScript
import org.opensearch.script.ScoreScript
import org.opensearch.script.ScriptContext
import org.opensearch.script.ScriptEngine
import org.opensearch.script.ScriptException
import org.opensearch.search.lookup.LeafSearchLookup
import org.opensearch.search.lookup.SearchLookup
import org.opensearch.search.lookup.SourceLookup
import kotlin.jvm.javaObjectType
import kotlin.reflect.KClass

class ChillScriptEngine(val limits: ExecutionLimits = ExecutionLimits()) : ScriptEngine {

    companion object {
        val SUPPORTED_CONTEXTS: Set<ScriptContext<*>> =
            setOf(ScoreScript.CONTEXT, FilterScript.CONTEXT, FieldScript.CONTEXT)
    }

    private val instrumenter = ExecutionLimitInstrumenter()

    init {
        // instrumented code reads the factor statically; one engine per node
        LimitedCharSequence.limitFactor = limits.regexLimitFactor
    }

    override fun getType(): String = ChillOpenSearch.LANGUAGE

    override fun getSupportedContexts(): Set<ScriptContext<*>> = SUPPORTED_CONTEXTS

    /** A bound slot with its server-side decode strategy resolved (once per compile). */
    class SlotPlan(val kind: String, val deserializer: DeserializationStrategy<*>?)

    /**
     * The verified, loadable form of one script. OpenSearch caches the factory this produces, so
     * deserialization+verification+serializer-resolution run once per unique script source. A
     * fresh lambda instance is deserialized per leaf (per segment, per query) so captured mutable
     * state never crosses threads; per-document execution reuses the leaf instance.
     */
    class CompiledChillScript<R>(
        val scriptName: String?,
        val className: String,
        private val serializedLambda: ByteArray,
        private val classLoader: ClassLoader,
        private val additionalPolicies: Set<String>,
        private val chill: Chill,
        scoreAccessed: Boolean,
        val slots: List<SlotPlan>,
        private val boundReceiver: Boolean,
        private val limits: ExecutionLimits,
        private val decodeResult: (Any?) -> R,
    ) {
        val needsSource: Boolean = slots.any { it.kind == ChillSlot.KIND_SOURCE }
        val needsScore: Boolean = scoreAccessed || slots.any { it.kind == ChillSlot.KIND_SCORE }

        fun instantiate(): Any =
            chill.instantiateSerializedFunctionSafely(className, serializedLambda, classLoader, additionalPolicies)

        /** Params decode once per query (they are constant per query). */
        fun decodeParams(params: Map<String, Any?>): Any? =
            slots.firstOrNull { it.kind == ChillSlot.KIND_PARAMS }
                ?.let { ParamsCodec.decodeFromMap(it.deserializer!!, params) }

        /** Per-document inputs; a mutable cell reused per leaf so execute() allocates nothing of its own. */
        class Inputs {
            @JvmField var decodedParams: Any? = null
            @JvmField var doc: Map<String, List<Any?>> = emptyMap()
            @JvmField var source: (() -> Map<String, Any?>)? = null
            @JvmField var score: Double = 0.0
        }

        /** True when the lambda runs against the contextual receiver and one must be built per document. */
        val needsReceiver: Boolean = !boundReceiver

        // resolved once per compile: one producer per slot, no per-document string dispatch
        private val producers: Array<(Inputs) -> Any?> = slots.map { slot ->
            when (slot.kind) {
                ChillSlot.KIND_PARAMS -> { i: Inputs -> i.decodedParams }
                ChillSlot.KIND_DOC -> { val d = slot.deserializer!!; { i: Inputs -> DocValuesCodec.decode(d, i.doc) } }
                ChillSlot.KIND_SOURCE -> {
                    val d = slot.deserializer!!
                    { i: Inputs -> ParamsCodec.decodeFromMap(d, i.source?.invoke() ?: throw IllegalStateException("source binding requires source access")) }
                }
                ChillSlot.KIND_SCORE -> { i: Inputs -> i.score }
                else -> throw IllegalStateException("unknown slot kind ${slot.kind}")
            }
        }.toTypedArray()

        /**
         * Invokes the lambda for one document with slot arguments in declared order
         * (`R.(A...) -> T` is `Function{1+n}`). Arms the per-execution budget; an exhausted limit
         * surfaces as a [ScriptException]. [receiver] may be null when [needsReceiver] is false.
         */
        fun execute(fn: Any, receiver: ChillSearchScript?, inputs: Inputs): R {
            val r: Any = if (boundReceiver) ChillBound else receiver ?: throw IllegalStateException("contextual script needs a receiver")
            val p = producers
            ExecutionBudget.begin(limits.maxLoopIterations, limits.maxAllocation)
            val result = try {
                @Suppress("UNCHECKED_CAST")
                when (p.size) {
                    0 -> (fn as kotlin.jvm.functions.Function1<Any?, Any?>).invoke(r)
                    1 -> (fn as kotlin.jvm.functions.Function2<Any?, Any?, Any?>).invoke(r, p[0](inputs))
                    2 -> (fn as kotlin.jvm.functions.Function3<Any?, Any?, Any?, Any?>).invoke(r, p[0](inputs), p[1](inputs))
                    3 -> (fn as kotlin.jvm.functions.Function4<Any?, Any?, Any?, Any?, Any?>).invoke(r, p[0](inputs), p[1](inputs), p[2](inputs))
                    4 -> (fn as kotlin.jvm.functions.Function5<Any?, Any?, Any?, Any?, Any?, Any?>).invoke(r, p[0](inputs), p[1](inputs), p[2](inputs), p[3](inputs))
                    else -> throw IllegalStateException("unsupported slot arity ${p.size}")
                }
            } catch (ex: ChillExecutionLimitError) {
                throw ScriptException(
                    "chill script exceeded an execution limit: ${ex.message}",
                    ex, emptyList(), scriptName ?: "<inline>", ChillOpenSearch.LANGUAGE,
                )
            }
            return decodeResult(result)
        }

        /** Convenience for tests and one-off calls: builds an [Inputs] per call. */
        fun execute(
            fn: Any,
            receiver: ChillSearchScript?,
            decodedParams: Any?,
            doc: Map<String, List<Any?>>,
            sourceProvider: (() -> Map<String, Any?>)?,
            score: Double = 0.0,
        ): R = execute(fn, receiver, Inputs().also { it.decodedParams = decodedParams; it.doc = doc; it.source = sourceProvider; it.score = score })
    }

    fun compileChill(name: String?, code: String): CompiledChillScript<Any?> =
        compileChill(name, code, null) { it }

    fun <R> compileChill(
        name: String?,
        code: String,
        expectedReturnType: KClass<*>?,
        decodeResult: (Any?) -> R,
    ): CompiledChillScript<R> {
        if (!Chill.isPrefixedBase64(code)) {
            throw ScriptException(
                "chill scripts must be a frozen lambda payload (chill~~<base64>); " +
                        "produce one with ChillOpenSearch.script(...) and @ChillLambda { ... }",
                IllegalArgumentException("not a chill payload"),
                emptyList(),
                code.take(64),
                ChillOpenSearch.LANGUAGE,
            )
        }

        val data = try {
            ChillOpenSearch.chill.deserFunctionFromPrefixedBase64(code)
        } catch (ex: Chill.ClassSerDerViolationsException) {
            throw ScriptException(
                "chill script rejected by policy: ${ex.message}",
                ex,
                ex.violations.sorted(),
                name ?: "<inline>",
                ChillOpenSearch.LANGUAGE,
            )
        } catch (ex: Chill.ClassSerDesException) {
            throw ScriptException(
                "invalid chill script payload: ${ex.message}",
                ex,
                emptyList(),
                name ?: "<inline>",
                ChillOpenSearch.LANGUAGE
            )
        }

        val contextualReceiverName = ChillSearchScript::class.java.name
        val boundReceiverName = ChillBound::class.java.name
        if (data.receiverClassName != contextualReceiverName && data.receiverClassName != boundReceiverName) {
            throw ScriptException(
                "unsupported chill script receiver ${data.receiverClassName}",
                IllegalArgumentException("unsupported receiver"),
                emptyList(),
                name ?: "<inline>",
                ChillOpenSearch.LANGUAGE,
            )
        }
        // the payload records the lambda's reified result type; a result that can never serve this
        // context is rejected here, at compile, not on the first document. `Any`/`Comparable`-like
        // declarations (untyped `when` branches) pass and are checked per result.
        if (expectedReturnType != null) {
            val declared = try {
                Class.forName(data.returnTypeClassName, false, javaClass.classLoader)
            } catch (_: ClassNotFoundException) {
                null // a user type: cannot serve a score/filter context, fall through to the check below
            }
            val expected = expectedReturnType.javaObjectType
            val compatible = declared != null && (expected.isAssignableFrom(declared) || declared.isAssignableFrom(expected))
            if (!compatible) {
                throw ScriptException(
                    "chill script returns ${data.returnTypeClassName}, but this context needs ${expected.simpleName}",
                    ClassCastException(),
                    emptyList(),
                    name ?: "<inline>",
                    ChillOpenSearch.LANGUAGE,
                )
            }
        }
        val slotKinds = data.slots.map { it.kind }
        val scoreIndex = slotKinds.indexOf(ChillSlot.KIND_SCORE)
        if (slotKinds.toSet().size != slotKinds.size || (scoreIndex != -1 && scoreIndex != slotKinds.lastIndex)) {
            throw invalidSlots(name, "slot kinds must be unique and score must be last: $slotKinds")
        }
        data.slots.firstOrNull { it.kind == ChillSlot.KIND_SCORE }?.let { scoreSlot ->
            if (scoreSlot.className != Double::class.javaObjectType.name) {
                throw invalidSlots(
                    name,
                    "score slot must bind ${Double::class.javaObjectType.name}, got ${scoreSlot.className}"
                )
            }
        }

        // verified bytes are instrumented for execution limits (loop budget, regex input caps)
        // before anything is defined; the inserted calls target trusted runtime classes
        val classLoader = ScriptClassLoader(javaClass.classLoader).apply {
            data.classes.forEach {
                val instrumented = try {
                    instrumenter.instrument(it.bytes)
                } catch (ex: ExecutionLimitInstrumenter.InstrumentationRejectedException) {
                    throw ScriptException(
                        "chill script cannot be execution-limited: ${ex.message}",
                        ex, emptyList(), name ?: "<inline>", ChillOpenSearch.LANGUAGE,
                    )
                }
                addClass(it.className, instrumented)
            }
        }

        // deserialization may reference exactly the classes that were just byte-verified
        val additionalPolicies = data.classes.map {
            PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance))
        }.toPolicy().toSet()

        // resolve slot deserializers against the verified, freshly-defined classes
        val slotPlans = data.slots.map { slot ->
            if (slot.kind == ChillSlot.KIND_SCORE) return@map SlotPlan(slot.kind, null)
            val clazz = try {
                Class.forName(slot.className, false, classLoader)
            } catch (ex: ClassNotFoundException) {
                throw ScriptException(
                    "bound slot class ${slot.className} is not shipped or loadable",
                    ex,
                    emptyList(),
                    name ?: "<inline>",
                    ChillOpenSearch.LANGUAGE
                )
            }
            SlotPlan(slot.kind, serializer(clazz) as DeserializationStrategy<*>)
        }

        // needs_score: derived statically from the verification scan (a _score read compiles to
        // an instance call of the receiver's get_score getter)
        val receiverName = ChillSearchScript::class.java.name
        val scoreAccessed = data.verification.scanResults.allowances.any { allowance ->
            allowance is PolicyAllowance.ClassLevel.ClassMethodAccess &&
                    allowance.fqnTarget == receiverName &&
                    allowance.methodName == "get_score"
        }

        return CompiledChillScript(
            name, data.className, data.serializedLambda, classLoader, additionalPolicies,
            ChillOpenSearch.chill, scoreAccessed, slotPlans,
            data.receiverClassName == boundReceiverName, limits, decodeResult,
        )
    }

    override fun <FactoryType> compile(
        name: String?,
        code: String,
        context: ScriptContext<FactoryType>,
        params: Map<String, String>,
    ): FactoryType {
        val factory: Any = when (context) {
            ScoreScript.CONTEXT -> scoreFactory(
                compileChill(name, code, Number::class) { result ->
                    (result as? Number)?.toDouble() ?: wrongType(name, code, "a number", result)
                },
            )

            FilterScript.CONTEXT -> filterFactory(
                compileChill(name, code, Boolean::class) { result ->
                    result as? Boolean ?: wrongType(name, code, "a boolean", result)
                }.withoutScoreSlot(name, "filter"),
            )

            FieldScript.CONTEXT -> fieldFactory(compileChill(name, code).withoutScoreSlot(name, "field"))
            else -> throw IllegalArgumentException("chill scripts are not supported for context [${context.name}]")
        }
        return context.factoryClazz.cast(factory)
    }

    @Suppress("UNCHECKED_CAST")
    private fun docsOf(doc: Map<String, *>): Map<String, List<Any?>> = doc as Map<String, List<Any?>>

    private fun wrongType(name: String?, source: String, expected: String, result: Any?): Nothing =
        throw ScriptException(
            "chill script must return $expected, got ${result?.javaClass?.name ?: "null"}",
            ClassCastException(), emptyList(), name ?: source, ChillOpenSearch.LANGUAGE,
        )

    private fun invalidSlots(name: String?, message: String): ScriptException = ScriptException(
        "invalid chill script slots: $message",
        IllegalArgumentException(message), emptyList(), name ?: "<inline>", ChillOpenSearch.LANGUAGE,
    )

    private fun <R> CompiledChillScript<R>.withoutScoreSlot(name: String?, context: String): CompiledChillScript<R> {
        if (slots.any { it.kind == ChillSlot.KIND_SCORE }) {
            throw ScriptException(
                "scoreType() is only available in the score context, not $context",
                IllegalArgumentException("score slot in $context context"),
                emptyList(), name ?: "<inline>", ChillOpenSearch.LANGUAGE,
            )
        }
        return this
    }

    private fun scoreFactory(compiled: CompiledChillScript<Double>): ScoreScript.Factory =
        ScoreScript.Factory { params: Map<String, Any>?, lookup: SearchLookup?, indexSearcher: IndexSearcher? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            object : ScoreScript.LeafFactory {
                override fun needs_score(): Boolean = compiled.needsScore

                override fun newInstance(ctx: LeafReaderContext): ScoreScript {
                    val fn = compiled.instantiate()
                    val inputs = CompiledChillScript.Inputs().also { it.decodedParams = decodedParams }
                    return object : ScoreScript(params, lookup, indexSearcher, ctx) {
                        init {
                            // the script's own leaf lookup publishes _source into params; no second lookup
                            if (compiled.needsSource) inputs.source = sourceFromParams(getParams())
                        }

                        override fun execute(explanation: ExplanationHolder?): Double {
                            val score = if (compiled.needsScore) get_score() else 0.0
                            val docs = docsOf(doc)
                            inputs.doc = docs
                            inputs.score = score
                            val receiver = if (compiled.needsReceiver) ChillSearchScript(getParams(), docs, score) else null
                            return compiled.execute(fn, receiver, inputs)
                        }
                    }
                }
            }
        }

    private fun filterFactory(compiled: CompiledChillScript<Boolean>): FilterScript.Factory =
        FilterScript.Factory { params: Map<String, Any>?, lookup: SearchLookup? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            FilterScript.LeafFactory { ctx: LeafReaderContext ->
                val fn = compiled.instantiate()
                // FilterScript keeps its leaf lookup private and does not publish _source into
                // params, so a source-bound filter is the one case needing a second leaf lookup
                val sourceLeaf: LeafSearchLookup? =
                    if (compiled.needsSource) lookup!!.getLeafSearchLookup(ctx) else null
                val inputs = CompiledChillScript.Inputs().also { it.decodedParams = decodedParams; it.source = sourceLeaf?.let { l -> { sourceMap(l) } } }
                object : FilterScript(params, lookup, ctx) {
                    override fun setDocument(docid: Int) {
                        super.setDocument(docid)
                        sourceLeaf?.setDocument(docid)
                    }

                    override fun execute(): Boolean {
                        val docs = docsOf(doc)
                        inputs.doc = docs
                        val receiver = if (compiled.needsReceiver) ChillSearchScript(getParams(), docs, 0.0) else null
                        return compiled.execute(fn, receiver, inputs)
                    }
                }
            }
        }

    private fun fieldFactory(compiled: CompiledChillScript<Any?>): FieldScript.Factory =
        FieldScript.Factory { params: Map<String, Any>?, lookup: SearchLookup? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            FieldScript.LeafFactory { ctx: LeafReaderContext ->
                val fn = compiled.instantiate()
                val inputs = CompiledChillScript.Inputs().also { it.decodedParams = decodedParams }
                object : FieldScript(params, lookup, ctx) {
                    init {
                        if (compiled.needsSource) inputs.source = sourceFromParams(getParams())
                    }

                    override fun execute(): Any? {
                        val docs = docsOf(doc)
                        inputs.doc = docs
                        val receiver = if (compiled.needsReceiver) ChillSearchScript(getParams(), docs, 0.0) else null
                        return compiled.execute(fn, receiver, inputs)
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun sourceMap(leaf: LeafSearchLookup): Map<String, Any?> = leaf.source() as Map<String, Any?>

    /**
     * `_source` as ScoreScript/FieldScript expose it through their own leaf lookup: `params` is a
     * `DynamicMap` whose `_source` entry loads the current document's source on read, so the read
     * happens per document, inside the provider.
     */
    @Suppress("UNCHECKED_CAST")
    private fun sourceFromParams(params: Map<String, Any?>): () -> Map<String, Any?> = {
        when (val v = params["_source"]) {
            is SourceLookup -> v.loadSourceIfNeeded() as Map<String, Any?>
            is Map<*, *> -> v as Map<String, Any?>
            else -> throw IllegalStateException("script params carry no _source lookup")
        }
    }
}
