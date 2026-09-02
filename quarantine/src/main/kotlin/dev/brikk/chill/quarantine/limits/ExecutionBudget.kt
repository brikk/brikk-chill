package dev.brikk.chill.quarantine.limits

/**
 * Per-thread loop budget consumed by instrumented code ([ExecutionLimitInstrumenter] inserts a
 * [tick] before every backward branch).
 *
 * The executing side calls [begin] before each unit of untrusted work (the OpenSearch plugin: once
 * per document). Because the budget is per thread rather than per method, nested loops, helper
 * methods and recursion all draw from the same allowance. Outside a [begin]/work window the
 * budget is effectively unlimited, so class initialisers run at define time are unaffected.
 */
object ExecutionBudget {

    private class Budget {
        @JvmField
        var remaining: Long = Long.MAX_VALUE
    }

    private val budget: ThreadLocal<Budget> = ThreadLocal.withInitial { Budget() }

    /** Arms the current thread with [maxIterations] loop iterations for the work that follows. */
    @JvmStatic
    fun begin(maxIterations: Long) {
        budget.get().remaining = maxIterations
    }

    /** Called by instrumented code before each backward branch. */
    @JvmStatic
    fun tick() {
        val b = budget.get()
        if (--b.remaining < 0) {
            b.remaining = -1 // stays exhausted: a script that catches Throwable cannot loop on
            throw ChillExecutionLimitError(
                "script exceeded the maximum number of loop iterations allowed per execution",
            )
        }
    }

    /** Iterations left on this thread; for diagnostics and tests. */
    @JvmStatic
    fun remaining(): Long = budget.get().remaining
}
