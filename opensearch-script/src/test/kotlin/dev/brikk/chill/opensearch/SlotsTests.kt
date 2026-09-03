package dev.brikk.chill.opensearch

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZonedDateTime

@Serializable
class Wrapper<T>(val value: T, val weight: Double = 1.0)

@Serializable
class Plain(val weight: Double = 1.0, @Contextual val at: ZonedDateTime? = null, val tags: List<String> = emptyList())

class SlotsTests {

    /**
     * The payload names the bound class only; the server resolves `serializer(Class)`. A generic
     * bound type freezes fine and fails on the node ("Serializer for class 'Wrapper' is not
     * found"), so it must be refused at the slot, client side, with the reason.
     */
    @Test
    fun genericBoundTypesAreRefusedAtTheSlotNotOnTheServer() {
        for (attempt in listOf<() -> Any>(
            { paramOf(Wrapper("x")) },
            { paramType<Wrapper<Int>>() },
            { docType<Wrapper<String>>() },
            { sourceType<Wrapper<Double>>() },
        )) {
            val ex = assertThrows<IllegalArgumentException> { attempt() }
            assertTrue("Wrapper" in ex.message!! && "server" in ex.message!!) { ex.message }
        }
    }

    @Test
    fun ordinaryBoundTypesIncludingContextualAndCollectionsPass() {
        paramOf(Plain())
        paramType<Plain>()
        docType<Plain>()
        sourceType<Plain>()
    }
}
