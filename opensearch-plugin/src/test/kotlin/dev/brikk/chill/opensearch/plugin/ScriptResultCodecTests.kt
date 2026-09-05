package dev.brikk.chill.opensearch.plugin

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opensearch.common.document.DocumentField
import org.opensearch.common.io.stream.BytesStreamOutput
import org.opensearch.core.common.bytes.BytesArray
import java.math.BigInteger
import java.time.ZonedDateTime
import java.util.Date
import java.util.Objects

class ScriptResultCodecTests {
    @Serializable
    data class Box<T>(val value: T)

    @Serializable
    data class WrappedBox(val box: Box<String>)

    private fun transport(value: Any?): Any? = BytesStreamOutput().use { out ->
        DocumentField("result", listOf(value)).writeTo(out)
        out.bytes().streamInput().use { DocumentField(it).values.single() }
    }

    @Test
    fun objectsBecomeTransportableMapsUsingTheirSerializerContract() {
        val at = ZonedDateTime.parse("2026-09-05T12:00:00Z")
        val result = ReadSummary(12.5, details = SummaryDetails(tags = listOf("one", "two")), at = at)
        val expected = mapOf(
            "read_count" to 12.5,
            "kind" to "article",
            "details" to mapOf("label" to "reads", "tags" to listOf("one", "two"), "note" to null),
            "at" to at.toString(),
        )
        val converted = ScriptResultCodec.encode(result)
        assertEquals(expected, converted)
        assertEquals(expected, transport(converted))
        assertEquals("not a response field", result.hidden)
        assertEquals(3L, transport(ScriptResultCodec.encode(CountedResult(3))))
        assertEquals("article", transport(ScriptResultCodec.encode(SummaryKind.ARTICLE)))
    }

    @Test
    fun nativeScalarsAndPrimitiveArraysAreNotChangedOrNarrowed() {
        val native = listOf(
            null, "text", true, 1.toByte(), 2.toShort(), 3, 4L, 1.5f, 2.5,
            BigInteger("123456789012345678901234567890"), Date(1234),
            ZonedDateTime.parse("2026-09-05T12:00:00Z"), BytesArray(byteArrayOf(1, 2)),
            byteArrayOf(1, 2), intArrayOf(3, 4), longArrayOf(5, 6), floatArrayOf(1.5f), doubleArrayOf(2.5),
        )
        for (value in native) {
            val encoded = ScriptResultCodec.encode(value)
            assertSame(value, encoded)
            assertTrue(Objects.deepEquals(value, transport(encoded))) { "transport changed ${value?.javaClass?.name}" }
        }
    }

    @Test
    fun nestedObjectsAreConvertedWithoutMutatingContainersOrFlatteningArrays() {
        val dto = ReadSummary(1.0)
        val value = linkedMapOf("list" to listOf(dto), "array" to arrayOf(dto), "set" to setOf(dto))
        val encoded = ScriptResultCodec.encode(value) as Map<*, *>
        val restored = transport(encoded) as Map<*, *>
        val dtoMap = ScriptResultCodec.encode(dto)
        assertEquals(listOf(dtoMap), restored["list"])
        assertEquals(listOf(dtoMap), restored["set"])
        assertTrue(Objects.deepEquals(arrayOf(dtoMap), restored["array"]))
        assertSame(dto, (value["list"] as List<*>).single())
        assertSame(dto, (value["array"] as Array<*>).single())
    }

    @Test
    fun objectsWithoutKotlinxSerializersAndInvalidMapKeysFailBeforeTransport() {
        for (value in listOf(NotSerializableResult("x"), listOf(NotSerializableResult("x")))) {
            val ex = assertThrows<SerializationException> { ScriptResultCodec.encode(value) }
            assertTrue("NotSerializableResult" in ex.message!!)
        }
        for (key in listOf(null, 1, SummaryKind.ARTICLE)) {
            val ex = assertThrows<SerializationException> { ScriptResultCodec.encode(mapOf(key to "x")) }
            assertTrue("String keys" in ex.message!!)
        }
    }

    @Test
    fun nativeContainerCyclesKeepOpenSearchsExistingFailureBehavior() {
        val circular = mutableListOf<Any>()
        circular.add(circular)
        assertThrows<IllegalArgumentException> { ScriptResultCodec.encode(circular) }
        val shared = listOf(ReadSummary(2.0))
        val encoded = ScriptResultCodec.encode(listOf(shared, shared)) as List<*>
        assertEquals(encoded[0], encoded[1])
    }

    @Test
    fun erasedGenericRootsNeedAWrapperWhoseSerializerKnowsTheTypeArguments() {
        val ex = assertThrows<SerializationException> { ScriptResultCodec.encode(Box("value")) }
        assertTrue("Box" in ex.message!!)
        assertEquals(
            mapOf("box" to mapOf("value" to "value")),
            transport(ScriptResultCodec.encode(WrappedBox(Box("value")))),
        )
    }
}
