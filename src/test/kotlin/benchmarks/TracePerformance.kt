package benchmarks

import DebuggerTestBase
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.debugger.ExecutionState
import org.junit.jupiter.api.Test
import java.lang.System.currentTimeMillis

class TracePerformance : DebuggerTestBase() {
    /**
     * Without:
     * 1424 ms elapsed!
     * Trace-based:
     * 1545 ms elapsed!
     * Snapshot based:
     * 526571 ms - (test time 8m47s)
     *
     * Run 2, start after warmup of 100 000 instructions, run for 1250 instructions:
     * Without:
     * 453 ms
     * Trace-based:
     * 500 ms
     * Snapshot-based:
     * 7414 ms
     */
    @Test
    fun `test`() {
        for (policy in listOf(
            Debugger.SnapshotPolicy.None(),
            Debugger.SnapshotPolicy.Tracing(1, listOf(ExecutionState.ProgramCounter)),
            Debugger.SnapshotPolicy.Checkpointing(0xffff)
        )) {
            println("Policy $policy")
            val pairs = mutableListOf<Pair<Long, Long>>()
            runWithDebugger("/Users/maarten/Projects/WARDuino-demos/breakout/breakout.wasm", false) { debugger ->
                debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.None())
                debugger.continueFor(100500)
                //it.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(0xffff))
                debugger.setSnapshotPolicy(policy)
                //it.setSnapshotPolicy(Debugger.SnapshotPolicy.None())
                var totalTime = 0L
                var position = 0L
                repeat(5) {
                    val startTime = currentTimeMillis()
                    val offset = 10000
                    debugger.continueFor(offset)
                    position += offset
                    totalTime += currentTimeMillis() - startTime
                    pairs.add(Pair(position, totalTime))
                    println("$totalTime ms elapsed!")
                }
            }
            println("Data $pairs")
        }
    }

    @Test
    fun x() {
        println(Debugger.SnapshotPolicy.Tracing(1, listOf(ExecutionState.ProgramCounter)).serialize())
    }
}
