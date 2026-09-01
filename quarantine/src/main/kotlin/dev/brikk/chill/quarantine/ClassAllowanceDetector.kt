package dev.brikk.chill.quarantine

import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

object ClassAllowanceDetector {
    private const val ASM_API = Opcodes.ASM9

    fun scanClassByteCodeForDesiredAllowances(classNamesWithBytes: List<NamedClassBytes>): ScanState {
        val collected = ScanState()
        classNamesWithBytes.forEach { ClassReader(it.bytes).accept(ClassAllowanceScanner(it.className, collected), 0) }
        return collected
    }

    class ScanState(
        val allowances: MutableList<PolicyAllowance.ClassLevel> = mutableListOf(),
        val createsMethods: MutableList<CreatedClassMethod> = mutableListOf(),
        val createsClass: MutableList<CreatedClass> = mutableListOf(),
        val createsFields: MutableList<CreatedClassField> = mutableListOf(),
    )

    private fun ScanState.requestClassRef(refClass: String) {
        allowances.add(PolicyAllowance.ClassLevel.ClassAccess(refClass, setOf(AccessTypes.ref_Class)))
    }

    private fun ScanState.requestClassInstanceRef(refClass: String) {
        allowances.add(PolicyAllowance.ClassLevel.ClassAccess(refClass, setOf(AccessTypes.ref_Class_Instance)))
    }

    /**
     * Record a type reference from an instruction operand that may be an internal class name
     * ("java/lang/String") or an array descriptor ("[Ljava/lang/String;", "[[I").
     */
    private fun ScanState.requestTypeReference(internalNameOrDesc: String, access: AccessTypes) {
        if (internalNameOrDesc.startsWith("[")) {
            val elementType = Type.getType(internalNameOrDesc).elementType
            when (elementType.sort) {
                Type.OBJECT -> allowances.add(PolicyAllowance.ClassLevel.ClassAccess(elementType.className, setOf(access)))
                // primitive arrays are matched against the bootstrap "[I" style entries
                else -> allowances.add(PolicyAllowance.ClassLevel.ClassAccess("[" + elementType.descriptor, setOf(AccessTypes.ref_Class_Instance)))
            }
        } else {
            allowances.add(PolicyAllowance.ClassLevel.ClassAccess(internalNameOrDesc, setOf(access)))
        }
    }

    private fun ScanState.requestsFromDescSig(desc: String?, signature: String?) {
        // desc is sig without generics
        val sig = signature ?: desc ?: throw IllegalArgumentException("Need either a descriptor or signature")
        SignatureReader(sig).accept(SignatureAllowanceScanner(this))
    }

    /**
     * Record the allowance a method handle constant requires: the exact member the handle points at.
     */
    private fun ScanState.requestsFromHandle(handle: Handle) {
        when (handle.tag) {
            Opcodes.H_INVOKESTATIC ->
                allowances.add(PolicyAllowance.ClassLevel.ClassMethodAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.call_Class_Static_Method)))
            Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESPECIAL, Opcodes.H_INVOKEINTERFACE ->
                allowances.add(PolicyAllowance.ClassLevel.ClassMethodAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.call_Class_Instance_Method)))
            Opcodes.H_NEWINVOKESPECIAL ->
                allowances.add(PolicyAllowance.ClassLevel.ClassConstructorAccess(handle.owner, handle.desc, setOf(AccessTypes.call_Class_Constructor)))
            Opcodes.H_GETFIELD ->
                allowances.add(PolicyAllowance.ClassLevel.ClassFieldAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.read_Class_Instance_Field)))
            Opcodes.H_PUTFIELD ->
                allowances.add(PolicyAllowance.ClassLevel.ClassFieldAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.write_Class_Instance_Field)))
            Opcodes.H_GETSTATIC ->
                allowances.add(PolicyAllowance.ClassLevel.ClassFieldAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.read_Class_Static_Field)))
            Opcodes.H_PUTSTATIC ->
                allowances.add(PolicyAllowance.ClassLevel.ClassFieldAccess(handle.owner, handle.name, handle.desc, setOf(AccessTypes.write_Class_Static_Field)))
            else -> throw IllegalStateException("Unknown method handle tag ${handle.tag} for ${handle.owner}.${handle.name}")
        }
        requestsFromDescSig(handle.desc, null)
    }

    /**
     * Record allowances required by a constant appearing in the constant pool: either as an LDC
     * operand or as a bootstrap method argument of an invokedynamic/condy instruction.
     */
    private fun ScanState.requestsFromConstant(value: Any?) {
        when (value) {
            is Type -> when (value.sort) {
                Type.OBJECT -> requestClassRef(value.className)
                Type.ARRAY -> requestTypeReference(value.descriptor, AccessTypes.ref_Class)
                Type.METHOD -> requestsFromDescSig(value.descriptor, null)
                else -> { /* primitive type constant */ }
            }
            is Handle -> requestsFromHandle(value)
            is ConstantDynamic -> {
                requestsFromBootstrap(value.bootstrapMethod, (0 until value.bootstrapMethodArgumentCount).map { value.getBootstrapMethodArgument(it) }, value.descriptor)
            }
            // String / boxed primitives carry no type access beyond their own literal
            else -> { }
        }
    }

    /**
     * Bootstrap methods the JVM/javac/kotlinc emit as pure language plumbing. The factory itself
     * grants no capability: everything it can wire together arrives as method handle or type
     * constants in the bootstrap arguments, and those are each checked individually.
     */
    private val INTRINSIC_BOOTSTRAP_OWNERS = setOf(
        "java/lang/invoke/LambdaMetafactory",
        "java/lang/invoke/StringConcatFactory",
        "java/lang/runtime/ObjectMethods",
        "java/lang/runtime/SwitchBootstraps",
        "java/lang/invoke/ConstantBootstraps",
    )

    private fun ScanState.requestsFromBootstrap(bsm: Handle, bsmArgs: List<Any?>, dynamicDesc: String?) {
        if (bsm.owner !in INTRINSIC_BOOTSTRAP_OWNERS) {
            // Unknown bootstrap: fail closed by requiring an explicit allowance for the bootstrap
            // method itself (policies will not normally contain one).
            requestsFromHandle(bsm)
        }

        // The dynamic call site descriptor: return type (e.g. the functional interface for
        // LambdaMetafactory) plus captured argument types.
        dynamicDesc?.let { desc ->
            val type = Type.getType(desc)
            if (type.sort == Type.METHOD) {
                val ret = type.returnType
                if (ret.sort == Type.OBJECT) requestClassInstanceRef(ret.className)
                requestsFromDescSig(desc, null)
            } else {
                requestsFromConstant(type)
            }
        }

        // Bootstrap arguments carry the real capabilities (e.g. the implMethod handle of a lambda).
        bsmArgs.forEach { requestsFromConstant(it) }
    }

    private fun ScanState.createClassMethod(accessFlags: Int, className: String, methodName: String, desc: String?, signature: String?, exceptions: List<String>) {
        createsMethods.add(CreatedClassMethod(accessFlags, className, methodName, desc, signature, exceptions))
        exceptions.forEach { requestClassRef(it) }
        requestsFromDescSig(desc, signature)
    }

    private fun ScanState.createClassField(accessFlags: Int, className: String, fieldName: String, desc: String?, signature: String?, value: Any?) {
        createsFields.add(CreatedClassField(accessFlags, className, fieldName, desc, signature, value))
        requestsFromDescSig(desc, signature)
    }

    private fun ScanState.createClass(accessFlags: Int, className: String, signature: String?, superName: String?, interfaces: List<String> = emptyList()) {
        createsClass.add(CreatedClass(accessFlags, className, signature, superName, interfaces))
        superName?.let { requestClassInstanceRef(it) }
        interfaces.forEach { requestClassRef(it) }
        signature?.let { requestsFromDescSig(null, it) }
    }

    data class CreatedClassMethod(val accessFlags: Int, val className: String, val methodName: String, val desc: String?, val signature: String?, val exceptions: List<String>) {
        val isStatic: Boolean = accessFlags.and(Opcodes.ACC_STATIC) != 0
    }

    data class CreatedClass(val accessFlags: Int, val className: String, val signature: String?, val superName: String?, val interfaces: List<String> = emptyList())

    data class CreatedClassField(val accessFlags: Int, val className: String, val fieldName: String, val desc: String?, val signature: String?, val value: Any?) {
        val isStatic: Boolean = accessFlags.and(Opcodes.ACC_STATIC) != 0
    }

    class ClassAllowanceScanner(val myClassName: String, val collect: ScanState) : ClassVisitor(ASM_API) {
        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
            collect.createClass(access, name, signature, superName, interfaces?.toList() ?: emptyList())
        }

        override fun visitMethod(access: Int, name: String, desc: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            collect.createClassMethod(access, myClassName, name, desc, signature, exceptions?.toList() ?: emptyList())
            return MethodAllowanceScanner(myClassName, name, collect)
        }

        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
            // inner classes are caught on "create class" when their own bytes are scanned
        }

        override fun visitOuterClass(owner: String, name: String?, desc: String?) {
            // not needed unless the outer class is itself referenced, which shows up elsewhere
        }

        override fun visitField(access: Int, name: String, desc: String?, signature: String?, value: Any?): FieldVisitor {
            collect.createClassField(access, myClassName, name, desc, signature, value)

            return object : FieldVisitor(ASM_API) {
                override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor {
                    collect.requestsFromDescSig(desc, null)
                    return AnnotationAllowanceScanner(collect)
                }

                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, desc: String?, visible: Boolean): AnnotationVisitor {
                    collect.requestsFromDescSig(desc, typePath?.toString())
                    return AnnotationAllowanceScanner(collect)
                }
            }
        }

        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, null)
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, typePath?.toString())
            return AnnotationAllowanceScanner(collect)
        }
    }

    class MethodAllowanceScanner(val myClassName: String, val methodName: String, val collect: ScanState) : MethodVisitor(ASM_API) {
        override fun visitMultiANewArrayInsn(desc: String?, dims: Int) {
            collect.requestsFromDescSig(desc, null)
        }

        override fun visitFrame(type: Int, nLocal: Int, local: Array<out Any>?, nStack: Int, stack: Array<out Any>?) {
            // frames only restate types already present in the instructions scanned elsewhere
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            when (opcode) {
                Opcodes.NEW,
                Opcodes.ANEWARRAY,
                Opcodes.CHECKCAST,
                Opcodes.INSTANCEOF,
                -> collect.requestTypeReference(type, AccessTypes.ref_Class_Instance)
                else -> throw IllegalStateException("Unknown opcode for method.visitTypeInsn: $opcode")
            }
        }

        override fun visitAnnotationDefault(): AnnotationVisitor = AnnotationAllowanceScanner(collect)

        override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, null)
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, typePath?.toString())
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, typePath?.toString())
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, typePath?.toString())
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitParameterAnnotation(parameter: Int, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, null)
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any?) {
            collect.requestsFromBootstrap(bootstrapMethodHandle, bootstrapMethodArguments.toList(), descriptor)
        }

        override fun visitLdcInsn(value: Any?) {
            collect.requestsFromConstant(value)
        }

        override fun visitLocalVariableAnnotation(typeRef: Int, typePath: TypePath?, start: Array<out Label>?, end: Array<out Label>?, index: IntArray?, desc: String?, visible: Boolean): AnnotationVisitor {
            collect.requestsFromDescSig(desc, typePath?.toString())
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitLocalVariable(name: String?, desc: String?, signature: String?, start: Label?, end: Label?, index: Int) {
            collect.requestsFromDescSig(desc, signature)
        }

        override fun visitParameter(name: String?, access: Int) {
            // noop
        }

        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
            // opcode is either INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC or INVOKEINTERFACE

            if (name == "<init>") {
                if (opcode == Opcodes.INVOKESPECIAL) {
                    collect.allowances.add(PolicyAllowance.ClassLevel.ClassConstructorAccess(owner, desc, setOf(AccessTypes.call_Class_Constructor)))
                } else {
                    throw IllegalStateException("Invalid op code for visitMethodInsn <init>: $opcode")
                }
            } else {
                val access = when (opcode) {
                    Opcodes.INVOKESPECIAL,
                    Opcodes.INVOKEVIRTUAL,
                    Opcodes.INVOKEINTERFACE,
                    -> AccessTypes.call_Class_Instance_Method
                    Opcodes.INVOKESTATIC -> AccessTypes.call_Class_Static_Method
                    else -> throw IllegalStateException("Invalid op code for visitMethodInsn: $opcode, name=$name")
                }
                collect.allowances.add(PolicyAllowance.ClassLevel.ClassMethodAccess(owner, name, desc, setOf(access)))
            }
            collect.requestsFromDescSig(desc, null)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
            // opcode is either GETSTATIC, PUTSTATIC, GETFIELD or PUTFIELD
            val access = when (opcode) {
                Opcodes.GETFIELD -> AccessTypes.read_Class_Instance_Field
                Opcodes.PUTFIELD -> AccessTypes.write_Class_Instance_Field
                Opcodes.GETSTATIC -> AccessTypes.read_Class_Static_Field
                Opcodes.PUTSTATIC -> AccessTypes.write_Class_Static_Field
                else -> throw IllegalStateException("Invalid op code for visitFieldInsn: $opcode")
            }
            collect.allowances.add(PolicyAllowance.ClassLevel.ClassFieldAccess(owner, name, desc, setOf(access)))
            collect.requestsFromDescSig(desc, null)
        }
    }

    class AnnotationAllowanceScanner(val collect: ScanState) : AnnotationVisitor(ASM_API) {
        override fun visitAnnotation(name: String?, desc: String?): AnnotationVisitor {
            collect.requestsFromDescSig(desc, null)
            return AnnotationAllowanceScanner(collect)
        }

        override fun visitEnum(name: String?, desc: String?, value: String?) {
            desc?.let { collect.requestsFromDescSig(it, null) }
        }

        override fun visit(name: String?, value: Any?) {
            collect.requestsFromConstant(value)
        }

        override fun visitArray(name: String?): AnnotationVisitor = AnnotationAllowanceScanner(collect)
    }

    class SignatureAllowanceScanner(val collect: ScanState) : SignatureVisitor(ASM_API) {
        override fun visitParameterType(): SignatureVisitor = this

        override fun visitFormalTypeParameter(name: String) { }

        override fun visitTypeVariable(name: String) { }

        override fun visitInnerClassType(name: String) {
            collect.allowances.add(PolicyAllowance.ClassLevel.ClassAccess(name.replace('/', '.'), setOf(AccessTypes.ref_Class)))
        }

        override fun visitClassType(name: String) {
            collect.allowances.add(PolicyAllowance.ClassLevel.ClassAccess(name.replace('/', '.'), setOf(AccessTypes.ref_Class)))
        }

        override fun visitClassBound(): SignatureVisitor = this
        override fun visitInterface(): SignatureVisitor = this
        override fun visitExceptionType(): SignatureVisitor = this
        override fun visitInterfaceBound(): SignatureVisitor = this
        override fun visitArrayType(): SignatureVisitor = this
        override fun visitSuperclass(): SignatureVisitor = this
        override fun visitReturnType(): SignatureVisitor = this
    }
}
