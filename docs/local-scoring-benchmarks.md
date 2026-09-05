# Local scoring benchmarks

`opensearch-plugin:benchmark` runs JMH against synthetic Lucene doc values and the real
OpenSearch Painless and Chill score-script factories. It does not connect to a cluster.
The benchmark sources and dependencies are not included in the plugin ZIP or published library.

## Run

```sh
./gradlew :opensearch-plugin:benchmark
```

The default compares the current checkout's Painless, cached-lookup Painless, direct Chill, and
bound Chill paths with one-field and twelve-field models. JMH uses two JVM forks, four warm-up
iterations and five measurement iterations, one second each, with its GC profiler enabled.
The benchmark JVM defaults to JDK 25; `-PbenchmarkJava=21` selects JDK 21 instead.

For a focused twelve-field comparison:

```sh
./gradlew :opensearch-plugin:benchmark --args='ScoringBenchmark -p binding=painless,painless_cached,direct,bound -p shape=wide12 -p access=all -p missingPercent=0 -f 2 -wi 4 -i 5 -prof gc -foe true'
```

Add `-PbenchmarkReleased` to run the same benchmark classes against the published `0.1.0`
runtime rather than the checkout's runtime. This avoids keeping a copied legacy decoder in
the benchmark or undoing working changes to obtain a baseline.
Use `-PbenchmarkReleased=0.1.1` to select a different published version.

Useful parameters:

- `shape=narrow,wide,wide12`: one, four, or twelve declared numeric properties.
- `access=one,all`: score using only `views`, or all fields declared by that model.
- `missingPercent=0,10`: dense values or staggered missing fields, with the DTO defaults applied.
- `binding=painless`: checks presence, then retrieves the field again for its value.
- `binding=painless_cached`: retains each field's doc-value list in a local variable, avoiding that second retrieval.
- `binding=direct`: the real Chill score factory using `doubleVal(...)`.
- `binding=bound`: the real Chill score factory decoding the declared DTO.
- `binding=codec,codec_instrumented`: decoder-plus-lambda controls, bypassing the factory wrapper, with and without instrumentation of the shipped serializer.
- `binding=lazy_control`: a reused getter view over the real lookup, with budget setup/cleanup. This omits the engine invocation wrapper and is a lower-bound experiment, not a supported alternative binding API.

JMH accepts `-rf json -rff <path>` or `-rf csv -rff <path>` to retain measurements. Use its
`-prof jfr:dir=<directory>;configName=profile` option for a separate profiling run. Do not compare
JFR-instrumented timing directly with timing from an unprofiled run.

## What is measured

Each invocation scans 65,536 documents in one Lucene segment. `OperationsPerInvocation`
normalizes time and allocation to **one scored document**, not one whole scan. Every fixture
checks its score sum against an independently calculated expected sum before measurement.

The index uses real `SortedNumericDocValuesField` data, real `SortedNumericIndexFieldData`,
`LeafDocLookup`, and each engine's `ScoreScript`. Only initial mapping discovery is stubbed;
field values and scoring are not mocked. Fresh leaf scripts/doc-value iterators are created
for every scan because a Lucene iterator cannot rewind. Script compilation is outside timing;
leaf creation and thaw are amortized over the scan, as they are over a segment in a query.

The data contains varying, non-cached-range numeric values. Missing fields are staggered by
column rather than making every property absent on the same documents. No HTTP, shard fan-out,
top-hit collection, source loading, or response serialization is measured. Consequently, these
numbers diagnose the scoring path; they are not predictions of production query latency.

Compare both Painless variants. Avoid presenting a win against repeated field retrieval as a
win against Painless with its lookup already cached. Likewise, a twelve-field eager DTO whose
lambda only reads one property still decodes the other eleven. Keep `access=one` and `access=all`
results separate when considering lazy projections.

These are numeric-field benchmarks. Nullable fields, dates, enums, lists, custom serializers,
and error behavior have regression coverage, but their performance requires separate fixtures.

## Mixed workloads

`WorkloadBenchmark` adds synthetic mixed-width inputs and query-parameter maps. Its index has
29 numeric columns, but its DTOs declare only the fields the calculation uses. It contains no
application scoring rules or production data.

- `scenario=fields10`: five `Int` and five `Long` fields, one required and the rest defaulted,
  summed without parameter maps. This is the field-binding control.
- `scenario=mixed10`: the same ten fields, four string-keyed parameter-map lookups, a ratio,
  logarithm, square root, and a three-way branch. The arithmetic is deliberately synthetic.
- `scenario=lookup2`: one `Int`, one `Long`, one parameter-map lookup, and a logarithmic calculation.
- `mapSize=64,1024`: entries in each parameter map.
- `hitPercent=0,50,100`: requested lookup-hit percentage for generated keys before missing fields
  are applied. A missing key field defaults to zero, which is not a table key.
- `missingPercent=0,10`: staggered missing fields; the required field is always present.

For example, compare the released runtime's scoring paths:

```sh
./gradlew -PbenchmarkReleased=0.1.1 :opensearch-plugin:benchmark --args='WorkloadBenchmark.scan -p scenario=mixed10 -p mapSize=1024 -p missingPercent=0,10 -p hitPercent=50 -f 2 -wi 4 -i 5 -prof gc -foe true'
```

The fixture validates every document's result against the generated inputs before timing, rather
than checking only the final sum. The timed scan still returns a checksum and reports ns/document.
Parameters are decoded once when creating the query factory, outside the scan, and shared across
its fresh leaf instances. Leaf creation and thaw remain amortized into the scan measurement.

Measure that parameter-materialization cost separately:

```sh
./gradlew -PbenchmarkReleased=0.1.1 :opensearch-plugin:benchmark --args='WorkloadBenchmark.querySetup -p binding=painless_cached,bound -p scenario=mixed10,lookup2 -p mapSize=64,1024 -p missingPercent=0 -p hitPercent=50 -f 2 -wi 3 -i 4 -prof gc -foe true'
```

`querySetup` reports microseconds and allocated bytes **per query-factory creation**, not per
document or per complete HTTP search. It starts with already-parsed wire parameter maps. Painless
can retain those maps directly; typed Chill params materialize the declared parameter object.
On a distributed search this work can occur per shard. Do not fold it into scoring latency without
accounting for how many documents and leaf instances share the factory.
