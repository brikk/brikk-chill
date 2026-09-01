package dev.brikk.chill.quarantine

import dev.brikk.chill.quarantine.generator.buildtime.LambdaBuildVerifier
import dev.brikk.chill.quarantine.generator.buildtime.LambdaBuildVerifier.DiscoveryMode
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LambdaScopeTests {

    private val quarantine = Quarantine(Quarantine.painlessPlusKotlinBootstrapPolicy)

    private fun testClassesDir(): File =
        File(ScopedLambdaFixturesMarker::class.java.protectionDomain.codeSource.location.toURI())

    private fun verifiedNames(verifier: LambdaBuildVerifier): Set<String> =
        verifier.verify(listOf(testClassesDir())).map { it.className }.toSet()

    @Test
    fun disabledScopeExcludesFrameworkLambdasInAllMode() {
        val names = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ALL))

        val sparkyName = OtherFrameworkFixtures.sparky.javaClass.name
        assertTrue(sparkyName !in names) { "@ChillVerifyAtBuild(enabled = false) scope must be excluded, got $names" }

        // everything else still verified in ALL mode
        assertTrue(BuildTimeLambdaFixtures.goodLambda.javaClass.name in names)
        assertTrue(OptInFixtures.checked.javaClass.name in names)
    }

    @Test
    fun annotatedModeOnlyVerifiesForcedOnScopes() {
        val names = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ANNOTATED))

        assertTrue(OptInFixtures.checked.javaClass.name in names) { "class-level opt-in missing from $names" }
        assertTrue(FunctionScopedFixtures.makeChecked()::class.java.name in names) { "function-level opt-in missing from $names" }
        assertTrue(PropertyScopedFixtures.propChecked.javaClass.name in names) { "property-level opt-in missing from $names" }

        assertTrue(FunctionScopedFixtures.makeUnmarked()::class.java.name !in names)
        assertTrue(BuildTimeLambdaFixtures.goodLambda.javaClass.name !in names)
        assertTrue(OtherFrameworkFixtures.sparky.javaClass.name !in names) { "forced-off scope never verifies" }
    }

    @Test
    fun nearestScopeWins() {
        // ALL mode: class-level enabled=false skips, member-level enabled=true overrides back on
        val allNames = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ALL))
        assertTrue(NestedOverrideFixtures.skippedByClass.javaClass.name !in allNames)
        assertTrue(NestedOverrideFixtures.forcedOn.javaClass.name in allNames) { "member-level override missing from $allNames" }

        // ANNOTATED mode: same outcome - member force-on wins over class force-off
        val annotatedNames = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ANNOTATED))
        assertTrue(NestedOverrideFixtures.forcedOn.javaClass.name in annotatedNames)
        assertTrue(NestedOverrideFixtures.skippedByClass.javaClass.name !in annotatedNames)
    }

    @Test
    fun callEmbeddedLambdasResolveTheEnclosingFunctionDirective() {
        val service = CallSiteFixtures()
        val skippedName = service.buildSkippedQuery().scriptField!!.javaClass.name
        val checkedName = service.buildCheckedQuery().scriptField!!.javaClass.name
        val unmarkedName = service.buildUnmarkedQuery().scriptField!!.javaClass.name

        // ALL mode: function-level enabled=false wins for the lambda inside the call chain
        val allNames = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ALL))
        assertTrue(skippedName !in allNames) { "call-embedded lambda in forced-off function must be skipped, got $allNames" }
        assertTrue(checkedName in allNames)
        assertTrue(unmarkedName in allNames) { "unmarked scope follows the ALL default" }

        // ANNOTATED mode: only the forced-on function's lambda is verified
        val annotatedNames = verifiedNames(LambdaBuildVerifier(quarantine, DiscoveryMode.ANNOTATED))
        assertTrue(checkedName in annotatedNames) { "call-embedded lambda in forced-on function missing from $annotatedNames" }
        assertTrue(skippedName !in annotatedNames)
        assertTrue(unmarkedName !in annotatedNames) { "unmarked scope follows the ANNOTATED default" }
    }

    @Test
    fun excludePatternsApplyInAllMode() {
        val verifier = LambdaBuildVerifier(
            quarantine,
            DiscoveryMode.ALL,
            excludeClassPatterns = listOf("dev.brikk.chill.quarantine.BuildTimeLambdaFixtures*"),
        )
        val names = verifiedNames(verifier)

        assertTrue(BuildTimeLambdaFixtures.goodLambda.javaClass.name !in names)
        assertTrue(BuildTimeLambdaFixtures.badLambda.javaClass.name !in names)
        assertTrue(OptInFixtures.checked.javaClass.name in names)
    }
}

// stable class for locating the test classes dir
internal class ScopedLambdaFixturesMarker
