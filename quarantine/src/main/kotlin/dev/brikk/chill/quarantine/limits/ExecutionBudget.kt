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

        @JvmField
        var maxAllocation: Int = Int.MAX_VALUE
    }

    const val DEFAULT_MAX_ALLOCATION: Int = 1 shl 20 // 1M elements / chars per single allocation

    private val budget: ThreadLocal<Budget> = ThreadLocal.withInitial { Budget() }

    /**
     * Arms the current thread for the work that follows: [maxIterations] loop iterations in total,
     * and at most [maxAllocation] elements (or chars) per single array / `repeat` allocation.
     */
    @JvmStatic
    @JvmOverloads
    fun begin(maxIterations: Long, maxAllocation: Int = DEFAULT_MAX_ALLOCATION) {
        val b = budget.get()
        b.remaining = maxIterations
        b.maxAllocation = maxAllocation
    }

    /**
     * Called by instrumented code with the requested length before `newarray` / `anewarray` and
     * before `String.repeat` / `CharSequence.repeat`; returns it unchanged when allowed. Keeps a
     * single allocation from taking the heap; growth by repeated small allocations is bounded by
     * the loop budget instead.
     */
    @JvmStatic
    fun checkAllocation(size: Int): Int {
        if (size > budget.get().maxAllocation) {
            throw ChillExecutionLimitError(
                "script requested a single allocation of $size elements, above the ${budget.get().maxAllocation} allowed",
            )
        }
        return size
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
