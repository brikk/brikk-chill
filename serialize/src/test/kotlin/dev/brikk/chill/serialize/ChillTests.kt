package dev.brikk.chill.serialize

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.Quarantine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Serializable

class MyReceiver(val score: Double, val doc: Map<String, String>) {
    fun docInt(field: String, default: Int): Int = doc[field]?.toInt() ?: default
}

/**
 * Defines the shipped class bytes itself (even when a same-named class exists locally), delegating
 * everything else to [fallback].
 */
class BytesClassLoader(classes: List<dev.brikk.chill.quarantine.NamedClassBytes>, private val fallback: ClassLoader) : ClassLoader(null) {
    private val shipped = classes.associate { it.className to it.bytes }
    private val defined = HashMap<String, Class<*>>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        defined[name]?.let { return it }
        val bytes = shipped[name] ?: return fallback.loadClass(name)
        return defineClass(name, bytes, 0, bytes.size).also { defined[name] = it }
    }
}

class ChillTests : Serializable {

    private val receiverPolicies = listOf(
        PolicyAllowance.ClassLevel.ClassAccess(MyReceiver::class.java.name, setOf(AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassMethodAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.call_Class_Instance_Method)),
        PolicyAllowance.ClassLevel.ClassPropertyAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.read_Class_Instance_Property)),
    ).toPolicy().toSet()

    private val chill = Chill(Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy + receiverPolicies))

    @Test
    fun freezeAndVerifyRoundTrip() {
        val z = 1
        val s = "jayson"

        val frozen = chill.serializeLambdaToBase64<MyReceiver, Any>(
            @JvmSerializableLambda { val x = 10; s + x + z + score + docInt("testField", 1) },
        )
        assertTrue(Chill.isPrefixedBase64(frozen))

        val thawed = chill.deserFromPrefixedBase64<MyReceiver, Any>(frozen)
        assertTrue(thawed.classes.isNotEmpty())
        assertTrue(thawed.className.contains("ChillTests"))
    }

    @Test
    fun frozenLambdaExecutesAfterThaw() {
        val bang = "!"

        val frozen = chill.serializeLambdaToBase64<MyReceiver, Any>(
            @JvmSerializableLambda { "score=" + score + bang },
        )
        val data = chill.deserFromPrefixedBase64<MyReceiver, Any>(frozen)

        // the shipped classes were verified; allow them by name for deserialization and load them
        val shippedPolicies = data.classes.map {
            PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance))
        }.toPolicy().toSet()

        val loader = BytesClassLoader(data.classes, ChillTests::class.java.classLoader)
        val fn = chill.instantiateSerializedLambdaSafely<MyReceiver, Any>(
            data.className, data.serializedLambda, loader, shippedPolicies,
        )

        val result = MyReceiver(41.5, emptyMap()).fn()
        assertEquals("score=41.5!", result)
    }

    @Test
    fun lambdaCallingOutsidePolicyIsRejectedAtFreeze() {
        val ex = assertThrows<Chill.ClassSerDerViolationsException> {
            chill.serializeLambdaToBase64<MyReceiver, Any>(
                @JvmSerializableLambda { System.getenv("PATH") ?: "" },
            )
        }
        assertTrue(ex.violations.any { "System.getenv" in it })
    }

    @Test
    fun unannotatedLambdaFailsFastWithGuidance() {
        val ex = assertThrows<IllegalArgumentException> {
            chill.serializeLambdaToBase64<MyReceiver, Any> { "indy has no class" }
        }
        assertTrue("JvmSerializableLambda" in ex.message!!)
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val frozen = chill.serializeLambdaToBase64<MyReceiver, Any>(
            @JvmSerializableLambda { score * 2 },
        )
        // flip a character in the body of the base64 payload
        val tampered = frozen.toCharArray().also {
            val idx = frozen.length / 2
            it[idx] = if (it[idx] == 'A') 'B' else 'A'
        }.concatToString()

        assertThrows<Chill.ClassSerDesException> {
            chill.deserFromPrefixedBase64<MyReceiver, Any>(tampered)
        }
    }

    @Test
    fun mismatchedReceiverIsRejected() {
        val frozen = chill.serializeLambdaToBase64<MyReceiver, Any>(
            @JvmSerializableLambda { score },
        )
        assertThrows<Chill.ClassSerDesException> {
            chill.deserFromPrefixedBase64<String, Any>(frozen)
        }
    }
}
