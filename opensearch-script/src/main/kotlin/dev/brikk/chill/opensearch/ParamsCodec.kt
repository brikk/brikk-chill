package dev.brikk.chill.opensearch

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.internal.GeneratedSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Encodes/decodes params (and `_source`) between `@Serializable` classes and the plain
 * `Map<String, Any?>` trees OpenSearch uses. Standard generated records and containers decode
 * directly; custom and JSON-specific serializers retain the JSON element path.
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
        ValueDecoder(prepare(map)).decodeSerializableValue(deserializer)

    /** Validate before invoking any user serializer, and materialize one-shot iterables once. */
    private fun prepare(value: Any?): Any? = when (value) {
        null, is JsonElement, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> value
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> {
            var copy: MutableMap<Any?, Any?>? = null
            var stringifyKeys = false
            for ((key, item) in value) {
                if (key !is String) stringifyKeys = true
                val prepared = prepare(item)
                if (prepared !== item) {
                    if (copy == null) copy = LinkedHashMap(value)
                    copy[key] = prepared
                }
            }
            val source = copy ?: value
            if (stringifyKeys) source.entries.associate { it.key.toString() to it.value } else source
        }
        is List<*> -> {
            var copy: MutableList<Any?>? = null
            value.forEachIndexed { index, item ->
                val prepared = prepare(item)
                if (prepared !== item) {
                    if (copy == null) copy = value.toMutableList()
                    copy[index] = prepared
                }
            }
            copy ?: value
        }
        is Iterable<*> -> value.map(::prepare)
        is Array<*> -> prepare(value.asList())
        else -> throw SerializationException("Value of type ${value::class.qualifiedName} is not JSON-representable in params")
    }

    private val primitiveSerializers: Set<Class<*>> = setOf(
        Boolean.serializer().javaClass, Byte.serializer().javaClass, Short.serializer().javaClass,
        Int.serializer().javaClass, Long.serializer().javaClass, Float.serializer().javaClass,
        Double.serializer().javaClass, Char.serializer().javaClass, String.serializer().javaClass,
    )
    @OptIn(ExperimentalSerializationApi::class)
    private val containerSerializers: Set<Class<*>> = setOf(
        ListSerializer(String.serializer()).javaClass, SetSerializer(String.serializer()).javaClass,
        MapSerializer(String.serializer(), String.serializer()).javaClass, ArraySerializer(String.serializer()).javaClass,
    )
    private val nullableSerializerClass = String.serializer().nullable.javaClass

    @OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
    private fun supportsDirect(deserializer: DeserializationStrategy<*>): Boolean {
        val type = deserializer.javaClass
        if (type in primitiveSerializers || type == nullableSerializerClass) return true
        if (type in containerSerializers) {
            val descriptor = deserializer.descriptor
            if (descriptor.kind != StructureKind.MAP) return true
            val key = descriptor.getElementDescriptor(0)
            return !key.isInline && (key.kind is PrimitiveKind || key.kind == SerialKind.ENUM)
        }
        // Descriptor shape alone is insufficient: a custom serializer can require JsonDecoder
        // while advertising the same shape as a generated record. Never try it twice.
        if (deserializer !is GeneratedSerializer<*>) return false
        val descriptor = deserializer.descriptor
        if (descriptor.isInline || (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT)) return false
        return (0 until descriptor.elementsCount).none { index ->
            descriptor.getElementDescriptor(index).isInline || descriptor.getElementAnnotations(index).any { it is JsonNames }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private open class ValueDecoder(protected var value: Any?) : AbstractDecoder() {
        override val serializersModule get() = json.serializersModule

        override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
            // Retain immutable wire scalars instead of unboxing/reboxing every map entry.
            // Identity checks are deliberate: a custom primitive serializer must still run.
            val v = value
            val scalar = when {
                deserializer === String.serializer() && v is String -> v
                deserializer === Double.serializer() && v is Double && v.isFinite() -> v
                deserializer === Long.serializer() && v is Long -> v
                deserializer === Int.serializer() && v is Int -> v
                deserializer === Boolean.serializer() && v is Boolean -> v
                deserializer === Float.serializer() && v is Float && v.isFinite() -> v
                deserializer === Short.serializer() && v is Short -> v
                deserializer === Byte.serializer() && v is Byte -> v
                else -> null
            }
            @Suppress("UNCHECKED_CAST")
            if (scalar != null) return scalar as T
            return if (value is JsonElement || !supportsDirect(deserializer)) {
                json.decodeFromJsonElement(deserializer, value.toJsonValue())
            } else {
                deserializer.deserialize(this)
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> ObjectDecoder(
                value as? Map<*, *> ?: throw SerializationException("${descriptor.serialName} requires an object"),
            )
            StructureKind.MAP -> MapDecoder(
                value as? Map<*, *> ?: throw SerializationException("${descriptor.serialName} requires an object"),
            )
            StructureKind.LIST -> ListDecoder(
                value as? List<*> ?: throw SerializationException("${descriptor.serialName} requires an array"),
            )
            else -> throw SerializationException("Unsupported direct structure ${descriptor.serialName}")
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
            throw SerializationException("${descriptor.serialName} requires a structure")

        private fun <T> scalar(serializer: DeserializationStrategy<T>): T =
            json.decodeFromJsonElement(serializer, value.toJsonValue())

        private fun integer(): Long? = when (val v = value) {
            is Byte -> v.toLong()
            is Short -> v.toLong()
            is Int -> v.toLong()
            is Long -> v
            else -> null
        }

        override fun decodeByte(): Byte = integer()?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }?.toByte() ?: scalar(Byte.serializer())
        override fun decodeShort(): Short = integer()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort() ?: scalar(Short.serializer())
        override fun decodeInt(): Int = integer()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: scalar(Int.serializer())
        override fun decodeLong(): Long = integer() ?: scalar(Long.serializer())

        override fun decodeDouble(): Double = when (val v = value) {
            is Double -> if (v.isFinite()) v else scalar(Double.serializer())
            is Byte, is Short, is Int, is Long -> (v as Number).toDouble()
            // Float -> Double must retain JSON's decimal-text roundtrip, not binary widening.
            else -> scalar(Double.serializer())
        }

        override fun decodeFloat(): Float = when (val v = value) {
            is Float -> if (v.isFinite()) v else scalar(Float.serializer())
            is Byte, is Short, is Int, is Long -> (v as Number).toFloat()
            else -> scalar(Float.serializer())
        }

        override fun decodeBoolean(): Boolean = value as? Boolean ?: scalar(Boolean.serializer())
        override fun decodeString(): String = value as? String ?: scalar(String.serializer())
        override fun decodeChar(): Char = (value as? String)?.singleOrNull() ?: scalar(Char.serializer())
        override fun decodeNotNullMark(): Boolean = value != null && value !== JsonNull
        override fun decodeNull(): Nothing? = null
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class ObjectDecoder(private val fields: Map<*, *>) : ValueDecoder(null) {
        private var index = -1

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (++index < descriptor.elementsCount) {
                val name = descriptor.getElementName(index)
                if (fields.containsKey(name)) {
                    value = fields[name]
                    return index
                }
            }
            return CompositeDecoder.DECODE_DONE
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private abstract class CollectionDecoder(private val elements: Int) : ValueDecoder(null) {
        private var index = -1
        protected abstract fun nextValue(index: Int): Any?
        override fun decodeSequentially(): Boolean = true

        private fun select(next: Int) {
            if (next == index) return
            check(next == index + 1 && next < elements) { "Collection elements must be decoded in order" }
            value = nextValue(next)
            index = next
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            if (index + 1 >= elements) return CompositeDecoder.DECODE_DONE
            select(index + 1)
            return index
        }

        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?,
        ): T {
            select(index)
            return decodeSerializableValue(deserializer)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class MapDecoder(map: Map<*, *>) : CollectionDecoder(map.size * 2) {
        private val size = map.size
        private val entries = map.entries.iterator()
        private var entry: Map.Entry<*, *>? = null
        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = size
        override fun nextValue(index: Int): Any? = if (index % 2 == 0) {
            entries.next().also { entry = it }.key
        } else {
            entry!!.value
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class ListDecoder(list: List<*>) : CollectionDecoder(list.size) {
        private val size = list.size
        private val elements = list.iterator()
        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = size
        override fun nextValue(index: Int): Any? = elements.next()
    }

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
