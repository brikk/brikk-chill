package dev.brikk.chill.serialize

/**
 * Marks a lambda for Chill freeze/thaw shipping.
 *
 * This is a typealias of [kotlin.jvm.JvmSerializableLambda]: the Kotlin compiler expands
 * typealiases before it applies the annotation's special-case, so `@ChillLambda { ... }` compiles
 * the lambda as a regular class (extending `kotlin.jvm.internal.Lambda` and implementing
 * `java.io.Serializable`) instead of an invokedynamic hidden class.
 *
 * Note: expression-target annotations have SOURCE retention, so this never appears in bytecode.
 * Build-time discovery (the `dev.brikk.chill` Gradle plugin) instead finds these lambdas by their
 * unique class shape - under Kotlin 2.x, only annotated lambdas compile to Lambda+Serializable
 * classes.
 */
typealias ChillLambda = kotlin.jvm.JvmSerializableLambda
