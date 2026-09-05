package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.DocValuesCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import org.opensearch.core.common.bytes.BytesReference
import org.opensearch.core.common.io.stream.StreamOutput
import org.opensearch.core.common.io.stream.Writeable
import org.opensearch.core.common.util.CollectionUtils

/** Converts object-valued script results before OpenSearch transports or renders them. */
internal object ScriptResultCodec {
    private val json = Json {
        encodeDefaults = true // response readers do not have the DTO's constructor defaults
        serializersModule = DocValuesCodec.serializersModule
    }

    // ClassValue avoids keeping shipped classloaders alive after their compiled scripts are evicted.
    private val encoders = object : ClassValue<(Any) -> Any?>() {
        override fun computeValue(type: Class<*>): (Any) -> Any? {
            try {
                StreamOutput.getWriter<Writeable.Writer<Any>>(type)
                return { it }
            } catch (_: IllegalArgumentException) {
                // Not an OpenSearch-native value. Resolve the shipped class's kotlinx serializer.
            }
            val serializer = try {
                serializer(type)
            } catch (ex: SerializationException) {
                throw SerializationException("script result ${type.name} needs a kotlinx.serialization serializer", ex)
            }
            return { value -> toValue(json.encodeToJsonElement(serializer, value)) }
        }
    }

    fun encode(value: Any?): Any? {
        CollectionUtils.ensureNoSelfReferences(value, "chill script result")
        return toValue(value)
    }

    private fun toValue(value: Any?): Any? = when (value) {
        null, JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.double
        is JsonObject -> value.mapValues { toValue(it.value) }
        is JsonArray -> value.map { toValue(it) }
        is Map<*, *> -> {
            if (value.keys.any { it !is String }) throw SerializationException("script result maps require non-null String keys")
            value.mapValues { toValue(it.value) }
        }
        is Collection<*> -> value.map { toValue(it) }
        is Array<*> -> value.map { toValue(it) }.toTypedArray() // arrays are one field value; collections are multiple
        is BytesReference -> value
        else -> encoders.get(
            Writeable.WriteableRegistry.getCustomClassFromInstance(value)
                ?: if (value is Enum<*>) value.declaringJavaClass else value.javaClass,
        )(value)
    }
}
