package dev.brikk.chill.opensearch.plugin

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class PluginDistributionTests {
    @Test
    fun publishedArchiveIsExactlyTheInstallablePlugin() {
        val version = System.getProperty("chill.plugin.version")!!
        val osVersion = System.getProperty("chill.opensearch.version")!!
        val directory = File(System.getProperty("chill.plugin.publication")!!)
        val artifactVersion = if (version.endsWith("-SNAPSHOT")) {
            val metadata = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(directory.resolve("maven-metadata.xml"))
            val snapshots = metadata.getElementsByTagName("snapshotVersion")
            val zip = (0 until snapshots.length).map { snapshots.item(it) as Element }.singleOrNull {
                it.getElementsByTagName("extension").item(0)?.textContent == "zip" &&
                    it.getElementsByTagName("classifier").item(0)?.textContent == "os-$osVersion"
            }
            assertNotNull(zip, "Maven publication must include the installable ZIP")
            zip!!.getElementsByTagName("value").item(0).textContent
        } else version
        val published = directory.resolve("chill-opensearch-plugin-$artifactVersion-os-$osVersion.zip")
        assertTrue(published.isFile) { "missing published plugin: $published" }
        assertArrayEquals(File(System.getProperty("chill.plugin.zip")!!).readBytes(), published.readBytes())

        ZipFile(published).use { zip ->
            val descriptor = Properties().apply {
                zip.getInputStream(zip.getEntry("plugin-descriptor.properties")).use { load(it) }
            }
            assertEquals(version, descriptor.getProperty("version"))
            assertEquals(osVersion, descriptor.getProperty("opensearch.version"))
            assertEquals("chill-script", descriptor.getProperty("name"))
            assertEquals(ChillScriptPlugin::class.java.name, descriptor.getProperty("classname"))
            assertNotNull(zip.getEntry("plugin-security.policy"))
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.startsWith("opensearch-plugin-") && it.endsWith(".jar") })
            assertFalse(names.any { "test-fixtures" in it || it.startsWith("opensearch-java-") })
        }
    }
}
