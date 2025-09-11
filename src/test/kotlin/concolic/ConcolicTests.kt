package concolic

import DebuggerTestBase
import be.ugent.topl.mio.concolic.ConcolicAnalysisResult
import be.ugent.topl.mio.concolic.analyse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DisplayName("WARDuino concolic execution tests")
class ConcolicTests : DebuggerTestBase() {
    @Test
    fun `Test result without any branches`() {
        val wasmFile = getFile("blink.wasm").absolutePath
        val result = analyse(config.symbolicWdcliPath, wasmFile, null, maxInstructions = 100)
        assertEquals(listOf(),result.paths)
    }

    private fun makeWat(content: String): String {
        return "(module\n" +
                "(import \"env\" \"chip_analog_read\" (func \$chip_analog_read (param i32) (result i32)))\n" +
                "(func \$main (local i32)\n" +
                content +
                ")\n(export \"main\" (func \$main)))"
    }

    fun getPathsFromWat(watString: String): ConcolicAnalysisResult {
        File("temp.wat").writeText(watString)
        val exitCode = ProcessBuilder("wat2wasm", "temp.wat").inheritIO().start().waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("Could not compile wat")
        }
        return analyse(config.symbolicWdcliPath, "temp.wasm", null, maxInstructions = 200)
    }

    @Test
    fun `Test wat`() {
        val watString = makeWat("""
            i32.const 3
            i32.const 1
            i32.add
            drop
        """.trimIndent())
        val result = getPathsFromWat(watString)
        assertEquals(listOf(),result.paths)
    }

    @Test
    fun `Test wat if 2 paths`() {
        val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            i32.const 10
            i32.lt_s
            if
                nop
            end
        """.trimIndent())
        val result = getPathsFromWat(watString)
        assertEquals(2,result.paths.size)
    }

    @Test
    fun `Test wat if 3 paths`() {
        val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            local.tee 0
            i32.const 10
            i32.lt_s
            if
                nop
            else
                local.get 0
                i32.const 20
                i32.lt_s
                if
                    nop
                end
            end
        """.trimIndent())
        val result = getPathsFromWat(watString)
        assertEquals(3,result.paths.size)
    }

    @ParameterizedTest(name = "{0} loop iteration(s)")
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7])
    fun testIfLoop(iterations: Int = 2) {
        val watString = makeWat($$"""
            (loop $for_2
                i32.const 0
                call $chip_analog_read
                i32.const 10
                i32.lt_s
                if
                    nop
                end
            
                local.get 0
                i32.const 1
                i32.add
                local.tee 0
                i32.const $${iterations}
                i32.lt_s
                br_if $for_2)
        """.trimIndent())
        val result = getPathsFromWat(watString)
        println(result)
        val expectedIterationCount = 1 shl iterations
        println("Expect $expectedIterationCount paths for $iterations iteration(s)")
        assertEquals(expectedIterationCount,result.getPathCount())
    }

    @Nested
    @DisplayName("Floating point operations")
    inner class FloatTests {
        @Test
        fun `Test wat floats no branches`() {
            val watString = makeWat($$"""
            f32.const 0.0
            f32.const 5.0
            f32.add
            drop
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(1,result.getPathCount())
        }

        @Test
        fun `Test wat floats equal`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 5.0
            f32.eq
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value == 5 })
            assertNotNull(result.paths.find { it.value != 5 })
        }

        @Test
        fun `Test wat floats not equal`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 5.0
            f32.ne
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value == 5 })
            assertNotNull(result.paths.find { it.value != 5 })
        }

        @Test
        fun `Test wat floats less than basic`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 5.0
            f32.lt
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < 5 })
            assertNotNull(result.paths.find { it.value >= 5 })
        }

        @Test
        fun `Test wat floats less than advanced`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 3.0
            f32.mul
            f32.const 2.0
            f32.add
            f32.const 50.0
            f32.lt
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < 16 })
            assertNotNull(result.paths.find { it.value >= 16 })
        }

        @ParameterizedTest(name = "Value in between {0} and {1}")
        @CsvSource("2,5", "3,6", "-3,3", "-3,1", "500,502", "500.999,501.0001")
        fun `Test wat floats between`(lower: Float, upper: Float) {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read (;Range 0 to 4096;)
            local.tee 0
            f32.convert_i32_s
            f32.const $$upper
            f32.lt
            local.get 0
            f32.convert_i32_s
            f32.const $$lower
            f32.gt
            i32.and
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < upper && it.value > lower })
            assertNotNull(result.paths.find { it.value >= upper || it.value <= lower})
        }

        @Test
        fun `Test wat floats i32_trunc_f32_s basic`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            i32.trunc_f32_s
            i32.const 10
            i32.lt_s
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < 10 })
            assertNotNull(result.paths.find { it.value >= 10 })
        }

        @Test
        fun `Test wat floats i32_trunc_f32_s medium`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 0x1.9p+5 (;=50;)
            f32.sub
            i32.trunc_f32_s
            i32.const 25
            i32.lt_s
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < 75 })
            assertNotNull(result.paths.find { it.value >= 75 })
        }

        @ParameterizedTest(name = "((value * {0}) as i32) < 50")
        @ValueSource(floats = [0.488f, 2.3f, 3.14f, 10.42f, 0.1f, 0.02f, 0.0125f, 0.01222f, 0.0122129f])
        fun `Test wat floats i32_trunc_f32_s medium 2`(v: Float) {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const $$v
            f32.mul
            i32.trunc_f32_s
            i32.const 50
            i32.lt_s
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
            assertNotNull(result.paths.find { it.value < 50 / v })
            assertNotNull(result.paths.find { it.value >= 50 / v })
        }

        @Test
        fun `Test wat floats i32_trunc_f32_s advanced`() {
            val watString = makeWat($$"""
            i32.const 0
            call $chip_analog_read
            f32.convert_i32_s
            f32.const 0x1.f3b646p-2 (;=0.488;)
            f32.mul
            f32.const 0x1.9p+5 (;=50;)
            f32.sub
            i32.trunc_f32_s
            i32.const 50
            i32.lt_s
            if
                nop
            end
        """.trimIndent())
            val result = getPathsFromWat(watString)
            assertEquals(2,result.getPathCount())
        }
    }
}
