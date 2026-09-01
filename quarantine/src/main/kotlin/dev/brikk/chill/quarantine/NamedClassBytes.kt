package dev.brikk.chill.quarantine

class NamedClassBytes(val className: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NamedClassBytes && other.className == className && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = className.hashCode() * 31 + bytes.contentHashCode()

    override fun toString(): String = "NamedClassBytes($className, ${bytes.size} bytes)"

    companion object {
        /**
         * Load the bytecode of [className] as a classpath resource from [loader].
         *
         * Returns null when the class has no backing `.class` resource, which is the case
         * for JVM hidden classes such as invokedynamic-generated lambdas.
         */
        fun fromClassLoaderOrNull(className: String, loader: ClassLoader): NamedClassBytes? {
            val stream = loader.getResourceAsStream(className.replace('.', '/') + ".class") ?: return null
            return NamedClassBytes(className, stream.use { it.readBytes() })
        }

        fun fromClassLoader(className: String, loader: ClassLoader): NamedClassBytes =
            fromClassLoaderOrNull(className, loader)
                ?: throw IllegalArgumentException("No .class resource found for $className in $loader")

        /**
         * Load the bytecode backing [lambda]'s class.
         *
         * Since Kotlin 2.0, lambdas compile via invokedynamic by default and have no class
         * resource to load. Callers that want to extract/ship a lambda class must annotate the
         * lambda with `@JvmSerializableLambda` (or compile the module with `-Xlambdas=class`).
         */
        fun fromLambda(lambda: Any): NamedClassBytes {
            val serClass = lambda.javaClass
            if (serClass.isHidden) {
                throw IllegalArgumentException(
                    "Lambda ${serClass.name} is an invokedynamic-generated hidden class with no " +
                        "loadable bytecode. Annotate the lambda with @JvmSerializableLambda " +
                        "(or build with -Xlambdas=class) so it compiles as a regular class.",
                )
            }
            return fromClassLoaderOrNull(serClass.name, serClass.classLoader)
                ?: throw IllegalArgumentException(
                    "No .class resource found for lambda class ${serClass.name}; if this is a " +
                        "dynamically generated class it cannot be extracted. For Kotlin lambdas, " +
                        "annotate with @JvmSerializableLambda.",
                )
        }
    }
}
