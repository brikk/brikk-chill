package dev.brikk.chill.opensearch

import kotlinx.serialization.Contextual
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DocValuesCodecTests {

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
}
