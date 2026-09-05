package dev.brikk.chill.opensearch

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.BigInteger
import java.util.LinkedList

@OptIn(ExperimentalSerializationApi::class)
class ParamsCodecCompatibilityTests {
    @Serializable
    data class Box<T>(val value: T)

    // The published 0.1.1 conversion is the compatibility oracle, including its JSON coercions.
    private fun Any?.jsonValue(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.jsonValue() })
        is Iterable<*> -> JsonArray(map { it.jsonValue() })
        is Array<*> -> JsonArray(map { it.jsonValue() })
        else -> throw SerializationException("not JSON-representable")
    }

    private fun <T> sameAsJson(serializer: KSerializer<T>, map: Map<String, Any?>) {
        val expected = runCatching { ParamsCodec.json.decodeFromJsonElement(serializer, map.jsonValue()) }
        val actual = runCatching { ParamsCodec.decodeFromMap(serializer, map) }
        assertEquals(expected.isSuccess, actual.isSuccess, "$map: expected $expected, got $actual")
        if (expected.isSuccess) {
            assertEquals(
                ParamsCodec.json.encodeToJsonElement(serializer, expected.getOrThrow()),
                ParamsCodec.json.encodeToJsonElement(serializer, actual.getOrThrow()),
                map.toString(),
            )
        } else if (expected.exceptionOrNull() is SerializationException) {
            assertTrue(actual.exceptionOrNull() is SerializationException) { actual.toString() }
        } else {
            assertEquals(expected.exceptionOrNull()!!::class, actual.exceptionOrNull()!!::class)
        }
    }

    private inline fun <reified T> scalar(vararg values: Any?) {
        for (value in values) sameAsJson(serializer<Box<T>>(), mapOf("value" to value))
    }

    @Test
    fun scalarCoercionsKeepJsonSemantics() {
        scalar<Byte>(1.toByte(), 127L, -128, 128, "001", "+1", "1e2", "1.0", null)
        scalar<Short>(1.toShort(), 32767L, -32768, 32768, "100e-2", "1e-1", 1.0)
        scalar<Int>(1, 2147483647L, 2147483648L, "1e2", "1e", " 42 ", "1.0e2", true)
        scalar<Long>(1, Long.MIN_VALUE, Long.MAX_VALUE, "9007199254740993e0", "18446744073709551616", "1e-400", BigInteger("123"), BigDecimal("1.0"))
        scalar<Double>(1, Long.MAX_VALUE, 0.1, 0.1f, -0.0, "+1.5", "0x1.0p2", "1f", "NaN", Double.NaN, Double.POSITIVE_INFINITY)
        scalar<Float>(1, Long.MAX_VALUE, 0.1f, 0.1, 1.0000000596046448, -0.0f, Double.MAX_VALUE, "1e-1000", "Infinity")
        scalar<Boolean>(true, false, "true", "TRUE", "FaLsE", " true ", 1, null)
        scalar<String>("text", "null", "", 1, true, JsonPrimitive(1), null)
        scalar<Char>("a", "", "ab", 7, 7.0, JsonPrimitive("x"))
        scalar<String?>(null, "null", "text", 1)
    }

    @Serializable
    data class Presence(val required: String?, val optional: String? = "default", val count: Int = 7)

    @Test
    fun missingDefaultsNullsAndIgnoredFieldsMatchJson() {
        for (map in listOf(
            emptyMap(), mapOf("required" to null), mapOf("required" to "x", "optional" to null),
            mapOf("required" to null, "count" to null), mapOf("required" to "x", "ignored" to listOf(1, "x", null)),
        )) sameAsJson(Presence.serializer(), map)
    }

    @Serializable
    data class Collections(
        val weights: Map<String, Double>, val nested: Map<Int, List<Long?>>, val flags: Set<Boolean>,
        val objects: List<Presence>, val array: IntArray,
    )

    @Test
    fun nestedContainersMapKeysArraysAndNumericValuesMatchJson() {
        sameAsJson(Collections.serializer(), mapOf(
            "weights" to linkedMapOf<Any?, Any?>(1 to 0.5, "1" to 2.5, null to 4),
            "nested" to linkedMapOf("01" to listOf(1L, null), "1" to listOf(2L)),
            "flags" to setOf(true, false), "objects" to arrayOf(mapOf("required" to null)), "array" to listOf(1, 2, 3),
        ))
        scalar<Map<List<Int>, String>>(mapOf(listOf(1, 2) to "unsupported structured key"))
        scalar<Map<Boolean, Int>>(mapOf(true to 1, "FALSE" to 2))
        scalar<Map<Int, List<String>>>(mapOf("1" to listOf("a"), "01" to listOf("b")))
        scalar<List<Int>>(listOf("1", 2, 3L), listOf(1, "bad"), arrayOf(1, 2))
    }

    @Test
    fun oneShotIterablesAreConsumedOnceAndTypedContainersAreCopied() {
        var iterations = 0
        val once = Iterable { check(iterations++ == 0); listOf(1, 2).iterator() }
        assertEquals(listOf(1, 2), ParamsCodec.decodeFromMap(serializer<Box<List<Int>>>(), mapOf("value" to once)).value)
        assertEquals(1, iterations)
        val inputList = mutableListOf(1, 2)
        val inputMap = mutableMapOf("a" to inputList)
        val decoded = ParamsCodec.decodeFromMap(serializer<Box<Map<String, List<Int>>>>(), mapOf("value" to inputMap))
        inputList.add(3)
        inputMap.clear()
        assertEquals(mapOf("a" to listOf(1, 2)), decoded.value)
    }

    @Test
    fun linkedListsAreTraversedSequentiallyAndImmutableScalarsCanBeShared() {
        val sequential = object : LinkedList<Int>(listOf(1, 2, 3)) {
            override fun get(index: Int): Int = error("Indexed traversal is not appropriate for this list")
        }
        assertEquals(listOf(1, 2, 3), ParamsCodec.decodeFromMap(serializer<Box<List<Int>>>(), mapOf("value" to sequential)).value)
        val number: Any = 2.5
        val input = mutableMapOf("a" to number)
        val output = ParamsCodec.decodeFromMap(serializer<Box<Map<String, Double>>>(), mapOf("value" to input)).value
        assertNotSame(input, output)
        assertSame(number, output["a"])
        input.clear()
        assertEquals(mapOf("a" to 2.5), output)
    }

    @Serializable
    data class Aliases(@SerialName("wire") @JsonNames("oldA", "oldB") val value: Int)

    @Serializable
    data class AliasCollision(@JsonNames("old") val a: Int, @JsonNames("old") val b: Int)

    @Serializable
    enum class Choice { @SerialName("ok") @JsonNames("legacy") OK, @SerialName("null") NULL }

    @Test
    fun aliasesAndEnumNamesRetainJsonPrecedenceAndValidation() {
        sameAsJson(Aliases.serializer(), linkedMapOf("oldA" to 1, "wire" to 2))
        sameAsJson(Aliases.serializer(), linkedMapOf("wire" to 2, "oldA" to 1))
        sameAsJson(Aliases.serializer(), linkedMapOf("oldB" to 3, "oldA" to 1))
        sameAsJson(Aliases.serializer(), linkedMapOf("oldA" to 1, "oldB" to 3))
        sameAsJson(AliasCollision.serializer(), mapOf("a" to 1, "b" to 2))
        sameAsJson(AliasCollision.serializer(), mapOf("old" to 1, "b" to 2))
        scalar<Choice>("ok", "legacy", "OK", "unknown", null, "null", 1)
    }

    @Serializable
    @JsonClassDiscriminator("tag")
    sealed class Variant {
        @Serializable @SerialName("n") data class N(val value: Int) : Variant()
        @Serializable @SerialName("s") data class S(val value: String) : Variant()
    }

    @Serializable @JvmInline value class Token(val text: String)
    @Serializable data class InlineFields(val token: Token, val count: ULong, val optional: UInt?)

    @Test
    fun polymorphismValueClassesAndUnsignedValuesKeepTheirJsonProtocol() {
        scalar<Variant>(mapOf("tag" to "n", "value" to 3), mapOf("tag" to "s", "value" to "x"), mapOf("value" to 3), mapOf("tag" to null, "value" to 3))
        sameAsJson(Variant.serializer(), mapOf("tag" to "n", "value" to 3))
        scalar<UInt>("4294967295", "+1", "123 junk", "1e2", "-1", "4294967296")
        scalar<ULong>("18446744073709551615", "18446744073709551616", 7L)
        scalar<List<Token>>(listOf("a", "b"))
        sameAsJson(InlineFields.serializer(), mapOf("token" to "x", "count" to "18446744073709551615", "optional" to null))
    }

    @Serializable(with = JsonOnlySerializer::class)
    data class JsonOnly(val value: String)

    object JsonOnlySerializer : KSerializer<JsonOnly> {
        var calls = 0
        override val descriptor = buildClassSerialDescriptor("JsonOnly") { element<String>("value") }
        override fun serialize(encoder: Encoder, value: JsonOnly) = encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, value.value)
        }
        override fun deserialize(decoder: Decoder): JsonOnly {
            calls++
            val text = (decoder as JsonDecoder).decodeJsonElement().jsonObject.getValue("value").jsonPrimitive.content
            check(text != "explode") { "deliberate custom serializer failure" }
            return JsonOnly(text)
        }
    }

    @Serializable
    data class Customs(val one: JsonOnly, val many: List<JsonOnly?>, val optional: JsonOnly?)

    @Serializable
    data class ContextualFields(@Contextual val one: JsonOnly, @Contextual val optional: JsonOnly?)

    @Test
    fun contextualFallbackUsesJsonAndInvokesTheCustomSerializerOnce() {
        JsonOnlySerializer.calls = 0
        val decoded = ParamsCodec.decodeFromMap(ContextualFields.serializer(), mapOf("one" to mapOf("value" to "context"), "optional" to null))
        assertEquals(ContextualFields(JsonOnly("context"), null), decoded)
        assertEquals(1, JsonOnlySerializer.calls)
    }

    @Test
    fun jsonOnlySerializersRunOnceAtRootAndNestedBoundariesIncludingFailures() {
        JsonOnlySerializer.calls = 0
        assertEquals(JsonOnly("root"), ParamsCodec.decodeFromMap(JsonOnlySerializer, mapOf("value" to "root")))
        assertEquals(1, JsonOnlySerializer.calls)
        JsonOnlySerializer.calls = 0
        val decoded = ParamsCodec.decodeFromMap(Customs.serializer(), mapOf(
            "one" to mapOf("value" to "a"), "many" to listOf(mapOf("value" to "b"), null), "optional" to null,
        ))
        assertEquals(Customs(JsonOnly("a"), listOf(JsonOnly("b"), null), null), decoded)
        assertEquals(2, JsonOnlySerializer.calls)
        JsonOnlySerializer.calls = 0
        assertThrows<IllegalStateException> { ParamsCodec.decodeFromMap(JsonOnlySerializer, mapOf("value" to "explode")) }
        assertEquals(1, JsonOnlySerializer.calls)
        JsonOnlySerializer.calls = 0
        assertThrows<IllegalStateException> {
            ParamsCodec.decodeFromMap(Customs.serializer(), mapOf(
                "one" to mapOf("value" to "a"), "many" to listOf(mapOf("value" to "explode")), "optional" to null,
            ))
        }
        assertEquals(2, JsonOnlySerializer.calls)
    }

    @Test
    fun ignoredUnsupportedValuesFailBeforeAnyUserDeserializerRuns() {
        for (unsupported in listOf(Any(), intArrayOf(1), 'x', 1u)) {
            JsonOnlySerializer.calls = 0
            assertThrows<SerializationException> {
                ParamsCodec.decodeFromMap(JsonOnlySerializer, mapOf("value" to "ok", "ignored" to listOf(unsupported)))
            }
            assertEquals(0, JsonOnlySerializer.calls)
        }
    }

    object DoublingSerializer : KSerializer<Double> {
        var calls = 0
        override val descriptor = Double.serializer().descriptor
        override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)
        override fun deserialize(decoder: Decoder): Double {
            calls++
            check(decoder is JsonDecoder)
            return decoder.decodeDouble() * 2
        }
    }

    @Serializable
    data class CustomPrimitive(@Serializable(with = DoublingSerializer::class) val value: Double)

    @Test
    fun customPrimitiveSerializersAreNotBypassedByTheScalarFastPath() {
        DoublingSerializer.calls = 0
        assertEquals(CustomPrimitive(5.0), ParamsCodec.decodeFromMap(CustomPrimitive.serializer(), mapOf("value" to 2.5)))
        assertEquals(1, DoublingSerializer.calls)
    }

    @Test
    fun transformingSerializersAndJsonElementsRetainTheirJsonView() {
        val transform = object : JsonTransformingSerializer<Presence>(Presence.serializer()) {
            override fun transformDeserialize(element: JsonElement): JsonElement = element.jsonObject.getValue("payload")
        }
        assertEquals(Presence("x"), ParamsCodec.decodeFromMap(transform, mapOf("payload" to mapOf("required" to "x"))))
        val element = buildJsonObject { put("quoted", "1"); put("numeric", 1); put("nothing", JsonNull) }
        val actual = ParamsCodec.decodeFromMap(serializer<Box<JsonElement>>(), mapOf("value" to element)).value
        assertEquals(element, actual)
        assertTrue(actual.jsonObject.getValue("quoted").jsonPrimitive.isString)
        assertFalse(actual.jsonObject.getValue("numeric").jsonPrimitive.isString)
    }
}
