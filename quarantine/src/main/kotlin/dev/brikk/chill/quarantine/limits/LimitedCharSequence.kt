package dev.brikk.chill.quarantine.limits

import java.util.function.Predicate
import java.util.regex.Pattern

/**
 * A [CharSequence] view that bounds how much work a regex engine may do on it: every [charAt]
 * is counted, and once the count passes `limitFactor x length` the match is aborted with
 * [ChillExecutionLimitError]. Catastrophic backtracking reads the same characters over and over,
 * so it trips this within a small multiple of the input length; ordinary matching stays far
 * below it. Same scheme as Painless's `LimitedCharSequence` and `regex.limit-factor`.
 *
 * [ExecutionLimitInstrumenter] rewrites regex call sites in untrusted code to pass their input
 * through [wrap]; sub-sequences share the counter so a match cannot reset it.
 */
class LimitedCharSequence private constructor(
    private val wrapped: CharSequence,
    private val counter: Counter,
) : CharSequence {

    private class Counter(val limit: Long) {
        @JvmField
        var count: Long = 0
    }

    companion object {
        /** `limitFactor` in effect for [wrap]; set by the executing side, `0` disables regex entirely. */
        @Volatile
        @JvmStatic
        var limitFactor: Int = DEFAULT_LIMIT_FACTOR

        const val DEFAULT_LIMIT_FACTOR: Int = 6

        /** Instrumented code calls this on the input argument of every regex operation. */
        @JvmStatic
        fun wrap(input: CharSequence): CharSequence {
            val factor = limitFactor
            if (factor <= 0) throw ChillExecutionLimitError("regular expressions are disabled for scripts")
            if (input is LimitedCharSequence) return input
            return LimitedCharSequence(input, Counter(factor.toLong() * input.length))
        }

        /** Replacement for `Pattern.asPredicate()` in instrumented code: tests limited input. */
        @JvmStatic
        fun limitedPredicate(pattern: Pattern): Predicate<String> =
            Predicate { s -> pattern.matcher(wrap(s)).find() }
    }

    override val length: Int get() = wrapped.length

    override fun get(index: Int): Char {
        if (++counter.count > counter.limit) {
            throw ChillExecutionLimitError(
                "regular expression exceeded ${limitFactor}x the input length in character reads " +
                    "(input length ${wrapped.length}); simplify the pattern or raise the regex limit factor",
            )
        }
        return wrapped[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        LimitedCharSequence(wrapped.subSequence(startIndex, endIndex), counter)

    override fun toString(): String = wrapped.toString()
}
