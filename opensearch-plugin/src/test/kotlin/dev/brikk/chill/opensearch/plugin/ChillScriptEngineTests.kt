package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.paramOf
import dev.brikk.chill.opensearch.paramType
import dev.brikk.chill.opensearch.scoreType
import dev.brikk.chill.opensearch.sourceType
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.serialize.Chill
import dev.brikk.chill.serialize.ChillLambda
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opensearch.script.FieldScript
import org.opensearch.script.FilterScript
import org.opensearch.script.IngestScript
import org.opensearch.script.ScoreScript
import org.opensearch.script.ScriptException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max

@Serializable
class RankParams(
    val nowEpochSec: Long,
    val minReads: Int = 0,
    val topicWeights: Map<String, Double> = emptyMap(),
    val authorPenalties: Map<String, Double> = emptyMap(),
)

@Serializable
class ArticleDoc(
    @SerialName("popularity_score") val popularity: Double = 0.0,
    @SerialName("read_count") val reads: Double = 0.0,
    @SerialName("word_count") val words: Double = 0.0,
    val featured: Long = 0,
    @SerialName("author_id") val authorId: Long = 0,
    @SerialName("topic_id") val topicId: Long = 0,
    @Contextual @SerialName("posted_at") val postedAt: ZonedDateTime,
)

@Serializable
enum class Kind { POST, PAGE }

@Serializable
class Geo(val lat: Double = 0.0, val lon: Double = 0.0)

/** Doc-values binding with a top-level enum (doc values are flat; the enum arrives as its name). */
@Serializable
class KindedDoc(val kind: Kind = Kind.POST, @SerialName("read_count") val reads: Double = 0.0)

/** `_source` binding with a nested serializable, an enum, and a list of nested objects. */
@Serializable
class ArticleSource(val geo: Geo = Geo(), val kind: Kind = Kind.PAGE, val related: List<Geo> = emptyList())

class ChillScriptEngineTests {

    private val engine = ChillScriptEngine()

    private val docDate: ZonedDateTime = ZonedDateTime.of(2026, 1, 14, 12, 0, 0, 0, ZoneOffset.UTC)
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)

    private val sampleDoc = mapOf(
        "popularity_score" to listOf(7L),
        "read_count" to listOf(120.0),
        "word_count" to listOf(800.0),
        "featured" to listOf(1L),
        "author_id" to listOf(42L),
        "topic_id" to listOf(9L),
        "posted_at" to listOf<Any?>(docDate),
    )

    @Test
    fun rejectsNonChillSource() {
        val ex = assertThrows<ScriptException> {
            engine.compile("test", "doc['f'].value * 2", ScoreScript.CONTEXT, emptyMap())
        }
        assertTrue("ChillOpenSearch.script" in ex.message!!) { "expected usage guidance, got: ${ex.message}" }
    }

    @Test
    fun compilesScoreScriptAndDetectsScoreAccess() {
        val withScore = ChillOpenSearch.script(@ChillLambda { _score * doubleVal("weight", 1.0) })
        val withoutScore = ChillOpenSearch.script(@ChillLambda { doubleVal("weight") })

        val scoreFactory = engine.compile("s1", withScore.source, ScoreScript.CONTEXT, emptyMap())
        assertTrue(scoreFactory.newFactory(emptyMap(), null, null).needs_score())

        val noScoreFactory = engine.compile("s2", withoutScore.source, ScoreScript.CONTEXT, emptyMap())
        assertFalse(noScoreFactory.newFactory(emptyMap(), null, null).needs_score())
    }

    @Test
    fun compilesFilterAndFieldContexts() {
        val script = ChillOpenSearch.script(@ChillLambda { intVal("count") > 2 })
        val filterFactory: FilterScript.Factory = engine.compile("f", script.source, FilterScript.CONTEXT, emptyMap())
        val fieldFactory: FieldScript.Factory = engine.compile("f", script.source, FieldScript.CONTEXT, emptyMap())
        assertTrue(filterFactory is FilterScript.Factory)
        assertTrue(fieldFactory is FieldScript.Factory)
    }

    @Test
    fun resultTypeIsCheckedAtCompileAgainstTheContext() {
        val text = ChillOpenSearch.script(@ChillLambda { "label:" + intVal("count") })
        val number = ChillOpenSearch.script(@ChillLambda { intVal("count") * 1.5 })
        val flag = ChillOpenSearch.script(@ChillLambda { intVal("count") > 2 })
        val ints = ChillOpenSearch.script(@ChillLambda { intVal("count") })

        // score wants a Number: Int and Double both qualify; String and Boolean fail at compile
        engine.compile("ok-d", number.source, ScoreScript.CONTEXT, emptyMap())
        engine.compile("ok-i", ints.source, ScoreScript.CONTEXT, emptyMap())
        for (bad in listOf(text, flag)) {
            val ex = assertThrows<ScriptException> { engine.compile("bad", bad.source, ScoreScript.CONTEXT, emptyMap()) }
            assertTrue("this context needs Number" in ex.message!!) { ex.message }
        }
        // filter wants Boolean
        engine.compile("ok-b", flag.source, FilterScript.CONTEXT, emptyMap())
        val ex = assertThrows<ScriptException> { engine.compile("bad", number.source, FilterScript.CONTEXT, emptyMap()) }
        assertTrue("this context needs Boolean" in ex.message!!) { ex.message }
        // field takes anything
        engine.compile("ok-f", text.source, FieldScript.CONTEXT, emptyMap())

        // a lambda whose branches force an Any result is still allowed (checked per document)
        val mixed = ChillOpenSearch.script<Any>(@ChillLambda { if (intVal("count") > 2) 1.0 else "nope" })
        engine.compile("any", mixed.source, ScoreScript.CONTEXT, emptyMap())
    }

    @Test
    fun unsupportedContextIsRejected() {
        val script = ChillOpenSearch.script(@ChillLambda { 1 })
        assertThrows<IllegalArgumentException> {
            engine.compile("x", script.source, IngestScript.CONTEXT, emptyMap())
        }
    }

    @Test
    fun policyViolationIsRejectedServerSideWithViolationDetails() {
        // freeze with a deliberately permissive client so the SERVER side does the rejecting
        val permissive = setOf(
            PolicyAllowance.ClassLevel.ClassMethodAccess(
                "java.lang.System",
                "*",
                "*",
                setOf(AccessTypes.call_Class_Static_Method)
            ),
        ).flatMap { it.asPolicyStrings() }.toSet()

        val hostilePayload = Chill(ChillOpenSearch.quarantine).serializeLambdaToBase64(
            ChillSearchScript::class, Any::class, permissive,
            lambda = @ChillLambda { System.getenv("PATH") ?: "" },
        )

        val ex = assertThrows<ScriptException> {
            engine.compile("evil", hostilePayload, ScoreScript.CONTEXT, emptyMap())
        }
        assertTrue(ex.scriptStack.any { "System.getenv" in it }) { "expected violation detail, got: ${ex.scriptStack}" }
    }

    @Test
    fun boundSlotsDecodeAndExecuteLikeARankingScript() {
        val script = ChillOpenSearch.script(paramOf(rankParams()), docType<ArticleDoc>()) @ChillLambda { p, d ->
            val ageDays = max(1.0 / 24, (p.nowEpochSec - d.postedAt.toEpochSecond()) / 86400.0)
            val freshness = exp(-ageDays / 30.0)
            val readGate = if (d.reads >= p.minReads) 1.0 else 0.4
            val topicBoost = 1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0)
            val authorFactor = max(0.05, 1.0 - (p.authorPenalties[d.authorId.toString()] ?: 0.0))
            val featuredFactor = if (d.featured == 1L) 1.15 else 1.0
            freshness * readGate * topicBoost * authorFactor * featuredFactor
        }

        val compiled = engine.compileChill("ranking", script.source)
        val fn = compiled.instantiate()
        val decodedParams = compiled.decodeParams(script.params)
        val receiver = ChillSearchScript(script.params, sampleDoc, 0.0)

        val result = compiled.execute(fn, receiver, decodedParams, sampleDoc, null) as Double

        // same math computed independently: age is exactly 1 day
        val expected = exp(-1.0 / 30.0) * 1.0 * (1.0 + 1.5) * max(0.05, 1.0 - 0.8) * 1.15
        assertEquals(expected, result, 1e-9)
    }

    @Test
    fun templateSourceIsParamsIndependentAndExecutes() {
        val template = ChillOpenSearch.script(paramType<RankParams>(), docType<ArticleDoc>()) @ChillLambda { p, d ->
            d.reads * (p.topicWeights[d.topicId.toString()] ?: 1.0)
        }

        val readyA = template.withParams(RankParams(nowEpochSec = 1, topicWeights = mapOf("9" to 2.0)))
        val readyB = template.withParams(RankParams(nowEpochSec = 1, topicWeights = mapOf("9" to 5.0)))
        assertEquals(readyA.source, readyB.source) { "params must not change the source" }

        val compiled = engine.compileChill("tpl", template.source)
        val fn = compiled.instantiate()
        val receiver = ChillSearchScript(emptyMap(), sampleDoc, 0.0)

        assertEquals(240.0, compiled.execute(fn, receiver, compiled.decodeParams(readyA.params), sampleDoc, null))
        assertEquals(600.0, compiled.execute(fn, receiver, compiled.decodeParams(readyB.params), sampleDoc, null))
    }

    @Test
    fun boundScoreExecutesLocallyAndThroughCompiledSlots() {
        val ranking = ChillOpenSearch.bound(
            paramType<RankParams>(),
            docType<ArticleDoc>(),
            scoreType(),
        ) @ChillLambda { p, d, score -> d.reads * (p.topicWeights[d.topicId.toString()] ?: 1.0) + score }
        val params = RankParams(nowEpochSec = 1, topicWeights = mapOf("9" to 2.0))
        val localDoc = ArticleDoc(reads = 120.0, topicId = 9, postedAt = docDate)
        val ready = ranking.withParams(params)

        val compiled = engine.compileChill("bound", ranking.source, Double::class) { result ->
            (result as Number).toDouble()
        }
        val remote = compiled.execute(
            compiled.instantiate(),
            ChillSearchScript(ready.params, sampleDoc, 3.0),
            compiled.decodeParams(ready.params),
            sampleDoc,
            null,
            3.0,
        )

        assertEquals(243.0, ranking.evaluate(params, localDoc, 3.0))
        assertEquals(243.0, remote)
    }

    @Test
    fun explicitScoreSlotControlsNeedsScore() {
        val withoutScore =
            ChillOpenSearch.bound(paramType<RankParams>(), docType<ArticleDoc>()) @ChillLambda { _, d -> d.reads }
        val withScore = ChillOpenSearch.bound(
            paramType<RankParams>(),
            docType<ArticleDoc>(),
            scoreType(),
        ) @ChillLambda { _, d, score -> d.reads + score }

        val withoutFactory = engine.compile("bound-no-score", withoutScore.source, ScoreScript.CONTEXT, emptyMap())
        val withFactory = engine.compile("bound-score", withScore.source, ScoreScript.CONTEXT, emptyMap())
        val encodedParams = withoutScore.withParams(RankParams(nowEpochSec = 1)).params
            .mapValues { (_, value) -> requireNotNull(value) }

        assertFalse(withoutFactory.newFactory(encodedParams, null, null).needs_score())
        assertTrue(withFactory.newFactory(encodedParams, null, null).needs_score())
    }

    @Test
    fun explicitScoreSlotIsRejectedOutsideScoreContext() {
        val withScore = ChillOpenSearch.bound(
            paramType<RankParams>(),
            docType<ArticleDoc>(),
            scoreType(),
        ) @ChillLambda { _, d, score -> d.reads + score }

        assertThrows<ScriptException> {
            engine.compile("score-as-filter", withScore.source, FilterScript.CONTEXT, emptyMap())
        }
        assertThrows<ScriptException> {
            engine.compile("score-as-field", withScore.source, FieldScript.CONTEXT, emptyMap())
        }
    }

    @Test
    fun capturedValuesShipInsideThePayload() {
        // captured from the enclosing scope: primitive, string, and a collection
        val floor = 500.0
        val suffix = "!"
        val hiddenAuthors = setOf(42L, 7L)

        val script = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            if (d.authorId in hiddenAuthors || d.reads < floor) "hidden$suffix" else "shown$suffix"
        }

        val compiled = engine.compileChill("captures", script.source)
        val fn = compiled.instantiate()
        val receiver = ChillSearchScript(emptyMap(), sampleDoc, 0.0)

        // author_id=42 is in the captured hidden set
        assertEquals("hidden!", compiled.execute(fn, receiver, null, sampleDoc, null))

        // different captured values -> different payloads (captures live in the source)
        val otherHidden = setOf(999L)
        val script2 = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            if (d.authorId in otherHidden || d.reads >= floor) "hidden$suffix" else "shown$suffix"
        }
        val compiled2 = engine.compileChill("captures2", script2.source)
        assertEquals("shown!", compiled2.execute(compiled2.instantiate(), receiver, null, sampleDoc, null))
        assertTrue(script.source != script2.source)
    }

    /**
     * OpenSearch rejects inline and stored scripts above `script.max_size_in_bytes` (65,535 by
     * default). Two small bound classes ship six compiler-generated classes, which uncompressed
     * with debug info measured ~45 KB; the envelope is deflated and debug-stripped to stay far
     * from the limit. The ceiling leaves headroom for compiler drift but fails on a regression to
     * either an uncompressed envelope or unstripped classes.
     */
    @Test
    fun representativeBoundPayloadStaysWellUnderTheOpenSearchScriptSizeLimit() {
        val ranking = ChillOpenSearch.bound(
            paramType<RankParams>(),
            docType<ArticleDoc>(),
            scoreType(),
        ) @ChillLambda { p, d, score -> score * d.reads * (1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0)) }

        val size = ranking.source.toByteArray(Charsets.UTF_8).size
        assertTrue(size < 16_000) { "bound payload is $size bytes; expected < 16000 (OpenSearch default limit is 65535)" }

        // and it still compiles and runs on the engine side after stripping + compression
        val compiled = engine.compileChill("size", ranking.source, Double::class) { (it as Number).toDouble() }
        val ready = ranking.withParams(RankParams(nowEpochSec = 1, topicWeights = mapOf("9" to 2.0)))
        val remote = compiled.execute(compiled.instantiate(), ChillSearchScript(ready.params, sampleDoc, 2.0), compiled.decodeParams(ready.params), sampleDoc, null, 2.0)
        assertEquals(2.0 * 120.0 * 3.0, remote)
    }

    // ---- ship closure: user types referenced by bound classes ----

    @Test
    fun boundClassesReferencingEnumsAndNestedSerializablesShipAndDecode() {
        // enum in a doc-values binding; nested class + enum + list-of-nested in a _source binding
        val script = ChillOpenSearch.script(docType<KindedDoc>(), sourceType<ArticleSource>()) @ChillLambda { d, s ->
            val kindWeight = if (d.kind == Kind.POST) 2.0 else 1.0
            val nearby = s.related.count { it.lat > s.geo.lat }
            kindWeight * d.reads + s.geo.lon + nearby + (if (s.kind == Kind.PAGE) 100.0 else 0.0)
        }

        val compiled = engine.compileChill("closure", script.source)
        val doc = sampleDoc + ("kind" to listOf("POST"))
        val source = mapOf(
            "geo" to mapOf("lat" to 10.0, "lon" to 0.5),
            "kind" to "PAGE",
            "related" to listOf(mapOf("lat" to 11.0), mapOf("lat" to 9.0), mapOf("lat" to 12.0, "lon" to 1.0)),
        )
        val result = compiled.execute(compiled.instantiate(), ChillSearchScript(emptyMap(), doc, 0.0), null, doc, { source })
        assertEquals(2.0 * 120.0 + 0.5 + 2 + 100.0, result)

        // the shipped set is the user's type graph and nothing else
        val shipped = ChillOpenSearch.chill.deserFunctionFromPrefixedBase64(script.source).classes.map { it.className }
        val pkg = "dev.brikk.chill.opensearch.plugin."
        assertTrue(shipped.all { it.startsWith(pkg) }) { shipped.toString() }
        for (needed in listOf("Kind", "Kind\$Companion", "Geo", "Geo\$\$serializer", "ArticleSource\$\$serializer", "KindedDoc\$\$serializer")) {
            assertTrue(pkg + needed in shipped) { "missing $needed in $shipped" }
        }
    }

    // ---- execution limits ----

    private val limitedEngine = ChillScriptEngine(ExecutionLimits(maxLoopIterations = 10_000, regexLimitFactor = 6))

    private fun <R> ChillScriptEngine.run(script: dev.brikk.chill.opensearch.ChillScript<R>, doc: Map<String, List<Any?>> = sampleDoc): Any? {
        val compiled = compileChill("limited", script.source)
        return compiled.execute(compiled.instantiate(), ChillSearchScript(script.params, doc, 0.0), compiled.decodeParams(script.params), doc, null)
    }

    @Test
    fun runawayLoopFailsTheDocumentWithAScriptException() {
        val spin = ChillOpenSearch.script(@ChillLambda {
            var i = 0L
            while (true) i++
            @Suppress("UNREACHABLE_CODE") i
        })
        val ex = assertThrows<ScriptException> { limitedEngine.run(spin) }
        assertTrue("loop iterations" in ex.message!!) { ex.message }
        assertEquals("limited", ex.script)

        // a loop that fits the budget is untouched, and the budget is per execution: it runs again
        val tags = listOf("a", "b", "c", "d")
        val fits = ChillOpenSearch.script(@ChillLambda { var n = 0; for (t in tags) for (i in 1..1000) n += t.length; n })
        repeat(3) { assertEquals(4000, limitedEngine.run(fits)) }
    }

    @Test
    fun catastrophicRegexFailsTheDocumentWithAScriptException() {
        val input = "a".repeat(40) + "!"
        val bomb = ChillOpenSearch.script(@ChillLambda { Regex("(a+)+b").containsMatchIn(input) })
        val ex = assertThrows<ScriptException> { limitedEngine.run(bomb) }
        assertTrue("regular expression exceeded" in ex.message!!) { ex.message }

        val benign = ChillOpenSearch.script(@ChillLambda { stringVals("tags").count { Regex("^how").containsMatchIn(it) } })
        assertEquals(1, limitedEngine.run(benign, sampleDoc + ("tags" to listOf("howto", "misc"))))
    }

    @Test
    fun oversizedAllocationFailsTheDocument() {
        val engine = ChillScriptEngine(ExecutionLimits(maxAllocation = 4096))
        val hog = ChillOpenSearch.script(@ChillLambda { "x".repeat(1 shl 20).length })
        val ex = assertThrows<ScriptException> { engine.run(hog) }
        assertTrue("single allocation" in ex.message!!) { ex.message }
        val fine = ChillOpenSearch.script(@ChillLambda { DoubleArray(100) { it * 0.5 }.sum() })
        assertEquals(2475.0, engine.run(fine))
    }

    @Test
    fun regexCanBeDisabledByFactorZero() {
        val engine = ChillScriptEngine(ExecutionLimits(regexLimitFactor = 0))
        val script = ChillOpenSearch.script(@ChillLambda { "x1".replace(Regex("\\d"), "") })
        val ex = assertThrows<ScriptException> { engine.run(script) }
        assertTrue("disabled" in ex.message!!) { ex.message }
        // restore the static factor for the other tests (one engine per node in production)
        ChillScriptEngine(ExecutionLimits())
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val script = ChillOpenSearch.script(@ChillLambda { 42 })
        val tampered = script.source.dropLast(8) + "AAAAAAAA"
        assertThrows<ScriptException> {
            engine.compile("t", tampered, ScoreScript.CONTEXT, emptyMap())
        }
    }

    private fun rankParams() = RankParams(
        nowEpochSec = now.toEpochSecond(),
        minReads = 50,
        topicWeights = mapOf("9" to 1.5),
        authorPenalties = mapOf("42" to 0.8),
    )
}
