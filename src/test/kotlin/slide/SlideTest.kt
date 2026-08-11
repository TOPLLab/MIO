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
import java.io.File
import kotlin.random.Random

@DisplayName("Slide behaviour tests")
class SlideTest : DebuggerTestBase() {
    fun randomNode(debugger: MultiverseDebugger, depth: Int) : MultiverseNode {
        var remainingDepth = Random.nextInt(depth)
        println("Determining a random node at depth $remainingDepth")
        var currentNode = debugger.graph.rootNode
        while (remainingDepth > 0 && currentNode.children.isNotEmpty()) {
            currentNode = currentNode.children[Random.nextInt(currentNode.children.size)]
            remainingDepth -= currentNode.totalInstrExecuted + 1
        }
        return currentNode
    }

    fun randomSlide(wasmFileName: String, snapshotInterval: Int = 0xf0000) {
        runWithDebugger(wasmFileName, true) { debugger ->
            debugger as MultiverseDebugger
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Tracing(listOf(ExecutionState.ProgramCounter), interval = snapshotInterval.toUInt()))
            debugger.reset()
            val depth = snapshotInterval * 5
            debugger.continueFor(depth)
            val destNode = randomNode(debugger, depth)
            val offset = if (destNode.totalInstrExecuted == 0) 0 else Random.nextInt(destNode.totalInstrExecuted)
            debugger.slide(destNode, offset)
        }
    }

    @RepeatedTest(10)
    fun `Random slide in temperature indicator program`() {
        randomSlide("temp-indicator.wasm", 100)
    }

    @RepeatedTest(10)
    fun `Random slide in temperature indicator program advanced`() {
        randomSlide("temp-indicator-advanced.wasm", 1000)
    }

    /*@RepeatedTest(10)
    fun `Random slide in sokoban`() {
        randomSlide("/Users/maarten/Projects/WARDuino-platformer/sokoban.wasm", 0xf0000)
    }*/

    override fun createDebugger(connection: Connection, wasmFilePath: String): Debugger {
        val binaryInfo = getBinaryInfo(config.wdcliPath, getFile(wasmFilePath).absolutePath)
        return MultiverseDebugger(connection, WasmBinary(File(wasmFilePath), binaryInfo.getOrThrow()), config.symbolicWdcliPath)
    }
}
