package dev.brikk.chill.quarantine

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

/**
 * Removes debug-only attributes from class bytes: `LineNumberTable`, `LocalVariableTable`,
 * `LocalVariableTypeTable`, `SourceFile`, `SourceDebugExtension`, `MethodParameters`.
 *
 * None of these affect execution, verification (the allowance scan only sees type references
 * that also appear in instructions), or `serialVersionUID` (computed from member names and
 * descriptors), so shipping stripped bytes changes nothing but size - roughly 25-35% smaller for
 * typical Kotlin classes. The visible cost: stack traces from shipped code show no line numbers.
 */
object DebugInfoStripper {

    fun strip(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        // no COMPUTE_* flags: frames and maxs are copied verbatim, nothing is recomputed
        val writer = ClassWriter(0)
        reader.accept(writer, ClassReader.SKIP_DEBUG)
        return writer.toByteArray()
    }

    fun strip(clazz: NamedClassBytes): NamedClassBytes = NamedClassBytes(clazz.className, strip(clazz.bytes))
}
