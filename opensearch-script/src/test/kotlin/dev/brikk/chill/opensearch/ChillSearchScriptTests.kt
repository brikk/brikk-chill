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
        val ranking = ChillOpenSearch.boundScore(paramType<Boost>(), docType<RankedDoc>()) @ChillLambda { p, d ->
            p.factor * d.value
        }

        val local: Double = ranking.evaluate(Boost(2.5), RankedDoc(4.0))

        assertEquals(10.0, local)
        assertEquals(mapOf("factor" to 2.5), ranking.withParams(Boost(2.5)).params)
    }

    @Test
    fun scoreTypeAddsAnExplicitFinalLocalParameter() {
        val ranking = ChillOpenSearch.boundScore(
            paramType<Boost>(),
            docType<RankedDoc>(),
            scoreType(),
        ) @ChillLambda { p, d, score -> p.factor * d.value + score }

        val local: Double = ranking.evaluate(Boost(2.0), RankedDoc(3.0), 1.5)

        assertEquals(7.5, local)
    }

    @Test
    fun storedScriptRefCarriesTypedParams() {
        val ref = storedChillScript("my-script", paramType<Boost>())
        val invocation = ref.withParams(Boost(2.0))
        assertEquals("my-script", invocation.id)
        assertEquals(mapOf("factor" to 2.0), invocation.params)
    }
}
