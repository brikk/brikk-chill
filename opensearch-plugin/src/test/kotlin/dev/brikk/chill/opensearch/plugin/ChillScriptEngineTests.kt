package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.paramOf
import dev.brikk.chill.opensearch.paramType
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
            PolicyAllowance.ClassLevel.ClassMethodAccess("java.lang.System", "*", "*", setOf(AccessTypes.call_Class_Static_Method)),
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
