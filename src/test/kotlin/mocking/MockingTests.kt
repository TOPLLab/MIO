package mocking

import DebuggerTestBase
import be.ugent.topl.mio.debugger.Debugger
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MockingTests : DebuggerTestBase() {
    @Test
    fun `Remove once`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 3))
            assertEquals(true, debugger.removePrimitiveOverride("chip_analog_read", listOf(12)))
        }
    }

    @Test
    fun `Remove twice`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 3))
            assertEquals(true, debugger.removePrimitiveOverride("chip_analog_read", listOf(12)))
            assertEquals(false, debugger.removePrimitiveOverride("chip_analog_read", listOf(12)))
        }
    }

    @Test
    fun `mixed register remove tests`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 3))
            assertEquals(false, debugger.removePrimitiveOverride("chip_analog_read", listOf(11)))
            assertEquals(true, debugger.removePrimitiveOverride("chip_analog_read", listOf(12)))
            assertEquals(false, debugger.removePrimitiveOverride("chip_analog_read", listOf(12)))
        }
    }

    @Test
    fun `register remove tests 2`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12, 5), 3))
            assertEquals(false, debugger.removePrimitiveOverride("chip_analog_read", listOf(11, 5)))
            assertEquals(true, debugger.removePrimitiveOverride("chip_analog_read", listOf(12, 5)))
            assertEquals(false, debugger.removePrimitiveOverride("chip_analog_read", listOf(12, 5)))
        }
    }

    @Test
    fun `add test`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 75))
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 12 && it.return_value == 75 })
        }
    }

    @Test
    fun `overwrite test`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 75))
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 12 && it.return_value == 75 })
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 76))
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 12 && it.return_value == 76 })
        }
    }

    @Test
    fun `add test 2`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(0, debugger.snapshotFull().second.overrides!!.size)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), 75))
            assertEquals(1, debugger.snapshotFull().second.overrides!!.size)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(13), 32))
            assertEquals(2, debugger.snapshotFull().second.overrides!!.size)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(14), 5))
            assertEquals(3, debugger.snapshotFull().second.overrides!!.size)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(15), 16))
            assertEquals(4, debugger.snapshotFull().second.overrides!!.size)
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 12 && it.return_value == 75 })
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 13 && it.return_value == 32 })
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 14 && it.return_value == 5 })
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args[0] == 15 && it.return_value == 16 })
        }
    }

    @RepeatedTest(20)
    fun `mock tests`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing())
            debugger.reset()

            debugger.addBreakpoint(0xd7)
            val randomValue = Random.nextInt(Integer.MAX_VALUE)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", listOf(12), randomValue))
            debugger.run()
            Thread.sleep(20) // TODO: Add a way to wait for breakpoints in tests
            debugger.stepInto()
            assertEquals(randomValue, debugger.checkpoints.last()!!.returns!![0])
        }
    }

    @RepeatedTest(5, name = "Random pin mock {currentRepetition}/{totalRepetitions}")
    fun `Test loadSnapshot(snapshot()) with mocking`() {
        runWithDebugger("temp-indicator-advanced.wasm", true) { debugger ->
            assertEquals(0, debugger.snapshotFull().second.overrides!!.size)
            val randomValue = Random.nextInt(Integer.MAX_VALUE)
            val randomPin = Random.nextInt(Integer.MAX_VALUE)
            println("mock chip_analog_read($randomPin) = $randomValue")
            val args = listOf(randomPin)
            assertEquals(true, debugger.addPrimitiveOverride("chip_analog_read", args, randomValue))
            debugger.loadSnapshot(debugger.snapshot())
            assertNotNull(debugger.snapshotFull().second.overrides!!.find { it.fidx == 1 && it.args == args && it.return_value == randomValue })
        }
    }
}
