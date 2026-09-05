package dev.brikk.chill.opensearch.plugin

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

/** Representative search-ranking params, shared by the unit and integration suites. */
@Serializable
class RankParams(
    val nowEpochSec: Long,
    val minReads: Int = 0,
    val topicWeights: Map<String, Double> = emptyMap(),
    val authorPenalties: Map<String, Double> = emptyMap(),
)

/** Representative article doc-values binding: renames, defaults, a date. */
@Serializable
class ArticleDoc(
    @SerialName("popularity_score") val popularity: Double = 0.0,
    @SerialName("read_count") val reads: Double = 0.0,
    @SerialName("word_count") val words: Double = 0.0,
    val featured: Long = 0,
    @SerialName("author_id") val authorId: Long = 0,
    @SerialName("topic_id") val topicId: Long = 0,
    @Contextual @SerialName("posted_at") val postedAt: ZonedDateTime,
)
