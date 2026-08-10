package benchmarks

import DebuggerTestBase
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.debugger.ExecutionState
import org.junit.jupiter.api.Test
import java.lang.System.currentTimeMillis

class TracePerformance : DebuggerTestBase() {
    @Test
    fun `test`() {
        val continueForCount = 5 // Was 5 in the paper.
        val results = mutableListOf<Result>()
        for (policy in listOf(
            Debugger.SnapshotPolicy.None(),
            Debugger.SnapshotPolicy.Tracing(listOf(ExecutionState.ProgramCounter)),
            Debugger.SnapshotPolicy.Checkpointing(0xffffU)
        )) {
            println("Policy $policy")
            val pairs = mutableListOf<Pair<Long, Long>>()
            runWithDebugger("benchmarks/breakout/upload.wasm", true) { debugger ->
                debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.None())
                debugger.continueFor(100500)
                debugger.setSnapshotPolicy(policy)
                var totalTime = 0L
                var position = 0L
                repeat(continueForCount) {
                    val startTime = currentTimeMillis()
                    val offset = 10000
                    debugger.continueFor(offset)
                    debugger.checkpoints.clear() // Clear checkpoint data to save memory.
                    position += offset
                    totalTime += currentTimeMillis() - startTime
                    pairs.add(Pair(position, totalTime))
                    println("$totalTime ms elapsed!")
                }
            }
            println("Data $pairs")
            results.add(Result(policy, pairs))
        }
        println()
        println("--- Test results ---")
        for (result in results) {
            println("${result.policy}: ${result.data}")
        }
    }

    data class Result(val policy: Debugger.SnapshotPolicy, val data: List<Pair<Long, Long>>)
}
