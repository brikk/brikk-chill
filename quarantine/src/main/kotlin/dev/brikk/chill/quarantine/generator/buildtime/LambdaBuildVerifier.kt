package dev.brikk.chill.quarantine.generator.buildtime

import dev.brikk.chill.policy.ALL_CLASS_ACCESS_TYPES
import dev.brikk.chill.quarantine.ClassAllowanceDetector
import dev.brikk.chill.quarantine.LambdaVerificationManifest
import dev.brikk.chill.quarantine.NamedClassBytes
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.VerificationCache
import org.objectweb.asm.ClassReader
import java.io.File

/**
 * Build-time (post-compilation) verification of serializable lambda classes.
 *
 * Discovery is structural rather than annotation-driven: `EXPRESSION`-target annotations like
 * `@JvmSerializableLambda` (and `@ChillLambda`) have SOURCE retention and never reach bytecode.
 * However, under Kotlin 2.x default invokedynamic lambda compilation, the *only* classes that
 * extend `kotlin.jvm.internal.Lambda` and implement `java.io.Serializable` are exactly the
 * annotated ones - so the class shape is a precise discovery signal.
 *
 * Verification mirrors the freeze-time logic in `Chill`: each lambda class is verified together
 * with the subset of its outer/inner relative classes that it actually references (those get
 * shipped alongside it). Captured variables appear as constructor params/fields of the lambda
 * class and are covered by the scan; runtime-polymorphic captured *instances* are still checked
 * at freeze/thaw time by the serialization trace.
 */
class LambdaBuildVerifier(
    val quarantine: Quarantine,
    val mode: DiscoveryMode = DiscoveryMode.ALL,
    excludeClassPatterns: List<String> = emptyList(),
) {

    enum class DiscoveryMode {
        /** Default-verify: every discovered serializable lambda unless a scope forces off. */
        ALL,

        /** Default-skip: only lambdas whose scope forces on via @ChillVerifyAtBuild. */
        ANNOTATED,
    }

    companion object {
        // descriptor string so this module carries no code dependency on the annotations artifact
        const val VERIFY_ANNOTATION_DESC = "Ldev/brikk/chill/annotations/ChillVerifyAtBuild;"

        private fun globToRegex(pattern: String): Regex =
            pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
    }

    private val excludeRegexes = excludeClassPatterns.map { globToRegex(it) }

    data class LambdaVerification(
        val className: String,
        val classSha256: String,
        val verifiedWith: List<String>,
        val violations: Set<String>,
    ) {
        val passed: Boolean get() = violations.isEmpty()
    }

    /**
     * True when the class bytes have the shape of a class-compiled Kotlin lambda: it extends
     * `kotlin.jvm.internal.Lambda` (which itself implements `java.io.Serializable`). Under the
     * Kotlin 2.x invokedynamic default, only `@JvmSerializableLambda`/`@ChillLambda` annotated
     * lambdas (or `-Xlambdas=class` modules) compile to such classes.
     */
    fun isSerializableLambdaClass(bytes: ByteArray): Boolean {
        return ClassReader(bytes).superName == "kotlin/jvm/internal/Lambda"
    }

    fun listClasses(classesDirs: List<File>): Map<String, File> =
        classesDirs.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .map { file ->
                    val className = file.relativeTo(dir).path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                    className to file
                }
        }.toMap()

    fun discoverLambdaClasses(classesDirs: List<File>): List<NamedClassBytes> =
        listClasses(classesDirs).mapNotNull { (className, file) ->
            val bytes = file.readBytes()
            if (isSerializableLambdaClass(bytes)) NamedClassBytes(className, bytes) else null
        }.sortedBy { it.className }

    fun verify(classesDirs: List<File>): List<LambdaVerification> {
        val allClasses = listClasses(classesDirs)
        val loader: (String) -> NamedClassBytes? = { name -> allClasses[name]?.let { NamedClassBytes(name, it.readBytes()) } }
        return discoverLambdaClasses(classesDirs)
            .filter { inVerificationScope(it, loader) }
            .map { lambdaClass -> verifyLambdaClass(lambdaClass, loader) }
    }

    /**
     * Applies exclude patterns, the nearest enclosing @ChillVerifyAtBuild directive, and finally
     * the mode default to decide whether a discovered lambda class should be verified.
     */
    fun inVerificationScope(lambdaClass: NamedClassBytes, relativeLoader: (String) -> NamedClassBytes?): Boolean {
        val className = lambdaClass.className
        if (excludeRegexes.any { it.matches(className) }) return false

        return resolveScopeDirective(lambdaClass, relativeLoader) ?: when (mode) {
            DiscoveryMode.ALL -> true
            DiscoveryMode.ANNOTATED -> false
        }
    }

    /** Reads @ChillVerifyAtBuild directives (the `enabled` value; true when omitted). */
    private class ClassMeta(bytes: ByteArray) {
        var classDirective: Boolean? = null
        val methodDirectives = mutableMapOf<String, Boolean>() // method name -> enabled
        var outerOwner: String? = null
        var outerMethod: String? = null

        private class DirectiveReader(val onResult: (Boolean) -> Unit) : org.objectweb.asm.AnnotationVisitor(org.objectweb.asm.Opcodes.ASM9) {
            private var enabled = true // annotation default value is not written to bytecode
            override fun visit(name: String?, value: Any?) {
                if (name == "enabled" && value is Boolean) enabled = value
            }
            override fun visitEnd() = onResult(enabled)
        }

        init {
            ClassReader(bytes).accept(object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
                    if (descriptor != VERIFY_ANNOTATION_DESC) return null
                    return DirectiveReader { classDirective = it }
                }

                override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
                    outerOwner = owner.replace('/', '.')
                    outerMethod = name
                }

                override fun visitMethod(access: Int, name: String, descriptor: String?, signature: String?, exceptions: Array<out String>?): org.objectweb.asm.MethodVisitor {
                    return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        override fun visitAnnotation(desc: String, visible: Boolean): org.objectweb.asm.AnnotationVisitor? {
                            if (desc != VERIFY_ANNOTATION_DESC) return null
                            return DirectiveReader { methodDirectives[name] = it }
                        }
                    }
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
    }

    /**
     * Resolves the nearest @ChillVerifyAtBuild directive for a lambda class, or null when no
     * enclosing scope carries one. Precedence, nearest first:
     *
     *  1. the enclosing member: the exact EnclosingMethod, or - derived from the `Outer$member$N`
     *     naming - the member function, property getter, or the synthetic property annotation
     *     holder (`get<Member>$annotations`)
     *  2. class-level directives walking the nesting chain inner -> outer (file-level annotations
     *     land on the facade class, so `@file:` is covered by the outermost step)
     */
    private fun resolveScopeDirective(lambdaClass: NamedClassBytes, relativeLoader: (String) -> NamedClassBytes?): Boolean? {
        val className = lambdaClass.className
        val meta = ClassMeta(lambdaClass.bytes)

        // nesting chain by name (Outer$a$b -> Outer$a -> Outer)
        val chain = generateSequence(className.substringBeforeLast('$', "")) { current ->
            current.substringBeforeLast('$', "").takeIf { it.isNotEmpty() && it != current }
        }.filter { it.isNotEmpty() }.toList()

        val chainMetas = LinkedHashMap<String, ClassMeta>()
        (listOfNotNull(meta.outerOwner) + chain).distinct().forEach { name ->
            relativeLoader(name)?.let { chainMetas[name] = ClassMeta(it.bytes) }
        }

        // 1. member-level directive on the immediate enclosing declaration
        val enclosingClassName = meta.outerOwner ?: chain.firstOrNull()
        val enclosingMeta = enclosingClassName?.let { chainMetas[it] }
        if (enclosingMeta != null) {
            val memberName = className.removePrefix("$enclosingClassName$").substringBefore('$')
            val candidateMethods = buildList {
                meta.outerMethod?.let { add(it) }
                if (memberName.isNotEmpty()) {
                    add(memberName) // enclosing function
                    val capitalized = memberName.replaceFirstChar { it.uppercaseChar() }
                    add("get$capitalized") // property getter
                    add("get$capitalized\$annotations") // synthetic holder for property annotations
                }
            }
            candidateMethods.firstNotNullOfOrNull { enclosingMeta.methodDirectives[it] }?.let { return it }
        }

        // 2. class-level directives, inner to outer
        return chainMetas.values.firstNotNullOfOrNull { it.classDirective }
    }

    fun verifyLambdaClass(lambdaClass: NamedClassBytes, relativeLoader: (String) -> NamedClassBytes?): LambdaVerification {
        val className = lambdaClass.className
        val outermost = className.substringBefore('$')

        val scan = ClassAllowanceDetector.scanClassByteCodeForDesiredAllowances(listOf(lambdaClass))
        val accessed = scan.allowances
            .filter { allowance -> allowance.actions.any { it in ALL_CLASS_ACCESS_TYPES } }
            .map { it.fqnTarget }
            .toSet()

        // same relative-shipping rule as freeze: only outer/inner family members actually referenced
        val relatives = accessed
            .filter { it != className && (it == outermost || it.startsWith("$outermost$")) }
            .mapNotNull { relativeLoader(it) }

        val toVerify = listOf(lambdaClass) + relatives
        val result = quarantine.verifyClassAgainstPolicies(toVerify)

        return LambdaVerification(
            className = className,
            classSha256 = VerificationCache.keyFor(lambdaClass.bytes),
            verifiedWith = relatives.map { it.className },
            violations = result.violations,
        )
    }

    fun manifestEntries(results: List<LambdaVerification>): List<LambdaVerificationManifest.Entry> =
        results.filter { it.passed }.map {
            LambdaVerificationManifest.Entry(
                className = it.className,
                classSha256 = it.classSha256,
                policyFingerprint = quarantine.policyFingerprint,
            )
        }
}
