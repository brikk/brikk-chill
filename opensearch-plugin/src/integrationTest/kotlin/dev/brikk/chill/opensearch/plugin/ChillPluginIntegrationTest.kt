package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.client.putChillScript
import dev.brikk.chill.opensearch.client.toScript
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.paramOf
import dev.brikk.chill.opensearch.paramType
import dev.brikk.chill.opensearch.scoreType
import dev.brikk.chill.opensearch.storedChillScript
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.serialize.Chill
import dev.brikk.chill.serialize.ChillLambda
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.opensearch.client.opensearch.OpenSearchClient
import org.opensearch.client.opensearch._types.query_dsl.Query
import org.opensearch.client.opensearch.core.SearchRequest
import org.opensearch.client.opensearch.core.SearchResponse
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max

@Serializable
class RankParams(
    val nowEpochSec: Long,
    val topicWeights: Map<String, Double> = emptyMap(),
    val authorPenalties: Map<String, Double> = emptyMap(),
)

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

@Serializable
class NeedsMissingField(
    @SerialName("no_such_field") val required: String,
    @Contextual @SerialName("posted_at") val postedAt: ZonedDateTime,
)

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChillPluginIntegrationTest {

    companion object {
        private val osVersion = System.getProperty("chill.opensearch.version")!!
        private val zipPath: Path = Path.of(System.getProperty("chill.plugin.zip")!!)

        @Container
        @JvmStatic
        val opensearch: GenericContainer<*> = GenericContainer(
            ImageFromDockerfile("chill-opensearch-it", false)
                .withFileFromPath("chill-plugin.zip", zipPath)
                .withDockerfileFromBuilder { builder ->
                    builder.from("opensearchproject/opensearch:$osVersion")
                        .copy("chill-plugin.zip", "/tmp/chill-plugin.zip")
                        .run("/usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/chill-plugin.zip")
                        .build()
                },
        )
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3))
    }

    private lateinit var client: OpenSearchClient

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)
    private val index = "articles"

    // docs designed so the expected math is easy to compute in the test
    private data class Fixture(
        val id: String,
        val popularity: Double,
        val reads: Double,
        val words: Double,
        val featured: Long,
        val authorId: Long,
        val topicId: Long,
        val ageHours: Long,
        val tags: List<String>
    )

    private val fixtures = listOf(
        Fixture(
            "fresh-weighted-topic",
            popularity = 10.0,
            reads = 900.0,
            words = 1200.0,
            featured = 1,
            authorId = 1,
            topicId = 9,
            ageHours = 24,
            tags = listOf("howto")
        ),
        Fixture(
            "old-weighted-topic",
            popularity = 50.0,
            reads = 8000.0,
            words = 2500.0,
            featured = 1,
            authorId = 2,
            topicId = 9,
            ageHours = 24 * 90,
            tags = listOf("howto", "evergreen")
        ),
        Fixture(
            "fresh-other-topic",
            popularity = 5.0,
            reads = 300.0,
            words = 700.0,
            featured = 0,
            authorId = 3,
            topicId = 5,
            ageHours = 12,
            tags = listOf("misc")
        ),
        Fixture(
            "penalized-author",
            popularity = 80.0,
            reads = 9000.0,
            words = 900.0,
            featured = 1,
            authorId = 777,
            topicId = 5,
            ageHours = 24,
            tags = listOf("listicle")
        ),
    )

    @BeforeAll
    fun setUp() {
        val transport = ApacheHttpClient5TransportBuilder
            .builder(HttpHost("http", opensearch.host, opensearch.getMappedPort(9200)))
            .build()
        client = OpenSearchClient(transport)

        client.indices().create { c ->
            c.index(index).mappings { m ->
                m.properties("popularity_score") { p -> p.double_ { it } }
                    .properties("read_count") { p -> p.double_ { it } }
                    .properties("word_count") { p -> p.double_ { it } }
                    .properties("featured") { p -> p.long_ { it } }
                    .properties("author_id") { p -> p.long_ { it } }
                    .properties("topic_id") { p -> p.long_ { it } }
                    .properties("posted_at") { p -> p.date { it } }
                    .properties("tags") { p -> p.keyword { it } }
            }
        }

        fixtures.forEach { f ->
            client.index { req ->
                req.index(index).id(f.id).document(
                    mapOf(
                        "popularity_score" to f.popularity,
                        "read_count" to f.reads,
                        "word_count" to f.words,
                        "featured" to f.featured,
                        "author_id" to f.authorId,
                        "topic_id" to f.topicId,
                        "posted_at" to now.minusHours(f.ageHours).toInstant().toString(),
                        "tags" to f.tags,
                    ),
                )
            }
        }
        client.indices().refresh { it.index(index) }
    }

    private fun scriptScoreSearch(script: org.opensearch.client.opensearch._types.Script): SearchResponse<Map<*, *>> =
        client.search(
            SearchRequest.of { s ->
                s.index(index).size(10).query(
                    Query.of { q ->
                        q.scriptScore { ss ->
                            ss.query(Query.of { it.matchAll { m -> m } }).script(script)
                        }
                    },
                )
            },
            Map::class.java,
        )

    private val rankParams = RankParams(
        nowEpochSec = now.toEpochSecond(),
        topicWeights = mapOf("9" to 1.5),
        authorPenalties = mapOf("777" to 0.8),
    )

    private fun expectedScore(f: Fixture): Double {
        val ageDays = max(1.0 / 24, f.ageHours / 24.0)
        val freshness = exp(-ageDays / 30.0)
        val topicBoost = 1.0 + (rankParams.topicWeights[f.topicId.toString()] ?: 0.0)
        val authorFactor = max(0.05, 1.0 - (rankParams.authorPenalties[f.authorId.toString()] ?: 0.0))
        val lengthGate = if (f.words < 800) 0.7 else 1.0
        val featuredFactor = if (f.featured == 1L) 1.15 else 1.0
        return freshness * topicBoost * authorFactor * lengthGate * featuredFactor
    }

    private val rankingTemplate =
        ChillOpenSearch.script(paramType<RankParams>(), docType<ArticleDoc>()) @ChillLambda { p, d ->
            val ageDays = max(1.0 / 24, (p.nowEpochSec - d.postedAt.toEpochSecond()) / 86400.0)
            val freshness = exp(-ageDays / 30.0)
            val topicBoost = 1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0)
            val authorFactor = max(0.05, 1.0 - (p.authorPenalties[d.authorId.toString()] ?: 0.0))
            val lengthGate = if (d.words < 800) 0.7 else 1.0
            val featuredFactor = if (d.featured == 1L) 1.15 else 1.0
            freshness * topicBoost * authorFactor * lengthGate * featuredFactor
        }

    @Test
    fun readyScriptScoresMatchTheSameMathComputedLocally() {
        val ready = ChillOpenSearch.script(paramOf(rankParams), docType<ArticleDoc>()) @ChillLambda { p, d ->
            val ageDays = max(1.0 / 24, (p.nowEpochSec - d.postedAt.toEpochSecond()) / 86400.0)
            val freshness = exp(-ageDays / 30.0)
            val topicBoost = 1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0)
            val authorFactor = max(0.05, 1.0 - (p.authorPenalties[d.authorId.toString()] ?: 0.0))
            val lengthGate = if (d.words < 800) 0.7 else 1.0
            val featuredFactor = if (d.featured == 1L) 1.15 else 1.0
            freshness * topicBoost * authorFactor * lengthGate * featuredFactor
        }

        val response = scriptScoreSearch(ready.toScript())
        val scoresById = response.hits().hits().associate { it.id() to it.score()!! }

        assertEquals(fixtures.size, scoresById.size)
        fixtures.forEach { f ->
            assertEquals(expectedScore(f), scoresById.getValue(f.id), 1e-5) { "score mismatch for ${f.id}" }
        }
        // weighted+fresh outranks the penalized author despite lower engagement
        val ranked = response.hits().hits().map { it.id() }
        assertTrue(ranked.indexOf("fresh-weighted-topic") < ranked.indexOf("penalized-author"))
    }

    @Test
    fun templateWithDifferentParamsReordersResults() {
        val penalties = mapOf("777" to 0.8)
        val weightNine = rankingTemplate.withParams(
            RankParams(
                now.toEpochSecond(),
                topicWeights = mapOf("9" to 5.0),
                authorPenalties = penalties
            )
        )
        val weightFive = rankingTemplate.withParams(
            RankParams(
                now.toEpochSecond(),
                topicWeights = mapOf("5" to 5.0),
                authorPenalties = penalties
            )
        )

        assertEquals(weightNine.source, weightFive.source) { "params must never change the source" }

        val topWithNine = scriptScoreSearch(weightNine.toScript()).hits().hits().first().id()
        val topWithFive = scriptScoreSearch(weightFive.toScript()).hits().hits().first().id()

        assertEquals("fresh-weighted-topic", topWithNine)
        assertEquals("fresh-other-topic", topWithFive)
    }

    @Test
    fun storedScriptRoundTripAndFirstUseRejection() {
        client.putChillScript("chill-ranking-v1", rankingTemplate)

        val ref = storedChillScript("chill-ranking-v1", paramType<RankParams>())
        val response = scriptScoreSearch(ref.withParams(rankParams).toScript())
        val scoresById = response.hits().hits().associate { it.id() to it.score()!! }
        fixtures.forEach { f ->
            assertEquals(expectedScore(f), scoresById.getValue(f.id), 1e-5)
        }

        // OpenSearch 3.x does NOT compile custom-language stored scripts at PUT time: a hostile
        // stored payload is accepted into cluster state but rejected at first USE (compile),
        // before anything executes
        val permissive = setOf(
            PolicyAllowance.ClassLevel.ClassMethodAccess(
                "java.lang.System",
                "*",
                "*",
                setOf(AccessTypes.call_Class_Static_Method)
            ),
        ).flatMap { it.asPolicyStrings() }.toSet()
        val hostileSource = Chill(ChillOpenSearch.quarantine).serializeLambdaToBase64(
            ChillSearchScript::class, Any::class, permissive,
            lambda = @ChillLambda { System.getenv("PATH") ?: "" },
        )
        client.putScript { req ->
            req.id("chill-hostile").script { s -> s.lang { l -> l.custom("chill") }.source(hostileSource) }
        }
        val hostileRef =
            org.opensearch.client.opensearch._types.Script.of { s -> s.stored { st -> st.id("chill-hostile") } }
        val ex =
            assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(hostileRef) }
        assertTrue("System.getenv" in errorText(ex)) { "got: ${errorText(ex)}" }
    }

    private fun errorText(ex: org.opensearch.client.opensearch._types.OpenSearchException): String {
        val sb = StringBuilder()
        fun walk(cause: org.opensearch.client.opensearch._types.ErrorCause?) {
            if (cause == null) return
            sb.append(cause.type()).append(": ").append(cause.reason()).append('\n')
            cause.metadata()
                .forEach { (k, v) -> sb.append("  ").append(k).append(" = ").append(v.toString()).append('\n') }
            cause.rootCause().forEach { walk(it) }
            walk(cause.causedBy())
            cause.suppressed().forEach { walk(it) }
        }
        walk(ex.error())
        return sb.toString()
    }

    @Test
    fun capturedValuesAffectRealQueryScores() {
        // captured from the enclosing scope: primitive threshold + a set of hidden authors
        val hiddenAuthors = setOf(777L)
        val minReads = 500.0

        val ready = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            when {
                d.authorId in hiddenAuthors -> 0.001
                d.reads >= minReads -> 10.0
                else -> 1.0
            }
        }

        val scores = scriptScoreSearch(ready.toScript()).hits().hits().associate { it.id() to it.score()!! }
        assertEquals(0.001, scores.getValue("penalized-author"), 1e-6) { "captured set must hide author 777" }
        assertEquals(10.0, scores.getValue("fresh-weighted-topic"), 1e-6) { "reads 900 >= captured 500" }
        assertEquals(1.0, scores.getValue("fresh-other-topic"), 1e-6) { "reads 300 < captured 500" }

        // different captured values -> different source (distinct scripts to the compile cache)
        val otherHidden = setOf(1L)
        val ready2 = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            when {
                d.authorId in otherHidden -> 0.001
                d.reads >= minReads -> 10.0
                else -> 1.0
            }
        }
        assertTrue(ready.source != ready2.source)
        val scores2 = scriptScoreSearch(ready2.toScript()).hits().hits().associate { it.id() to it.score()!! }
        assertEquals(0.001, scores2.getValue("fresh-weighted-topic"), 1e-6) { "captured set now hides author 1" }
    }

    @Test
    fun filterAndFieldContextsWork() {
        // filter: script query (FilterScript context)
        val filter = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d -> d.reads > 500.0 }
        val filtered = client.search(
            SearchRequest.of { s ->
                s.index(index).query(Query.of { q -> q.script { it.script(filter.toScript()) } })
            },
            Map::class.java,
        )
        assertEquals(
            setOf("fresh-weighted-topic", "old-weighted-topic", "penalized-author"),
            filtered.hits().hits().map { it.id() }.toSet(),
        )

        // field: script_fields (FieldScript context), doc + receiver helpers + capture together
        val prefix = "chill"
        val field = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            prefix + ":" + d.topicId + ":" + stringVals("tags").sorted().joinToString("|")
        }
        val withFields = client.search(
            SearchRequest.of { s ->
                s.index(index).query(Query.of { it.ids { i -> i.values("old-weighted-topic") } })
                    .scriptFields("computed") { sf -> sf.script(field.toScript()) }
            },
            Map::class.java,
        )
        val computed = withFields.hits().hits().first().fields()["computed"]!!.toJson().asJsonArray()
        assertEquals("chill:9:evergreen|howto", computed.getString(0))
    }

    @Test
    fun rejectionsSurfaceCleanlyOverHttp() {
        // violating inline script
        val permissive = setOf(
            PolicyAllowance.ClassLevel.ClassMethodAccess(
                "java.lang.System",
                "*",
                "*",
                setOf(AccessTypes.call_Class_Static_Method)
            ),
        ).flatMap { it.asPolicyStrings() }.toSet()
        val hostile = Chill(ChillOpenSearch.quarantine).serializeLambdaToBase64(
            ChillSearchScript::class, Any::class, permissive,
            lambda = @ChillLambda { System.getenv("HOME") ?: "" },
        )
        val hostileScript = org.opensearch.client.opensearch._types.Script.of { s ->
            s.inline { i -> i.lang { l -> l.custom("chill") }.source(hostile) }
        }
        val ex1 =
            assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(hostileScript) }
        assertTrue("System.getenv" in errorText(ex1)) { "got: ${errorText(ex1)}" }

        // non-chill source
        val garbage = org.opensearch.client.opensearch._types.Script.of { s ->
            s.inline { i -> i.lang { l -> l.custom("chill") }.source("doc['read_count'].value * 2") }
        }
        val ex2 =
            assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(garbage) }
        assertTrue("chill" in errorText(ex2)) { "got: ${errorText(ex2)}" }

        // missing required doc field -> loud, named
        val needy = ChillOpenSearch.script(docType<NeedsMissingField>()) @ChillLambda { d -> d.required.length }
        val ex3 =
            assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(needy.toScript()) }
        assertTrue("no_such_field" in errorText(ex3)) { "got: ${errorText(ex3)}" }
    }

    @Test
    fun sameBoundScoreRunsLocallyAndInOpenSearch() {
        val ranking = ChillOpenSearch.boundScore(
            paramOf(rankParams),
            docType<ArticleDoc>(),
            scoreType(),
        ) @ChillLambda { p, d, score ->
            score * d.reads * (1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0))
        }

        // These are documents the client already has in hand. The match_all base score is 1.0.
        val localScores = fixtures.associate { fixture ->
            val document = ArticleDoc(
                fixture.popularity,
                fixture.reads,
                fixture.words,
                fixture.featured,
                fixture.authorId,
                fixture.topicId,
                now.minusHours(fixture.ageHours),
            )
            fixture.id to ranking.evaluate(document, 1.0)
        }

        val remoteScores = scriptScoreSearch(ranking.toScript()).hits().hits().associate { it.id() to it.score()!! }

        assertEquals(localScores.keys, remoteScores.keys)
        localScores.forEach { (id, localScore) ->
            assertEquals(localScore, remoteScores.getValue(id), 1e-3) { "local/remote score mismatch for $id" }
        }
    }
}
