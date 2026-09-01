package dev.brikk.chill.serialize

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.quarantine.Quarantine
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InvalidClassException
import java.util.Base64

class HardeningTests {

    private val receiverPolicies = listOf(
        PolicyAllowance.ClassLevel.ClassAccess(MyReceiver::class.java.name, setOf(AccessTypes.ref_Class_Instance)),
        PolicyAllowance.ClassLevel.ClassMethodAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.call_Class_Instance_Method)),
        PolicyAllowance.ClassLevel.ClassPropertyAccess(MyReceiver::class.java.name, "*", "*", setOf(AccessTypes.read_Class_Instance_Property)),
    ).toPolicy().toSet()

    private fun quarantine() = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy + receiverPolicies)

    @Test
    fun forgedLengthPrefixIsRejectedWithoutAllocation() {
        // craft a payload that parses up to the first class-bytes block, then lies about its size
        val content = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { stream ->
                stream.writeUTF("x9a0K1") // marker
                stream.writeInt(3) // version
                stream.writeUTF("com.example.Fake\$fn\$1")
                stream.writeUTF(MyReceiver::class.java.name)
                stream.writeUTF("java.lang.Object")
                stream.writeInt(0) // no slots
                stream.writeInt(1) // one class
                stream.writeUTF("com.example.Fake\$fn\$1")
                stream.writeInt(Int.MAX_VALUE) // forged length prefix, ~2GB
                stream.write(ByteArray(16)) // but almost no actual data
            }
        }.toByteArray()
        val payload = "chill~~" + Base64.getEncoder().encodeToString(content)

        val ex = assertThrows<Chill.ClassSerDesException> {
            Chill(quarantine()).deserFromPrefixedBase64<MyReceiver, Any>(payload)
        }
        assertTrue("exceeds remaining payload" in ex.message!!) { "expected length guard, got: ${ex.message}" }
    }

    @Test
    fun forgedClassCountIsRejected() {
        val content = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { stream ->
                stream.writeUTF("x9a0K1")
                stream.writeInt(3)
                stream.writeUTF("com.example.Fake\$fn\$1")
                stream.writeUTF(MyReceiver::class.java.name)
                stream.writeUTF("java.lang.Object")
                stream.writeInt(0) // no slots
                stream.writeInt(Int.MAX_VALUE) // forged class count
            }
        }.toByteArray()
        val payload = "chill~~" + Base64.getEncoder().encodeToString(content)

        val ex = assertThrows<Chill.ClassSerDesException> {
            Chill(quarantine()).deserFromPrefixedBase64<MyReceiver, Any>(payload)
        }
        assertTrue("out of bounds" in ex.message!!)
    }

    @Test
    fun objectGraphBombIsRejectedByThawLimits() {
        // deeply nested ArrayLists: every class is policy-allowed, only the *shape* is hostile
        var bomb = ArrayList<Any>()
        repeat(300) { bomb = arrayListOf<Any>(bomb) }
        val captured = bomb

        val chill = Chill(quarantine())
        val frozen = chill.serializeLambdaToBase64<MyReceiver, Any>(
            @ChillLambda { captured.size },
        )
        val data = chill.deserFromPrefixedBase64<MyReceiver, Any>(frozen)

        val shippedPolicies = data.classes.map {
            PolicyAllowance.ClassLevel.ClassAccess(it.className, setOf(AccessTypes.ref_Class_Instance))
        }.toPolicy().toSet()
        val loader = BytesClassLoader(data.classes, javaClass.classLoader)

        assertThrows<InvalidClassException> {
            chill.instantiateSerializedLambdaSafely<MyReceiver, Any>(
                data.className, data.serializedLambda, loader, shippedPolicies,
            )
        }
    }

    @Test
    fun secretKeyAuthenticatesPayloads() {
        val keyA = "team-secret-a".toByteArray()
        val keyB = "different-key".toByteArray()

        val sender = Chill(quarantine(), hmacKey = keyA)
        val frozen = sender.serializeLambdaToBase64<MyReceiver, Any>(
            @ChillLambda { score * 2 },
        )

        // matching key accepts
        val sameKey = Chill(quarantine(), hmacKey = keyA)
        sameKey.deserFromPrefixedBase64<MyReceiver, Any>(frozen)

        // wrong key rejects: the signature is now an authentication tag
        val wrongKey = Chill(quarantine(), hmacKey = keyB)
        val ex = assertThrows<Chill.ClassSerDesException> {
            wrongKey.deserFromPrefixedBase64<MyReceiver, Any>(frozen)
        }
        assertTrue("signature is not valid" in ex.message!!)

        // and the public-constant default also rejects key-signed payloads
        val publicDefault = Chill(quarantine())
        assertThrows<Chill.ClassSerDesException> {
            publicDefault.deserFromPrefixedBase64<MyReceiver, Any>(frozen)
        }
    }
}
