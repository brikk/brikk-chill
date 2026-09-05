# Code review findings

Review of the OpenSearch scripting modules (`opensearch-script`, `opensearch-plugin`) after the
library upgrade. Baseline at review time: unit suites green; 7/7 integration tests pass against a
real OpenSearch 3.7.0 node via Testcontainers.

Status legend: `[ ]` open, `[x]` done, `[-]` won't fix / documented instead.

## High

### [x] 1. No execution bounds on scripts (DoS)

Was: the sandbox verified *what* code references, not *how long* it runs. `kotlin.text` /
`kotlin.collections` are allowed wholesale, so `while (true) {}`, huge `repeat`, or a
catastrophic-backtracking `Regex` in a per-document score function pinned a data node. The HMAC
key is public, so anyone who can query can send a payload.

Done (ported from Painless's `max_loop_counter` / `regex.limit-factor`, as bytecode
instrumentation of the verified classes on the server, after verification and before define):
- **Loops**: `ExecutionBudget.tick()` inserted before every backward branch. The budget is
  per thread and re-armed by the engine per document execution, so nested loops, helper methods
  and recursion share it (stricter than Painless's per-function slot). Node setting
  `chill.script.max_loop_iterations`, default 1,000,000.
- **Regex**: every call site of `kotlin.text.Regex.*`, the `StringsKt` regex extensions,
  `Pattern.*`/`Matcher.*` taking a `CharSequence` has the input wrapped in
  `LimitedCharSequence` (charAt count <= factor x length); `Pattern.asPredicate()` (the one
  unlimited JDK path the base policy exposed) is redirected to a limited predicate; foreign
  method handles to regex ops are rejected at compile. Kotlin compiles `regex::matches` to a
  `FunctionReferenceImpl` class with an ordinary call site, so it is limited too. Node setting
  `chill.script.regex_limit_factor`, default 6; 0 disables regex.
- Neither rewrite changes stack depth or frame-visible locals, so no frames are recomputed and
  no class needs loading during instrumentation.
- `ChillExecutionLimitError extends Error`; the engine converts it to a `ScriptException` naming
  the script. A script `catch (Throwable)` cannot loop on: the budget stays exhausted.
- Found while testing: `ScriptClassLoader` was parent-first, so a shipped class name the parent
  could see resolved to the parent's class. Made child-first for shipped names. Besides making
  instrumentation testable, this closes a thaw hole: shipped names are granted
  `ref_Class_Instance`, so a payload could ship harmless bytes under the name of a Serializable
  server class and have the *real* class deserialized with attacker-chosen fields.

Not bounded: allocation size (`ByteArray(Int.MAX_VALUE)`, `"x".repeat(1e9)`) and recursion
depth (self-limiting via StackOverflowError, which propagates as a normal error). Painless does
not bound these either; the JVM heap limit is the backstop. Tracked as a Low item.

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

### [x] 3. Unmapped doc fields throw instead of defaulting

Was: `LeafDocLookup.get()` throws `IllegalArgumentException("No field found for [x] in mapping")`
for fields absent from the mapping (only `containsKey` is safe; `size`/`isEmpty`/`keySet` throw
too). `DocValuesCodec` and `ChillSearchScript.values()` both called `get`, so a bound class whose
property had no mapped field failed the whole query on the server while constructing the same
class locally used the default. The integration test only passed because the IAE message
happened to contain the field name.

Done: every lookup goes through `DocValuesCodec.docValues(field)`, which probes `containsKey`
first; unmapped and mapped-but-empty both read as "no values", so kotlinx applies the default or
raises `MissingFieldException` naming the field. Unit test uses a `LeafDocLookup`-shaped stub
(`get` throws, `containsKey` safe, `size` throws); integration test checks a bound class with an
unmapped defaulted field scores identically via `evaluate` and on the node, that receiver
helpers default on unmapped fields, and that the required-field error is kotlinx's and not the
lookup's.

Not changed: raw `doc["unmapped"]` on the receiver still throws, as it does in Painless.

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

### [x] 5. Bound API is much narrower than `script()`

Was: `boundScore` covered only `(params, doc)` / `(params, doc, score)`, always `Double`, four
result classes hard-coded to that shape; a stored ref lost the evaluator.

Done: `ChillOpenSearch.bound(...)` mirrors `script(...)` for every slot combination (0-3 of
params/doc/source, optional trailing `scoreType()`), any `R`, against the empty `ChillBound`
receiver. Two generic result types replace the per-shape classes: `ChillBoundScript<R, E>`
(ready; `evaluate: E` typed by the remaining slots, e.g. `(ArticleDoc, Double) -> Double`) and
`ChillBoundTemplate<P, R, E, B>` (`evaluate` takes params first; `withParams` -> ready bound;
`stored(id)` -> `ChillStoredBoundRef` whose `withParams` yields id + params + `evaluate`).
Existing call sites compiled unchanged. Integration test registers a bound template, queries by
id, and reranks locally with the same lambda; also runs a bound `Boolean` filter.

### [x] 6. Bound types must be flat; failure mode is confusing

Was: `shipSet` shipped a bound class plus its lexically nested classes only, so an `enum`
property or a nested `@Serializable` type failed at freeze with a policy violation that read
like a security error.

Done: `ShipClosure` (quarantine) computes the transitive closure: root + nested classes, then
every class the bytecode references that the policy does not cover and that lives in the same
classloader as the root, re-scanned. The walk stops at stdlib/kotlinx/JDK. Tests: closure
contains exactly the user's type graph and verifies clean where the root alone did not; engine
test decodes a top-level enum from doc values and a nested class + enum + list-of-nested from
`_source`.

### [x] 7. Generic bound types fail server-side

Was: `paramType<Wrapper<String>>()` froze fine (client has the full reified serializer) and
failed on the node with "Serializer for class 'Wrapper' is not found" (server rebuilds from the
class name alone).

Done: every slot constructor resolves `serializer(Class)` the way the server will and compares
descriptor shape with the reified serializer; mismatch or failure throws
`IllegalArgumentException` at the slot naming the class and the reason. Contextual and
collection-typed properties pass.

### [x] 8. Local/remote parity caveats not surfaced

Two were behaviour bugs, two were OpenSearch facts that needed to be stated and made testable:

- [x] Nullable = optional. `val x: String?` with no default was *required* by the doc decoder.
      `decodeElementIndex` now yields absent nullable elements so they decode as `null`, matching
      `Json`'s `explicitNulls=false` and a locally constructed instance.
- [x] Explicit `null` params were dropped by `toJsonData()`, so the node applied the class default.
      Now sent as JSON `null`. Integration test: `floor = null` scores as null on the node, and the
      omitted case defaults identically on both sides.
- [x] Scores are float32, and the client reads the float's *shortest decimal form* from the JSON
      (`300.14285`), not the widened double. `Number.asIndexScore()` reproduces that exact round
      trip; every integration assertion now compares with equality instead of a tolerance (the
      old 1e-3 / 1e-5 tolerances were hiding this).
- [x] Multi-valued doc values are sorted (keywords also de-duplicated). Documented on
      `ChillBoundScript` and `DocValuesCodec`; integration test shows `_source` order
      `zeta|alpha|zeta|mid` vs node `alpha|mid|zeta`, and parity on the sorted view.

### [x] 9. `ChillScript<out R>` is phantom for `script()`

Was: `freeze` always recorded `Any` as the return type, so the node's check never fired for
`script()`; a `String` result in a score query failed per document. The typed client extensions
in design §5 did not exist.

Done:
- `script(...)` and `bound(...)` are `inline` with `reified R`; the payload records the actual
  result class. The node checks it against the context at compile (score: any `Number`; filter:
  `Boolean`; field: anything) and rejects with "returns X, but this context needs Y". A lambda
  whose branches force `Any` still passes and is checked per result; Kotlin requires the explicit
  `script<Any>` in that case and says so.
- opensearch-java extensions typed by result: `ScriptScoreQuery.Builder.chill(ChillScript<Number>)`,
  `ScriptScoreFunction.Builder.chill(ChillScript<Number>)`, `ScriptQuery.Builder.chill(ChillScript<Boolean>)`,
  `SearchRequest.Builder.chillScriptField(name, ChillScript<*>)`, plus `ChillStoredScript` variants.
  Passing a `Boolean` script to a score builder is now a compile error in the IDE. Integration
  test builds a function_score with a chill filter, chill score function, and chill script field.

## Low

- [x] Allocation bounds. The instrumenter now routes the length operand of `newarray` /
      `anewarray` (and single-dim `multianewarray`) and the count of `String.repeat` /
      `CharSequence.repeat` through `ExecutionBudget.checkAllocation(I)I` (stack-neutral). Node
      setting `chill.script.max_allocation`, default 1M elements per single allocation. Growth by
      repeated small allocations is bounded by the loop budget. Kotlin builds nested arrays per
      level so each dimension is checked. Not covered: a single huge `StringBuilder.ensureCapacity`
      or `ArrayList(n)` (both internal `anewarray`s live in the JDK, not in shipped code); the heap
      remains the backstop for those, as in Painless.

- [x] Per-document hot path: slot producers resolved once per compile into an array, arity
      dispatch on `p.size`, a per-leaf reusable `Inputs` cell instead of a list per document, and
      no `ChillSearchScript` built for bound scripts (`needsReceiver`). Arity 4 (params+doc+source
      +score) now dispatches too.
- [x] Second `LeafSearchLookup` for `_source`: Score and Field scripts now read `_source` from
      their own leaf lookup via the `params` `DynamicMap` (per document, inside the provider);
      `FilterScript` keeps its lookup private and publishes no `_source`, so a source-bound filter
      is the one case that still takes its own. `sourceType` had no integration coverage; added
      one across all three contexts, including original tag order from `_source`.
- [x] Wording: §6 now says "before any shipped class is defined"; §7 flow names the
      instrumenter step and states that serializer resolution runs shipped `<clinit>` /
      `Companion.serializer()` (verified and instrumented) once per compile.
- [x] A shipped class whose name the policy covers (`kotlin.collections.CollectionsKt`) is now
      rejected at thaw ("names reserved by policy") instead of silently dropped. The sender never
      ships such classes legitimately (its ship set is already filtered), so the only way to see
      one is a hand-built envelope. Hardening test builds and signs one with the public key.
- [x] Design doc drift: §2 types now match the code (`ChillScript<R>`, bound/stored variants),
      `ChillSearchScript` shown as the final class it is, `ChillStoredScriptRef` naming, §6 stored
      rejection timing, §9 case list extended to the 15 integration cases. The three missing cases
      are now in the suite: captured `var` (`Ref.DoubleRef`) ships and reads on the node; captured
      `java.io.File` rejected at freeze with `java.io File` in `violations`; `_score` from a
      `constant_score` base is seen by a reading script and 0.0 for a bound script without the slot.
- [x] `RankParams` / `ArticleDoc` now live once in `opensearch-plugin/src/testFixtures`
      (`java-test-fixtures`; the fixtures variant is skipped from publishing). Side benefit: the
      bound classes now ship from a different output directory than the lambdas, exercising the
      ship path across the user's own modules.

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
