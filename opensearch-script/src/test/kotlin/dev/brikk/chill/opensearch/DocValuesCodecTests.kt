package dev.brikk.chill.opensearch

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DocValuesCodecTests {

    @Serializable(with = RepeatedReadSerializer::class)
    data class RepeatedRead(val value: Long)

    object RepeatedReadSerializer : KSerializer<RepeatedRead> {
        override val descriptor = PrimitiveSerialDescriptor("RepeatedRead", PrimitiveKind.LONG)
        override fun serialize(encoder: Encoder, value: RepeatedRead) = encoder.encodeLong(value.value)
        override fun deserialize(decoder: Decoder): RepeatedRead {
            val first = decoder.decodeLong()
            check(first == decoder.decodeLong())
            return RepeatedRead(first)
        }
    }

    @Serializable
    enum class Kind { @SerialName("post") POST, PAGE }

    @Serializable
    class KindLists(val kinds: List<Kind?> = emptyList(), val primary: Kind = Kind.PAGE)

    @Test
    fun enumListsUseSerializedNamesAndPreserveElements() {
        val decoded = DocValuesCodec.decode(
            KindLists.serializer(),
            mapOf("kinds" to listOf("PAGE", "post", null, "PAGE"), "primary" to listOf("post")),
        )
        assertEquals(listOf(Kind.PAGE, Kind.POST, null, Kind.PAGE), decoded.kinds)
        assertEquals(Kind.POST, decoded.primary)
        for (doc in listOf<Map<String, List<Any?>>>(emptyMap(), mapOf("kinds" to emptyList()))) {
            assertEquals(emptyList<Kind>(), DocValuesCodec.decode(KindLists.serializer(), doc).kinds)
        }
    }

    @Test
    fun invalidEnumListElementsNameTheFieldAndIndex() {
        for (bad in listOf("POST", "unknown", 7)) {
            val ex = assertThrows<SerializationException> {
                DocValuesCodec.decode(KindLists.serializer(), mapOf("kinds" to listOf("PAGE", bad)))
            }
            assertTrue("'kinds'[1]" in ex.message!!) { ex.message }
            if (bad is String) assertTrue("post" in ex.message!! && "PAGE" in ex.message!!) { ex.message }
        }
    }

    @Serializable
    class ArticleDoc(
        @SerialName("popularity_score") val popularity: Double = 0.0,
        val reads: Double = 0.0,
        @SerialName("author_id") val authorId: Long = 0,
        val labels: List<String> = emptyList(),
        @Contextual @SerialName("posted_at") val postedAt: ZonedDateTime,
        val featured: Boolean = false,
    )

    private val date: ZonedDateTime = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun decodesRenamesDefaultsListsAndDates() {
        val doc = mapOf(
            "popularity_score" to listOf(7L), // Long doc value into Double property: widened
            "author_id" to listOf(42L),
            "labels" to listOf("draft", "archived"),
            "posted_at" to listOf<Any?>(date),
            // "reads" missing -> default 0.0; "featured" missing -> default false
        )

        val decoded = DocValuesCodec.decode(serializer<ArticleDoc>(), doc)
        assertEquals(7.0, decoded.popularity)
        assertEquals(0.0, decoded.reads)
        assertEquals(42L, decoded.authorId)
        assertEquals(listOf("draft", "archived"), decoded.labels)
        assertEquals(date, decoded.postedAt)
        assertEquals(false, decoded.featured)
    }

    @Test
    fun nullablePropertiesAreOptionalAndDecodeAsNullWhenAbsent() {
        @Serializable
        class Optionalish(val reads: Double, val label: String?, val score: Double? = 1.0)

        val decoded = DocValuesCodec.decode(serializer<Optionalish>(), mapOf("reads" to listOf(3.0)))
        assertEquals(3.0, decoded.reads)
        assertEquals(null, decoded.label) // nullable, no default, absent -> null, not MissingFieldException
        assertEquals(null, decoded.score) // absent nullable decodes null even with a non-null default, as Json does

        val present = DocValuesCodec.decode(serializer<Optionalish>(), mapOf("reads" to listOf(3.0), "label" to listOf("x"), "score" to listOf(2.0)))
        assertEquals("x", present.label)
        assertEquals(2.0, present.score)
    }

    @Test
    fun missingRequiredFieldNamesTheField() {
        val ex = assertThrows<MissingFieldException> {
            DocValuesCodec.decode(serializer<ArticleDoc>(), emptyMap())
        }
        assertTrue("posted_at" in ex.message!!) { "expected field name in: ${ex.message}" }
    }

    @Test
    fun typeMismatchIsLoudWithFieldDetail() {
        @Serializable
        class Strict(val reads: Double)

        val ex = assertThrows<SerializationException> {
            DocValuesCodec.decode(serializer<Strict>(), mapOf("reads" to listOf("not-a-number")))
        }
        assertTrue("reads" in ex.message!! && "String" in ex.message!!) { "got: ${ex.message}" }
    }

    @Test
    fun dateFieldRejectsNonDateValue() {
        val ex = assertThrows<SerializationException> {
            DocValuesCodec.decode(
                serializer<ArticleDoc>(),
                mapOf("posted_at" to listOf(123456L)),
            )
        }
        assertTrue("posted_at" in ex.message!!)
    }

    /**
     * Mirrors `org.opensearch.search.lookup.LeafDocLookup`: `get` throws for a field that is not
     * in the mapping, `containsKey` is the only safe probe, and size/isEmpty/keySet throw.
     */
    private class LeafDocLookupLike(private val mapped: Map<String, List<Any?>>) : AbstractMap<String, List<Any?>>() {
        val reads = mutableMapOf<String, Int>()
        override fun get(key: String): List<Any?> {
            reads[key] = (reads[key] ?: 0) + 1
            return mapped[key] ?: throw IllegalArgumentException("No field found for [$key] in mapping")
        }

        override fun containsKey(key: String): Boolean = key in mapped
        override val entries: Set<Map.Entry<String, List<Any?>>> get() = throw UnsupportedOperationException()
        override val size: Int get() = throw UnsupportedOperationException()
        override fun isEmpty(): Boolean = throw UnsupportedOperationException()
    }

    @Test
    fun eachSelectedFieldIsLoadedOnceIncludingNullableAndListFields() {
        @Serializable
        data class Selected(val views: Long, val optional: Long?, val tags: List<String>)

        val doc = LeafDocLookupLike(mapOf("views" to listOf(120L), "optional" to listOf(7L), "tags" to listOf("a", "b")))
        val decoded = DocValuesCodec.decode(Selected.serializer(), doc)
        assertEquals(Selected(120, 7, listOf("a", "b")), decoded)
        assertEquals(mapOf("views" to 1, "optional" to 1, "tags" to 1), doc.reads)
    }

    @Test
    fun customSerializersCanRereadAFieldAndCachedValuesDoNotLeakAcrossDocuments() {
        @Serializable
        data class Custom(val amount: RepeatedRead = RepeatedRead(42))
        val values = mutableListOf<Any?>(7L)
        val doc = LeafDocLookupLike(mapOf("amount" to values))
        val first = DocValuesCodec.decode(Custom.serializer(), doc)
        assertEquals(Custom(RepeatedRead(7)), first)
        assertEquals(1, doc.reads["amount"])
        values.clear()
        assertEquals(Custom(), DocValuesCodec.decode(Custom.serializer(), doc))
        values.add(9L)
        assertEquals(Custom(RepeatedRead(9)), DocValuesCodec.decode(Custom.serializer(), doc))
        assertEquals(Custom(RepeatedRead(7)), first)
        assertEquals(3, doc.reads["amount"])
    }

    @Test
    fun unmappedFieldsBehaveLikeMissingFieldsAgainstAnOpenSearchStyleDocMap() {
        // only two of ArticleDoc's fields exist in this "mapping"
        val doc = LeafDocLookupLike(mapOf("posted_at" to listOf<Any?>(date), "reads" to listOf(10.0)))

        val decoded = DocValuesCodec.decode(serializer<ArticleDoc>(), doc)
        assertEquals(10.0, decoded.reads)
        assertEquals(0.0, decoded.popularity) // unmapped -> default, not "No field found"
        assertEquals(emptyList<String>(), decoded.labels)

        // and a required unmapped field is the kotlinx error naming the field, not the lookup's IAE
        @Serializable
        class Needs(@SerialName("no_such_field") val required: String)
        val ex = assertThrows<MissingFieldException> { DocValuesCodec.decode(serializer<Needs>(), doc) }
        assertTrue("no_such_field" in ex.message!!)

        // receiver helpers take the same path
        val receiver = ChillSearchScript(emptyMap(), doc, 0.0)
        assertEquals(10.0, receiver.doubleVal("reads"))
        assertEquals(7.5, receiver.doubleVal("no_such_field", 7.5))
        assertEquals(emptyList<String>(), receiver.stringVals("no_such_field"))
    }
}
