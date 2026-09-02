package dev.brikk.chill.quarantine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ObjectStreamClass
import java.io.Serializable

/** No explicit serialVersionUID: the JVM computes it from the class shape, as for Kotlin lambdas. */
class StrippableFixture(val weight: Double, val labels: List<String>) : Serializable {
    fun score(base: Double): Double {
        val boosted = base * weight
        return if (labels.isEmpty()) boosted else boosted + labels.size
    }
}

class DebugInfoStripperTests {

    private val original = NamedClassBytes.fromClassLoader(StrippableFixture::class.java.name, javaClass.classLoader)

    private class DebugAttributes(var source: String? = null, var lineNumbers: Int = 0, var localVariables: Int = 0)

    private fun debugAttributesOf(bytes: ByteArray): DebugAttributes {
        val found = DebugAttributes()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitSource(source: String?, debug: String?) {
                    found.source = source
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitLineNumber(line: Int, start: Label) {
                            found.lineNumbers++
                        }

                        override fun visitLocalVariable(name: String, descriptor: String, signature: String?, start: Label, end: Label, index: Int) {
                            found.localVariables++
                        }
                    }
            },
            0,
        )
        return found
    }

    @Test
    fun stripsDebugAttributesAndShrinks() {
        val before = debugAttributesOf(original.bytes)
        assertTrue(before.lineNumbers > 0 && before.localVariables > 0 && before.source != null) { "fixture must be compiled with debug info" }

        val stripped = DebugInfoStripper.strip(original)
        val after = debugAttributesOf(stripped.bytes)
        assertNull(after.source)
        assertEquals(0, after.lineNumbers)
        assertEquals(0, after.localVariables)
        assertTrue(stripped.bytes.size < original.bytes.size)
    }

    /**
     * The property thaw depends on: an instance serialized from the compiled class must
     * deserialize into the stripped class, so the computed serialVersionUID must be identical.
     */
    @Test
    fun strippedClassKeepsSerialVersionUidAndBehaviour() {
        val strippedBytes = DebugInfoStripper.strip(original.bytes)
        // child-first for the fixture only, so kotlin-stdlib still resolves from the parent
        val loader = object : ClassLoader(javaClass.classLoader) {
            private val defined by lazy { defineClass(original.className, strippedBytes, 0, strippedBytes.size) }
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == original.className) defined else super.loadClass(name, resolve)
        }
        val strippedClass = Class.forName(original.className, true, loader)
        assertFalse(strippedClass === StrippableFixture::class.java)

        assertEquals(
            ObjectStreamClass.lookup(StrippableFixture::class.java).serialVersionUID,
            ObjectStreamClass.lookup(strippedClass).serialVersionUID,
        )

        val instance = strippedClass.getConstructor(Double::class.java, List::class.java).newInstance(2.0, listOf("a", "b"))
        assertEquals(8.0, strippedClass.getMethod("score", Double::class.java).invoke(instance, 3.0))
    }
}
