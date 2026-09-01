package dev.brikk.chill.annotations

/**
 * Forces build-time Chill verification on or off for the serializable lambdas
 * (`@ChillLambda` / `@JvmSerializableLambda`) declared in the annotated scope.
 *
 * When the annotation is absent, the `dev.brikk.chill` Gradle plugin's configured mode decides:
 * `all` verifies every discovered lambda, `annotated` verifies none without a marker. Adding this
 * one annotation overrides that default in either direction:
 *
 *  - `@ChillVerifyAtBuild` (or `enabled = true`) - always verify lambdas in this scope
 *  - `@ChillVerifyAtBuild(enabled = false)` - never verify lambdas in this scope; use this to
 *    coexist with other frameworks that need class-compiled serializable lambdas (Spark, Flink,
 *    Hazelcast, Ignite, ...) without scanning them against the Chill policy
 *
 * The nearest scope wins: a member-level annotation overrides a class-level one, which overrides
 * an outer class or file-level one.
 *
 * This must be a declaration-site annotation: expression-target annotations are forced to SOURCE
 * retention by the language and never reach bytecode, so the lambda expression itself cannot
 * carry a binary marker. The plugin instead maps each discovered lambda class back to its
 * enclosing declaration (via the `EnclosingMethod` attribute and the `Outer$member$1` naming).
 *
 * Placement notes:
 *  - class/object/file level covers every lambda declared inside
 *  - function level covers lambdas declared in that function body
 *  - property level covers the property's initializer lambda
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.BINARY)
annotation class ChillVerifyAtBuild(val enabled: Boolean = true)
