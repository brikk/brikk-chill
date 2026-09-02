package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy

/**
 * Hand-written allowances for the bytecode the kotlinx-serialization *compiler plugin* emits into
 * a `@Serializable` class, its `Companion`, and its `$serializer`. These reference a little
 * kotlin plumbing that is not part of the kotlinx runtime jar and so cannot come out of the jar
 * scan: name-only reflection metadata (works without kotlin-reflect, cannot invoke anything),
 * annotation type references (zero capability), and the lazy-descriptor enum constants.
 */
object KotlinxSerializationSupportPolicies {

    val policy: Set<String> = (
        listOf(
            PolicyAllowance.ClassLevel.ClassAccess("kotlin.jvm.internal.Reflection", setOf(AccessTypes.ref_Class_Static)),
            PolicyAllowance.ClassLevel.ClassMethodAccess("kotlin.jvm.internal.Reflection", "*", "*", setOf(AccessTypes.call_Class_Static_Method)),
            PolicyAllowance.ClassLevel.ClassAccess("kotlin.reflect.KClass", setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance)),
            PolicyAllowance.ClassLevel.ClassAccess(
                "kotlin.LazyThreadSafetyMode",
                setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance, AccessTypes.ref_Class_Static),
            ),
        ) +
            listOf("kotlin.jvm.JvmStatic", "kotlin.jvm.JvmField", "kotlin.Deprecated", "kotlin.DeprecationLevel", "kotlin.ReplaceWith")
                .map { PolicyAllowance.ClassLevel.ClassAccess(it, setOf(AccessTypes.ref_Class)) } +
            listOf("SYNCHRONIZED", "PUBLICATION", "NONE").map {
                PolicyAllowance.ClassLevel.ClassFieldAccess(
                    "kotlin.LazyThreadSafetyMode", it, "Lkotlin/LazyThreadSafetyMode;", setOf(AccessTypes.read_Class_Static_Field),
                )
            }
        ).toPolicy().toSet()
}
