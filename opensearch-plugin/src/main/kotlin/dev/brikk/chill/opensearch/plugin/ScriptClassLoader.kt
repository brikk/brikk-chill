package dev.brikk.chill.opensearch.plugin

/**
 * Defining classloader for the verified, instrumented classes shipped with one script; parented
 * to the plugin classloader so the receiver, chill runtime, and kotlin-stdlib resolve normally.
 *
 * Shipped names are **child-first**: a class registered here is always defined from its shipped
 * bytes, even if the parent could also load a class of that name. That guarantees the bytes that
 * were verified and instrumented are the bytes that execute, and it closes a thaw-time hole:
 * shipped class names are granted `ref_Class_Instance` for deserialization, so a parent-first
 * lookup would let a payload name a Serializable server class, ship harmless bytes under that
 * name, and have the *real* class instantiated with attacker-chosen field values.
 */
class ScriptClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    private val classes = HashMap<String, ByteArray>()
    private val defined = HashMap<String, Class<*>>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name !in classes) return super.loadClass(name, resolve)
        val clazz = defineShipped(name)
        if (resolve) resolveClass(clazz)
        return clazz
    }

    override fun findClass(name: String): Class<*> =
        if (name in classes) defineShipped(name) else throw ClassNotFoundException(name)

    private fun defineShipped(name: String): Class<*> = synchronized(getClassLoadingLock(name)) {
        defined.getOrPut(name) { classes.getValue(name).let { defineClass(name, it, 0, it.size) } }
    }

    fun addClass(className: String, bytes: ByteArray) {
        val previous = classes.put(className, bytes)
        check(previous == null) { "Class $className registered twice for one script" }
    }
}
