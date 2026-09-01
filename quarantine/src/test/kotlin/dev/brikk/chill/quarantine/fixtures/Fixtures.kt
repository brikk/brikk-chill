package dev.brikk.chill.quarantine.fixtures

/**
 * Uses only whitelisted JDK surface (String, StringBuilder, Math) plus compiler-emitted plumbing:
 * kotlin Intrinsics null checks and invokedynamic string concat (StringConcatFactory).
 */
class SafeOps {
    fun combine(a: String, b: Int): String {
        val sb = StringBuilder()
        sb.append(a)
        sb.append(b)
        val bigger = Math.max(b, 10)
        return sb.toString() + "!" + a + bigger
    }
}

/**
 * Calls JDK surface well outside the whitelist.
 */
class UnsafeOps {
    fun leakEnvironment(): String? = System.getenv("PATH")

    fun spawn(): Process = ProcessBuilder("true").start()
}

/**
 * Uses a Java-style lambda via invokedynamic (LambdaMetafactory): the impl method is a synthetic
 * private static method on this class (self-exempt), and the SAM interface is java.util.function.
 */
class IndyLambdaOps {
    fun transform(input: String): String {
        val fn = java.util.function.Function<String, String> { it + "!" }
        return fn.apply(input)
    }
}

/**
 * The invokedynamic impl method calls something outside the whitelist - the scanner must see
 * through LambdaMetafactory and flag it.
 */
class IndyLambdaViolationOps {
    fun bad(): java.util.function.Supplier<String?> {
        return java.util.function.Supplier { System.getenv("HOME") }
    }
}
