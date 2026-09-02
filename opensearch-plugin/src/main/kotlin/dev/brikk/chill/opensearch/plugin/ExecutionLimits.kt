package dev.brikk.chill.opensearch.plugin

import dev.brikk.chill.quarantine.limits.LimitedCharSequence
import org.opensearch.common.settings.Setting
import org.opensearch.common.settings.Settings

/**
 * Node-level bounds on what one chill script execution (one document) may do. Enforced by
 * bytecode instrumentation of the shipped classes at compile time
 * (`ExecutionLimitInstrumenter`), the same way Painless bounds `max_loop_counter` and
 * `regex.limit-factor`; not by the policy, which governs *what* code may reference, not for how
 * long it runs.
 */
class ExecutionLimits(
    /** Loop iterations allowed per document execution, across all loops, helpers, and recursion. */
    val maxLoopIterations: Long = MAX_LOOP_ITERATIONS.get(Settings.EMPTY),
    /** A regex may read at most this many times the input length before it is aborted; 0 disables regex. */
    val regexLimitFactor: Int = REGEX_LIMIT_FACTOR.get(Settings.EMPTY),
) {
    companion object {
        val MAX_LOOP_ITERATIONS: Setting<Long> = Setting.longSetting(
            "chill.script.max_loop_iterations", 1_000_000L, 1L, Setting.Property.NodeScope,
        )

        val REGEX_LIMIT_FACTOR: Setting<Int> = Setting.intSetting(
            "chill.script.regex_limit_factor", LimitedCharSequence.DEFAULT_LIMIT_FACTOR, 0, Setting.Property.NodeScope,
        )

        val ALL: List<Setting<*>> = listOf(MAX_LOOP_ITERATIONS, REGEX_LIMIT_FACTOR)

        fun fromSettings(settings: Settings): ExecutionLimits =
            ExecutionLimits(MAX_LOOP_ITERATIONS.get(settings), REGEX_LIMIT_FACTOR.get(settings))
    }
}
