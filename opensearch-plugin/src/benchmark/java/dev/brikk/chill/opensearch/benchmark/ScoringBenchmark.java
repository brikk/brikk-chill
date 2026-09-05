package dev.brikk.chill.opensearch.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ScoringBenchmark {
    @Param({"painless", "painless_cached", "direct", "bound", "codec", "codec_instrumented", "lazy_control"})
    public String binding;

    @Param({"narrow", "wide", "wide12"})
    public String shape;

    @Param({"one", "all"})
    public String access;

    @Param({"0", "10"})
    public int missingPercent;

    private ScoringFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = new ScoringFixture(shape, missingPercent, access);
        fixture.verify(binding);
    }

    @Benchmark
    @OperationsPerInvocation(ScoringFixture.DOCUMENTS)
    public double scan() {
        return fixture.scan(binding);
    }

    @TearDown(Level.Trial)
    public void close() {
        fixture.close();
    }
}
