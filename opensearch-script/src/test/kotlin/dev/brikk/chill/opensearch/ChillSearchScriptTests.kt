package dev.brikk.chill.opensearch

import dev.brikk.chill.serialize.Chill
import dev.brikk.chill.serialize.ChillLambda
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChillSearchScriptTests {

    private val receiver = ChillSearchScript(
        params = mapOf("boost" to 3, "name" to "chill"),
        doc = mapOf(
            "price" to listOf(9.5),
            "count" to listOf(7L),
            "tags" to listOf("a", "b"),
            "flag" to listOf(true),
        ),
        _score = 1.25,
    )

    @Test
    fun docValueHelpers() {
        assertEquals(9.5, receiver.doubleVal("price"))
        assertEquals(7, receiver.intVal("count"))
        assertEquals(7L, receiver.longVal("count"))
        assertEquals(listOf("a", "b"), receiver.stringVals("tags"))
        assertEquals(true, receiver.boolVal("flag"))
        assertEquals("missing", receiver.stringVal("nope", "missing"))
        assertEquals(0, receiver.intVal("nope"))
    }

    @Test
    fun paramHelpers() {
        assertEquals(3, receiver.paramInt("boost"))
        assertEquals(3.0, receiver.paramDouble("boost"))
        assertEquals("chill", receiver.paramString("name", "x"))
        assertEquals("x", receiver.paramString("nope", "x"))
    }

    @Serializable
    class Boost(val factor: Double)

    @Serializable
    class RankedDoc(val value: Double)

    @Test
    fun unboundScriptFreezes() {
        val script = ChillOpenSearch.script(@ChillLambda { doubleVal("price") * _score })
        assertTrue(Chill.isPrefixedBase64(script.source))
        assertTrue(script.params.isEmpty())
    }

    @Test
    fun paramOfProducesReadyScriptWithEncodedParams() {
        val script = ChillOpenSearch.script(paramOf(Boost(2.0))) @ChillLambda { p -> doubleVal("price") * p.factor }
        assertTrue(Chill.isPrefixedBase64(script.source))
        assertEquals(mapOf("factor" to 2.0), script.params)
    }

    @Test
    fun paramTypeProducesTemplateAndWithParamsCompletes() {
        val template = ChillOpenSearch.script(paramType<Boost>()) @ChillLambda { p -> doubleVal("price") * p.factor }
        val ready = template.withParams(Boost(3.5))
        assertEquals(template.source, ready.source) { "params must never alter the source" }
        assertEquals(mapOf("factor" to 3.5), ready.params)
    }

    @Test
    fun readyAndTemplatePathsProduceIdenticalSourceShape() {
        val block: ChillSearchScript.(Boost) -> Double = @ChillLambda { p -> p.factor }
        val ready = ChillOpenSearch.script(paramOf(Boost(1.0)), block)
        val template = ChillOpenSearch.script(paramType<Boost>(), block)

        assertEquals(ready.source, template.source)
        assertEquals(1.0, ready.params["factor"])
    }

    @Test
    fun boundScoreEvaluatesTypedDocumentsLocally() {
        val ranking = ChillOpenSearch.bound(paramType<Boost>(), docType<RankedDoc>()) @ChillLambda { p, d ->
            p.factor * d.value
        }

        val local: Double = ranking.evaluate(Boost(2.5), RankedDoc(4.0))

        assertEquals(10.0, local)
        assertEquals(mapOf("factor" to 2.5), ranking.withParams(Boost(2.5)).params)
    }

    @Test
    fun scoreTypeAddsAnExplicitFinalLocalParameter() {
        val ranking = ChillOpenSearch.bound(
            paramType<Boost>(),
            docType<RankedDoc>(),
            scoreType(),
        ) @ChillLambda { p, d, score -> p.factor * d.value + score }

        val local: Double = ranking.evaluate(Boost(2.0), RankedDoc(3.0), 1.5)

        assertEquals(7.5, local)
    }

    @Test
    fun boundCoversEverySlotShapeAndAnyResultType() {
        // doc-only filter (Boolean), no params: ready immediately
        val filter = ChillOpenSearch.bound(docType<RankedDoc>()) @ChillLambda { d -> d.value > 2.0 }
        val passes: Boolean = filter.evaluate(RankedDoc(3.0))
        assertTrue(passes && !filter.evaluate(RankedDoc(1.0)))

        // params value + source + score -> String field
        val label = ChillOpenSearch.bound(paramOf(Boost(2.0)), sourceType<RankedDoc>(), scoreType()) @ChillLambda { p, s, score ->
            "v" + (p.factor * s.value + score).toInt()
        }
        val text: String = label.evaluate(RankedDoc(3.0), 1.0)
        assertEquals("v7", text)
        assertEquals(mapOf("factor" to 2.0), label.params)

        // no slots at all still gets an evaluator
        val constant = ChillOpenSearch.bound @ChillLambda { 42 }
        assertEquals(42, constant.evaluate())
    }

    @Test
    fun boundTemplateKeepsItsEvaluatorThroughWithParamsAndStored() {
        val template = ChillOpenSearch.bound(paramType<Boost>(), docType<RankedDoc>(), scoreType()) @ChillLambda { p, d, score ->
            p.factor * d.value + score
        }

        val ready = template.withParams(Boost(2.0))
        assertEquals(template.source, ready.source)
        assertEquals(7.5, ready.evaluate(RankedDoc(3.0), 1.5)) // params already applied
        assertEquals(7.5, template.evaluate(Boost(2.0), RankedDoc(3.0), 1.5))

        val stored = template.stored("rank-v1").withParams(Boost(3.0))
        assertEquals("rank-v1", stored.id)
        assertEquals(mapOf("factor" to 3.0), stored.params)
        assertEquals(10.5, stored.evaluate(RankedDoc(3.0), 1.5))
    }

    @Test
    fun storedScriptRefCarriesTypedParams() {
        val ref = storedChillScript("my-script", paramType<Boost>())
        val invocation = ref.withParams(Boost(2.0))
        assertEquals("my-script", invocation.id)
        assertEquals(mapOf("factor" to 2.0), invocation.params)
    }
}
