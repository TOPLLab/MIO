package slide

import DebuggerTestBase
import WasmBinary
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.debugger.ExecutionState
import be.ugent.topl.mio.debugger.MultiverseDebugger
import be.ugent.topl.mio.debugger.MultiverseNode
import be.ugent.topl.mio.ui.disassemble
import getBinaryInfo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


@DisplayName("Slide behaviour tests")
class SlideTest : DebuggerTestBase() {
    fun randomNode(debugger: MultiverseDebugger, depth: Int) : MultiverseNode {
        var remainingDepth = Random.nextInt(depth)
        return randomNodeAtDepth(debugger, remainingDepth)
    }

    fun randomNodeAtDepth(debugger: MultiverseDebugger, depth: Int) : MultiverseNode {
        var remainingDepth = depth
        println("Determining a random node at depth $remainingDepth")
        var currentNode = debugger.graph.rootNode
        while (remainingDepth > 0 && currentNode.children.isNotEmpty()) {
            currentNode = currentNode.children[Random.nextInt(currentNode.children.size)]
            remainingDepth -= currentNode.totalInstrExecuted + 1
        }
        return currentNode
    }

    fun randomSlide(wasmFileName: String, snapshotInterval: Int = 0xf0000, slideCount: Int = 3, depths: List<Int> = listOf()) {
        runWithDebugger(wasmFileName, true) { debugger ->
            debugger as MultiverseDebugger
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(listOf(ExecutionState.ProgramCounter), interval = snapshotInterval.toUInt()))
            debugger.reset()
            val depth = snapshotInterval * 5
            debugger.continueFor(depth)
            repeat(slideCount) {
                println("Performing slide ${it + 1}/$slideCount")
                val destNode = if(it < depths.size) randomNodeAtDepth(debugger, depths[it]) else randomNode(debugger, depth)
                val offset = if (destNode.totalInstrExecuted == 0) 0 else Random.nextInt(destNode.totalInstrExecuted)
                debugger.slide(destNode, offset)
            }
        }
    }

    @RepeatedTest(10)
    fun `Random slide in temperature indicator program - interval 20`() {
        randomSlide("temp-indicator.wasm", 20)
    }

    /**
     * Seems like this one fails even though all the other tests work fine, strange but maybe this is the one edge case
     * I was still planning to solve.
     */
    @RepeatedTest(10)
    fun `Random slide in temperature indicator program - interval 100`() {
        randomSlide("temp-indicator.wasm", 100)
    }

    @RepeatedTest(10)
    fun `Random slide in temperature indicator program advanced - interval 1000`() {
        randomSlide("temp-indicator-advanced.wasm", 1000, slideCount = 15)
    }

    /*@RepeatedTest(3)
    fun `Random slide in sokoban`() {
        randomSlide("/Users/maarten/Projects/WARDuino-platformer/sokoban.wasm", 0xf0000, slideCount=15)
    }*/

    fun slideToRandomNode(debugger: MultiverseDebugger) {
        val destNode = randomNode(debugger, Int.MAX_VALUE)
        val offset = if (destNode.totalInstrExecuted == 0) 0 else Random.nextInt(destNode.totalInstrExecuted)
        debugger.slide(destNode, offset)
    }

    @RepeatedTest(3)
    fun `test concolic with slide`() {
        val wasmFileName = "temp-indicator.wasm"
        val snapshotInterval = 20
        val slideCount = 10
        runWithDebugger(wasmFileName, true) { debugger ->
            debugger as MultiverseDebugger
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(listOf(ExecutionState.ProgramCounter), interval = snapshotInterval.toUInt()))
            debugger.reset()
            debugger.predictFuture(maxInstructions = 75)
            repeat(slideCount/2) {
                println("Performing slide ${it + 1}/$slideCount")
                slideToRandomNode(debugger)
                debugger.predictFuture(maxInstructions = 50)
            }

            repeat(slideCount/2) {
                println("Performing slide ${slideCount/2 + it + 1}/$slideCount")
                slideToRandomNode(debugger)
            }
        }
    }

    @Test
    fun `Slide to start from next choicepoint`() {
        runWithDebugger("temp-indicator.wasm", true) { debugger ->
            debugger as MultiverseDebugger
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(listOf(ExecutionState.ProgramCounter), interval = 5U))
            // Go to the first choice point in this program.
            debugger.continueFor(14)
            // Now go back to the start node.
            debugger.slide(debugger.graph.rootNode, 0)
        }
    }

    @Test
    fun `Check count with double checkpoint`() {
        runWithDebugger("temp-indicator.wasm", true) { debugger ->
            debugger as MultiverseDebugger
            debugger.printListener = { it ->
                println(it)
            }
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(
                listOf(ExecutionState.ProgramCounter),
                interval = 18U
            ))
            // continueFor also takes snapshots, this will result in an instructions_executed=0 snapshot, make sure the
            // count is still correct.
            assertEquals(0, debugger.graph.rootNode.totalInstrExecuted)
            debugger.continueFor(18)
            assertEquals(3, debugger.graph.instructionOffset)
            assertEquals(3, debugger.graph.currentNode.totalInstrExecuted)
            debugger.continueFor(1)
            assertEquals(14, debugger.graph.rootNode.totalInstrExecuted)
            assertEquals(4, debugger.graph.instructionOffset)
            assertEquals(4, debugger.graph.currentNode.totalInstrExecuted)

            debugger.reset()
            assertEquals(debugger.graph.rootNode, debugger.graph.currentNode)
            assertEquals(0, debugger.graph.instructionOffset)
            assertEquals(14, debugger.graph.currentNode.totalInstrExecuted)
            debugger.continueFor(19)
            assertEquals(4, debugger.graph.instructionOffset)
            assertEquals(4, debugger.graph.currentNode.totalInstrExecuted)
        }
    }

    @Test
    fun `Check update and counters`() {
        runWithDebugger("temp-indicator.wasm", true) { debugger ->
            debugger as MultiverseDebugger
            debugger.printListener = { it ->
                println(it)
            }
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(
                listOf(ExecutionState.ProgramCounter),
                interval = 9U
            ))
            debugger.continueFor(19)
            assertNotNull(debugger.graph.rootNode.checkpoints.find { it.instructions_executed == 9 })
            assertEquals(3, debugger.graph.currentNode.checkpoints.size)
            // Try to mess with the VM counters, if they are not reset on snapshot load this will cause drift resulting
            // in a failure.
            debugger.slide(debugger.graph.rootNode, 10)
            debugger.slide(debugger.graph.rootNode, 9)
            val targetNode = debugger.graph.rootNode.children.first()
            assertEquals(3, targetNode.checkpoints.size)
            println("targetNode checkpoint offsets = [${targetNode.checkpoints.map {
                "${it.instructions_executed}"
            }.joinToString { it }}], current instructionOffset = ${debugger.graph.instructionOffset}")
            debugger.slide(targetNode, 2)
            println("targetNode checkpoint offsets = [${targetNode.checkpoints.map {
                "${it.instructions_executed}"
            }.joinToString { it }}], current instructionOffset = ${debugger.graph.instructionOffset}")
            println(disassemble(debugger.wasmBinary.file.absolutePath))
            // Handle uncaught exceptions in the collecting thread
            withUncaughtHandler {
                /**
                 * What happens:
                 * [Start checkpoint, Global check point, Continue for checkpoint]
                 * Re-execute
                 * [Start checkpoint, Global check point, Continue for checkpoint (now skipped), Continue for checkpoint (with counter off by one)]
                 */
                debugger.continueFor(9)
            }
            assertEquals(4, debugger.graph.currentNode.checkpoints.size)
        }
    }

    override fun createDebugger(connection: Connection, wasmFilePath: String): Debugger {
        val binaryInfo = getBinaryInfo(config.wdcliPath, getFile(wasmFilePath).absolutePath)
        return MultiverseDebugger(connection, WasmBinary(File(wasmFilePath), binaryInfo.getOrThrow()), config.symbolicWdcliPath)
    }
}
