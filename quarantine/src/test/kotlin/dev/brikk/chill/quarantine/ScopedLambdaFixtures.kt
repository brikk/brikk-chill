package dev.brikk.chill.quarantine

import dev.brikk.chill.annotations.ChillVerifyAtBuild

/**
 * Simulates another framework's serializable lambdas (Spark/Flink style) that must not be
 * verified against the chill policy: the lambda body would violate it.
 */
@ChillVerifyAtBuild(enabled = false)
object OtherFrameworkFixtures {
    val sparky: () -> String? = @JvmSerializableLambda { System.getenv("SPARK_HOME") }
}

@ChillVerifyAtBuild
object OptInFixtures {
    val checked: () -> String = @JvmSerializableLambda { "opted in " + Math.max(1, 2) }
}

object FunctionScopedFixtures {
    @ChillVerifyAtBuild
    fun makeChecked(): () -> String = @JvmSerializableLambda { "function scoped" }

    fun makeUnmarked(): () -> String = @JvmSerializableLambda { "not opted in" }
}

object PropertyScopedFixtures {
    @ChillVerifyAtBuild
    val propChecked: () -> String = @JvmSerializableLambda { "property scoped" }
}

/**
 * Nearest scope wins: the class forces verification off, the member forces it back on.
 */
@ChillVerifyAtBuild(enabled = false)
object NestedOverrideFixtures {
    val skippedByClass: () -> String? = @JvmSerializableLambda { System.getenv("SKIPPED") }

    @ChillVerifyAtBuild(enabled = true)
    val forcedOn: () -> String = @JvmSerializableLambda { "member override" }
}
