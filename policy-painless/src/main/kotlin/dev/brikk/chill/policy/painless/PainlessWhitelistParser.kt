package dev.brikk.chill.policy.painless

import dev.brikk.chill.policy.AccessPolicies
import dev.brikk.chill.policy.AccessTypes
import dev.brikk.chill.policy.PolicyAllowance
import dev.brikk.chill.policy.erasedType
import dev.brikk.chill.policy.toPolicy
import dev.brikk.chill.policy.typeToSigPart
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Type

/**
 * Parses Painless whitelist definition files in the modern (Elasticsearch 6.x+/OpenSearch) grammar
 * and produces Chill policy allowances, validating every entry reflectively against the running JDK.
 *
 * Grammar handled:
 * ```
 * class java.fq.Name {          # painless display name derives from the fq name
 *   (params)                    # constructor
 *   ReturnType name(params)     # method; params/returns are painless display names
 *   ReturnType fq.Impl name(p)  # augmented method (impl lives in the painless runtime) - skipped
 *   Type FIELD_NAME             # field
 *   ... @annotation             # trailing annotations (e.g. @nondeterministic) are ignored
 * }
 * static_import { ... }         # painless language-level imports - skipped
 * ```
 *
 * Inheritance: the modern grammar has no `extends` clause; painless resolves it from the real Java
 * hierarchy. Bytecode call sites name the receiver's static type though, so member allowances of
 * every whitelisted ancestor are re-emitted for each whitelisted class.
 *
 * When [strict] is false (default), whitelist entries that don't resolve against the current JDK
 * are skipped with a warning instead of failing: skipping only ever *shrinks* the policy.
 */
class PainlessWhitelistParser(
    val strict: Boolean = false,
    val warn: (String) -> Unit = { System.err.println("[painless-parser] WARN: $it") },
) {

    private val classLineRegex = """^class\s+([\w$][\w\d.$]*)\b[^{]*\{$""".toRegex()

    private val builtinTypes = mapOf(
        "void" to "void",
        "boolean" to "boolean",
        "byte" to "byte",
        "short" to "short",
        "char" to "char",
        "int" to "int",
        "long" to "long",
        "float" to "float",
        "double" to "double",
        "def" to "java.lang.Object",
    )

    data class Source(val name: String, val lines: List<String>)

    fun sourcesFromDirs(dirs: List<File>): List<Source> = dirs.flatMap { dir ->
        (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .sortedBy { it.name }
            .map { Source(it.name, it.readLines()) }
    }

    fun makePolicy(dirs: List<File>): List<String> = readDefinitions(sourcesFromDirs(dirs)).toPolicy()

    fun writePolicy(dirs: List<File>, output: File) {
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            makePolicy(dirs).forEach {
                writer.write(it)
                writer.newLine()
            }
        }
    }

    fun readDefinitions(sources: List<Source>): AccessPolicies {
        // pass 1: painless display name -> fq java name
        val displayToJava = HashMap<String, String>()
        sources.forEach { source ->
            source.cleanLines().forEach { line ->
                classLineRegex.matchEntire(line)?.let { match ->
                    val fqName = match.groupValues[1]
                    val display = fqName.substringAfterLast('.').replace('$', '.')
                    val existing = displayToJava[display]
                    if (existing != null && existing != fqName) {
                        // e.g. java.text.Annotation vs java.lang.annotation.Annotation; first mapping
                        // wins for display-name resolution, both class blocks still parse (member
                        // lines can always use fq names to disambiguate)
                        warn("Painless display name collision: $display -> $existing (kept) and $fqName @ ${source.name}")
                    } else {
                        displayToJava[display] = fqName
                    }
                }
            }
        }

        fun javaTypeName(painlessName: String): String {
            val baseName = painlessName.substringBefore('[')
            val suffix = painlessName.substring(baseName.length)
            val javaBase = builtinTypes[baseName]
                ?: displayToJava[baseName]
                ?: baseName.takeIf { it.contains('.') } // already a fq name
                ?: throw IllegalStateException("Unknown painless type $baseName")
            return javaBase + suffix
        }

        val problems = mutableListOf<String>()
        fun problem(msg: String) {
            problems += msg
            if (!strict) warn(msg)
        }

        // per-class allowances, used later for hierarchy re-emission
        val classAllowances = LinkedHashMap<String, MutableList<PolicyAllowance.ClassLevel>>()
        val classObjects = LinkedHashMap<String, Class<*>>()

        sources.forEach { source ->
            var currentClassName: String? = null
            var currentClass: Class<*>? = null
            var skippingBlock = false

            source.cleanLines().forEach { rawLine ->
                val line = rawLine.removeSuffix(";").trim()
                when {
                    skippingBlock -> if (line == "}") skippingBlock = false

                    line.startsWith("static_import") && line.endsWith("{") -> skippingBlock = true

                    line.startsWith("class ") -> {
                        val match = classLineRegex.matchEntire(line)
                            ?: throw IllegalStateException("Invalid class definition [ $line ] @ ${source.name}")
                        val fqName = match.groupValues[1]
                        try {
                            currentClass = loadClass(fqName)
                            currentClassName = fqName
                            classObjects[fqName] = currentClass
                            // every whitelisted class is referencable (capabilities come from members)
                            classAllowances.getOrPut(fqName) { mutableListOf() }.add(
                                PolicyAllowance.ClassLevel.ClassAccess(fqName, setOf(AccessTypes.ref_Class, AccessTypes.ref_Class_Instance, AccessTypes.ref_Class_Static)),
                            )
                        } catch (ex: ClassNotFoundException) {
                            problem("Class not found on this JDK: $fqName @ ${source.name}")
                            currentClassName = null
                            currentClass = null
                            skippingBlock = true
                        }
                    }

                    line == "}" -> {
                        currentClassName = null
                        currentClass = null
                    }

                    else -> {
                        val className = currentClassName
                        val clazz = currentClass
                        if (className == null || clazz == null) {
                            throw IllegalStateException("Member line outside class block [ $line ] @ ${source.name}")
                        }
                        try {
                            parseMember(line, source.name, className, clazz, ::javaTypeName)
                                ?.let { classAllowances.getValue(className).addAll(it) }
                        } catch (ex: MemberResolutionException) {
                            problem(ex.message!!)
                        }
                    }
                }
            }
        }

        if (strict && problems.isNotEmpty()) {
            throw IllegalStateException("Painless whitelist entries failed to resolve:\n  " + problems.joinToString("\n  "))
        }

        // Hierarchy re-emission: bytecode call sites name the receiver's static type, so each class
        // inherits the member allowances of every whitelisted ancestor (methods/fields/properties;
        // constructors don't inherit).
        val inheritedAllowances = classObjects.flatMap { (fqName, clazz) ->
            clazz.allAncestors()
                .mapNotNull { ancestor -> classAllowances[ancestor.name]?.takeIf { ancestor.name != fqName } }
                .flatten()
                .mapNotNull { allowance ->
                    when (allowance) {
                        is PolicyAllowance.ClassLevel.ClassMethodAccess ->
                            PolicyAllowance.ClassLevel.ClassMethodAccess(fqName, allowance.methodName, allowance.methodSig, allowance.actions)
                        is PolicyAllowance.ClassLevel.ClassFieldAccess ->
                            PolicyAllowance.ClassLevel.ClassFieldAccess(fqName, allowance.fieldName, allowance.fieldTypeSig, allowance.actions)
                        is PolicyAllowance.ClassLevel.ClassPropertyAccess ->
                            PolicyAllowance.ClassLevel.ClassPropertyAccess(fqName, allowance.propertyName, allowance.propertyTypeSig, allowance.actions)
                        else -> null
                    }
                }
        }

        return (classAllowances.values.flatten() + inheritedAllowances).sortedBy { it.fqnTarget }
    }

    private class MemberResolutionException(message: String) : Exception(message)

    private fun parseMember(
        line: String,
        sourceName: String,
        className: String,
        clazz: Class<*>,
        javaTypeName: (String) -> String,
    ): List<PolicyAllowance.ClassLevel>? {
        val cleaned = annotationRegex.replace(line, "").trim()

        return when {
            cleaned.startsWith("(") -> {
                val params = paramList(cleaned)
                resolveConstructor(sourceName, className, clazz, params, javaTypeName)
            }
            cleaned.contains('(') -> {
                val head = cleaned.substringBefore('(').trim().split(whitespaceRegex)
                when (head.size) {
                    3 -> null // augmented method: impl lives in the painless runtime jar, not applicable
                    2 -> {
                        val (returnType, methodName) = head
                        val params = paramList(cleaned.substring(cleaned.indexOf('(')))
                        resolveMethod(sourceName, className, clazz, returnType, methodName, params, javaTypeName)
                    }
                    else -> throw IllegalStateException("Invalid method definition [ $className => $line ] @ $sourceName")
                }
            }
            else -> {
                val parts = cleaned.split(whitespaceRegex)
                if (parts.size != 2) throw IllegalStateException("Invalid field definition [ $className => $line ] @ $sourceName")
                val (fieldType, fieldName) = parts
                resolveFieldOrProperty(sourceName, className, clazz, fieldType, fieldName, javaTypeName)
            }
        }
    }

    private val annotationRegex = """\s+@[\w_]+""".toRegex()
    private val whitespaceRegex = """\s+""".toRegex()

    private fun paramList(parenPart: String): List<String> {
        val inner = parenPart.substringAfter('(').substringBeforeLast(')').trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { it.trim() }
    }

    private fun Class<*>.safeName(): String = this.typeName
    private fun Type.safeName(): String = this.erasedType().typeName

    private fun Constructor<*>.wildCardSignature(indicesToOverrideWithObject: Set<Int> = emptySet()): String {
        val checkParams = parameterTypes.map { typeToSigPart(it.safeName()) }.mapIndexed { index, param ->
            if (index in indicesToOverrideWithObject) "Ljava.lang.Object;" else param
        }
        val checkReturn = annotatedReturnType.type.let { typeToSigPart(it.safeName()) }
        return "(${checkParams.joinToString("")})$checkReturn"
    }

    private fun Method.wildCardSignature(returnTypeIsDef: Boolean = false, indicesToOverrideWithObject: Set<Int> = emptySet()): String {
        val checkParams = parameterTypes.map { typeToSigPart(it.safeName()) }.mapIndexed { index, param ->
            if (index in indicesToOverrideWithObject) "Ljava.lang.Object;" else param
        }
        val checkReturn = if (returnTypeIsDef) "Ljava.lang.Object;" else returnType.let { typeToSigPart(it.safeName()) }
        return "(${checkParams.joinToString("")})$checkReturn"
    }

    private fun defIndices(paramTypes: List<String>): Set<Int> =
        paramTypes.mapIndexedNotNull { index, paramType -> index.takeIf { paramType == "def" || paramType.startsWith("def[") } }.toSet()

    private fun resolveConstructor(
        sourceName: String,
        className: String,
        clazz: Class<*>,
        paramTypes: List<String>,
        javaTypeName: (String) -> String,
    ): List<PolicyAllowance.ClassLevel> {
        val defParamIndices = defIndices(paramTypes)
        val seekParams = paramTypes.map { typeToSigPart(javaTypeName(it)) }.mapIndexed { index, param ->
            if (index in defParamIndices) "Ljava.lang.Object;" else param
        }
        val seekSig = "(${seekParams.joinToString("")})${typeToSigPart(className)}"

        val constructors = clazz.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
            .filter { it.parameterCount == paramTypes.size }
            .filter { it.wildCardSignature(defParamIndices) == seekSig }

        if (constructors.isEmpty()) {
            throw MemberResolutionException("Constructor not found: $className(${paramTypes.joinToString(",")}) @ $sourceName")
        }

        return constructors.map { constructor ->
            PolicyAllowance.ClassLevel.ClassConstructorAccess(className, constructor.wildCardSignature(), setOf(AccessTypes.call_Class_Constructor))
        }
    }

    private fun resolveMethod(
        sourceName: String,
        className: String,
        clazz: Class<*>,
        returnType: String,
        methodName: String,
        paramTypes: List<String>,
        javaTypeName: (String) -> String,
    ): List<PolicyAllowance.ClassLevel> {
        val defParamIndices = defIndices(paramTypes)
        val returnTypeIsDef = returnType == "def"

        val seekParams = paramTypes.map { typeToSigPart(javaTypeName(it)) }.mapIndexed { index, param ->
            if (index in defParamIndices) "Ljava.lang.Object;" else param
        }
        val seekReturn = if (returnTypeIsDef) "Ljava.lang.Object;" else typeToSigPart(javaTypeName(returnType))
        val seekSig = "(${seekParams.joinToString("")})$seekReturn"

        val methods = (clazz.declaredMethods + clazz.methods)
            .filter { Modifier.isPublic(it.modifiers) && methodName == it.name && paramTypes.size == it.parameterCount }
            .filter { it.wildCardSignature(returnTypeIsDef, defParamIndices) == seekSig }

        if (methods.isEmpty()) {
            throw MemberResolutionException("Method not found: $className.$methodName$seekSig @ $sourceName")
        }

        return methods.map { method ->
            val access = if (Modifier.isStatic(method.modifiers)) AccessTypes.call_Class_Static_Method else AccessTypes.call_Class_Instance_Method
            val signature = method.wildCardSignature()
            signature to PolicyAllowance.ClassLevel.ClassMethodAccess(className, methodName, signature, setOf(access))
        }.distinctBy { it.first }.map { it.second }
    }

    private fun String.asGetterNames(): List<String> =
        (this.first().uppercaseChar() + this.substring(1)).let { listOf("get$it", "is$it", "has$it") }

    private fun String.asSetterNames(): List<String> =
        (this.first().uppercaseChar() + this.substring(1)).let { listOf("set$it") }

    private fun resolveFieldOrProperty(
        sourceName: String,
        className: String,
        clazz: Class<*>,
        fieldType: String,
        fieldName: String,
        javaTypeName: (String) -> String,
    ): List<PolicyAllowance.ClassLevel> {
        val seekReturn = typeToSigPart(javaTypeName(fieldType))

        // painless whitelists list real Java fields; fall back to bean getters/setters
        val field = (clazz.declaredFields + clazz.fields)
            .filter { Modifier.isPublic(it.modifiers) && fieldName == it.name }
            .firstOrNull { typeToSigPart(it.type.safeName()) == seekReturn }

        if (field != null) {
            val access = when {
                Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers) -> setOf(AccessTypes.read_Class_Static_Field)
                Modifier.isStatic(field.modifiers) -> setOf(AccessTypes.read_Class_Static_Field, AccessTypes.write_Class_Static_Field)
                Modifier.isFinal(field.modifiers) -> setOf(AccessTypes.read_Class_Instance_Field)
                else -> setOf(AccessTypes.read_Class_Instance_Field, AccessTypes.write_Class_Instance_Field)
            }
            return listOf(PolicyAllowance.ClassLevel.ClassFieldAccess(className, fieldName, seekReturn, access))
        }

        val getterNames = fieldName.asGetterNames()
        val setterNames = fieldName.asSetterNames()

        val getter = clazz.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name in getterNames && it.parameterCount == 0 }
            .firstOrNull { typeToSigPart(it.returnType.safeName()) == seekReturn }
        val setter = clazz.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name in setterNames && it.parameterCount == 1 }
            .firstOrNull { typeToSigPart(it.parameterTypes[0].safeName()) == seekReturn }

        if (getter != null) {
            val static = Modifier.isStatic(getter.modifiers)
            val readAccess = if (static) AccessTypes.read_Class_Static_Property else AccessTypes.read_Class_Instance_Property
            val writeAccess = when {
                setter == null -> null
                static && Modifier.isStatic(setter.modifiers) -> AccessTypes.write_Class_Static_Property
                !static && !Modifier.isStatic(setter.modifiers) -> AccessTypes.write_Class_Instance_Property
                else -> null
            }
            return listOf(PolicyAllowance.ClassLevel.ClassPropertyAccess(className, fieldName, seekReturn, setOfNotNull(readAccess, writeAccess)))
        }

        throw MemberResolutionException("Field/property not found: $className.$fieldName:$seekReturn @ $sourceName")
    }

    private fun Source.cleanLines(): List<String> =
        lines.asSequence().map { it.trim() }.filterNot { it.isBlank() || it.startsWith('#') }.toList()

    private fun loadClass(className: String): Class<*> = when (className) {
        "boolean" -> Boolean::class.javaPrimitiveType!!
        "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!
        "char" -> Char::class.javaPrimitiveType!!
        "int" -> Int::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!
        "float" -> Float::class.javaPrimitiveType!!
        "double" -> Double::class.javaPrimitiveType!!
        else -> Class.forName(className)
    }

    private fun Class<*>.allAncestors(): Set<Class<*>> {
        val result = LinkedHashSet<Class<*>>()
        var frontier: List<Class<*>> = listOfNotNull(superclass) + interfaces
        while (frontier.isNotEmpty()) {
            frontier = frontier.filter { result.add(it) }
                .flatMap { c -> listOfNotNull(c.superclass) + c.interfaces }
        }
        return result
    }
}
