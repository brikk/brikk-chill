package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy

/**
 * The minimal hand-written policy needed to verify bytecode the Kotlin 2.x compiler emits for
 * plain classes and (class-compiled) lambdas, before any generated kotlin-stdlib policy is added.
 */
internal object KotlinBootstrapPolicies {

    val primitiveArrayAccessPolicy = listOf('B', 'C', 'D', 'F', 'I', 'J', 'S', 'V', 'Z').map {
        PolicyAllowance.ClassLevel.ClassAccess("[$it", setOf(AccessTypes.ref_Class_Instance))
    }.toPolicy()

    private val intrinsicsNullChecks = listOf(
        // Kotlin >= 1.4 names
        "checkNotNullParameter" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkNotNullExpressionValue" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkNotNull" to "(Ljava/lang/Object;)V",
        "checkNotNull" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkExpressionValueIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkParameterIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkFieldIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkFieldIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V",
        "checkReturnedValueIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;)V",
        "checkReturnedValueIsNotNull" to "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V",
        "throwNpe" to "()V",
        "throwNpe" to "(Ljava/lang/String;)V",
        "throwJavaNpe" to "()V",
        "throwJavaNpe" to "(Ljava/lang/String;)V",
        "throwUninitializedPropertyAccessException" to "(Ljava/lang/String;)V",
        "areEqual" to "(Ljava/lang/Object;Ljava/lang/Object;)Z",
        "compare" to "(II)I",
        "compare" to "(JJ)I",
        "stringPlus" to "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;",
        "hashCode" to "(Ljava/lang/Object;)I",
    ).map { (name, sig) ->
        PolicyAllowance.ClassLevel.ClassMethodAccess("kotlin.jvm.internal.Intrinsics", name, sig, setOf(AccessTypes.call_Class_Static_Method))
    }

    // Function0..Function22 + FunctionN
    private val functionInterfaceRefs = ((0..22).map { "kotlin.jvm.functions.Function$it" } + "kotlin.jvm.internal.FunctionBase").map {
        PolicyAllowance.ClassLevel.ClassAccess(it, setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance))
    }

    val kotlinBootstrapPolicy = primitiveArrayAccessPolicy + (listOf(
        // Pure synthetic marker; can't be produced by scanning a real Class instance
        PolicyAllowance.ClassLevel.ClassAccess("java.lang.Synthetic", setOf(AccessTypes.ref_Class)),

        // Superclass of every class-compiled Kotlin lambda
        PolicyAllowance.ClassLevel.ClassAccess("kotlin.jvm.internal.Lambda", setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassConstructorAccess("kotlin.jvm.internal.Lambda", "(I)V", setOf(AccessTypes.call_Class_Constructor)),

        // kotlin.Unit: needed by every () -> Unit lambda; not replaceable by Void
        PolicyAllowance.ClassLevel.ClassAccess("kotlin.Unit", setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassFieldAccess("kotlin.Unit", "INSTANCE", "Lkotlin/Unit;", setOf(AccessTypes.read_Class_Static_Field)),

        // Nullability annotations kotlinc stamps on signatures
        PolicyAllowance.ClassLevel.ClassAccess("org.jetbrains.annotations.NotNull", setOf(AccessTypes.ref_Class)),
        PolicyAllowance.ClassLevel.ClassAccess("org.jetbrains.annotations.Nullable", setOf(AccessTypes.ref_Class)),

        // Intrinsics class itself
        PolicyAllowance.ClassLevel.ClassAccess("kotlin.jvm.internal.Intrinsics", setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Static)),

        // Kotlin metadata annotations present on (nearly) every Kotlin class
        PolicyAllowance.ClassLevel.ClassAccess("kotlin.Metadata", setOf(AccessTypes.ref_Class)),
        PolicyAllowance.ClassLevel.ClassAccess("kotlin.jvm.internal.SourceDebugExtension", setOf(AccessTypes.ref_Class)),

        // @JvmSerializableLambda lambdas implement java.io.Serializable
        PolicyAllowance.ClassLevel.ClassAccess("java.io.Serializable", setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance)),
    ) + intrinsicsNullChecks + functionInterfaceRefs).toPolicy()
}
