package slide

import DebuggerTestBase
import WasmBinary
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.debugger.ExecutionState
import be.ugent.topl.mio.debugger.MultiverseDebugger
import be.ugent.topl.mio.debugger.MultiverseNode
import getBinaryInfo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

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

    override fun createDebugger(connection: Connection, wasmFilePath: String): Debugger {
        val binaryInfo = getBinaryInfo(config.wdcliPath, getFile(wasmFilePath).absolutePath)
        return MultiverseDebugger(connection, WasmBinary(File(wasmFilePath), binaryInfo.getOrThrow()), config.symbolicWdcliPath)
    }
}
