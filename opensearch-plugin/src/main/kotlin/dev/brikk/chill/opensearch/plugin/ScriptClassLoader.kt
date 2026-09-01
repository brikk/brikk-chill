package dev.brikk.chill.opensearch.plugin

/**
 * Defining classloader for the verified classes shipped with one script; parented to the plugin
 * classloader so the receiver, chill runtime, and kotlin-stdlib resolve normally.
 */
class ScriptClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val classes = HashMap<String, ByteArray>()

    override fun findClass(name: String): Class<*> {
        val classBytes = classes[name] ?: return super.findClass(name)
        return defineClass(name, classBytes, 0, classBytes.size)
    }

    fun addClass(className: String, bytes: ByteArray) {
        val previous = classes.put(className, bytes)
        check(previous == null) { "Class $className registered twice for one script" }
    }
}
