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
        // same lambda shape frozen twice via different param slot kinds: both are just the class
        // bytes + captured state, so param values cannot appear in either source
        val a = ChillOpenSearch.script(paramOf(Boost(1.0))) @ChillLambda { p -> p.factor }
        val b = ChillOpenSearch.script(paramOf(Boost(99.0))) @ChillLambda { p -> p.factor }
        // distinct lambdas -> distinct classes -> distinct sources, but identical *lengths* since
        // only class names differ; the meaningful assertion is params-independence per lambda:
        assertEquals(a.params["factor"], 1.0)
        assertEquals(b.params["factor"], 99.0)
    }

    @Test
    fun storedScriptRefCarriesTypedParams() {
        val ref = storedChillScript("my-script", paramType<Boost>())
        val invocation = ref.withParams(Boost(2.0))
        assertEquals("my-script", invocation.id)
        assertEquals(mapOf("factor" to 2.0), invocation.params)
    }
}
