package dev.brikk.chill.quarantine.limits

import dev.brikk.chill.quarantine.NamedClassBytes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Rewrites verified, untrusted class bytes so that their execution is bounded at runtime:
 *
 *  - **loops**: an [ExecutionBudget.tick] call is inserted before every backward branch (any
 *    jump or switch whose target precedes it), so each loop iteration draws from the per-thread
 *    budget the executing side arms with [ExecutionBudget.begin]
 *  - **regular expressions**: at every call site of a regex operation taking a `CharSequence`
 *    (`kotlin.text.Regex` methods, the `kotlin.text.StringsKt` regex extensions,
 *    `java.util.regex.Pattern` / `Matcher`) the input argument is passed through
 *    [LimitedCharSequence.wrap]; `Pattern.asPredicate()` is redirected to
 *    [LimitedCharSequence.limitedPredicate]
 *  - method-reference handles to those regex operations (`regex::matches`) have no call site to
 *    rewrite and are rejected with [InstrumentationRejectedException]; a lambda body works instead
 *
 * Neither rewrite changes stack depth or introduces locals that live across a branch target, so
 * the class's existing stack map frames remain valid and nothing is recomputed - which also
 * means no class needs loading during instrumentation. Run this *after* policy verification (the
 * inserted calls target trusted runtime classes, not policy) and before the class is defined.
 */
class ExecutionLimitInstrumenter {

    class InstrumentationRejectedException(message: String) : RuntimeException(message)

    /** A regex operation whose `CharSequence` argument at [inputArgIndex] (descriptor args, 0-based) is limited. */
    private class LimitedCall(val inputArgIndex: Int)

    private companion object {
        const val BUDGET = "dev/brikk/chill/quarantine/limits/ExecutionBudget"
        const val LIMITED = "dev/brikk/chill/quarantine/limits/LimitedCharSequence"
        val CHAR_SEQUENCE: Type = Type.getType(CharSequence::class.java)
        const val PATTERN = "java/util/regex/Pattern"
        const val AS_PREDICATE_DESC = "()Ljava/util/function/Predicate;"
        const val LIMITED_PREDICATE_DESC = "(Ljava/util/regex/Pattern;)Ljava/util/function/Predicate;"

        fun key(owner: String, name: String, desc: String) = "$owner.$name$desc"

        /**
         * Regex operations discovered from the runtime's own classes, so the table always matches
         * the stdlib and JDK actually present (including Kotlin's synthetic `name$default` bridges).
         */
        val limitedCalls: Map<String, LimitedCall> = buildMap {
            fun add(owner: Class<*>, method: Method, requireParam: Class<*>? = null) {
                val params = method.parameterTypes
                if (requireParam != null && params.none { it == requireParam }) return
                val input = params.indexOfFirst { it == CharSequence::class.java }
                if (input < 0) return
                val desc = Type.getMethodDescriptor(method)
                val call = LimitedCall(input)
                put(key(Type.getInternalName(owner), method.name, desc), call)
                put(key(Type.getInternalName(method.declaringClass), method.name, desc), call)
            }
            Regex::class.java.methods.forEach { add(Regex::class.java, it) }
            // the facade users call; static extension functions taking a Regex
            val stringsKt = Class.forName("kotlin.text.StringsKt")
            stringsKt.methods.filter { Modifier.isStatic(it.modifiers) }.forEach { add(stringsKt, it, requireParam = Regex::class.java) }
            Pattern::class.java.methods.forEach { add(Pattern::class.java, it) }
            Matcher::class.java.methods.forEach { add(Matcher::class.java, it) }
        }

        val asPredicateKey = key(PATTERN, "asPredicate", AS_PREDICATE_DESC)
    }

    fun instrument(clazz: NamedClassBytes): NamedClassBytes = NamedClassBytes(clazz.className, instrument(clazz.bytes))

    fun instrument(bytes: ByteArray): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        node.methods.forEach { instrument(node.name, it) }
        val writer = ClassWriter(0) // frames and max stack are unchanged; max locals is maintained by hand
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun instrument(className: String, method: MethodNode) {
        val insns = method.instructions
        if (insns.size() == 0) return

        val position = HashMap<AbstractInsnNode, Int>(insns.size())
        insns.forEachIndexed { i, insn -> position[insn] = i }
        fun isBackward(from: AbstractInsnNode, target: LabelNode) = position.getValue(target) <= position.getValue(from)

        val loopBranches = ArrayList<AbstractInsnNode>()
        val regexCalls = ArrayList<Pair<MethodInsnNode, LimitedCall>>()
        val predicateCalls = ArrayList<MethodInsnNode>()

        for (insn in insns) {
            when (insn) {
                is JumpInsnNode -> if (isBackward(insn, insn.label)) loopBranches += insn
                is TableSwitchInsnNode -> if ((insn.labels + insn.dflt).any { isBackward(insn, it) }) loopBranches += insn
                is LookupSwitchInsnNode -> if ((insn.labels + insn.dflt).any { isBackward(insn, it) }) loopBranches += insn
                is MethodInsnNode -> {
                    val k = key(insn.owner, insn.name, insn.desc)
                    limitedCalls[k]?.let { regexCalls += insn to it }
                    if (k == asPredicateKey) predicateCalls += insn
                }
                is InvokeDynamicInsnNode -> insn.bsmArgs.filterIsInstance<Handle>().forEach { rejectRegexHandle(className, method, it) }
                is LdcInsnNode -> (insn.cst as? Handle)?.let { rejectRegexHandle(className, method, it) }
            }
        }

        loopBranches.forEach { branch ->
            insns.insertBefore(branch, MethodInsnNode(Opcodes.INVOKESTATIC, BUDGET, "tick", "()V", false))
        }
        regexCalls.forEach { (call, limited) -> limitInput(method, insns, call, limited) }
        predicateCalls.forEach { call ->
            insns.set(call, MethodInsnNode(Opcodes.INVOKESTATIC, LIMITED, "limitedPredicate", LIMITED_PREDICATE_DESC, false))
        }
    }

    private fun rejectRegexHandle(className: String, method: MethodNode, handle: Handle) {
        val k = key(handle.owner, handle.name, handle.desc)
        if (k in limitedCalls || k == asPredicateKey) {
            throw InstrumentationRejectedException(
                "$className.${method.name}: method reference to regex operation ${handle.owner.replace('/', '.')}.${handle.name} " +
                    "cannot be execution-limited; call it inside a lambda body instead",
            )
        }
    }

    /**
     * Before `call`, the stack holds `[..., recv?, a0 .. a(n-1)]`. Arguments after the input are
     * spilled to fresh locals, the input is wrapped in place, and the spilled arguments reloaded.
     * The locals are only live inside this basic block, so frames need no update; `maxLocals`
     * grows to cover them.
     */
    private fun limitInput(method: MethodNode, insns: InsnList, call: MethodInsnNode, limited: LimitedCall) {
        val args = Type.getArgumentTypes(call.desc)
        val p = limited.inputArgIndex
        check(args[p] == CHAR_SEQUENCE) { "regex input parameter must be CharSequence: ${call.owner}.${call.name}${call.desc}" }

        val trailing = args.drop(p + 1)
        var next = method.maxLocals
        val slots = trailing.map { t -> next.also { next += t.size } }
        method.maxLocals = maxOf(method.maxLocals, next)

        val patch = InsnList()
        for (i in trailing.indices.reversed()) {
            patch.add(VarInsnNode(trailing[i].getOpcode(Opcodes.ISTORE), slots[i]))
        }
        patch.add(MethodInsnNode(Opcodes.INVOKESTATIC, LIMITED, "wrap", "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", false))
        for (i in trailing.indices) {
            patch.add(VarInsnNode(trailing[i].getOpcode(Opcodes.ILOAD), slots[i]))
        }
        insns.insertBefore(call, patch)
    }
}
