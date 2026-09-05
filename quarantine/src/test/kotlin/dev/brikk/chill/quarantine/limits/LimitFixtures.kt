package dev.brikk.chill.quarantine.limits

import java.util.regex.Pattern

/** Untrusted-script-shaped code: loops of every shape and each regex entry point a Kotlin script can reach. */
@Suppress("ControlFlowWithEmptyBody")
class LimitFixture {
    fun spin(): Int {
        var i = 0
        while (true) {
            i++
        }
    }

    fun sum(n: Int): Long {
        var s = 0L
        for (i in 0 until n) s += i
        return s
    }

    fun nested(n: Int): Int {
        var c = 0
        for (i in 0 until n) for (j in 0 until n) c++
        return c
    }

    fun countDown(n: Int): Int {
        var i = n
        do {
            i--
        } while (i > 0)
        return i
    }

    fun helperLoops(n: Int): Long = (0 until n).sumOf { sum(10) }

    fun swallowing(): Int {
        var escaped = 0
        while (true) {
            try {
                while (true) {
                    escaped++
                }
            } catch (t: Throwable) {
                escaped = -1
            }
        }
    }

    fun find(pattern: String, input: String): String? = Regex(pattern).find(input)?.value

    fun findFrom(pattern: String, input: String, start: Int): String? = Regex(pattern).find(input, start)?.value

    fun replaceExt(input: String, pattern: String): String = input.replace(Regex(pattern), "_")

    fun split(input: String): List<String> = input.split(Regex("\\s+"))

    fun predicate(pattern: String, input: String): Boolean = Pattern.compile(pattern).asPredicate().test(input)

    fun bigPrimitiveArray(n: Int): Int = LongArray(n).size

    fun bigObjectArray(n: Int): Int = arrayOfNulls<String>(n).size

    fun bigString(n: Int): Int = "ab".repeat(n).length

    fun nestedArrays(n: Int, m: Int): Int = Array(n) { IntArray(m) }.sumOf { it.size }
}

class RegexRefFixture {
    fun matching(regex: Regex, inputs: List<String>): List<String> = inputs.stream().filter(regex::matches).toList()
}
