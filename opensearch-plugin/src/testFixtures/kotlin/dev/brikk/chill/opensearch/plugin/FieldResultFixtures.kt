package dev.brikk.chill.opensearch.plugin

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.ZonedDateTime

@Serializable
enum class SummaryKind { @SerialName("article") ARTICLE }

@Serializable
data class SummaryDetails(val label: String = "reads", val tags: List<String> = emptyList(), val note: String? = null)

@Serializable
data class ReadSummary(
    @SerialName("read_count") val reads: Double,
    val kind: SummaryKind = SummaryKind.ARTICLE,
    val details: SummaryDetails = SummaryDetails(),
    @Contextual val at: ZonedDateTime? = null,
    @Transient val hidden: String = "not a response field",
)

class NotSerializableResult(val value: String) : java.io.Serializable

@Serializable(with = CountedResultSerializer::class)
class CountedResult(val iterations: Int)

object CountedResultSerializer : KSerializer<CountedResult> {
    override val descriptor = PrimitiveSerialDescriptor("CountedResult", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: CountedResult) {
        var total = 0
        repeat(value.iterations) { total++ }
        encoder.encodeInt(total)
    }
    override fun deserialize(decoder: Decoder) = CountedResult(decoder.decodeInt())
}
