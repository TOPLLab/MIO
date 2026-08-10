package benchmarks

import DebuggerTestBase
import be.ugent.topl.mio.debugger.Debugger
import getBinaryInfo
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileWriter
import java.lang.System.currentTimeMillis

/**
 * Benchmarks which use a headless version of MIO to test the performance of various operations. To get accurate
 * performance estimates they should be performed with an actual microcontroller since communication latency is an
 * important factor.
 */
class Benchmarks : DebuggerTestBase() {
    fun useEmulator() = config.useEmulator || config.port == null

    /**
     * Executes the code using continueFor over n instructions and measures how much time it takes with different
     * checkpointing strategies.
     */
    @Test
    fun `Measure impact of checkpointing on forward execution`() {
        val writer = FileWriter(File("results-forward-execution.csv"))
        writer.write("Interval,Instructions executed,Time(ms)\n")
        val results = mutableListOf<Triple<Int, Double, Debugger.SnapshotPolicy>>()
        for (policy in listOf(
            Debugger.SnapshotPolicy.None(),
            Debugger.SnapshotPolicy.Checkpointing(1U),
            Debugger.SnapshotPolicy.Checkpointing(5U),
            Debugger.SnapshotPolicy.Checkpointing(10U),
            Debugger.SnapshotPolicy.Checkpointing(50U),
            Debugger.SnapshotPolicy.Checkpointing(100U))) {

            for (n in 250 ..< 1500 step 250) {
                println("Progress $n/1500")
                var totalTime = 0L
                val times = 10
                repeat(times) {
                    runWithDebugger("prime/prime-no-mem.wasm", useEmulator()) {
                        it.setSnapshotPolicy(policy)
                        val startTime = currentTimeMillis()
                        it.continueFor(n)
                        totalTime += currentTimeMillis() - startTime
                    }
                }
                writer.write("${if(policy is Debugger.SnapshotPolicy.Checkpointing) policy.interval else 0}, $n, ${totalTime.toDouble() / times}\n")
                writer.flush()
            }
        }
        writer.close()

        println("Policy, Instructions executed, Time elapsed")
        for (result in results) {
            println("${result.third}, ${result.first}, ${result.second}")
        }
    }

    /**
     * Executes 1000 instructions, then steps back 1, needing 999 instructions to be re-executed. Because checkpoints
     * are deleted, it first has to re-execute 999 then 1999 then 2999 and so on. Since it steps back one instruction it
     * also needs to step forward.
     */
    @Test
    fun `Measure re-execution speed when stepping back`() {
        val wasmFile = "prime/prime-no-mem.wasm"
        val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile(wasmFile).absolutePath)
        val results = mutableListOf<List<Pair<Int, Long>>>()
        repeat(10) {
            runWithDebugger(wasmFile, useEmulator()) {
                var t = 0
                val timings = mutableListOf<Pair<Int, Long>>()
                it.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(0xffffffU))
                it.stepInto()
                it.checkpoints[it.checkpoints.size - 1] = null
                timings.add(Pair(t, timeElapsed {
                    it.stepBack(1) {}
                }))

                (0..30).forEach { i ->
                    it.continueFor(1000)
                    it.checkpoints[it.checkpoints.size - 1] = null
                    t += 999
                    timings.add(Pair(t, timeElapsed {
                        it.stepBack(1) {}
                    }))
                    it.checkpoints[it.checkpoints.size - 1] = null
                }
                println(timings)
                results.add(timings)
            }
        }
        val average = mutableListOf<Pair<Int, Float>>()
        for (i in results[0].indices) {
            var sum = 0.0
            for (run in results.indices) {
                sum += results[run][i].second
            }
            average.add(Pair(results[0][i].first, sum.toFloat() / results.size))
        }
        val writer = FileWriter(File("results-step-back-re-execution.csv"))
        writer.write("t, avg_time\n")
        for (pair in average) {
            writer.write("${pair.first}, ${pair.second}\n")
        }
        writer.close()
    }

    private fun timeElapsed(action: () -> Unit): Long {
        val startTime = currentTimeMillis()
        action()
        return currentTimeMillis() - startTime
    }
}
