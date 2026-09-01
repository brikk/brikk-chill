package dev.brikk.chill.quarantine

import dev.brikk.chill.annotations.ChillVerifyAtBuild

/**
 * Mimics the real-world shape: the serializable lambda is embedded as an argument of a chained
 * DSL call inside a function body. The only place a binary annotation can live for such lambdas
 * is the enclosing function (or class/file), resolved via the EnclosingMethod attribute.
 */
class FakeSearchBuilder {
    var scriptField: (Map<String, List<String>>.() -> Any?)? = null

    fun addScriptField(name: String, lambda: Map<String, List<String>>.() -> Any?): FakeSearchBuilder {
        scriptField = lambda
        return this
    }

    fun setQuery(q: String): FakeSearchBuilder = this
}

class CallSiteFixtures {

    @ChillVerifyAtBuild(enabled = false)
    fun buildSkippedQuery(): FakeSearchBuilder =
        FakeSearchBuilder()
            .addScriptField("scriptField1") @JvmSerializableLambda {
                System.getenv("NOT_CHECKED_HERE")
            }
            .setQuery("title:*")

    @ChillVerifyAtBuild
    fun buildCheckedQuery(): FakeSearchBuilder =
        FakeSearchBuilder()
            .addScriptField("scriptField1") @JvmSerializableLambda {
                val currentValue = this["notes"] ?: emptyList()
                currentValue.size
            }
            .setQuery("title:*")

    fun buildUnmarkedQuery(): FakeSearchBuilder =
        FakeSearchBuilder()
            .addScriptField("scriptField1") @JvmSerializableLambda {
                "unmarked"
            }
            .setQuery("title:*")
}
