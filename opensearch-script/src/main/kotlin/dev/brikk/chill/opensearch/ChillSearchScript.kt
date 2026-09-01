package dev.brikk.chill.opensearch

/**
 * The receiver every chill OpenSearch script lambda runs against, on both sides:
 * the client compiles lambdas typed `ChillSearchScript.() -> Any?`, and the server plugin
 * constructs an instance per document execution.
 *
 * Successor of the old `EsKotlinScriptTemplate`, trimmed to the supported contexts
 * (score / filter / field):
 *  - [params] - the script params from the query
 *  - [doc]    - doc values for the current document (each field is a list of values)
 *  - [_score] - the current document score (score context only; 0.0 elsewhere)
 *
 * The named helper methods are plain (non-inline) members so that script bytecode calls them
 * *on the receiver*, which the receiver wildcard policy allows - their bodies execute as trusted
 * plugin code. The reified `asList`/`asValue` helpers are inline and their expansions verify
 * against the kotlin.collections package allowances.
 */
class ChillSearchScript(
    val params: Map<String, Any?>,
    val doc: Map<String, List<Any?>>,
    val _score: Double,
) {
    // ---- doc value accessors ----

    fun values(field: String): List<Any?> = doc[field] ?: emptyList()

    fun value(field: String): Any? = values(field).firstOrNull()

    fun stringVal(field: String): String? = value(field)?.toString()
    fun stringVal(field: String, default: String): String = stringVal(field) ?: default
    fun stringVals(field: String): List<String> = values(field).mapNotNull { it?.toString() }

    fun intVal(field: String, default: Int = 0): Int = (value(field) as? Number)?.toInt() ?: default
    fun intVals(field: String): List<Int> = values(field).mapNotNull { (it as? Number)?.toInt() }

    fun longVal(field: String, default: Long = 0L): Long = (value(field) as? Number)?.toLong() ?: default
    fun longVals(field: String): List<Long> = values(field).mapNotNull { (it as? Number)?.toLong() }

    fun doubleVal(field: String, default: Double = 0.0): Double = (value(field) as? Number)?.toDouble() ?: default
    fun doubleVals(field: String): List<Double> = values(field).mapNotNull { (it as? Number)?.toDouble() }

    fun boolVal(field: String, default: Boolean = false): Boolean = value(field) as? Boolean ?: default

    // ---- param accessors ----

    fun param(name: String): Any? = params[name]

    fun paramString(name: String, default: String): String = params[name]?.toString() ?: default
    fun paramInt(name: String, default: Int = 0): Int = (params[name] as? Number)?.toInt() ?: default
    fun paramLong(name: String, default: Long = 0L): Long = (params[name] as? Number)?.toLong() ?: default
    fun paramDouble(name: String, default: Double = 0.0): Double = (params[name] as? Number)?.toDouble() ?: default
    fun paramBool(name: String, default: Boolean = false): Boolean = params[name] as? Boolean ?: default

    // ---- generic casts (inline: expansions are verified script bytecode) ----

    inline fun <reified T : Any> Any?.asList(): List<T> = (this as? List<*>)?.filterIsInstance<T>() ?: emptyList()

    inline fun <reified T : Any> Any?.asValue(): T? = this as? T
    inline fun <reified T : Any> Any?.asValue(default: T): T = this as? T ?: default
}
