package org.adaway.model.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨端规则测试向量（G1/D）：读取 C 宿主测试用的**同一份** rule_vectors.txt，
 * 断言 Android 侧的「是否含不支持的修饰符」判定与 C 侧一致。
 *
 * 为什么只断言第三列：Android 侧没有归一化这一步（它只做分类），归一化由 C 服务端负责；
 * 而「含资源类型修饰符的规则要跳过并计数、绝不静默降级成整域拦截」是两端必须一致的判定。
 */
class RuleVectorsTest {

    private fun vectorFile(): File {
        // Gradle 单元测试的工作目录是模块目录（app/），向量文件在仓库根的 webserver/jni 下。
        val candidates = listOf(
            File("../webserver/jni/rule_vectors.txt"),
            File("webserver/jni/rule_vectors.txt"),
        )
        val found = candidates.firstOrNull { it.isFile }
        return found ?: error("找不到 rule_vectors.txt，找过：" + candidates.joinToString { it.path })
    }

    private fun vectors(): List<Triple<String, String, Int>> =
        vectorFile().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("\t")
                require(parts.size == 3) { "向量列数不对：" + line }
                Triple(parts[0], parts[1], parts[2].trim().toInt())
            }

    @Test
    fun vectorsArePresentAndWellFormed() {
        val v = vectors()
        assertTrue("向量太少，可能读错文件了：" + v.size, v.size >= 10)
    }

    @Test
    fun androidModifierDecisionMatchesTheSharedVectors() {
        var checked = 0
        for ((rule, _norm, wantMod) in vectors()) {
            if (rule.isEmpty() || rule.startsWith("#")) continue   // 空行与注释不是规则
            val got = if (RuleModifiers.hasUnsupportedModifier(rule)) 1 else 0
            assertEquals("规则 " + rule + " 的修饰符判定与 C 侧不一致", wantMod, got)
            checked++
        }
        assertTrue("实际断言的规则太少：" + checked, checked >= 8)
    }

    @Test
    fun modifierRulesAreNeverSilentlyDowngraded() {
        val withModifier = vectors().filter { it.third == 1 }
        assertTrue("向量里至少要有一条带修饰符的规则", withModifier.isNotEmpty())
        for ((rule, _, _) in withModifier) {
            assertTrue(rule + " 应被判为含修饰符", RuleModifiers.hasUnsupportedModifier(rule))
        }
    }
}
