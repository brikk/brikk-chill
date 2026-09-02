# Code review findings

Review of the OpenSearch scripting modules (`opensearch-script`, `opensearch-plugin`) after the
library upgrade. Baseline at review time: unit suites green; 7/7 integration tests pass against a
real OpenSearch 3.7.0 node via Testcontainers.

Status legend: `[ ]` open, `[x]` done, `[-]` won't fix / documented instead.

## High

### [ ] 1. No execution bounds on scripts (DoS)

The sandbox verifies *what* code references, not *how long* it runs.
`ChillOpenSearch.scriptKotlinPackagePolicies` (`opensearch-script/.../ChillOpenSearch.kt:60`)
opens `kotlin.collections` / `kotlin.text` / `kotlin.sequences` / `kotlin.ranges` /
`kotlin.comparisons` wholesale, so any of these inside a per-document score function pins or OOMs
a data node:

- `while (true) {}`
- `"x".repeat(Int.MAX_VALUE)`
- a catastrophic-backtracking `Regex`

Painless has `max_loop_counter` and `regex.limit-factor` for exactly this. The HMAC key is a
public constant (`serialize/.../Chill.kt:69`), so anyone who can send a query can send a payload.

Options:
- ASM rewriting pass at compile that injects a decrementing loop counter at backward branches
  (throw on exhaustion). ASM is already in-tree.
- Ban or limit `kotlin.text.Regex` (or wrap with a step-limited matcher).
- At minimum: document as a loud, known limitation.

### [x] 2. Payload size vs `script.max_size_in_bytes`

Was: the bound-score example (`RankParams` + `ArticleDoc` + lambda) froze to **44,959 bytes**
against OpenSearch's default 65,535-byte limit on inline and stored scripts. Two small classes
used 69% of the budget, mostly compiler-generated `$serializer` code plus debug attributes.

Done:
- Envelope is deflated (best compression) before base64; inflate is capped at 32 MiB
  (`Chill.MAX_ENVELOPE_BYTES`) so a crafted 64 KB input cannot force a huge allocation.
- Shipped class bytes are debug-stripped (`DebugInfoStripper`, `ClassReader.SKIP_DEBUG`) after
  client-side verification; `Chill(stripDebugInfo = false)` restores line numbers in thawed
  stack traces at a ~10% size cost. Tested: serialVersionUID and behaviour unchanged.
- Measured after: bound 10,351 (-77%), doc-only 7,411, plain receiver 1,299. Regression test
  pins the bound example under 16,000 bytes.

Decided against: dropping `Companion` from the ship set. The class's `<clinit>` instantiates it,
so removal means rewriting user bytecode, for ~1.2 KB uncompressed per class (a few hundred
bytes compressed). Revisit only if payloads grow again. `kotlin.Metadata` is the other large
attribute; stripping it is riskier (kotlinx reflection-lite may consult it) and was not attempted.

### [ ] 3. Unmapped doc fields throw instead of defaulting

`LeafDocLookup.get()` throws `IllegalArgumentException("No field found for [x] in mapping")` for
fields absent from the mapping; only `containsKey` is safe. Both call `get`:

- `opensearch-script/.../DocValuesCodec.kt:84` — `doc[name].isNullOrEmpty()`
- `opensearch-script/.../ChillSearchScript.kt:26` — `doc[field] ?: emptyList()`

So "missing field, property has default -> default used" (design doc §4) only holds when the
field is *mapped* but empty for that doc. Against an index where the field isn't mapped at all,
the query fails. This also breaks local/remote parity: locally `ArticleDoc(...)` uses the
default; remotely the search 500s.

`rejectionsSurfaceCleanlyOverHttp` (integration test, line 437) passes only because the IAE
message happens to contain the field name.

Fix: guard with `containsKey` before `get` in both places. Add a unit test with a stub doc map
whose `get` throws for unknown keys, and an integration case with a mapped-but-absent field vs an
unmapped field.

### [x] 4. Policy built by scanning jars at runtime, on the client too

Was: `kotlinxSerializationPolicies` scanned the kotlinx jar via `protectionDomain.codeSource`
and `KotlinStdlibPolicyGenerator` regex-matched classpath URLs, at first freeze, in the client
process as well as the server. Broke in nested/fat jars, cost seconds of startup, and let client
and server compute different truths for different library versions.

Done:
- `quarantine` generates `META-INF/chill/policy/kotlin-stdlib.ctena` and
  `kotlinx-serialization-core.ctena` at build time (`generateLibraryPolicies` task,
  `LibraryPolicyWriter`, provenance header with jar sha256). `LibraryPolicies` loads them.
  kotlinx is a scan-only build configuration, not a dependency of the quarantine jar.
- `ChillPolicyLoader` resolves a name as: explicit `overrideDirectory` > `chill.policy.dir`
  system property > classpath resource. Override *replaces* the named policy.
- `ChillScriptPlugin` uses OpenSearch's plugin config dir (`config/chill-script/`) as the
  override directory.
- `ChillOpenSearch` composes from `LibraryPolicies`; the hand-written serializer-support
  allowances moved to `quarantine` (`KotlinxSerializationSupportPolicies`) and are written into
  the kotlinx `.ctena` so an override replaces them too.
- Found while testing: enum `values()` compiles to `Object.clone()`, which no policy allowed, so
  a `@Serializable enum` in a bound class could never verify. Added to the Kotlin bootstrap policy.

- Gradle plugin: `chill.policies { register("<name>") { jars.from(...) } }` registers
  `chillGeneratePolicy<Name>` (shipped profiles `kotlin-stdlib`, `kotlinx-serialization-core`, or
  `custom`), scanning through an isolated platform-parent `URLClassLoader` so the policy describes
  the consumer's jars and not the daemon's. Output `build/chill/policy/` doubles as the override
  dir for `chillVerifyLambdas` and is what to hand to the runtime. Functional tests cover a real
  stdlib regeneration and a narrowed override replacing (not merging with) the shipped policy.

## Medium (generality)

### [ ] 5. Bound API is much narrower than `script()`

`boundScore` (`ChillOpenSearch.kt:259-301`) exists only for `(params, doc)` and
`(params, doc, score)`, always returns `Double`, always requires a params slot, no `sourceType`,
no doc-only. `ChillBoundScore*` classes (`ChillScript.kt:33-65`) hard-code `Double`.

Proposal: make the bound form the general one — `ChillBoundScript.(slots...) -> R` with
`evaluate(...): R`; the engine already coerces `R` per context. That gives local filters and
local field computations for free.

Related: a stored bound template loses its evaluator. `storedChillScript(id, paramType<P>())`
returns a plain `ChillStoredScriptRef`. For the local-rerank story, `template.stored(id)` should
keep `evaluate`.

### [ ] 6. Bound types must be flat; failure mode is confusing

`shipSet` (`ChillOpenSearch.kt:178`) ships a bound class plus its *lexically nested* classes only.
A doc/params class with an `enum` property, or a `_source` class with a nested `@Serializable`
type, has a `$serializer` referencing `Status$$serializer` / `Status.values()`, which isn't
shipped and isn't in policy -> freeze fails with a policy-violation message that reads like a
security error.

`decodeEnum` (`DocValuesCodec.kt:119`) is effectively unreachable unless the enum is nested
inside the doc class. (Nested enums now verify: the `Object.clone()` gap was fixed under #4.)

Fix: compute the ship closure transitively (classes from the user's classloader referenced by the
bound class / its serializer that are not covered by policy), the same way
`Chill.serializeFunctionToBase64` already walks lambda relatives. Add tests: enum property,
nested `@Serializable` in `sourceType`.

### [ ] 7. Generic bound types fail server-side

The client has a full `KSerializer<P>` from `serializer<P>()`, but the envelope carries only
`P::class.java.name`, and the server rebuilds via `serializer(clazz)`
(`opensearch-plugin/.../ChillScriptEngine.kt:219`). `paramType<Wrapper<String>>()` freezes fine
and fails at compile on the node with kotlinx's "Serializer for class 'Wrapper' is not found".

Fix: validate at freeze — resolve `serializer(boundClass)` client-side and compare descriptors to
the reified serializer; fail there with a clear message. (`Chill.kt:258` already has
`// TODO: handle types with generics`.)

### [ ] 8. Local/remote parity caveats not surfaced

Will bite anyone using `evaluate` as a reranker against objects built from `_source`:

- [ ] Lucene scores are float32; `hit.score()` is a widened float. Test tolerances (1e-3, 1e-5)
      are quietly papering over this. Either document, or have `evaluate` offer a
      `toFloat().toDouble()` mode when parity is the goal.
- [ ] Multi-valued numeric doc values come back sorted; keyword doc values sorted *and*
      deduplicated. `List<T>` doc-bound properties won't match `_source` order.
- [ ] `val x: String?` with no default is *required* in a custom kotlinx decoder (only `Json` has
      `explicitNulls=false`). Users expect nullable to mean optional. Supportable in
      `decodeElementIndex`: return the index for missing nullable elements and let
      `decodeNotNullMark()` return false.
- [ ] `toJsonData()` (`opensearch-script/.../client/OpenSearchJavaExtensions.kt:21`) drops
      null-valued params, so an explicit `null` becomes "absent" and the server applies the class
      default instead. Use `JsonData.of(JsonValue.NULL)` or equivalent.

### [ ] 9. `ChillScript<out R>` is phantom for `script()`

`freeze` always writes `Any::class` as the return type (`ChillOpenSearch.kt:159`), so the
server's return-type check (`ChillScriptEngine.kt:173`) only fires for `boundScore`. A
`script { "str" }` used as a score script fails per-document at execute time, not at compile.

Design §5 lists `ScriptScoreQuery.Builder.chill(...)`, `FunctionScore.Builder.chill(...)`,
`SearchRequest.Builder.chillScriptField(...)`; none exist. Typed versions
(`ChillScript<Number>` for score, `ChillScript<Boolean>` for filter) would make `R` do real work
at the call site.

## Low

- [ ] **Per-document hot path** (`ChillScriptEngine.kt:83-116`): `slots.map` allocates a list,
      `when (slot.kind)` string-dispatches, and a `ChillSearchScript` is built even when
      `boundReceiver` is true (`:306`, discarded at `:97`). Precompute an array of arg producers
      and the invoke arity at compile time.
- [ ] `newInstance` creates a second `LeafSearchLookup` for source (`ChillScriptEngine.kt:297`)
      rather than reusing the script's own leaf lookup.
- [ ] Serializer resolution at compile executes shipped `Companion.serializer()` and `<clinit>`.
      Verified code, but design §6/§7 say "before anything executes". Adjust wording (or see #2,
      which removes the companion call).
- [ ] `requireSealed = false` packages + `filterKnownClasses` means a shipped class named e.g.
      `kotlin.collections.Evil` is silently dropped rather than rejected. Not exploitable (it
      can't load), but a shipped class landing in an allowed package should be a hard error.
- [ ] Design doc drift (`docs/opensearch-scripting-design.md`):
  - no mention of `boundScore` / `ChillBoundScript` / `scoreType()` (the primary example)
  - `ChillSearchScript` is a final class, not `abstract`
  - `ChillScript` is `open`, doc says final
  - `ChillScriptRef` vs actual `ChillStoredScriptRef` / `ChillStoredScript`
  - §9 item 7 (needs_score via explain), the `var` -> `Ref` capture case, and the `File` capture
    rejection case are not in the integration suite
- [ ] `RankParams` / `ArticleDoc` duplicated between `test` and `integrationTest` source sets.

## What's solid (no action)

- Envelope v3 with slot descriptors and bounds-checked lengths
- Constant-time HMAC compare
- JEP 290 filter plus `readClassDescriptor` gating on thaw
- Per-leaf lambda instantiation (no captured state across threads)
- Static `needs_score` detection from the verification scan
- `ScriptException` carrying violations in `scriptStack`
- Integration suite runs against the real plugin zip, including hostile-payload cases

## Suggested order

1. #3 (small, clearly correct, has a test story)
2. #2 (gzip + SKIP_DEBUG; contained, existing tests protect it)
3. #4 (build-time policy resources)
4. #1 (loop counter instrumentation)
5. #5 / #6 / #7 together (bound API generalization + ship closure + freeze-time validation)
6. #8 / #9 / Low items
