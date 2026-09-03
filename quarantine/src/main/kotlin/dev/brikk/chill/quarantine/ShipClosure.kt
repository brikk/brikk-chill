package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance

/**
 * Computes the set of classes that must ship alongside a root class so it can be verified and
 * defined on the receiving side: the root, its nested classes, and - transitively - every class
 * its bytecode references that the policy does not already cover and that lives in the same
 * classloader as the root.
 *
 * A `@Serializable` class with an `enum` property references `Kind`, `Kind$Companion` and the
 * enum's serializer; one with a nested `@Serializable` property references `Geo` and
 * `Geo$$serializer`. None of those are lexically nested in the root, so a nested-classes-only ship
 * set left them behind, and the receiver failed with a policy violation that read like a security
 * error. The walk stops at anything the policy allows (stdlib, kotlinx, JDK), so the closure is
 * exactly the user's own type graph.
 */
class ShipClosure(private val verifier: Quarantine, private val additionalPolicies: Set<String> = emptySet()) {

    class UnshippableReferenceException(message: String) : IllegalArgumentException(message)

    fun compute(root: Class<*>): List<Class<*>> = compute(listOf(root))

    fun compute(roots: List<Class<*>>): List<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()

        fun enqueueWithNested(c: Class<*>) {
            if (result.add(c)) {
                queue.addLast(c)
                c.declaredClasses.forEach { enqueueWithNested(it) }
            }
        }
        roots.forEach { enqueueWithNested(it) }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val bytes = NamedClassBytes.fromClassLoaderOrNull(current.name, current.classLoader)
                ?: throw UnshippableReferenceException("${current.name} has no loadable bytecode and cannot ship")
            referencedClassNames(bytes)
                .filterNot { name -> result.any { it.name == name } }
                .filterNot { name -> coveredByPolicy(name) }
                .forEach { name ->
                    val referenced = try {
                        Class.forName(name, false, current.classLoader)
                    } catch (_: ClassNotFoundException) {
                        return@forEach // not loadable here: the verifier reports it as a violation
                    }
                    if (referenced.classLoader !== current.classLoader) {
                        // outside the user's loader and not allowed by policy: the verifier will
                        // report it; shipping a class from another loader is never the answer
                        return@forEach
                    }
                    enqueueWithNested(referenced)
                }
        }
        return result.toList()
    }

    private fun referencedClassNames(bytes: NamedClassBytes): Set<String> =
        ClassAllowanceDetector.scanClassByteCodeForDesiredAllowances(listOf(bytes)).allowances
            .map { it.fqnTarget }
            .filterNot { it.startsWith("[") }
            .toSet()

    private fun coveredByPolicy(name: String): Boolean = with(verifier) {
        PolicyAllowance.ClassLevel.ClassAccess(name, setOf(AccessTypes.ref_Class)).assertAllowance(additionalPolicies) ||
            PolicyAllowance.ClassLevel.ClassAccess(name, setOf(AccessTypes.ref_Class_Instance)).assertAllowance(additionalPolicies)
    }
}
