package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.opensearch.ChillOpenSearch
import dev.brikk.chill.opensearch.ChillSearchScript
import dev.brikk.chill.opensearch.asIndexScore
import dev.brikk.chill.opensearch.client.chill
import dev.brikk.chill.opensearch.client.chillScriptField
import dev.brikk.chill.opensearch.client.putChillScript
import dev.brikk.chill.opensearch.client.toScript
import dev.brikk.chill.opensearch.docType
import dev.brikk.chill.opensearch.paramOf
import dev.brikk.chill.opensearch.paramType
import dev.brikk.chill.opensearch.scoreType
import dev.brikk.chill.opensearch.sourceType
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

/** `bonus_multiplier` is not in the index mapping at all; its default must apply on both sides. */
@Serializable
class ArticleWithUnmappedField(
    @SerialName("read_count") val reads: Double = 0.0,
    @SerialName("bonus_multiplier") val bonus: Double = 1.5,
)

/** Nullable params: an explicit null must reach the node as null, not fall back to the default. */
@Serializable
class NullableParams(val floor: Double? = 5.0, val label: String? = null)

/** `tags` is a multi-valued keyword: doc values arrive sorted and de-duplicated. */
@Serializable
class TaggedDoc(val tags: List<String> = emptyList(), @SerialName("read_count") val reads: Double = 0.0)

/** `_source` binding: the raw indexed document, including tags in their original order. */
@Serializable
class ArticleSource(
    @SerialName("read_count") val reads: Double = 0.0,
    val tags: List<String> = emptyList(),
    @SerialName("author_id") val authorId: Long = 0,
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
            // small loop budget so the runaway-loop case proves the node setting is honoured
            .withEnv("chill.script.max_loop_iterations", "50000")
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
            "unsorted-tags",
            popularity = 1.0,
            reads = 100.0,
            words = 1000.0,
            featured = 0,
            authorId = 4,
            topicId = 7,
            ageHours = 24 * 200,
            tags = listOf("zeta", "alpha", "zeta", "mid")
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
            assertEquals(expectedScore(f).asIndexScore(), scoresById.getValue(f.id)) { "score mismatch for ${f.id}" }
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
            assertEquals(expectedScore(f).asIndexScore(), scoresById.getValue(f.id))
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
        assertEquals(0.001.asIndexScore(), scores.getValue("penalized-author")) { "captured set must hide author 777" }
        assertEquals(10.0, scores.getValue("fresh-weighted-topic")) { "reads 900 >= captured 500" }
        assertEquals(1.0, scores.getValue("fresh-other-topic")) { "reads 300 < captured 500" }

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
        assertEquals(0.001.asIndexScore(), scores2.getValue("fresh-weighted-topic")) { "captured set now hides author 1" }
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

        // missing required doc field (not even in the mapping) -> kotlinx's error naming the field,
        // not the doc lookup's "No field found ... in mapping"
        val needy = ChillOpenSearch.script(docType<NeedsMissingField>()) @ChillLambda { d -> d.required.length }
        val ex3 =
            assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(needy.toScript()) }
        val text3 = errorText(ex3)
        assertTrue("no_such_field" in text3 && "required" in text3 && "No field found" !in text3) { "got: $text3" }
    }

    @Test
    fun sameBoundScoreRunsLocallyAndInOpenSearch() {
        val ranking = ChillOpenSearch.bound(
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
            assertEquals(localScore.asIndexScore(), remoteScores.getValue(id)) { "local/remote score mismatch for $id" }
        }
    }

    @Test
    fun unmappedDocFieldWithDefaultScoresTheSameLocallyAndRemotely() {
        val ranking = ChillOpenSearch.bound(
            paramOf(rankParams),
            docType<ArticleWithUnmappedField>(),
        ) @ChillLambda { _, d -> d.reads * d.bonus }

        val local = fixtures.associate { it.id to ranking.evaluate(ArticleWithUnmappedField(reads = it.reads)) }
        val remote = scriptScoreSearch(ranking.toScript()).hits().hits().associate { it.id() to it.score()!! }

        assertEquals(local.keys, remote.keys)
        local.forEach { (id, score) ->
            assertEquals(score.asIndexScore(), remote.getValue(id)) { "unmapped-field default must apply server side for $id" }
        }
        // receiver helpers on an unmapped field: default, not an error
        val helper = ChillOpenSearch.script(@ChillLambda { doubleVal("bonus_multiplier", 42.0) })
        val helperScores = scriptScoreSearch(helper.toScript()).hits().hits().map { it.score()!! }.toSet()
        assertEquals(setOf(42.0), helperScores)
    }

    @Test
    fun executionLimitsSurfaceAsScriptErrorsOverHttp() {
        // runaway loop: stopped by the node's chill.script.max_loop_iterations (50k here), per document
        val spin = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            var acc = d.reads
            while (acc >= 0) acc += 1.0
            acc
        }
        val ex1 = assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(spin.toScript()) }
        assertTrue("loop iterations" in errorText(ex1)) { "got: ${errorText(ex1)}" }

        // 100k iterations: fine under the 1M default, over the 50k this node is configured with,
        // so this only passes if the setting reached the engine
        val overBudget = ChillOpenSearch.script(@ChillLambda { var n = 0.0; for (i in 1..100_000) n += 1.0; n })
        val ex1b = assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(overBudget.toScript()) }
        assertTrue("loop iterations" in errorText(ex1b)) { "got: ${errorText(ex1b)}" }

        // a loop under the budget runs normally
        val bounded = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d ->
            var n = 0.0
            for (i in 1..10_000) n += 1.0
            n + d.reads
        }
        val scores = scriptScoreSearch(bounded.toScript()).hits().hits().associate { it.id() to it.score()!! }
        assertEquals(10_000.0 + 900.0, scores.getValue("fresh-weighted-topic"))

        // catastrophic backtracking: cut off by the regex limit factor
        val victim = "a".repeat(40) + "!"
        val bomb = ChillOpenSearch.script(@ChillLambda { if (Regex("(a+)+b").containsMatchIn(victim)) 1.0 else 2.0 })
        val ex2 = assertThrows<org.opensearch.client.opensearch._types.OpenSearchException> { scriptScoreSearch(bomb.toScript()) }
        assertTrue("regular expression exceeded" in errorText(ex2)) { "got: ${errorText(ex2)}" }

        // ordinary regex over doc values keeps working
        val tagged = ChillOpenSearch.script(@ChillLambda { if (stringVals("tags").any { Regex("^how").containsMatchIn(it) }) 5.0 else 1.0 })
        val tagScores = scriptScoreSearch(tagged.toScript()).hits().hits().associate { it.id() to it.score()!! }
        assertEquals(5.0, tagScores.getValue("fresh-weighted-topic"))
        assertEquals(1.0, tagScores.getValue("penalized-author"))
    }

    @Test
    fun storedBoundTemplateRanksOnTheNodeAndRerankLocallyWithTheSameLambda() {
        val template = ChillOpenSearch.bound(paramType<RankParams>(), docType<ArticleDoc>(), scoreType()) @ChillLambda { p, d, score ->
            val boost = 1.0 + (p.topicWeights[d.topicId.toString()] ?: 0.0)
            val penalty = 1.0 - (p.authorPenalties[d.authorId.toString()] ?: 0.0)
            score * d.reads * boost * penalty
        }
        client.putChillScript("chill-bound-rank-v1", template)
        val stored = template.stored("chill-bound-rank-v1").withParams(rankParams)

        val remote = scriptScoreSearch(stored.toScript()).hits().hits().associate { it.id() to it.score()!! }
        val local = fixtures.associate { f ->
            f.id to stored.evaluate(ArticleDoc(f.popularity, f.reads, f.words, f.featured, f.authorId, f.topicId, now.minusHours(f.ageHours)), 1.0)
        }
        assertEquals(local.keys, remote.keys)
        local.forEach { (id, score) -> assertEquals(score.asIndexScore(), remote.getValue(id)) { "stored bound mismatch for $id" } }

        // a bound filter (Boolean) and a bound field (String) run in their contexts too
        val filter = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { d -> d.featured == 1L && d.words >= 1000 }
        val filtered = client.search(
            SearchRequest.of { s -> s.index(index).query(Query.of { q -> q.script { it.script(filter.toScript()) } }) },
            Map::class.java,
        ).hits().hits().map { it.id() }.toSet()
        assertEquals(fixtures.filter { filter.evaluate(ArticleDoc(featured = it.featured, words = it.words, postedAt = now)) }.map { it.id }.toSet(), filtered)
    }

    @Test
    fun parityCaveatsAreExactlyAsDocumented() {
        // 1. explicit null param reaches the node as null (floor default is 5.0; we send null)
        val nullAware = ChillOpenSearch.bound(paramType<NullableParams>(), docType<ArticleDoc>()) @ChillLambda { p, d ->
            if (p.floor == null) 1.0 else if (d.reads >= p.floor) 2.0 else 0.5
        }
        val sentNull = nullAware.withParams(NullableParams(floor = null))
        assertTrue(sentNull.params.containsKey("floor") && sentNull.params["floor"] == null) { sentNull.params.toString() }
        val nullScores = scriptScoreSearch(sentNull.toScript()).hits().hits().map { it.score()!! }.toSet()
        assertEquals(setOf(1.0), nullScores) { "null must not be replaced by the default 5.0 on the node" }
        // and when omitted, the default applies on both sides
        val defaulted = nullAware.withParams(NullableParams())
        val expected = fixtures.associate { f -> f.id to defaulted.evaluate(ArticleDoc(reads = f.reads, postedAt = now)) }
        val actual = scriptScoreSearch(defaulted.toScript()).hits().hits().associate { it.id() to it.score()!! }
        assertEquals(expected, actual)

        // 2. multi-valued keyword doc values: sorted + de-duplicated, unlike _source order
        val joined = ChillOpenSearch.bound(docType<TaggedDoc>()) @ChillLambda { d -> d.tags.joinToString("|") }
        val fromNode = client.search(
            SearchRequest.of { s ->
                s.index(index).query(Query.of { it.ids { i -> i.values("unsorted-tags") } })
                    .scriptFields("t") { sf -> sf.script(joined.toScript()) }
            },
            Map::class.java,
        ).hits().hits().first().fields()["t"]!!.toJson().asJsonArray().getString(0)
        val sourceOrder = fixtures.first { it.id == "unsorted-tags" }.tags
        assertEquals("zeta|alpha|zeta|mid", joined.evaluate(TaggedDoc(tags = sourceOrder)))
        assertEquals("alpha|mid|zeta", fromNode)
        assertEquals(fromNode, joined.evaluate(TaggedDoc(tags = sourceOrder.toSortedSet().toList()))) { "parity holds on the sorted, de-duplicated view" }

        // 3. scores are float32: exact equality holds only after asIndexScore()
        val precise = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { d -> d.reads / 3.0 + 1.0 / 7.0 }
        val remote = scriptScoreSearch(precise.toScript()).hits().hits().associate { it.id() to it.score()!! }
        fixtures.forEach { f ->
            val local = precise.evaluate(ArticleDoc(reads = f.reads, postedAt = now))
            assertTrue(local != remote.getValue(f.id) || local == local.asIndexScore()) { "double-vs-float difference expected for ${f.id}" }
            assertEquals(local.asIndexScore(), remote.getValue(f.id)) { "exact after float rounding for ${f.id}" }
        }
    }

    @Test
    fun typedClientExtensionsBuildEveryContext() {
        val score = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { d -> d.reads / 100.0 }
        val filter = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { d -> d.featured == 1L }
        val label = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { d -> "topic-" + d.topicId }
        // These do not compile, by design (the result type picks the context):
        //   q.scriptScore { it.chill(filter) }   // ChillScript<Boolean> is not a ChillScript<Number>
        //   q.script { it.chill(score) }         // ChillScript<Double> is not a ChillScript<Boolean>

        val response = client.search(
            SearchRequest.of { s ->
                s.index(index)
                    .query(Query.of { q ->
                        q.functionScore { fs ->
                            fs.query(Query.of { qq -> qq.script { it.chill(filter) } })
                                .functions { f -> f.scriptScore { ss -> ss.chill(score) } }
                                .boostMode(org.opensearch.client.opensearch._types.query_dsl.FunctionBoostMode.Replace)
                        }
                    })
                    .chillScriptField("label", label)
            },
            Map::class.java,
        )
        val hits = response.hits().hits()
        assertEquals(fixtures.filter { it.featured == 1L }.map { it.id }.toSet(), hits.map { it.id() }.toSet())
        hits.forEach { hit ->
            val f = fixtures.first { it.id == hit.id() }
            assertEquals(score.evaluate(ArticleDoc(reads = f.reads, postedAt = now)).asIndexScore(), hit.score()!!)
            assertEquals("topic-${f.topicId}", hit.fields()["label"]!!.toJson().asJsonArray().getString(0))
        }

        // script_score query form, and a stored reference through the same extension
        client.putChillScript("typed-ext-v1", ChillOpenSearch.bound(paramType<RankParams>(), docType<ArticleDoc>()) @ChillLambda { p, d -> d.reads * (1 + (p.topicWeights[d.topicId.toString()] ?: 0.0)) })
        val stored = storedChillScript("typed-ext-v1", paramType<RankParams>()).withParams(rankParams)
        val viaScriptScore = client.search(
            SearchRequest.of { s -> s.index(index).query(Query.of { q -> q.scriptScore { ss -> ss.query(Query.of { it.matchAll { m -> m } }).chill(stored) } }) },
            Map::class.java,
        ).hits().hits().first()
        assertEquals("old-weighted-topic", viaScriptScore.id())
    }

    @Test
    fun sourceBindingWorksInScoreFilterAndFieldContexts() {
        val byId = fixtures.associateBy { it.id }

        // score: doc values and _source together, plus the base score
        val score = ChillOpenSearch.bound(docType<ArticleDoc>(), sourceType<ArticleSource>(), scoreType()) @ChillLambda { d, s, base ->
            base * d.reads + s.tags.size * 1000.0
        }
        val scores = scriptScoreSearch(score.toScript()).hits().hits().associate { it.id() to it.score()!! }
        fixtures.forEach { f ->
            val local = score.evaluate(ArticleDoc(reads = f.reads, postedAt = now), ArticleSource(tags = f.tags), 1.0)
            assertEquals(local.asIndexScore(), scores.getValue(f.id)) { "source-bound score for ${f.id}" }
        }

        // filter: _source only (FilterScript has no _source in params; the engine takes its own leaf lookup)
        val filter = ChillOpenSearch.bound(sourceType<ArticleSource>()) @ChillLambda { s -> "evergreen" in s.tags || s.authorId == 777L }
        val filtered = client.search(
            SearchRequest.of { q -> q.index(index).query(Query.of { it.script { sq -> sq.chill(filter) } }) },
            Map::class.java,
        ).hits().hits().map { it.id() }.toSet()
        assertEquals(fixtures.filter { filter.evaluate(ArticleSource(tags = it.tags, authorId = it.authorId)) }.map { it.id }.toSet(), filtered)

        // field: _source keeps original tag order (contrast with the sorted doc values)
        val order = ChillOpenSearch.bound(sourceType<ArticleSource>()) @ChillLambda { s -> s.tags.joinToString("|") }
        val fields = client.search(
            SearchRequest.of { q -> q.index(index).size(10).query(Query.of { it.matchAll { m -> m } }).chillScriptField("order", order) },
            Map::class.java,
        ).hits().hits().associate { it.id() to it.fields()["order"]!!.toJson().asJsonArray().getString(0) }
        assertEquals("zeta|alpha|zeta|mid", fields.getValue("unsorted-tags"))
        fixtures.forEach { f -> assertEquals(order.evaluate(ArticleSource(tags = f.tags)), fields.getValue(f.id)) }
        assertEquals(byId.size, fields.size)
    }

    @Test
    fun captureEdgeCasesFromTheDesignDoc() {
        // captured mutable local: kotlinc wraps it in a kotlin.jvm.internal.Ref.DoubleRef, which
        // must ship, thaw, and read on the node (writes stay local to each leaf instance)
        var threshold = 400.0
        threshold += 100.0
        val mutable = ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d -> if (d.reads >= threshold) 2.0 else 1.0 }
        val scores = scriptScoreSearch(mutable.toScript()).hits().hits().associate { it.id() to it.score()!! }
        fixtures.forEach { f -> assertEquals(if (f.reads >= 500.0) 2.0 else 1.0, scores.getValue(f.id)) { f.id } }

        // captured non-policy object: rejected at freeze, client side, naming the class
        val handle = java.io.File("/etc/hosts")
        val ex = assertThrows<Chill.ClassSerDerViolationsException> {
            ChillOpenSearch.script(docType<ArticleDoc>()) @ChillLambda { d -> if (handle.exists()) d.reads else 0.0 }
        }
        assertTrue(ex.violations.any { it.startsWith("java.io File") }) { ex.violations.toString() }
    }

    @Test
    fun needsScoreIsDerivedFromWhatTheScriptReads() {
        // a base query with a deterministic non-1.0 score: a script reading _score sees it; a bound
        // script with no score slot gets 0.0 from the engine and never asks Lucene to compute one
        val base = Query.of { q ->
            q.constantScore { cs -> cs.filter(Query.of { f -> f.term { t -> t.field("tags").value { v -> v.stringValue("howto") } } }).boost(2.5f) }
        }
        fun run(script: org.opensearch.client.opensearch._types.Script) = client.search(
            SearchRequest.of { s -> s.index(index).query(Query.of { q -> q.scriptScore { ss -> ss.query(base).script(script) } }) },
            Map::class.java,
        ).hits().hits().associate { it.id() to it.score()!! }

        val usesScore = ChillOpenSearch.script(@ChillLambda { _score * 10.0 })
        val ignoresScore = ChillOpenSearch.script(@ChillLambda { _score + 7.0 })
        val boundNoScore = ChillOpenSearch.bound(docType<ArticleDoc>()) @ChillLambda { _ -> 3.0 }

        val withScore = run(usesScore.toScript())
        assertEquals(setOf("fresh-weighted-topic", "old-weighted-topic"), withScore.keys)
        assertEquals(setOf(25.0), withScore.values.toSet()) { "expected constant 2.5 x 10, got $withScore" }
        assertEquals(setOf(9.5), run(ignoresScore.toScript()).values.toSet())
        assertEquals(setOf(3.0), run(boundNoScore.toScript()).values.toSet())
    }
}
