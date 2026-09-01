package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.ChillSlot
import dev.brikk.chill.opensearch.DocValuesCodec
import dev.brikk.chill.opensearch.ParamsCodec
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
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

class ChillScriptEngine : ScriptEngine {

    companion object {
        val SUPPORTED_CONTEXTS: Set<ScriptContext<*>> = setOf(ScoreScript.CONTEXT, FilterScript.CONTEXT, FieldScript.CONTEXT)
    }

    override fun getType(): String = ChillOpenSearch.LANGUAGE

    override fun getSupportedContexts(): Set<ScriptContext<*>> = SUPPORTED_CONTEXTS

    /** A bound slot with its server-side decode strategy resolved (once per compile). */
    class SlotPlan(val kind: String, val deserializer: DeserializationStrategy<*>)

    /**
     * The verified, loadable form of one script. OpenSearch caches the factory this produces, so
     * deserialization+verification+serializer-resolution run once per unique script source. A
     * fresh lambda instance is deserialized per leaf (per segment, per query) so captured mutable
     * state never crosses threads; per-document execution reuses the leaf instance.
     */
    class CompiledChillScript(
        val className: String,
        private val serializedLambda: ByteArray,
        private val classLoader: ClassLoader,
        private val additionalPolicies: Set<String>,
        private val chill: Chill,
        val scoreAccessed: Boolean,
        val source: String,
        val slots: List<SlotPlan>,
    ) {
        val needsSource: Boolean = slots.any { it.kind == ChillSlot.KIND_SOURCE }

        fun instantiate(): Any =
            chill.instantiateSerializedFunctionSafely(className, serializedLambda, classLoader, additionalPolicies)

        /** Params decode once per query (they are constant per query). */
        fun decodeParams(params: Map<String, Any?>): Any? =
            slots.firstOrNull { it.kind == ChillSlot.KIND_PARAMS }?.let { ParamsCodec.decodeFromMap(it.deserializer, params) }

        /**
         * Invokes the lambda for one document: builds slot arguments in declared order and
         * dispatches on arity (`R.(A...) -> T` is `Function{1+n}`).
         */
        @Suppress("UNCHECKED_CAST")
        fun execute(
            fn: Any,
            receiver: ChillSearchScript,
            decodedParams: Any?,
            doc: Map<String, List<Any?>>,
            sourceProvider: (() -> Map<String, Any?>)?,
        ): Any? {
            val args = slots.map { slot ->
                when (slot.kind) {
                    ChillSlot.KIND_PARAMS -> decodedParams
                    ChillSlot.KIND_DOC -> DocValuesCodec.decode(slot.deserializer, doc)
                    ChillSlot.KIND_SOURCE -> ParamsCodec.decodeFromMap(
                        slot.deserializer,
                        sourceProvider?.invoke() ?: throw IllegalStateException("source binding requires source access"),
                    )
                    else -> throw IllegalStateException("unknown slot kind ${slot.kind}")
                }
            }
            return when (args.size) {
                0 -> (fn as kotlin.jvm.functions.Function1<Any?, Any?>).invoke(receiver)
                1 -> (fn as kotlin.jvm.functions.Function2<Any?, Any?, Any?>).invoke(receiver, args[0])
                2 -> (fn as kotlin.jvm.functions.Function3<Any?, Any?, Any?, Any?>).invoke(receiver, args[0], args[1])
                3 -> (fn as kotlin.jvm.functions.Function4<Any?, Any?, Any?, Any?, Any?>).invoke(receiver, args[0], args[1], args[2])
                else -> throw IllegalStateException("unsupported slot arity ${args.size}")
            }
        }
    }

    fun compileChill(name: String?, code: String): CompiledChillScript {
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
            ChillOpenSearch.chill.deserFromPrefixedBase64(ChillSearchScript::class, Any::class, code)
        } catch (ex: Chill.ClassSerDerViolationsException) {
            throw ScriptException(
                "chill script rejected by policy: ${ex.message}", ex, ex.violations.sorted(), name ?: "<inline>", ChillOpenSearch.LANGUAGE,
            )
        } catch (ex: Chill.ClassSerDesException) {
            throw ScriptException("invalid chill script payload: ${ex.message}", ex, emptyList(), name ?: "<inline>", ChillOpenSearch.LANGUAGE)
        }

        val classLoader = ScriptClassLoader(javaClass.classLoader).apply {
            data.classes.forEach { addClass(it.className, it.bytes) }
        }

        // deserialization may reference exactly the classes that were just byte-verified
        val additionalPolicies = data.classes.map {
            PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance))
        }.toPolicy().toSet()

        // resolve slot deserializers against the verified, freshly-defined classes
        val slotPlans = data.slots.map { slot ->
            val clazz = try {
                Class.forName(slot.className, false, classLoader)
            } catch (ex: ClassNotFoundException) {
                throw ScriptException("bound slot class ${slot.className} is not shipped or loadable", ex, emptyList(), name ?: "<inline>", ChillOpenSearch.LANGUAGE)
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
            data.className, data.serializedLambda, classLoader, additionalPolicies,
            ChillOpenSearch.chill, scoreAccessed, name ?: "<inline>", slotPlans,
        )
    }

    override fun <FactoryType> compile(
        name: String?,
        code: String,
        context: ScriptContext<FactoryType>,
        params: Map<String, String>,
    ): FactoryType {
        val compiled = compileChill(name, code)

        val factory: Any = when (context) {
            ScoreScript.CONTEXT -> scoreFactory(compiled)
            FilterScript.CONTEXT -> filterFactory(compiled)
            FieldScript.CONTEXT -> fieldFactory(compiled)
            else -> throw IllegalArgumentException("chill scripts are not supported for context [${context.name}]")
        }
        return context.factoryClazz.cast(factory)
    }

    @Suppress("UNCHECKED_CAST")
    private fun docsOf(doc: Map<String, *>): Map<String, List<Any?>> = doc as Map<String, List<Any?>>

    private fun wrongType(compiled: CompiledChillScript, expected: String, result: Any?): Nothing = throw ScriptException(
        "chill script must return $expected, got ${result?.javaClass?.name ?: "null"}",
        ClassCastException(), emptyList(), compiled.source, ChillOpenSearch.LANGUAGE,
    )

    private fun scoreFactory(compiled: CompiledChillScript): ScoreScript.Factory =
        ScoreScript.Factory { params: Map<String, Any>?, lookup: SearchLookup?, indexSearcher: IndexSearcher? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            object : ScoreScript.LeafFactory {
                override fun needs_score(): Boolean = compiled.scoreAccessed

                override fun newInstance(ctx: LeafReaderContext): ScoreScript {
                    val fn = compiled.instantiate()
                    val sourceLeaf: LeafSearchLookup? = if (compiled.needsSource) lookup!!.getLeafSearchLookup(ctx) else null
                    return object : ScoreScript(params, lookup, indexSearcher, ctx) {
                        override fun setDocument(docid: Int) {
                            super.setDocument(docid)
                            sourceLeaf?.setDocument(docid)
                        }

                        override fun execute(explanation: ExplanationHolder?): Double {
                            val score = if (compiled.scoreAccessed) get_score() else 0.0
                            val receiver = ChillSearchScript(getParams(), docsOf(doc), score)
                            val result = compiled.execute(fn, receiver, decodedParams, docsOf(doc), sourceLeaf?.let { { sourceMap(it) } })
                            return (result as? Number)?.toDouble() ?: wrongType(compiled, "a number", result)
                        }
                    }
                }
            }
        }

    private fun filterFactory(compiled: CompiledChillScript): FilterScript.Factory =
        FilterScript.Factory { params: Map<String, Any>?, lookup: SearchLookup? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            FilterScript.LeafFactory { ctx: LeafReaderContext ->
                val fn = compiled.instantiate()
                val sourceLeaf: LeafSearchLookup? = if (compiled.needsSource) lookup!!.getLeafSearchLookup(ctx) else null
                object : FilterScript(params, lookup, ctx) {
                    override fun setDocument(docid: Int) {
                        super.setDocument(docid)
                        sourceLeaf?.setDocument(docid)
                    }

                    override fun execute(): Boolean {
                        val receiver = ChillSearchScript(getParams(), docsOf(doc), 0.0)
                        val result = compiled.execute(fn, receiver, decodedParams, docsOf(doc), sourceLeaf?.let { { sourceMap(it) } })
                        return result as? Boolean ?: wrongType(compiled, "a boolean", result)
                    }
                }
            }
        }

    private fun fieldFactory(compiled: CompiledChillScript): FieldScript.Factory =
        FieldScript.Factory { params: Map<String, Any>?, lookup: SearchLookup? ->
            val decodedParams = compiled.decodeParams(params ?: emptyMap())
            FieldScript.LeafFactory { ctx: LeafReaderContext ->
                val fn = compiled.instantiate()
                val sourceLeaf: LeafSearchLookup? = if (compiled.needsSource) lookup!!.getLeafSearchLookup(ctx) else null
                object : FieldScript(params, lookup, ctx) {
                    override fun setDocument(docid: Int) {
                        super.setDocument(docid)
                        sourceLeaf?.setDocument(docid)
                    }

                    override fun execute(): Any? {
                        val receiver = ChillSearchScript(getParams(), docsOf(doc), 0.0)
                        return compiled.execute(fn, receiver, decodedParams, docsOf(doc), sourceLeaf?.let { { sourceMap(it) } })
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun sourceMap(leaf: LeafSearchLookup): Map<String, Any?> = leaf.source() as Map<String, Any?>
}
