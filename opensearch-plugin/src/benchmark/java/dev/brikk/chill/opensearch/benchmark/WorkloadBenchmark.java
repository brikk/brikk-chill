package dev.brikk.chill.opensearch.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class WorkloadBenchmark {
    @Param({"painless", "painless_cached", "direct", "bound"})
    public String binding;

    @Param({"fields10", "mixed10", "lookup2"})
    public String scenario;

    @Param({"0", "10"})
    public int missingPercent;

    @Param({"50"})
    public int hitPercent;

    @Param({"64", "1024"})
    public int mapSize;

    private WorkloadFixture fixture;

    @Setup(Level.Trial)
    public void setup() {
        fixture = new WorkloadFixture(scenario, missingPercent, hitPercent, mapSize);
        fixture.verify(binding);
    }

    @Benchmark
    @OperationsPerInvocation(WorkloadFixture.DOCUMENTS)
    public double scan() {
        return fixture.scan(binding);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public Object querySetup() {
        return fixture.newQuery(binding);
    }

    @TearDown(Level.Trial)
    public void close() {
        fixture.close();
    }
}
