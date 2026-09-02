# Chill OpenSearch scripting — design

Status: **implemented** (unit + real-node integration tested). Deviations found during
implementation are folded in below and marked *(as-built)*.

## 1. Modules

Two OpenSearch modules, one conceptual split:

| module | role | key dependencies |
|---|---|---|
| `opensearch-script` | **client side + shared**: the script context class, binding slot API, freeze (`script(...)`), `ChillScript`/`ChillScriptTemplate` types, opensearch-java client extensions | `serialize` (api), `kotlinx-serialization-core` (api), `opensearch-java` (**compileOnly** — users bring their own client; extensions only resolve when it is present) |
| `opensearch-plugin` | **server side**: `ScriptPlugin`/`ScriptEngine` for lang `"chill"`; receives payloads, verifies (cached), decodes bound slots, executes | `opensearch-script` (api), `org.opensearch:opensearch` (compileOnly) |

Because `opensearch-java` is compileOnly in `opensearch-script`, the plugin zip never bundles the
client, and client apps never drag server types.

## 2. Two script kinds, enforced by type

The core rule: **param values never enter the frozen payload.** The payload (the `source` string)
is a function of the lambda + bound *types* only, so OpenSearch's compile cache gets one compile
per unique script regardless of param values, and the same source works inline or stored.

That yields two distinct result types, and the slot you pass picks between them at compile time:

```kotlin
// READY TO RUN: params value supplied now -> ChillScript (source + encoded params)
val ready: ChillScript =
    ChillOpenSearch.script(paramOf(ranking), docType<ArticleDoc>()) @ChillLambda { p, d -> ... }

// REUSABLE: params type only -> ChillScriptTemplate<RankParams> (source, no params yet)
val template: ChillScriptTemplate<RankParams> =
    ChillOpenSearch.script(paramType<RankParams>(), docType<ArticleDoc>()) @ChillLambda { p, d -> ... }

// a template becomes runnable only by supplying its declared params type:
val readyToo: ChillScript = template.withParams(ranking)
```

Misuse is unrepresentable:

- `paramOf(value)` slots produce `ChillScript`; `paramType<P>()` slots produce
  `ChillScriptTemplate<P>` — different overloads, different return types.
- A `ChillScriptTemplate<P>` has no way into a query without `withParams(p: P)` (typed).
- `withParams` on a ready script does not exist; `ChillScript` is final and complete.
- Scripts with no params slot are trivially ready (`ChillScript` with empty params).
- `docType<D>()` / `sourceType<S>()` are always type-only (there is no document at build time).

Both kinds produce the **identical source string** for the same lambda: `template.withParams(x)`
and `script(paramOf(x), ...)` with the same lambda hit the same server-side compile cache entry.

The source string is `chill~~` + base64 of a deflated envelope; shipped class bytes carry no debug
attributes. OpenSearch caps inline and stored scripts at `script.max_size_in_bytes` (64 KiB by
default); the representative two-class bound script is ~10 KB.

### Types

```kotlin
class ChillScript internal constructor(
    val source: String,               // the chill~~<base64> payload
    val params: Map<String, Any?>,    // kotlinx-encoded from the params instance (empty if unbound)
) {
    companion object { const val LANG = "chill" }
}

class ChillScriptTemplate<P : Any> internal constructor(
    val source: String,
    internal val paramsSerializer: KSerializer<P>,
) {
    fun withParams(params: P): ChillScript
}
```

## 3. Slot API

```kotlin
fun <P : Any> paramOf(value: P): ParamValueSlot<P>       // ready-to-run params
inline fun <reified P : Any> paramType(): ParamTypeSlot<P>  // reusable params
inline fun <reified D : Any> docType(): DocSlot<D>          // doc values binding
inline fun <reified S : Any> sourceType(): SourceSlot<S>    // _source binding (discouraged; costly)
```

`ChillOpenSearch.script(...)` overloads by slot count (0–3 slots + lambda); slot *kinds* within a
call are validated at freeze time (two doc slots, params slot twice, etc. fail immediately,
client side). Lambda parameters arrive in slot order and are named by the user:

```kotlin
script(paramOf(ranking), docType<ArticleDoc>()) @ChillLambda { p, d -> ... }
script(docType<ArticleDoc>()) @ChillLambda { video -> ... }
script(paramType<Weights>(), docType<ArticleDoc>(), sourceType<RawVideo>()) @ChillLambda { w, d, s -> ... }
```

Slots are orthogonal to **lexical capture**: the lambda remains a real closure, and values captured
from the surrounding scope ship inside the frozen payload (as constructor state of the lambda
class), exactly as chill has always done — the captured object graph is traced, name-checked
against policy at freeze, and policy-gated again at thaw:

```kotlin
val floor = 5
val labels = setOf("draft", "archived")

val ready = script(docType<ArticleDoc>()) @ChillLambda { d ->
    if (d.reads < floor || labels.any { it in d.tags }) 0.0 else d.popularity
}
```

Captures suit fixed, build-time constants; a params slot suits per-query values (captures bake
into the source string, so distinct captured values are distinct scripts to the compile cache —
another reason params-as-slot is the default for anything that varies).

The receiver stays the one common, unbound context — near-native OpenSearch feel — regardless of
which slots are bound:

```kotlin
abstract class ChillSearchScript {
    val doc: Map<String, List<Any?>>     // raw doc values, doc["field"]
    val params: Map<String, Any?>        // raw script params
    val _score: Double                   // score context only; 0.0 elsewhere
    // old-style map helpers: doc.doubleVal("f", 0.0), doc.stringVals("tags"),
    // params.intVal("floor", 0), asList<T>(), asValue<T>() ...
}
```

No renames, no bound properties on the context: bindings are lambda parameters only.

## 4. Bindings: kotlinx.serialization

Bound classes are ordinary `@Serializable` classes. Their compiler-generated `Companion` and
`$serializer` classes ship with the frozen lambda and are **byte-verified like everything else**
— extending nothing, trusted for nothing. The kotlinx-serialization-core runtime is whitelisted
by a policy **generated at build time** by scanning the jar (same treatment as kotlin-stdlib),
never by hand-listing and never at runtime. Both policies ship as
`META-INF/chill/policy/<name>.ctena` resources in the `quarantine` jar (`LibraryPolicies`), so
client and server hold the identical policy for the identical library versions.

To run against a library version this build has not seen, regenerate the named policy from your
own dependencies with the Gradle plugin (`chill { policies { register("kotlin-stdlib") { jars.from(...) } } }`
-> `build/chill/policy/<name>.ctena`; `chillVerifyLambdas` uses it automatically) and drop the file
where `ChillPolicyLoader` looks: on the server, the
plugin's config directory (`config/chill-script/<name>.ctena`); on the client, an explicit
`ChillPolicyLoader.overrideDirectory` or the `chill.policy.dir` system property. An override
**replaces** the shipped policy of that name. Without an override, version skew fails closed
(a member the shipped policy does not know is rejected at freeze).

```kotlin
@Serializable
class RankParams(
    val nowEpochSec: Long,
    val minReads: Int = 0,                                // default = missing-param policy
    val topicWeights: Map<String, Double> = emptyMap(),   // params are anything JSON'able
)

@Serializable
class ArticleDoc(
    @SerialName("popularity_score") val popularity: Double = 0.0, // rename + missing-field default
    val reads: Double = 0.0,
    @SerialName("posted_at") val postedAt: ZonedDateTime,
)
```

### Decoder rules (the doc-values format)

| situation | behavior |
|---|---|
| missing field, property has default | default used (kotlinx semantics) |
| missing field, no default | `MissingFieldException` naming the field |
| numeric doc value into wider/narrower numeric property | `Number` widening/narrowing silently (Long→Double etc.) |
| any other type mismatch | loud `SerializationException` with field, expected, actual |
| date doc value | binds to **`ZonedDateTime` only** (the native doc-values type). Derivations (`.toEpochSecond()`, `.toInstant()`) are the script's business. No implicit `Long` epoch binding — no hidden unit conventions. |
| scalar property vs list doc value | scalar takes first value; `List<T>` property takes all |

Decode costs: params decode **once per query** (at factory creation); doc decodes per document;
source decodes per document *and* forces `_source` loading — supported, documented as expensive.

The same format runs in reverse for `ChillScript.params`: the params instance is kotlinx-encoded
to the `Map<String, Any?>` OpenSearch expects, so one `@Serializable` class defines the contract
on both ends (no hand-built `mapOf(... JsonData.of(...))` transcription).

## 5. Client extensions (opensearch-java)

Small extension surface resolving the chain from `ChillScript` onward:

```kotlin
// conversion
fun ChillScript.toScript(): org.opensearch.client.opensearch._types.Script   // inline, lang "chill"

// query construction
fun ScriptScoreQuery.Builder.chill(script: ChillScript): ScriptScoreQuery.Builder
fun FunctionScore.Builder.chill(script: ChillScript): FunctionScore.Builder
fun SearchRequest.Builder.chillScriptField(name: String, script: ChillScript): SearchRequest.Builder
```

Usage in a search-ranking-shaped call:

```kotlin
val ready = template.withParams(rankingParams)
FunctionScore.Builder().chill(ready).build()
```

## 6. Stored scripts

Same mechanism, zero plugin changes: a stored script is just `lang` + `source` kept in cluster
state, and the engine's `compile()` is invoked identically for stored and inline scripts (name =
the stored id instead of null). Verification and caching behave the same.

Stored scripts pair naturally with **templates** (store the value-free source once, send params
per query):

```kotlin
// register (typically at deploy time)
client.putChillScript("score-creator-band-v1", template)   // PUT _scripts/score-creator-band-v1

// reference per query with typed params
val ref: ChillScriptRef = storedChillScript("score-creator-band-v1", paramType<RankParams>())
FunctionScore.Builder().chill(ref.withParams(ranking)).build()
```

`ChillScriptRef.withParams` produces the stored-reference form of the client `Script`
(id + params) rather than inline source. Storing a *ready* script is deliberately not offered:
stored + baked params is a footgun (two sources of params truth).

*(as-built)* OpenSearch 3.x does **not** compile custom-language stored scripts at PUT time: a
policy-violating payload is accepted into cluster state and rejected at **first use** (compile,
before anything executes), with the violations in the error. Verified by integration test.

## 7. Server flow and caching

```
compile(name, source, context, params)          [invoked by OpenSearch; cached by OS per source]
  ├─ prefix check (chill~~) ......................... reject with usage guidance
  ├─ deserFromPrefixedBase64 ........................ envelope + HMAC integrity
  │    └─ verifyClassAgainstPolicies ................ scan cache + verify-result memo (existing)
  ├─ slot descriptors from envelope ................. kinds + bound class names
  ├─ ScriptClassLoader .............................. defines only verified shipped classes
  ├─ serializer resolution per bound class .......... trusted engine code, once per compile
  └─ context factory (score/filter/field)
       ├─ newFactory: decode params ................. once per query
       └─ newInstance (per leaf): thaw lambda ....... policy-gated deserialization (existing)
            └─ execute (per doc): decode doc slot, invoke lambda with (slots...) on the context
```

Caching layers, outermost first: OpenSearch script compile cache (per source) → chill verify-result
memo (per class-set + policy) → scan cache (per class bytes). Verification of a repeated script
costs a map lookup.

## 7a. Execution limits

Verification bounds *what* a script may reference; execution limits bound *how long* it runs.
After verification and before any class is defined, the plugin instruments the shipped bytes
(`ExecutionLimitInstrumenter`, in `quarantine`):

- every backward branch calls `ExecutionBudget.tick()`; the engine arms a per-thread budget
  before each document, so all loops, helpers and recursion in one execution share
  `chill.script.max_loop_iterations` (default 1,000,000)
- every regex operation's `CharSequence` input (`kotlin.text.Regex`, `StringsKt` regex
  extensions, `Pattern`/`Matcher`) is wrapped in `LimitedCharSequence`, which aborts once
  character reads exceed `chill.script.regex_limit_factor` x input length (default 6; 0 disables
  regex); `Pattern.asPredicate()` is redirected to a limited predicate

Both surface as `ScriptException` for that document. Same model as Painless's
`max_loop_counter` and `regex.limit-factor`, applied to compiled Kotlin instead of a Painless AST.
Local `evaluate()` on the client runs the original, uninstrumented lambda.

## 8. Envelope v3 (freeze generalization — standing TODO, independent of OpenSearch)

The freeze envelope generalizes from `(receiver, return)` to `(receiver, return, slots[])` where
each slot is `(kind: PARAMS|DOC|SOURCE, className)`. Lambdas are then `FunctionN` — already fine
for `@JvmSerializableLambda`, serialization, and verification; only the envelope and the
invocation sites change. This lands in `serialize` as its own change so parameterized lambdas are
supported everywhere, not just for OpenSearch.

## 9. Integration tests (real OpenSearch via Testcontainers)

`opensearch-plugin` gains an `integrationTest` source set (wired into `check` only when Docker is
available; plain `test` stays fast and hermetic):

- container: `opensearchproject/opensearch:3.7.0`, security disabled, plugin installed at startup
  from the `pluginZip` output (mounted, `opensearch-plugin install file:///...`)
- fixture index: representative search-ranking docs — numeric engagement fields, long ids, a date
  field, keyword lists

Cases (a representative ranking function):

1. **function_score ready script**: weight/penalty maps in params, exponential freshness
   decay, threshold gates → assert exact scores against the same math computed in the test
2. **template + withParams**: same source, two param sets, assert both orderings; assert the
   source strings are identical (compile-cache guarantee)
3. **stored script**: PUT template, query by ref with typed params; assert rejection at PUT time
   for a policy-violating payload (store-time verification)
4. **doc binding**: `@SerialName` renames, missing-field defaults, `MissingFieldException` surface
   for a missing required field, ZonedDateTime binding + epoch math
5. **filter + field contexts**: boolean filter script; script field returning a computed string
6. **rejections end-to-end**: violating lambda → HTTP error carrying violation strings; tampered
   payload → integrity error; non-chill source → usage guidance
7. **needs_score**: `_score`-using script vs not, asserted via explain or behavior
8. **captured values** (closure state, not slot inputs):
   - captured primitive and String used in ranking math (`val floor = 5; ... { d -> if (d.views < floor) ... }`)
     → asserted in real query results
   - captured collection/map (e.g. `Set<String>` of blocked labels) traversed per doc
   - captures **combined with** bound slots in one lambda (capture + params + doc)
   - captured mutable local (`var` → `Ref` wrapper class) — verify it ships, thaws, and executes
   - captured non-policy object (e.g. a `File`) → rejected at freeze, client side, with the
     serialization-trace violation naming the class
   - two freezes with different captured values → different sources (compile-cache identity
     documented behavior, contrast with the template test in case 2)

## 10. Decisions log (gaps filled; flag any for veto)

- Slot function names: `paramOf(value)`, `paramType<P>()`, `docType<D>()`, `sourceType<S>()` (as specified)
- Result type names: `ChillScript`, `ChillScriptTemplate<P>`, `ChillScriptRef` (stored reference)
- Entry point: `ChillOpenSearch.script(...)` returning the above; `scriptSource()` string-only form
  remains for non-opensearch-java clients (it is just `ChillScript.source`)
- Dates: `ZonedDateTime` required; `Instant` NOT auto-converted after all — one canonical type,
  conversions explicit in script code (simplest rule that matches doc-values reality)
- Stored ready-scripts: not offered (params belong to queries, not cluster state)
- opensearch-java: compileOnly in `opensearch-script`; no third module
- kotlinx runtime shipped inside the plugin zip; its policy (and kotlin-stdlib's) generated at
  build time into the `quarantine` jar, overridable per name from files, never scanned at runtime
- Client-side freeze verifies against the same policy as the server default, so violations fail
  at build/test time in the app, not at query time
