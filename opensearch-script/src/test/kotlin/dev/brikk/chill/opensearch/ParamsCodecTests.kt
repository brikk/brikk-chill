package dev.brikk.chill.opensearch

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParamsCodecTests {

    @Serializable
    class RankParams(
        val nowEpochSec: Long,
        val tier: String,
        val minReads: Int = 0,
        val topicWeights: Map<String, Double> = emptyMap(),
        val nested: Map<String, List<String>> = emptyMap(),
    )

    @Test
    fun roundTripsThroughAnyMap() {
        val params = RankParams(
            nowEpochSec = 1_700_000_000,
            tier = "standard",
            minReads = 50,
            topicWeights = mapOf("123" to 2.5, "456" to 1.0),
            nested = mapOf("a" to listOf("x", "y")),
        )

        val map = ParamsCodec.encodeToMap(serializer<RankParams>(), params)
        assertEquals(1_700_000_000L, map["nowEpochSec"])
        assertEquals("standard", map["tier"])
        assertEquals(mapOf("123" to 2.5, "456" to 1.0), map["topicWeights"])

        val back = ParamsCodec.decodeFromMap(serializer<RankParams>(), map)
        assertEquals(params.topicWeights, back.topicWeights)
        assertEquals(params.nested, back.nested)
        assertEquals(params.minReads, back.minReads)
    }

    @Test
    fun defaultsAreLeanOnTheWireAndApplyOnDecode() {
        val map = ParamsCodec.encodeToMap(serializer<RankParams>(), RankParams(1, "x"))
        assertTrue("minReads" !in map) { "defaults should not be encoded: $map" }

        val back = ParamsCodec.decodeFromMap(serializer<RankParams>(), map)
        assertEquals(0, back.minReads)
    }

    @Test
    fun missingRequiredParamIsLoud() {
        assertThrows<MissingFieldException> {
            ParamsCodec.decodeFromMap(serializer<RankParams>(), mapOf("tier" to "x"))
        }
    }
}
