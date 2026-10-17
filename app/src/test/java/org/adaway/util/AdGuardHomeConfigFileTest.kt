package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** AdGuardHomeConfigFile 的单测：临时目录里做完整的 备份→应用→回滚 验证。 */
class AdGuardHomeConfigFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val yaml = listOf(
        "http:",
        "  address: 0.0.0.0:3000",
        "dns:",
        "  blocking_mode: default",
        "  blocking_ipv4: 0.0.0.0",
    ).joinToString("\n")

    private fun config(content: String = yaml): File =
        tmp.newFile("AdGuardHome.yaml").apply { writeText(content, Charsets.UTF_8) }

    @Test
    fun appliesAndLeavesBackup() {
        val f = config()
        val out = AdGuardHomeConfigFile.apply(f, "192.168.1.10", null)
        assertTrue(out is AdGuardHomeConfigFile.Outcome.Applied)
        val applied = out as AdGuardHomeConfigFile.Outcome.Applied
        assertTrue(applied.backup.isFile)
        assertEquals(yaml, applied.backup.readText(Charsets.UTF_8))
        assertTrue(f.readText(Charsets.UTF_8).contains("blocking_mode: custom_ip"))
        assertTrue(f.readText(Charsets.UTF_8).contains("blocking_ipv4: 192.168.1.10"))
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }

    @Test
    fun unrecognizedFormatTouchesNothing() {
        val f = config("http:\n  address: x\n")
        val before = f.readText(Charsets.UTF_8)
        val out = AdGuardHomeConfigFile.apply(f, "10.0.0.1", null)
        assertTrue(out is AdGuardHomeConfigFile.Outcome.Unrecognized)
        assertEquals("格式不认时必须一字不改", before, f.readText(Charsets.UTF_8))
        assertEquals("不应留下备份", 0, f.parentFile.listFiles { x -> x.name.contains(".bak-") }!!.size)
    }

    @Test
    fun missingFileIsReportedNotThrown() {
        val missing = File(tmp.root, "nope.yaml")
        val out = AdGuardHomeConfigFile.apply(missing, "10.0.0.1", null)
        assertTrue(out is AdGuardHomeConfigFile.Outcome.Failed)
    }
}
