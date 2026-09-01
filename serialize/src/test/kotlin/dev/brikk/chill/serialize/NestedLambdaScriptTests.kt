package dev.brikk.chill.serialize

import dev.brikk.chill.policy.ALL_PACKAGE_ACCESS_TYPES
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.Quarantine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScriptCtx(val doc: Map<String, List<String>>)

/**
 * The realistic case: a script lambda embedded in a client call chain, full of *nested* lambdas
 * (map/takeIf/filterNot...). Nested lambdas compile via invokedynamic with their bodies as
 * synthetic methods *inside* the shipped lambda class, so the frozen class is self-contained:
 * the scanner sees through LambdaMetafactory and the thawed instance can spin them up again.
 */
class NestedLambdaScriptTests {

    private val receiverPolicies = listOf(
        PolicyAllowance.ClassLevel.ClassAccess(ScriptCtx::class.java.name, setOf(AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassMethodAccess(ScriptCtx::class.java.name, "*", "*", setOf(AccessTypes.call_Class_Instance_Method)),
        PolicyAllowance.ClassLevel.ClassPropertyAccess(ScriptCtx::class.java.name, "*", "*", setOf(AccessTypes.read_Class_Instance_Property)),
    ).toPolicy().toSet()

    // wildcard package allowances for the stdlib facades the script leans on; the per-class
    // generated stdlib policy black-lists whole facade classes when any single member fails,
    // which is too coarse for text/collection helpers used here
    private val stdlibPackagePolicies = listOf(
        PolicyAllowance.PackageAccess("kotlin.text", ALL_PACKAGE_ACCESS_TYPES, requireSealed = false),
        PolicyAllowance.PackageAccess("kotlin.collections", ALL_PACKAGE_ACCESS_TYPES, requireSealed = false),
    ).toPolicy().toSet()

    private val chill = Chill(
        Quarantine(Quarantine.painlessPlusKotlinFullPolicy + receiverPolicies + stdlibPackagePolicies),
    )

    @Test
    fun nestedLambdaScriptFreezesThawsAndExecutes() {
        val taggedLinePattern = """(\w+):(.*)"""

        val frozen = chill.serializeLambdaToBase64<ScriptCtx, Any>(
            @ChillLambda {
                val currentValue = doc["notes"] ?: emptyList()
                currentValue.map { value -> taggedLinePattern.toRegex().matchEntire(value)?.takeIf { it.groups.size > 2 } }
                    .filterNotNull()
                    .map { match ->
                        val typeName = match.groups[1]!!.value.lowercase()
                        match.groups[2]!!.value.split(',')
                            .map { it.trim().lowercase() }
                            .filterNot { it.isBlank() }
                            .map { "$typeName: $it" }
                    }.flatten()
            },
        )

        val data = chill.deserFromPrefixedBase64<ScriptCtx, Any>(frozen)

        val shippedPolicies = data.classes.map {
            PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance))
        }.toPolicy().toSet()

        val loader = BytesClassLoader(data.classes, javaClass.classLoader)
        val fn = chill.instantiateSerializedLambdaSafely<ScriptCtx, Any>(
            data.className, data.serializedLambda, loader, shippedPolicies,
        )

        val ctx = ScriptCtx(
            doc = mapOf(
                "notes" to listOf(
                    "alpha: One,TWO ,  ",
                    "no-separator-line",
                    "beta:three-four",
                ),
            ),
        )

        assertEquals(
            listOf("alpha: one", "alpha: two", "beta: three-four"),
            ctx.fn(),
        )
    }
}
