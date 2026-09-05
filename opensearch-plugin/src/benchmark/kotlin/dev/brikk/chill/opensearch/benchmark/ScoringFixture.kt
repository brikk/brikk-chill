package dev.brikk.chill.opensearch.benchmark

import dev.brikk.chill.opensearch.ChillBound
import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.DocValuesCodec
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.plugin.ChillScriptEngine
import dev.brikk.chill.quarantine.limits.ExecutionBudget
import dev.brikk.chill.serialize.ChillLambda
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import org.apache.lucene.document.Document
import org.apache.lucene.document.SortedNumericDocValuesField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.ByteBuffersDirectory
import org.mockito.MockMakers
import org.mockito.Mockito
import org.opensearch.common.settings.Settings
import org.opensearch.index.fielddata.IndexNumericFieldData
import org.opensearch.index.fielddata.plain.SortedNumericIndexFieldData
import org.opensearch.index.mapper.MapperService
import org.opensearch.index.mapper.NumberFieldMapper
import org.opensearch.painless.PainlessModulePlugin
import org.opensearch.script.ScoreScript
import org.opensearch.search.lookup.SearchLookup

@Serializable
data class NarrowDoc(val views: Long = 0)

@Serializable
data class WideDoc(val views: Long = 0, val reactions: Long = 0, val duration: Long = 0, val publishedEpoch: Long = 0)

@Serializable
data class Wide12Doc(
    val views: Long = 0, val reactions: Long = 0, val duration: Long = 0, val publishedEpoch: Long = 0,
    val shares: Long = 0, val comments: Long = 0, val bookmarks: Long = 0, val channelSize: Long = 0,
    val ageDays: Long = 0, val quality: Long = 0, val engagement: Long = 0, val languageScore: Long = 0,
)

/** All values and engine execution are real. Only one-time mapping discovery is stubbed. */
class ScoringFixture(shape: String, missingPercent: Int, access: String) : AutoCloseable {
    companion object { const val DOCUMENTS = 65_536 }

    private val directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val lookup: SearchLookup
    private val searcher: IndexSearcher
    private val factories: Map<String, ScoreScript.Factory>
    private val compiled: ChillScriptEngine.CompiledChillScript<Any?>
    private val localSerializer: DeserializationStrategy<*>
    private val localFunction: Function2<Any?, Any?, Any?>
    private val expected: Double
    private val accessedFields: List<String>

    init {
        val fields = listOf("views", "reactions", "duration", "publishedEpoch", "shares", "comments", "bookmarks", "channelSize", "ageDays", "quality", "engagement", "languageScore")
        val fieldCount = when (shape) { "narrow" -> 1; "wide" -> 4; "wide12" -> 12; else -> error(shape) }
        require(access == "one" || access == "all")
        accessedFields = fields.take(if (access == "one") 1 else fieldCount)
        var sum = 0.0
        IndexWriter(directory, IndexWriterConfig()).use { writer ->
            repeat(DOCUMENTS) { i ->
                val document = Document()
                val views = 1000L + ((i * 1103515245L + 12345L) and 0x7fffffffL)
                var score = 1.0
                fields.forEachIndexed { n, field ->
                    if ((i + n * 13) % 100 >= missingPercent) {
                        document.add(SortedNumericDocValuesField(field, views + n))
                        if (n < accessedFields.size) score += views + n
                    }
                }
                writer.addDocument(document)
                sum += score
            }
            writer.forceMerge(1)
            writer.commit()
        }
        expected = sum
        reader = DirectoryReader.open(directory)
        check(reader.leaves().size == 1 && reader.maxDoc() == DOCUMENTS)
        searcher = IndexSearcher(reader)

        val mapper = Mockito.mock(MapperService::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))
        for (field in fields) {
            Mockito.`when`(mapper.fieldType(field)).thenReturn(NumberFieldMapper.NumberFieldType(field, NumberFieldMapper.NumberType.LONG))
        }
        val fieldData = fields.associateWith { SortedNumericIndexFieldData(it, IndexNumericFieldData.NumericType.LONG) }
        lookup = SearchLookup(mapper, { type, _ -> fieldData.getValue(type.name()) }, 0)

        val source: String
        val original: Any
        if (shape == "narrow") {
            val fn: ChillBound.(NarrowDoc) -> Double = @ChillLambda { d -> 1.0 + Math.max(0.0, d.views.toDouble()) }
            source = ChillOpenSearch.bound(docType<NarrowDoc>(), fn).source
            original = fn
            localSerializer = NarrowDoc.serializer()
        } else if (shape == "wide") {
            val fn: ChillBound.(WideDoc) -> Double = if (access == "one") {
                @ChillLambda { d -> 1.0 + Math.max(0.0, d.views.toDouble()) }
            } else {
                @ChillLambda { d -> 1.0 + Math.max(0.0, d.views.toDouble() + d.reactions + d.duration + d.publishedEpoch) }
            }
            source = ChillOpenSearch.bound(docType<WideDoc>(), fn).source
            original = fn
            localSerializer = WideDoc.serializer()
        } else {
            val fn: ChillBound.(Wide12Doc) -> Double = if (access == "one") {
                @ChillLambda { d -> 1.0 + Math.max(0.0, d.views.toDouble()) }
            } else {
                @ChillLambda { d -> 1.0 + Math.max(0.0, d.views.toDouble() + d.reactions + d.duration + d.publishedEpoch + d.shares + d.comments + d.bookmarks + d.channelSize + d.ageDays + d.quality + d.engagement + d.languageScore) }
            }
            source = ChillOpenSearch.bound(docType<Wide12Doc>(), fn).source
            original = fn
            localSerializer = Wide12Doc.serializer()
        }
        @Suppress("UNCHECKED_CAST")
        localFunction = original as Function2<Any?, Any?, Any?>
        val chill = ChillScriptEngine()
        compiled = chill.compileChill("benchmark-codec", source)
        val direct = when (accessedFields.size) {
            1 -> ChillOpenSearch.script(@ChillLambda { 1.0 + Math.max(0.0, doubleVal("views")) })
            4 -> ChillOpenSearch.script(@ChillLambda { 1.0 + Math.max(0.0, doubleVal("views") + doubleVal("reactions") + doubleVal("duration") + doubleVal("publishedEpoch")) })
            else -> ChillOpenSearch.script(@ChillLambda { 1.0 + Math.max(0.0, doubleVal("views") + doubleVal("reactions") + doubleVal("duration") + doubleVal("publishedEpoch") + doubleVal("shares") + doubleVal("comments") + doubleVal("bookmarks") + doubleVal("channelSize") + doubleVal("ageDays") + doubleVal("quality") + doubleVal("engagement") + doubleVal("languageScore")) })
        }
        val painless = PainlessModulePlugin().getScriptEngine(Settings.EMPTY, listOf(ScoreScript.CONTEXT))
        val painlessSource = accessedFields.mapIndexed { n, field ->
            "double v$n = doc.containsKey('$field') && doc['$field'].size() != 0 ? (double)doc['$field'].value : 0.0;"
        }.joinToString(" ") + " return 1.0 + Math.max(0.0, ${accessedFields.indices.joinToString(" + ") { "v$it" }});"
        val cachedPainlessSource = accessedFields.mapIndexed { n, field ->
            "def f$n = doc.containsKey('$field') ? doc['$field'] : null; double v$n = f$n != null && f$n.size() != 0 ? (double)f$n.value : 0.0;"
        }.joinToString(" ") + " return 1.0 + Math.max(0.0, ${accessedFields.indices.joinToString(" + ") { "v$it" }});"
        factories = mapOf(
            "painless" to painless.compile("benchmark", painlessSource, ScoreScript.CONTEXT, emptyMap()),
            "painless_cached" to painless.compile("benchmark-cached", cachedPainlessSource, ScoreScript.CONTEXT, emptyMap()),
            "direct" to chill.compile("benchmark", direct.source, ScoreScript.CONTEXT, emptyMap()),
            "bound" to chill.compile("benchmark", source, ScoreScript.CONTEXT, emptyMap()),
        )
    }

    fun verify(binding: String) {
        check(scan(binding) == expected) { "$binding did not score the synthetic documents correctly" }
    }

    fun scan(binding: String): Double {
        val leaf = reader.leaves().single()
        var sum = 0.0
        val factory = factories[binding]
        if (factory != null) {
            // Fresh iterators per scan: Lucene doc-value iterators cannot rewind to doc zero.
            val script = factory.newFactory(emptyMap(), lookup, searcher).newInstance(leaf)
            for (i in 0 until DOCUMENTS) {
                script.setDocument(i)
                sum += script.execute(null)
            }
            return sum
        }

        val doc = lookup.getLeafSearchLookup(leaf).doc()
        @Suppress("UNCHECKED_CAST")
        val values = doc as Map<String, List<Any?>>
        if (binding == "lazy_control") {
            val view = LazyView(ChillSearchScript(emptyMap(), values, 0.0))
            for (i in 0 until DOCUMENTS) {
                doc.setDocument(i)
                ExecutionBudget.begin(1_000_000)
                try {
                    var total = 0.0
                    for (field in accessedFields) total += view.value(field)
                    sum += 1.0 + Math.max(0.0, total)
                } finally { ExecutionBudget.end() }
            }
            return sum
        }

        require(binding == "codec" || binding == "codec_instrumented")
        val serializer = if (binding == "codec") localSerializer else compiled.slots.single().deserializer!!
        @Suppress("UNCHECKED_CAST")
        val fn = if (binding == "codec") localFunction else compiled.instantiate() as Function2<Any?, Any?, Any?>
        for (i in 0 until DOCUMENTS) {
            doc.setDocument(i)
            ExecutionBudget.begin(1_000_000)
            try {
                val decoded = DocValuesCodec.decode(serializer, values)
                sum += (fn.invoke(ChillBound, decoded) as Number).toDouble()
            } finally { ExecutionBudget.end() }
        }
        return sum
    }

    private class LazyView(private val receiver: ChillSearchScript) {
        fun value(field: String): Double = receiver.doubleVal(field)
    }

    override fun close() {
        reader.close()
        directory.close()
    }
}
