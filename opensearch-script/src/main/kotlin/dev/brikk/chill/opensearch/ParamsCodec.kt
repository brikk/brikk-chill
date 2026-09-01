package dev.brikk.chill.opensearch

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Encodes/decodes params (and `_source`) between `@Serializable` classes and the plain
 * `Map<String, Any?>` trees OpenSearch uses, via the JSON element model - so params are exactly
 * "anything JSON'able" and one class defines the contract on both sides.
 */
object ParamsCodec {
    val json: Json = Json {
        encodeDefaults = false // lean params maps; the receiving side applies class defaults
        ignoreUnknownKeys = true
    }

    fun <P> encodeToMap(serializer: KSerializer<P>, value: P): Map<String, Any?> {
        val element = json.encodeToJsonElement(serializer, value)
        return (element as? JsonObject)?.toAnyMap()
            ?: throw SerializationException("params must encode to an object, got ${element::class.simpleName}")
    }

    fun <P> decodeFromMap(deserializer: DeserializationStrategy<P>, map: Map<String, Any?>): P =
        json.decodeFromJsonElement(deserializer, map.toJsonElement())

    // ---- JsonElement -> Any? tree ----

    private fun JsonObject.toAnyMap(): Map<String, Any?> = mapValues { (_, v) -> v.toAny() }

    private fun JsonElement.toAny(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> toAnyMap()
        is JsonArray -> map { it.toAny() }
    }

    // ---- Any? tree -> JsonElement ----

    private fun Map<*, *>.toJsonElement(): JsonObject =
        JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonValue() })

    private fun Any?.toJsonValue(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> toJsonElement()
        is Iterable<*> -> JsonArray(map { it.toJsonValue() })
        is Array<*> -> JsonArray(map { it.toJsonValue() })
        else -> throw SerializationException("Value of type ${this::class.qualifiedName} is not JSON-representable in params")
    }
}
