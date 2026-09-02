package dev.brikk.chill.serialize

import dev.brikk.chill.policy.ALL_CLASS_ACCESS_TYPES
import dev.brikk.chill.quarantine.ClassAllowanceDetector
import dev.brikk.chill.quarantine.DebugInfoStripper
import dev.brikk.chill.quarantine.LambdaVerificationManifest
import dev.brikk.chill.quarantine.NamedClassBytes
import dev.brikk.chill.quarantine.Quarantine
import dev.brikk.chill.quarantine.VerificationCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.ObjectInputFilter
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.OutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.reflect.KClass

/**
 * Safe serialization ("freeze") and deserialization ("thaw") of Kotlin lambdas so they can be
 * shipped and executed elsewhere, verified against a [Quarantine] policy on both sides.
 *
 * Lambdas must be class-compiled: annotate them with `@JvmSerializableLambda` (or build the
 * calling module with `-Xlambdas=class`). Kotlin 2.x compiles lambdas via invokedynamic by
 * default, and those have no extractable class and are not serializable.
 *
 * Note: the embedded signature is an integrity check against accidental corruption, not
 * authentication - the MAC key is a public constant. Verification security comes from the
 * bytecode policy check, which runs again on the receiving side.
 */
class Chill(
    val verifier: Quarantine = Quarantine(),
    /**
     * Build-time verification manifest override (mainly for tests). When null, manifests are
     * loaded from `META-INF/chill/verified-lambdas.manifest` resources visible to the lambda's
     * own classloader (as written by the `dev.brikk.chill` Gradle plugin). A freeze-side
     * verification is skipped when the lambda class hash and policy fingerprint match.
     */
    val buildVerification: Map<String, LambdaVerificationManifest.Entry>? = null,
    /**
     * HMAC-SHA256 key for the payload signature. When null, a well-known public constant is used
     * and the signature is an *integrity* check only (anyone can forge it; security rests on the
     * policy verification). Provide a shared secret to additionally *authenticate* payloads:
     * a receiver configured with the key will reject anything not frozen with the same key.
     */
    hmacKey: ByteArray? = null,
    /**
     * Resource limits enforced (via JEP 290 object input filtering) while deserializing the
     * captured state of a thawed lambda; protects against object-graph bombs built from
     * otherwise policy-allowed classes.
     */
    val thawLimits: ThawLimits = ThawLimits(),
    /**
     * Strip debug attributes (line numbers, local variable names, source file) from shipped class
     * bytes. Execution, verification, and `serialVersionUID` are unaffected; payloads shrink
     * substantially; stack traces from thawed code lose line numbers.
     */
    val stripDebugInfo: Boolean = true,
) {

    data class ThawLimits(
        val maxDepth: Long = 128,
        val maxReferences: Long = 4096,
        val maxArrayLength: Long = 64 * 1024,
        val maxStreamBytes: Long = 8L * 1024 * 1024,
    )

    companion object {
        private const val BINARY_PREFIX = "chill~~"
        private const val MARKER_SIG = "x9a0K1"
        private const val MARKER_VER = 3
        private const val SIG_SEED = "ChillWitMeLambda"
        private const val MAX_SHIPPED_CLASSES = 1024
        private const val MAX_SLOTS = 16

        /**
         * Cap on the inflated envelope. Inputs are already bounded by the transport (OpenSearch
         * limits inline scripts to `script.max_size_in_bytes`, 64 KiB by default) and deflate
         * expands at most ~1000:1, so this only guards a deliberately crafted bomb.
         */
        const val MAX_ENVELOPE_BYTES: Int = 32 * 1024 * 1024

        fun isPrefixedBase64(scriptSource: String): Boolean = scriptSource.startsWith(BINARY_PREFIX)

        /** Envelope bytes -> `chill~~<base64(deflate(bytes))>`. */
        internal fun encodeEnvelope(content: ByteArray): String {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            val compressed = try {
                ByteArrayOutputStream(content.size / 2).also { out ->
                    DeflaterOutputStream(out, deflater).use { it.write(content) }
                }.toByteArray()
            } finally {
                deflater.end()
            }
            return BINARY_PREFIX + Base64.getEncoder().encodeToString(compressed)
        }

        /** Inverse of [encodeEnvelope], bounded by [MAX_ENVELOPE_BYTES]. */
        internal fun decodeEnvelope(scriptSource: String): ByteArray {
            if (!isPrefixedBase64(scriptSource)) throw ClassSerDesException("Script is not valid encoded classes")
            val compressed = Base64.getDecoder().decode(scriptSource.substring(BINARY_PREFIX.length))
            val inflater = Inflater()
            try {
                InflaterInputStream(ByteArrayInputStream(compressed), inflater).use { input ->
                    val out = ByteArrayOutputStream(compressed.size * 4)
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > MAX_ENVELOPE_BYTES) {
                            throw ClassSerDesException("Serialized payload inflates beyond $MAX_ENVELOPE_BYTES bytes")
                        }
                        out.write(buffer, 0, read)
                    }
                    return out.toByteArray()
                }
            } catch (ex: java.util.zip.ZipException) {
                throw ClassSerDesException(
                    "Serialized payload is not a compressed chill envelope, be sure client and server have matching versions",
                    ex,
                )
            } finally {
                inflater.end()
            }
        }

        private val manifestByLoader = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<ClassLoader, Map<String, LambdaVerificationManifest.Entry>>(),
        )
    }

    private fun manifestFor(classLoader: ClassLoader): Map<String, LambdaVerificationManifest.Entry> =
        buildVerification ?: manifestByLoader.getOrPut(classLoader) { LambdaVerificationManifest.loadAll(classLoader) }

    /**
     * True when [classBytes] was verified at build time under the same policy this instance uses.
     */
    fun isBuildTimeVerified(classBytes: NamedClassBytes, classLoader: ClassLoader): Boolean {
        val entry = manifestFor(classLoader)[classBytes.className] ?: return false
        return entry.policyFingerprint == verifier.policyFingerprint &&
                entry.classSha256 == VerificationCache.keyFor(classBytes.bytes)
    }

    private val macKey: ByteArray = hmacKey ?: SIG_SEED.toByteArray()

    private fun newDigest(): Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(macKey, "HmacSHA256"))
    }

    private fun Mac.update(s: String): Mac = apply { update(s.toByteArray()) }
    private fun Mac.update(b: ByteArray): Mac = apply { update(b, 0, b.size) }
    private fun Mac.finishHex(): String = doFinal().joinToString("") { "%02X".format(it) }

    /**
     * A bound input slot of a parameterized lambda: [kind] is interpreted by the consumer (e.g.
     * the OpenSearch plugin defines "params"/"doc"/"source"), [className] is the bound type the
     * lambda expects as the argument in that position.
     */
    data class SlotDescriptor(val kind: String, val className: String)

    class SerializedLambdaClassData(
        val className: String,
        val receiverClassName: String,
        val returnTypeClassName: String,
        val classes: List<NamedClassBytes>,
        val serializedLambda: ByteArray,
        val verification: Quarantine.VerifyResults,
        val slots: List<SlotDescriptor> = emptyList(),
    )

    inline fun <reified R : Any, reified T : Any> deserFromPrefixedBase64(
        scriptSource: String,
        additionalPolicies: Set<String> = emptySet()
    ): SerializedLambdaClassData {
        return deserFromPrefixedBase64(R::class, T::class, scriptSource, additionalPolicies)
    }

    fun <R : Any, T : Any> deserFromPrefixedBase64(
        lambdaReceiver: KClass<R>,
        lambdaReturnType: KClass<T>,
        scriptSource: String,
        additionalPolicies: Set<String> = emptySet()
    ): SerializedLambdaClassData {
        val content = deserFunctionFromPrefixedBase64(scriptSource, additionalPolicies)
        val receiverClassName = lambdaReceiver.java.name
        val returnTypeClassName = lambdaReturnType.java.name
        if (receiverClassName != content.receiverClassName) throw ClassSerDesException("Serialized lambda does not have expected receiver $receiverClassName, instead is ${content.receiverClassName}")
        if (returnTypeClassName != content.returnTypeClassName) throw ClassSerDesException("Serialized lambda does not have expected return type $returnTypeClassName, instead is ${content.returnTypeClassName}")
        return content
    }

    /**
     * Verifies and reads a payload without prescribing its receiver and return type. Consumers must
     * validate [SerializedLambdaClassData.receiverClassName] and [SerializedLambdaClassData.returnTypeClassName]
     * before invocation.
     */
    fun deserFunctionFromPrefixedBase64(
        scriptSource: String,
        additionalPolicies: Set<String> = emptySet()
    ): SerializedLambdaClassData {
        try {
            val decodedBinary = decodeEnvelope(scriptSource)
            val content = DataInputStream(ByteArrayInputStream(decodedBinary)).use { stream ->
                val markerSig = stream.readString()
                val markerVer = stream.readInt()

                if (MARKER_SIG != markerSig || MARKER_VER != markerVer) {
                    throw ClassSerDesException("Serialized class has wrong signature or version, be sure client and server have matching versions")
                }

                val className = stream.readString()
                val checkReceiverClassName = stream.readString()
                val checkReturnTypeClassName = stream.readString()
                val slots = stream.readInt().let { count ->
                    if (count < 0 || count > MAX_SLOTS) throw ClassSerDesException("Serialized slot count $count is out of bounds")
                    (1..count).map { SlotDescriptor(kind = stream.readString(), className = stream.readString()) }
                }
                val classes = stream.readInt().let { count ->
                    if (count < 0 || count > MAX_SHIPPED_CLASSES) throw ClassSerDesException("Serialized class count $count is out of bounds")
                    (1..count).map {
                        val name = stream.readString()
                        val bytes = stream.readByteArray()
                        NamedClassBytes(name, bytes)
                    }
                }

                val serializedInstanceBytes = stream.readByteArray()

                val sentSig = stream.readString()

                val digest = newDigest()
                digest.update(className)
                digest.update(checkReceiverClassName)
                digest.update(checkReturnTypeClassName)
                slots.forEach {
                    digest.update(it.kind)
                    digest.update(it.className)
                }
                classes.forEach {
                    digest.update(it.className)
                    digest.update(it.bytes)
                }
                digest.update(serializedInstanceBytes)
                val calcSig = digest.finishHex()

                // constant-time comparison: with a secret hmacKey the signature is an
                // authentication tag, so avoid leaking a byte-position oracle
                if (!java.security.MessageDigest.isEqual(sentSig.toByteArray(), calcSig.toByteArray())) {
                    throw ClassSerDesException("Serialized classes signature is not valid")
                }
                val verification = verifier.verifyClassAgainstPolicies(classes, additionalPolicies)
                if (verification.failed) {
                    throw ClassSerDerViolationsException(
                        "The Lambda classes have invalid references:  \n${verification.violationsAsString()}",
                        verification.violations
                    )
                }

                SerializedLambdaClassData(
                    className,
                    checkReceiverClassName,
                    checkReturnTypeClassName,
                    verification.filteredClasses,
                    serializedInstanceBytes,
                    verification,
                    slots,
                )
            }
            return content
        } catch (ex: Throwable) {
            if (ex is ClassSerDesException) throw ex
            throw ClassSerDesException(ex.message ?: "unknown error", ex)
        }
    }

    private fun DataInputStream.readString(): String = this.readUTF()
    private fun DataInputStream.readByteArray(): ByteArray {
        val bytesSize = readInt()
        // validate the attacker-controlled length prefix against the bytes actually present
        // BEFORE allocating (a forged prefix must not force a multi-GB allocation)
        if (bytesSize < 0 || bytesSize > available()) {
            throw ClassSerDesException("Serialized byte block length $bytesSize exceeds remaining payload")
        }
        val bytesBuffer = ByteArray(bytesSize)
        readFully(bytesBuffer)
        return bytesBuffer
    }

    private fun DataOutputStream.writeString(s: String) = this.writeUTF(s)
    private fun DataOutputStream.writeByteArray(b: ByteArray) {
        writeInt(b.size)
        write(b)
    }

    inline fun <reified R : Any, reified T : Any> serializeLambdaToBase64(
        additionalPolicies: Set<String> = emptySet(),
        noinline lambda: R.() -> T?
    ): String {
        return serializeLambdaToBase64(R::class, T::class, additionalPolicies, lambda = lambda)
    }

    inline fun <reified R : Any, reified T : Any> serializeLambdaToBase64(noinline lambda: R.() -> T?): String {
        return serializeLambdaToBase64(R::class, T::class, emptySet(), lambda = lambda)
    }

    // TODO: handle types with generics
    fun <R : Any, T : Any> serializeLambdaToBase64(
        lambdaReceiver: KClass<R>,
        lambdaReturnType: KClass<T>,
        additionalPolicies: Set<String> = emptySet(),
        shipClasses: List<Class<*>> = emptyList(),
        lambda: R.() -> T?,
    ): String = serializeFunctionToBase64(
        lambdaReceiver,
        lambdaReturnType,
        emptyList(),
        additionalPolicies,
        shipClasses,
        lambda
    )

    /**
     * Freezes a parameterized lambda: a `R.(A0, A1, ...) -> T?` function whose bound argument
     * types are described by [slots] (in parameter order). The lambda must be class-compiled
     * (`@ChillLambda`). Slot values are never part of the payload - they are supplied by the
     * executing side at invocation time.
     */
    fun serializeFunctionToBase64(
        lambdaReceiver: KClass<*>,
        lambdaReturnType: KClass<*>,
        slots: List<SlotDescriptor>,
        additionalPolicies: Set<String> = emptySet(),
        /**
         * Extra classes to verify and ship alongside the lambda (e.g. bound slot classes or
         * helper classes that are not nested relatives of the lambda's declaring class).
         */
        shipClasses: List<Class<*>> = emptyList(),
        lambda: Any,
    ): String {
        require(slots.size <= MAX_SLOTS) { "Too many slots: ${slots.size}" }
        val serClassBytes = NamedClassBytes.fromLambda(lambda) // fails fast for invokedynamic lambdas
        val serClass = lambda.javaClass
        val className = serClassBytes.className
        val receiverClassName = lambdaReceiver.java.name
        val returnTypeClassName = lambdaReturnType.java.name

        val classScanResults = ClassAllowanceDetector.scanClassByteCodeForDesiredAllowances(listOf(serClassBytes))
        val accessedClassNames =
            classScanResults.allowances.filter { allowance -> allowance.actions.any { it in ALL_CLASS_ACCESS_TYPES } }
                .map { it.fqnTarget }.plus(className).toSet()

        // serialize the lambda instance while tracing every class in the captured object graph
        val classesIncludedInSerialization = mutableSetOf<Class<out Any>>()
        val serializedBytes = ByteArrayOutputStream().apply {
            TraceUsedClassesObjectOutputStream(this, classesIncludedInSerialization).use { stream ->
                stream.writeObject(lambda)
            }
        }.toByteArray()
        val serializedClassNames = classesIncludedInSerialization.map { it.name }.toSet()

        // take the starting lambda, and maybe its outer and inner classes but only if they are referenced
        val outerClasses = generateSequence<Class<*>>(serClass) { seed -> seed.declaringClass.takeIf { it != seed } } +
                generateSequence<Class<*>>(serClass) { seed -> seed.enclosingClass.takeIf { it != seed } }
        val innerClasses = generateSequence<List<Class<*>>>(listOf<Class<*>>(serClass)) { seed ->
            val l = seed.map { c -> c.classes.filterNot { it == c }.toList() }.flatten()
            if (l.isEmpty()) null else l
        }.flatten()
        val shipClassBytes =
            shipClasses.associate { it.name to NamedClassBytes.fromClassLoader(it.name, it.classLoader) }
        val serClassRelatives = (innerClasses + outerClasses + serClass).map { it.name }.toSet() + shipClassBytes.keys

        val classesToVerifyAndShip =
            ((accessedClassNames + serializedClassNames + className).filter { it in serClassRelatives } + shipClassBytes.keys).distinct()

        val serializedClassesToVerifyAccess = serializedClassNames.filterNot { it in serClassRelatives }
        val serializedClassVerificationResult =
            verifier.verifyClassNamesAgainstPolicies(serializedClassesToVerifyAccess, additionalPolicies)
        if (serializedClassVerificationResult.failed) {
            throw ClassSerDerViolationsException(
                "The Lambda causes serialization of classes not in policy:  \n${serializedClassVerificationResult.violationsAsString()}",
                serializedClassVerificationResult.violations
            )
        }

        val classesToVerifyAndShipAsBytes = classesToVerifyAndShip.map { name ->
            when {
                name == className -> serClassBytes
                name in shipClassBytes -> shipClassBytes.getValue(name)
                else -> NamedClassBytes.fromClassLoader(name, serClass.classLoader)
            }
        }

        // When the build already verified this exact lambda class under the same policy, and the
        // lambda ships alone (no relatives), the freeze-side verification can be skipped. The
        // receiving side never trusts this and always re-verifies.
        val preVerified = classesToVerifyAndShipAsBytes.size == 1 &&
                isBuildTimeVerified(serClassBytes, serClass.classLoader)

        val verifiedClassesToShip = if (preVerified) {
            verifier.filterKnownClasses(classesToVerifyAndShipAsBytes, additionalPolicies)
        } else {
            val verification = verifier.verifyClassAgainstPolicies(classesToVerifyAndShipAsBytes, additionalPolicies)
            if (verification.failed) {
                throw ClassSerDerViolationsException(
                    "The Lambda classes have invalid references:  \n${verification.violationsAsString()}",
                    verification.violations
                )
            }
            verification.filteredClasses
        }
        // verified as compiled; shipped without debug attributes (the receiver re-verifies what it gets)
        val actualClassesToShipAsBytes =
            if (stripDebugInfo) verifiedClassesToShip.map(DebugInfoStripper::strip) else verifiedClassesToShip

        val content = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { stream ->
                stream.writeString(MARKER_SIG)
                stream.writeInt(MARKER_VER)

                stream.writeString(className)
                stream.writeString(receiverClassName)
                stream.writeString(returnTypeClassName)
                stream.writeInt(slots.size)
                slots.forEach {
                    stream.writeString(it.kind)
                    stream.writeString(it.className)
                }
                stream.writeInt(actualClassesToShipAsBytes.size)
                actualClassesToShipAsBytes.forEach {
                    stream.writeString(it.className)
                    stream.writeByteArray(it.bytes)
                }

                stream.writeByteArray(serializedBytes)

                val digest = newDigest()
                digest.update(className)
                digest.update(receiverClassName)
                digest.update(returnTypeClassName)
                slots.forEach {
                    digest.update(it.kind)
                    digest.update(it.className)
                }
                actualClassesToShipAsBytes.forEach {
                    digest.update(it.className)
                    digest.update(it.bytes)
                }
                digest.update(serializedBytes)

                stream.writeString(digest.finishHex())
            }
        }.toByteArray()

        return encodeEnvelope(content)
    }

    inline fun <reified R : Any, reified T : Any> instantiateSerializedLambdaSafely(
        className: String,
        serBytes: ByteArray,
        classLoader: ClassLoader,
        additionalPolicies: Set<String> = emptySet()
    ): R.() -> T? {
        return instantiateSerializedLambdaSafely(
            R::class,
            T::class,
            className,
            serBytes,
            classLoader,
            additionalPolicies
        )
    }

    @Suppress("UNCHECKED_CAST", "unused")
    fun <R : Any, T : Any> instantiateSerializedLambdaSafely(
        lambdaReceiver: KClass<R>,
        lambdaReturnType: KClass<T>,
        className: String,
        serBytes: ByteArray,
        classLoader: ClassLoader,
        additionalPolicies: Set<String> = emptySet()
    ): R.() -> T? {
        return instantiateSerializedFunctionSafely(className, serBytes, classLoader, additionalPolicies) as R.() -> T
    }

    /**
     * Deserializes a frozen lambda instance with the policy-gated stream; the caller invokes it
     * as the `kotlin.jvm.functions.FunctionN` matching its receiver + slot arity.
     */
    fun instantiateSerializedFunctionSafely(
        className: String,
        serBytes: ByteArray,
        classLoader: ClassLoader,
        additionalPolicies: Set<String> = emptySet()
    ): Any {
        val tracer = RestrictUsedClassesObjectInputStream(
            verifier,
            additionalPolicies,
            classLoader,
            thawLimits,
            ByteArrayInputStream(serBytes)
        )
        return tracer.use { stream ->
            stream.readObject()
        }
    }

    private class RestrictUsedClassesObjectInputStream(
        val verifier: Quarantine,
        val additionalPolicies: Set<String>,
        val classLoader: ClassLoader,
        limits: ThawLimits,
        input: InputStream,
    ) : ObjectInputStream(input) {

        init {
            // JEP 290 filter as a second enforcement layer alongside readClassDescriptor below:
            // resource limits guard against object-graph bombs built from policy-allowed classes,
            // then every class in the graph is checked against the policy
            objectInputFilter = ObjectInputFilter { info ->
                if (info.depth() > limits.maxDepth ||
                    info.references() > limits.maxReferences ||
                    info.arrayLength() > limits.maxArrayLength ||
                    info.streamBytes() > limits.maxStreamBytes
                ) {
                    return@ObjectInputFilter ObjectInputFilter.Status.REJECTED
                }
                val clazz = info.serialClass() ?: return@ObjectInputFilter ObjectInputFilter.Status.UNDECIDED
                val name = generateSequence(clazz) { it.componentType }.last().name
                if (verifier.verifyClassNamesAgainstPolicies(listOf(name), additionalPolicies).failed) {
                    ObjectInputFilter.Status.REJECTED
                } else {
                    ObjectInputFilter.Status.ALLOWED
                }
            }
        }

        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            val name = desc.name
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: ClassNotFoundException) {
                // ignore, maybe is outside of our classloader?
            }
            return super.resolveClass(desc)
        }

        override fun readClassDescriptor(): ObjectStreamClass {
            val temp = super.readClassDescriptor()
            val verify = verifier.verifyClassNamesAgainstPolicies(listOf(temp.name), additionalPolicies)
            if (verify.failed) {
                throw ClassSerDerViolationsException(
                    "Invalid class ${temp.name} not allowed for lambda deserialization, violations: ${verify.violationsAsString()}",
                    verify.violations
                )
            }
            return temp
        }
    }

    private class TraceUsedClassesObjectOutputStream(
        output: OutputStream,
        val classes: MutableSet<Class<out Any>> = mutableSetOf()
    ) : ObjectOutputStream(output) {
        override fun annotateClass(cl: Class<*>) {
            classes.add(cl)
            super.annotateClass(cl)
        }

        override fun annotateProxyClass(cl: Class<*>) {
            classes.add(cl)
            super.annotateProxyClass(cl)
        }

        override fun writeClassDescriptor(desc: ObjectStreamClass) {
            desc.forClass()?.let { classes.add(it) }
            super.writeClassDescriptor(desc)
        }
    }

    open class ClassSerDesException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
    class ClassSerDerViolationsException(msg: String, val violations: Set<String>, cause: Throwable? = null) :
        ClassSerDesException(msg, cause)
}
