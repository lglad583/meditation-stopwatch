package com.meditation.stopwatch

import com.meditation.stopwatch.visuals.Glsl
import com.meditation.stopwatch.visuals.Movements
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes every movement's complete fragment shader to build/shaders/NN-name.frag so it can be
 * validated offline with `glslangValidator -S frag <file>` (ES 3.00 profile is set by #version).
 */
class ShaderDumpTest {
    @Test
    fun dumpShaders() {
        val dir = File("build/shaders").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "00-vertex.vert").writeText(Glsl.VERTEX + "\n")
        Movements.all.forEachIndexed { i, m ->
            val name = m.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            File(dir, String.format("%02d-%s.frag", i + 1, name)).writeText(Glsl.fragment(m) + "\n")
        }
        assertTrue("need at least 4 movements", Movements.all.size >= 4)
    }
}
