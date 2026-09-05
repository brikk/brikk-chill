package dev.brikk.chill.opensearch.benchmark

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillScript
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.paramType
import dev.brikk.chill.opensearch.plugin.ChillScriptEngine
import dev.brikk.chill.serialize.ChillLambda
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
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class MixedInputs(
    val i0: Int, val i1: Int = 0, val i2: Int = 0, val i3: Int = 0, val i4: Int = 0,
    val l0: Long = 0, val l1: Long = 0, val l2: Long = 0, val l3: Long = 0, val l4: Long = 0,
)

@Serializable
data class LookupInputs(val i0: Int, val l3: Long = 0)

@Serializable
data class LookupParameters(val t0: Map<String, Double>)

@Serializable
data class MixedParameters(
    val t0: Map<String, Double>, val t1: Map<String, Double>,
    val t2: Map<String, Double>, val t3: Map<String, Double>,
)

/** Deliberately synthetic arithmetic, not an application ranking formula. */
object SyntheticMath {
    fun mixed(i0: Int, i1: Int, i2: Int, l0: Long, l1: Long, l2: Long,
              a0: Double, a1: Double, a2: Double, a3: Double): Double {
        val magnitude = Math.log1p(l0.toDouble()) + Math.sqrt(i1.toDouble() + 1.0)
        val ratio = (l1.toDouble() + 1.0) / (l2.toDouble() + i2 + 2.0)
        val factor = when (i0 % 3) { 0 -> 1.0; 1 -> 1.5; else -> 2.0 }
        return 1.0 + magnitude * factor + ratio + a0 + a1 + a2 + a3
    }

    fun lookup(i0: Int, a0: Double): Double = 1.0 + Math.log1p(a0 * a0 + i0.toDouble() * i0)
}

class WorkloadFixture(scenario: String, missingPercent: Int, hitPercent: Int, mapSize: Int) : AutoCloseable {
    companion object { const val DOCUMENTS = 65_536 }

    private val directory = ByteBuffersDirectory()
    private val reader: DirectoryReader
    private val lookup: SearchLookup
    private val searcher: IndexSearcher
    private val factories: Map<String, ScoreScript.Factory>
    private val leafFactories: Map<String, ScoreScript.LeafFactory>
    private val wireParams: Map<String, Any>
    private val expected = DoubleArray(DOCUMENTS)

    init {
        require(scenario in setOf("fields10", "mixed10", "lookup2"))
        require(missingPercent in 0..100 && hitPercent in 0..100 && mapSize > 0)
        val boundFields = (0..4).map { "i$it" } + (0..4).map { "l$it" }
        val fields = boundFields + (0 until 19).map { "u$it" }
        val tables = (0..3).map { table ->
            (0 until mapSize).associate { (10_000 + it).toString() to ((it + table * 3) % 17 + 1) / 4.0 }
        }
        val parameters = MixedParameters(tables[0], tables[1], tables[2], tables[3])
        IndexWriter(directory, IndexWriterConfig()).use { writer ->
            repeat(DOCUMENTS) { row ->
                val values = LongArray(10)
                val document = Document()
                fields.forEachIndexed { column, field ->
                    val seed = (row * 1103515245L + column * 12345L + 17L) and 0x7fffffffL
                    val value = when (column) {
                        0 -> row % 3L
                        1, 2 -> seed % 4096
                        3, 4, 8, 9 -> (if (seed % 100 < hitPercent) 10_000L else 1_000_000L) + seed % mapSize
                        else -> 1000 + seed % 1_000_000
                    }
                    val present = column == 0 || column >= 10 || (row + column * 13) % 100 >= missingPercent
                    if (present) {
                        document.add(SortedNumericDocValuesField(field, value))
                        if (column < 10) values[column] = value
                    }
                }
                writer.addDocument(document)
                val a0 = tables[0][values[8].toString()] ?: 0.0
                expected[row] = when (scenario) {
                    "fields10" -> 1.0 + values.sum()
                    "lookup2" -> SyntheticMath.lookup(values[0].toInt(), a0)
                    else -> SyntheticMath.mixed(
                        values[0].toInt(), values[1].toInt(), values[2].toInt(), values[5], values[6], values[7], a0,
                        tables[1][values[9].toString()] ?: 0.0,
                        tables[2][values[3].toString()] ?: 0.0,
                        tables[3][values[4].toString()] ?: 0.0,
                    )
                }
            }
            writer.forceMerge(1)
            writer.commit()
        }
        reader = DirectoryReader.open(directory)
        searcher = IndexSearcher(reader)
        check(reader.leaves().size == 1 && reader.maxDoc() == DOCUMENTS)
        val mapper = Mockito.mock(MapperService::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))
        for (field in fields) {
            val type = if (field.startsWith("i")) NumberFieldMapper.NumberType.INTEGER else NumberFieldMapper.NumberType.LONG
            Mockito.`when`(mapper.fieldType(field)).thenReturn(NumberFieldMapper.NumberFieldType(field, type))
        }
        val fieldData = fields.associateWith {
            SortedNumericIndexFieldData(it, if (it.startsWith("i")) IndexNumericFieldData.NumericType.INT else IndexNumericFieldData.NumericType.LONG)
        }
        lookup = SearchLookup(mapper, { type, _ -> fieldData.getValue(type.name()) }, 0)

        val bound: ChillScript<Double> = when (scenario) {
            "fields10" -> ChillOpenSearch.bound(docType<MixedInputs>()) @ChillLambda { d ->
                1.0 + d.i0 + d.i1 + d.i2 + d.i3 + d.i4 + d.l0 + d.l1 + d.l2 + d.l3 + d.l4
            }
            "lookup2" -> ChillOpenSearch.bound(paramType<LookupParameters>(), docType<LookupInputs>()) @ChillLambda { p, d ->
                SyntheticMath.lookup(d.i0, p.t0[d.l3.toString()] ?: 0.0)
            }.withParams(LookupParameters(tables[0]))
            else -> ChillOpenSearch.bound(paramType<MixedParameters>(), docType<MixedInputs>()) @ChillLambda { p, d ->
                SyntheticMath.mixed(d.i0, d.i1, d.i2, d.l0, d.l1, d.l2,
                    p.t0[d.l3.toString()] ?: 0.0, p.t1[d.l4.toString()] ?: 0.0,
                    p.t2[d.i3.toString()] ?: 0.0, p.t3[d.i4.toString()] ?: 0.0)
            }.withParams(parameters)
        }
        wireParams = bound.params.mapValues { requireNotNull(it.value) }
        check(scenario != "fields10" || wireParams.isEmpty())
        val direct = when (scenario) {
            "fields10" -> ChillOpenSearch.script(@ChillLambda {
                1.0 + intVal("i0") + intVal("i1") + intVal("i2") + intVal("i3") + intVal("i4") +
                    longVal("l0") + longVal("l1") + longVal("l2") + longVal("l3") + longVal("l4")
            })
            "lookup2" -> ChillOpenSearch.script(@ChillLambda {
                val i0 = intVal("i0")
                val l3 = longVal("l3")
                val a0 = ((param("t0") as Map<*, *>)[l3.toString()] as? Number)?.toDouble() ?: 0.0
                SyntheticMath.lookup(i0, a0)
            })
            else -> ChillOpenSearch.script(@ChillLambda {
                val i0 = intVal("i0"); val i1 = intVal("i1"); val i2 = intVal("i2")
                val i3 = intVal("i3"); val i4 = intVal("i4")
                val l0 = longVal("l0"); val l1 = longVal("l1"); val l2 = longVal("l2")
                val l3 = longVal("l3"); val l4 = longVal("l4")
                val a0 = ((param("t0") as Map<*, *>)[l3.toString()] as? Number)?.toDouble() ?: 0.0
                val a1 = ((param("t1") as Map<*, *>)[l4.toString()] as? Number)?.toDouble() ?: 0.0
                val a2 = ((param("t2") as Map<*, *>)[i3.toString()] as? Number)?.toDouble() ?: 0.0
                val a3 = ((param("t3") as Map<*, *>)[i4.toString()] as? Number)?.toDouble() ?: 0.0
                SyntheticMath.mixed(i0, i1, i2, l0, l1, l2, a0, a1, a2, a3)
            })
        }
        val selected = if (scenario == "lookup2") listOf("i0", "l3") else boundFields
        fun painlessSource(cached: Boolean): String {
            val declarations = selected.mapIndexed { n, field ->
                val type = if (field.startsWith("i")) "int" else "long"
                if (cached) {
                    "def f$n = doc.containsKey('$field') ? doc['$field'] : null; $type $field = f$n != null && f$n.size() != 0 ? ($type)f$n.value : 0;"
                } else {
                    "$type $field = doc.containsKey('$field') && doc['$field'].size() != 0 ? ($type)doc['$field'].value : 0;"
                }
            }.joinToString("\n")
            if (scenario == "fields10") return declarations + "\nreturn 1.0 + ${selected.joinToString(" + ")};"
            val keys = if (scenario == "lookup2") listOf("l3") else listOf("l3", "l4", "i3", "i4")
            val weights = keys.mapIndexed { n, key ->
                "def w$n = params.t$n.get(String.valueOf($key)); double a$n = w$n == null ? 0.0 : (double)w$n;"
            }.joinToString("\n")
            val calculation = if (scenario == "lookup2") {
                "return 1.0 + Math.log1p(a0 * a0 + (double)i0 * i0);"
            } else {
                """
                double magnitude = Math.log1p((double)l0) + Math.sqrt((double)i1 + 1.0);
                double ratio = ((double)l1 + 1.0) / ((double)l2 + i2 + 2.0);
                double factor = i0 % 3 == 0 ? 1.0 : (i0 % 3 == 1 ? 1.5 : 2.0);
                return 1.0 + magnitude * factor + ratio + a0 + a1 + a2 + a3;
                """.trimIndent()
            }
            return declarations + "\n" + weights + "\n" + calculation
        }
        val chill = ChillScriptEngine()
        val painless = PainlessModulePlugin().getScriptEngine(Settings.EMPTY, listOf(ScoreScript.CONTEXT))
        factories = mapOf(
            "painless" to painless.compile("workload", painlessSource(false), ScoreScript.CONTEXT, emptyMap()),
            "painless_cached" to painless.compile("workload-cached", painlessSource(true), ScoreScript.CONTEXT, emptyMap()),
            "direct" to chill.compile("workload", direct.source, ScoreScript.CONTEXT, emptyMap()),
            "bound" to chill.compile("workload", bound.source, ScoreScript.CONTEXT, emptyMap()),
        )
        // One query's params are shared across its leaf instances, not decoded for every segment.
        leafFactories = factories.mapValues { newQuery(it.key) }
    }

    fun newQuery(binding: String): ScoreScript.LeafFactory =
        factories.getValue(binding).newFactory(wireParams, lookup, searcher)

    fun verify(binding: String) {
        val script = leafFactories.getValue(binding).newInstance(reader.leaves().single())
        for (i in 0 until DOCUMENTS) {
            script.setDocument(i)
            val actual = script.execute(null)
            check(actual.isFinite() && abs(actual - expected[i]) <= max(1e-10, abs(expected[i]) * 1e-12)) {
                "$binding: row $i expected ${expected[i]}, got $actual"
            }
        }
    }

    fun scan(binding: String): Double {
        val script = leafFactories.getValue(binding).newInstance(reader.leaves().single())
        var sum = 0.0
        for (i in 0 until DOCUMENTS) {
            script.setDocument(i)
            sum += script.execute(null)
        }
        return sum
    }

    override fun close() {
        reader.close()
        directory.close()
    }
}
