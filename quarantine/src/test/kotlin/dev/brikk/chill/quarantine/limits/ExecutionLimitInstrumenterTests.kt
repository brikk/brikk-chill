package dev.brikk.chill.quarantine.limits

import dev.brikk.chill.quarantine.NamedClassBytes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.invoke.LambdaMetafactory
import java.lang.reflect.InvocationTargetException

class ExecutionLimitInstrumenterTests {

    private val instrumenter = ExecutionLimitInstrumenter()

    /** Child-first loader for the instrumented copies; everything else (stdlib, JDK) from the parent. */
    private class InstrumentedLoader(private val classes: Map<String, ByteArray>, parent: ClassLoader) : ClassLoader(parent) {
        private val defined = HashMap<String, Class<*>>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            defined[name] ?: classes[name]?.let { bytes -> defineClass(name, bytes, 0, bytes.size).also { defined[name] = it } }
                ?: super.loadClass(name, resolve)
        }
    }

    /** Loads [kClass] and its nested classes, instrumented; returns a fresh instance of the top class. */
    private fun instrumented(kClass: Class<*>): Any {
        val names = generateSequence(listOf(kClass)) { level -> level.flatMap { it.declaredClasses.toList() }.takeIf { it.isNotEmpty() } }
            .flatten().map { it.name }.toList() + "${kClass.name}\$matching\$1" // callable-reference class is not a declared class
        val bytes = names.mapNotNull { n -> NamedClassBytes.fromClassLoaderOrNull(n, kClass.classLoader) }
            .associate { it.className to instrumenter.instrument(it.bytes) }
        val loader = InstrumentedLoader(bytes, kClass.classLoader)
        return Class.forName(kClass.name, true, loader).getConstructor().newInstance()
    }

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val method = javaClass.methods.first { it.name == name }
        return try {
            method.invoke(this, *args)
        } catch (ex: InvocationTargetException) {
            throw ex.cause!!
        }
    }

    @BeforeEach
    fun arm() {
        ExecutionBudget.begin(1_000)
        LimitedCharSequence.limitFactor = LimitedCharSequence.DEFAULT_LIMIT_FACTOR
    }

    @AfterEach
    fun disarm() {
        ExecutionBudget.begin(Long.MAX_VALUE)
        LimitedCharSequence.limitFactor = LimitedCharSequence.DEFAULT_LIMIT_FACTOR
    }

    // ---- loops ----

    @Test
    fun loopsWithinBudgetRunUnchanged() {
        val fixture = instrumented(LimitFixture::class.java)
        assertEquals(4950L, fixture.call("sum", 100))
        assertEquals(400, fixture.call("nested", 20))
        assertEquals(0, fixture.call("countDown", 50))
        assertEquals(1_000L - (100 + 20 + 400 + 50), ExecutionBudget.remaining()) { "one tick per iteration" }

        // the same code uninstrumented never ticks: the budget is a property of the rewrite, not the JVM
        ExecutionBudget.begin(1_000)
        assertEquals(12497500L, LimitFixture().sum(5000))
        assertEquals(1_000L, ExecutionBudget.remaining()) { "uninstrumented code must not consume budget" }
    }

    @Test
    fun infiniteLoopIsStoppedByTheBudget() {
        val fixture = instrumented(LimitFixture::class.java)
        val ex = assertThrows<ChillExecutionLimitError> { fixture.call("spin") }
        assertTrue("loop iterations" in ex.message!!)
    }

    @Test
    fun budgetIsSharedAcrossNestedLoopsAndHelperMethodsPerExecution() {
        val fixture = instrumented(LimitFixture::class.java)
        // 40 x 40 = 1600 iterations across two loop levels: over a 1000 budget, under a 10000 one
        assertThrows<ChillExecutionLimitError> { fixture.call("nested", 40) }
        ExecutionBudget.begin(10_000)
        assertEquals(1600, fixture.call("nested", 40))

        // outer loop of 150 calling a helper that loops 10: 150 + 1500 > 1000 even though no single loop is
        ExecutionBudget.begin(1_000)
        assertThrows<ChillExecutionLimitError> { fixture.call("helperLoops", 150) }
        ExecutionBudget.begin(10_000)
        assertEquals(150L * 45, fixture.call("helperLoops", 150))
    }

    @Test
    fun catchingThrowableCannotKeepLooping() {
        val fixture = instrumented(LimitFixture::class.java)
        // inner loop exhausts the budget, catch swallows it, outer loop's next tick throws again
        assertThrows<ChillExecutionLimitError> { fixture.call("swallowing") }
        assertTrue(ExecutionBudget.remaining() < 0)
    }

    // ---- allocations ----

    @Test
    fun singleAllocationsAboveTheCapAreRefusedAndSmallOnesUntouched() {
        val fixture = instrumented(LimitFixture::class.java)
        ExecutionBudget.begin(1_000_000, maxAllocation = 10_000)

        assertEquals(5_000, fixture.call("bigPrimitiveArray", 5_000))
        assertEquals(5_000, fixture.call("bigObjectArray", 5_000))
        assertEquals(10_000, fixture.call("bigString", 5_000))
        assertEquals(3 * 100, fixture.call("nestedArrays", 3, 100))

        for ((method, arg) in listOf("bigPrimitiveArray" to 10_001, "bigObjectArray" to 200_000, "bigString" to 1_000_000)) {
            val ex = assertThrows<ChillExecutionLimitError>(method) { fixture.call(method, arg) }
            assertTrue("single allocation" in ex.message!!) { ex.message }
        }
        // inner dimension over the cap is caught at its own newarray
        assertThrows<ChillExecutionLimitError> { fixture.call("nestedArrays", 2, 20_000) }
    }

    // ---- regex ----

    @Test
    fun ordinaryRegexUseWorksThroughEveryEntryPoint() {
        val fixture = instrumented(LimitFixture::class.java)
        assertEquals("123", fixture.call("find", "\\d+", "abc123def"))
        assertEquals("456", fixture.call("findFrom", "\\d+", "123-456", 3)) // trailing int arg spilled and restored
        assertEquals("a_b_c", fixture.call("replaceExt", "a1b22c", "\\d+"))
        assertEquals(listOf("a", "b", "c"), fixture.call("split", "a b  c"))
        assertEquals(true, fixture.call("predicate", "\\d", "x9"))
        assertEquals(false, fixture.call("predicate", "\\d", "xy"))
    }

    @Test
    fun catastrophicBacktrackingIsCutOffAtEveryEntryPoint() {
        val fixture = instrumented(LimitFixture::class.java)
        val bomb = "(a+)+b"
        val input = "a".repeat(40) + "!" // naive matching is exponential in 40 here
        val cases: List<Pair<String, Array<Any?>>> = listOf(
            "find" to arrayOf(bomb, input),
            "findFrom" to arrayOf(bomb, input, 0),
            "replaceExt" to arrayOf(input, bomb),
            "predicate" to arrayOf(bomb, input),
        )
        for ((method, args) in cases) {
            val ex = assertThrows<ChillExecutionLimitError>("$method should be limited") { fixture.call(method, *args) }
            assertTrue("regular expression exceeded" in ex.message!!) { ex.message }
        }
    }

    @Test
    fun regexThroughAKotlinMethodReferenceIsLimitedToo() {
        val fixture = instrumented(RegexRefFixture::class.java)
        assertEquals(listOf("a1"), fixture.call("matching", Regex("[a-z]\\d"), listOf("a1", "bb")))
        assertThrows<ChillExecutionLimitError> {
            fixture.call("matching", Regex("(a+)+b"), listOf("a".repeat(40) + "!"))
        }
    }

    @Test
    fun zeroLimitFactorDisablesRegex() {
        val fixture = instrumented(LimitFixture::class.java)
        LimitedCharSequence.limitFactor = 0
        val ex = assertThrows<ChillExecutionLimitError> { fixture.call("find", "a", "a") }
        assertTrue("disabled" in ex.message!!)
        assertEquals(4950L, fixture.call("sum", 100)) // loops unaffected
    }

    /**
     * A direct method-handle to a regex operation (what javac emits for `regex::matches`, unlike
     * kotlinc) has no call site to rewrite; it must be refused rather than silently unlimited.
     */
    @Test
    fun foreignMethodHandleToRegexIsRejected() {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "gen/RegexHandle", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "pred", "(Ljava/util/regex/Pattern;)Ljava/util/function/Predicate;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            val bsm = Handle(
                Opcodes.H_INVOKESTATIC, Type.getInternalName(LambdaMetafactory::class.java), "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false,
            )
            visitInvokeDynamicInsn(
                "test", "(Ljava/util/regex/Pattern;)Ljava/util/function/Predicate;", bsm,
                Type.getMethodType("(Ljava/lang/Object;)Z"),
                Handle(Opcodes.H_INVOKEVIRTUAL, "java/util/regex/Pattern", "matcher", "(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;", false),
                Type.getMethodType("(Ljava/lang/CharSequence;)Z"),
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()

        val ex = assertThrows<ExecutionLimitInstrumenter.InstrumentationRejectedException> { instrumenter.instrument(cw.toByteArray()) }
        assertTrue("Pattern.matcher" in ex.message!! && "lambda body" in ex.message!!) { ex.message }
    }
}
