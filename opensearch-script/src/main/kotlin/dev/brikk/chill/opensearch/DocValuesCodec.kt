package dev.brikk.chill.opensearch

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.ZonedDateTime

/**
 * Decodes `@Serializable` classes from OpenSearch doc values (`Map<String, List<Any?>>`).
 *
 * Rules:
 *  - field name = `@SerialName` or the property name
 *  - missing/empty field (unmapped in the index, or mapped with no value for this document):
 *    skipped -> the property default applies, or kotlinx throws `MissingFieldException` naming
 *    the field when there is no default
 *  - numeric doc value into any numeric property: `Number` conversion, silently
 *  - any other type mismatch: loud `SerializationException` with field, expected, and actual
 *  - date doc values bind to [ZonedDateTime] only (mark the property `@Contextual`);
 *    derivations (`toEpochSecond()` etc.) belong to the script
 *  - a nullable property with no doc value decodes as `null` (nullable means optional)
 *  - scalar property takes the first doc value; `List<T>` property takes all values. Doc values
 *    are stored sorted (numerics/dates keep duplicates; keywords are also de-duplicated), so a
 *    `List<T>` property sees sorted values, not `_source` order - use `sourceType` when order matters
 *  - nested classes are not supported (doc values are flat)
 */
object DocValuesCodec {

    fun <T> decode(deserializer: DeserializationStrategy<T>, doc: Map<String, List<Any?>>): T =
        deserializer.deserialize(DocValuesDecoder(doc))

    /**
     * The doc values of [field], or an empty list when the field is absent. OpenSearch's
     * `LeafDocLookup.get` throws for a field that is not in the index mapping (only `containsKey`
     * is safe to probe), so every lookup goes through here: an unmapped field and a mapped field
     * with no value for this document both read as "no values", which is what lets a property
     * default apply on the server exactly as it does when the class is constructed locally.
     */
    fun Map<String, List<Any?>>.docValues(field: String): List<Any?> =
        if (containsKey(field)) this[field] ?: emptyList() else emptyList()

    val serializersModule: SerializersModule = SerializersModule {
        contextual(ChillZonedDateTimeSerializer)
    }

    /** Implemented by our decoders so type-specific serializers can pull the raw doc value. */
    interface RawDocValue {
        fun rawValue(): Any?
        fun fieldName(): String
    }

    object ChillZonedDateTimeSerializer : KSerializer<ZonedDateTime> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("dev.brikk.chill.opensearch.ZonedDateTime", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ZonedDateTime = when (decoder) {
            is RawDocValue -> decoder.rawValue() as? ZonedDateTime
                ?: throw SerializationException(
                    "doc field '${decoder.fieldName()}' expected a date (ZonedDateTime) but doc value is " +
                        (decoder.rawValue()?.javaClass?.name ?: "null"),
                )
            else -> ZonedDateTime.parse(decoder.decodeString())
        }

        override fun serialize(encoder: Encoder, value: ZonedDateTime) {
            encoder.encodeString(value.toString())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class DocValuesDecoder(val doc: Map<String, List<Any?>>) : AbstractDecoder(), RawDocValue {
        override val serializersModule: SerializersModule = DocValuesCodec.serializersModule

        private var rootDescriptor: SerialDescriptor? = null
        private var index = -1
        private var currentValues: List<Any?>? = null

        override fun fieldName(): String = rootDescriptor?.getElementName(index) ?: "<root>"
        private fun values(): List<Any?> = currentValues ?: doc.docValues(fieldName())
        override fun rawValue(): Any? = values().firstOrNull()

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (true) {
                index++
                if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
                val name = descriptor.getElementName(index)
                // present: decode it. Missing and nullable: decode as null (a nullable property is
                // optional, as with Json's explicitNulls=false, and as a locally constructed
                // instance with a null would be). Missing otherwise: skip, so kotlinx applies the
                // default or raises MissingFieldException naming the field.
                val values = doc.docValues(name)
                if (values.isNotEmpty() || descriptor.getElementDescriptor(index).isNullable) {
                    // LeafDocLookup.get reloads doc values even on a cache hit. Keep the field
                    // selected by the presence check for scalar reads, null probes and lists.
                    currentValues = values
                    return index
                }
            }
        }

        override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
            if (rootDescriptor == null) {
                if (descriptor.kind != StructureKind.CLASS && descriptor.kind != StructureKind.OBJECT) {
                    throw SerializationException("doc binding requires a @Serializable class, got ${descriptor.kind}")
                }
                rootDescriptor = descriptor
                return this
            }
            if (descriptor.kind == StructureKind.LIST) {
                return DocListDecoder(fieldName(), values())
            }
            throw SerializationException(
                "doc binding supports flat classes with scalar/list fields; unsupported nested ${descriptor.kind} at '${fieldName()}'",
            )
        }

        private fun mismatch(expected: String): Nothing = throw SerializationException(
            "doc field '${fieldName()}' expected $expected but doc value is ${rawValue()?.javaClass?.name ?: "null"}",
        )

        private fun number(): Number = rawValue() as? Number ?: mismatch("a number")

        override fun decodeBoolean(): Boolean = rawValue() as? Boolean ?: mismatch("Boolean")
        override fun decodeByte(): Byte = number().toByte()
        override fun decodeShort(): Short = number().toShort()
        override fun decodeInt(): Int = number().toInt()
        override fun decodeLong(): Long = number().toLong()
        override fun decodeFloat(): Float = number().toFloat()
        override fun decodeDouble(): Double = number().toDouble()
        override fun decodeChar(): Char = (rawValue() as? String)?.singleOrNull() ?: mismatch("a single-character String")
        override fun decodeString(): String = rawValue() as? String ?: mismatch("String")
        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            val name = rawValue() as? String ?: mismatch("an enum name (String)")
            val idx = enumDescriptor.getElementIndex(name)
            if (idx == CompositeDecoder.UNKNOWN_NAME) mismatch("one of ${(0 until enumDescriptor.elementsCount).map { enumDescriptor.getElementName(it) }}")
            return idx
        }

        override fun decodeNotNullMark(): Boolean = rawValue() != null
        override fun decodeNull(): Nothing? = null
        override fun decodeValue(): Any = rawValue() ?: mismatch("a value")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class DocListDecoder(val field: String, val values: List<Any?>) : AbstractDecoder(), RawDocValue {
        override val serializersModule: SerializersModule = DocValuesCodec.serializersModule

        private var index = -1

        override fun fieldName(): String = field
        override fun rawValue(): Any? = values.getOrNull(index)

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            index++
            return if (index >= values.size) CompositeDecoder.DECODE_DONE else index
        }

        override fun decodeCollectionSize(descriptor: SerialDescriptor): Int = values.size

        private fun mismatch(expected: String): Nothing = throw SerializationException(
            "doc field '$field'[$index] expected $expected but doc value is ${rawValue()?.javaClass?.name ?: "null"}",
        )

        private fun number(): Number = rawValue() as? Number ?: mismatch("a number")

        override fun decodeBoolean(): Boolean = rawValue() as? Boolean ?: mismatch("Boolean")
        override fun decodeByte(): Byte = number().toByte()
        override fun decodeShort(): Short = number().toShort()
        override fun decodeInt(): Int = number().toInt()
        override fun decodeLong(): Long = number().toLong()
        override fun decodeFloat(): Float = number().toFloat()
        override fun decodeDouble(): Double = number().toDouble()
        override fun decodeChar(): Char = (rawValue() as? String)?.singleOrNull() ?: mismatch("a single-character String")
        override fun decodeString(): String = rawValue() as? String ?: mismatch("String")

        override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
            val name = rawValue() as? String ?: mismatch("an enum name (String)")
            val idx = enumDescriptor.getElementIndex(name)
            if (idx == CompositeDecoder.UNKNOWN_NAME) mismatch("one of ${(0 until enumDescriptor.elementsCount).map { enumDescriptor.getElementName(it) }}")
            return idx
        }

        override fun decodeNotNullMark(): Boolean = rawValue() != null
        override fun decodeValue(): Any = rawValue() ?: mismatch("a value")
    }
}
