import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.debugger.MultiverseDebugger
import org.apache.commons.math3.stat.StatUtils
import org.junit.jupiter.api.Test
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import be.ugent.topl.mio.woodstate.compressRLE
import java.io.File
import java.io.FileWriter
import java.lang.System.currentTimeMillis
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.assertEquals

class DebuggerTests : DebuggerTestBase() {
    @Test
    fun `Test if step back results in the same state`() {
        val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile("blink.wasm").absolutePath)
        val connection = ProcessConnection(wdcliPath, this.javaClass.getResource("/blink.wasm")!!.file, "--no-socket")
        val debugger = Debugger(connection)
        debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing())
        val beforeSnapshot = debugger.snapshot()
        debugger.stepInto()
        val stepAfter = debugger.snapshot()
        debugger.stepBack(1)
        assertEquals(beforeSnapshot, debugger.snapshot())
        debugger.stepInto()
        assertEquals(stepAfter, debugger.snapshot())
        debugger.close()
    }

    private fun timeElapsed(action: () -> Unit): Long {
        val startTime = currentTimeMillis()
        action()
        return currentTimeMillis() - startTime
    }

    @Test
    fun `Test memory serialisation`() {
        runWithDebugger("/home/maarten/Documents/Projects/maarten-thesis-23-24/samples/robotarm/robotarm.wasm") {
            val s = it.snapshotFull()
            assert(s.second.memory!!.bytes.isEmpty())
        }
        runWithDebugger("prime/prime.wasm") {
            val s = it.snapshotFull()
            val bytes = s.second.memory!!.bytes
            assertEquals(2 * 0x10000, bytes.size)
            for (b in bytes) {
                assertEquals(0, b)
            }
        }
    }

    // forward-execution-checkpointing.csv
    // First graph
    @Test
    fun `Test continue for operation speed with and without checkpointing`() {
        val writer = FileWriter(File("results-forward-execution.csv"))
        val results = mutableListOf<Triple<Int, Double, Debugger.SnapshotPolicy>>()
        for (policy in listOf(
            //      Debugger.SnapshotPolicy.None(),
            Debugger.SnapshotPolicy.Checkpointing(1),
            /*Debugger.SnapshotPolicy.Checkpointing(5),
            Debugger.SnapshotPolicy.Checkpointing(10),
            Debugger.SnapshotPolicy.Checkpointing(50),
            Debugger.SnapshotPolicy.Checkpointing(100)*/)) {

            for (n in 250 ..< 1500 step 250) {
                println("Progress $n/1500")
                var totalTime = 0L
                val times = 10
                repeat(times) {
                    runWithDebugger("prime/prime-no-mem.wasm") {
                        it.setSnapshotPolicy(policy)
                        val startTime = currentTimeMillis()
                        it.continueFor(n)
                        totalTime += currentTimeMillis() - startTime
                    }
                }
                writer.write("$policy, $n, ${totalTime.toDouble() / times}\n")
                writer.flush()
                //results.add(Triple(n, totalTime.toDouble() / times, policy))
            }
        }

        //println("Executed $n instructions")
        println("Policy, Instructions executed, Time elapsed")
        for (result in results) {
            println("${result.third}, ${result.first}, ${result.second}")
            //println("${result.second} ms elapsed with policy ${result.third}")
        }
    }

    @Test
    fun `Test continue for operation`() {
        //val connection = ProcessConnection(wdcliPath, getFile("blink.wasm").path, "--no-socket")
        val connection = ProcessConnection(wdcliPath, getFile("/home/maarten/Documents/Projects/maarten-thesis-23-24/wardbg/src/test/resources/prime/prime-no-mem.wasm").path, "--no-socket")
        val debugger = Debugger(connection)
        debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(100))
        debugger.pause()
        val count = debugger.checkpoints.size
        debugger.continueFor(1000)
        assertEquals(count + 50, debugger.checkpoints.size)
        debugger.close()
    }

    @Test
    fun `Test snapshot policy serialization`() {
        assertEquals("00", Debugger.SnapshotPolicy.None().serialize())
        assertEquals("01", Debugger.SnapshotPolicy.AtEveryInstruction().serialize())
        assertEquals("0205", Debugger.SnapshotPolicy.Checkpointing(5).serialize())
        assertEquals("02ff", Debugger.SnapshotPolicy.Checkpointing(255).serialize())
    }

    @Test
    fun `Test stepBack performance  2`() {
        val writer = FileWriter(File("results-step-back.csv"))
        val timings = mutableListOf<MutableList<Pair<Int, Double>>>()
        //for (checkpointInterval in listOf(1, 5, 10, 20, 50)) {
        //for (checkpointInterval in listOf(1, 5, 10, 20, 50)) {
        //for (checkpointInterval in listOf(5, 20)) {
        //for (checkpointInterval in listOf(5)) {
        for (checkpointInterval in listOf(1)) {
        //for (checkpointInterval in listOf(10, 20, 50)) {
        //for (checkpointInterval in listOf(1)) {
        //for (checkpointInterval in listOf(5)) {
        //for (checkpointInterval in listOf(10)) {
        //for (checkpointInterval in listOf(50)) {
            timings.add(mutableListOf())
            //val wasmFile = "led_demo.wasm"
            val len = (ceil(50.0 / checkpointInterval) * checkpointInterval).toInt()
            val wasmFile = "prime/prime-no-mem.wasm"
            val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile(wasmFile).absolutePath)
            val x = (3 .. len step 2).toMutableSet()
            if (!x.contains(len))
                x.add(len)
            x.addAll(0 .. len step checkpointInterval)
            val xSorted = x.toMutableList()
            xSorted.sort()
            for (n in xSorted) {
                runWithDebugger(wasmFile, emulator = false) { debugger ->
                    debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(checkpointInterval))
                    var time = 0L
                    //val repeatCount = 50
                    //val repeatCount = 5
                    val repeatCount = 3
                    repeat(repeatCount) {
                        //debugger.printCheckpoints(binaryInfo)
                        //val x1 = debugger.inspect(ExecutionState.ProgramCounter).pc!!
                        debugger.continueFor(len)
                        time += timeElapsed {
                            debugger.stepBack(n) {}
                        }
                        debugger.stepBack(len - n) {}
                        /*val x2 = debugger.inspect(ExecutionState.ProgramCounter).pc!!
                        debugger.printCheckpoints(binaryInfo)
                        assert(x1 == x2) {
                            println("$x1, $x2")
                        }*/
                    }
                    timings.last().add(Pair(n, time/repeatCount.toDouble()))
                    println("TIME ${time/repeatCount.toDouble()} n = $n, checkpointInterval = $checkpointInterval")
                }
            }
            for (time in timings.last()) {
                writer.write("(${time.first}, ${time.second})")
            }
            writer.write("\n")
            writer.flush()
        }

        // Print results:
        for (timing in timings) {
            for (time in timing) {
                print("(${time.first}, ${time.second})")
            }
            println()
        }
    }

    @Test
    /*@ParameterizedTest
    @ValueSource(ints = [1, 3, 5, 8, 10, 15])
    fun `Test stepBack performance, min max average time`(stepSize: Int) {*/
    fun `Test stepBack performance`() {
        val fileWriter = File("results.txt").bufferedWriter()
        val results = mutableListOf<Pair<Triple<Int, Int, Int>, Triple<Long, Long, Double>>>()
        //for (checkpointInterval in listOf(1, 2, 5, 10)) {
        //for (checkpointInterval in listOf(1, 5, 10, 50, 100)) {
        for (checkpointInterval in listOf(5, 10, 50, 100)) {
            for (stepSize in listOf(1, 3, 5, 8, 10, 15)) {
            //for (stepSize in listOf(5, 8, 10, 15)) {
                val n = 200
                val timings = mutableListOf<Long>()
                runWithDebugger("led_demo.wasm") { debugger ->
                    debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(checkpointInterval))
                    val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, File(this.javaClass.getResource("/led_demo.wasm")!!.file).absolutePath)
                    debugger.continueFor(n)

                    for (i in 0 ..< (n / stepSize)) {
                        timings.add(timeElapsed {
                            debugger.stepBack(stepSize) {}
                        })
                        if (i % 10 == 0) {
                            println("Progress $i/$n")
                        }
                    }
                }

                println("timings = $timings")
                val timingsDoubleArray = timings.toList().map { it.toDouble() }.toDoubleArray()
                fileWriter.write(
                    """
                    \addplot+ [
                        boxplot prepared={
                    """.trimIndent())
                fileWriter.write("\tlower whisker = ${StatUtils.min(timingsDoubleArray)},\n")
                fileWriter.write("\tlower quartile = ${StatUtils.percentile(timingsDoubleArray, 25.0)},\n")
                fileWriter.write("\tmedian = ${StatUtils.percentile(timingsDoubleArray, 50.0)},\n")
                fileWriter.write("\tupper quartile =  ${StatUtils.percentile(timingsDoubleArray, 75.0)},\n")
                fileWriter.write("\tupper whisker =  ${StatUtils.max(timingsDoubleArray)}\n")
                fileWriter.write(
                    """
                        },
                    ]
                    table [row sep=\\,y index=0] {
                        data\\ 1\\ 3.5\\
                    };
                    
                    """.trimIndent())
                fileWriter.flush()

                results.add(Pair(Triple(n, stepSize, checkpointInterval), Triple(timings.min(), timings.max(), timings.average())))
                /*println("#--------------------------------------------------#")
                println(" Benchmark results (n = $n, stepSize = $stepSize)")
                println("#--------------------------------------------------#")
                println(timings)
                println("min ${timings.min()}ms")
                println("avg ${timings.average()}ms")
                println("max ${timings.max()}ms")*/
            }
        }
        println("N, Step size, Checkpointing interval, Min, Max, Average")
        for (result in results) {
            println("${result.first.first}, ${result.first.second}, ${result.first.third}, ${result.second.first}, ${result.second.second}, ${result.second.third}")
        }
        fileWriter.close()
    }

    @Test
    fun `x` () {
        runWithDebugger("prime/prime.wasm", false) {
            it.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(10))
            it.continueFor(50)
        }
    }

    // step-back-reexecute.csv
    @Test
    fun `Step back test 3`() {
        val wasmFile = "prime/prime-no-mem.wasm"
        val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile(wasmFile).absolutePath)
        val results = mutableListOf<List<Pair<Int, Long>>>()
        repeat(10) {
            runWithDebugger(wasmFile, false) {
                var t = 0
                val timings = mutableListOf<Pair<Int, Long>>()
                it.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(0xffffff))
                it.stepInto()
                it.checkpoints[it.checkpoints.size - 1] = null
                timings.add(Pair(t, timeElapsed {
                    it.stepBack(1) {}
                }))
                //it.printCheckpoints()

                //for (i in 0..1000) {
                //it.continueFor(2)
                for (i in 0..30) {
                    it.continueFor(1000)
                    it.checkpoints[it.checkpoints.size - 1] = null
                    t += 999
                    timings.add(Pair(t, timeElapsed {
                        it.stepBack(1) {}
                    }))
                    it.checkpoints[it.checkpoints.size - 1] = null
                    //it.printCheckpoints()
                }
                println(timings)
                results.add(timings)
                //it.continueFor(50)
                //it.printCheckpoints()
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
        println("t, avg_time")
        for (pair in average) {
            println("${pair.first}, ${pair.second}")
        }
    }

    @Test
    fun multiverseTest() {
        //val wasmFile = "blink.wasm"
        val wasmFile = "/home/maarten/Documents/Projects/maarten-thesis-23-24/samples/led_demo/led_demo.wasm"
        val connection = ProcessConnection(wdcliPath, getFile(wasmFile).path, "--no-socket")
        val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile(wasmFile).absolutePath)
        val debugger = MultiverseDebugger(connection, WasmBinary(File(wasmFile), binaryInfo.getOrThrow()), config.symbolicWdcliPath)
        debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing(10))
        debugger.pause()
        //debugger.continueFor(5)
        debugger.step(5)
        debugger.printCheckpoints(binaryInfo.getOrThrow())
        debugger.stepBack(1)
        debugger.step(1)
        debugger.close()
    }

    @Test
    fun testOverrides() {
        val wasmFile = "blink.wasm"
        val connection = ProcessConnection(wdcliPath, getFile(wasmFile).path, "--no-socket")
        val binaryInfo = getBinaryInfo(config.symbolicWdcliPath, getFile(wasmFile).absolutePath)
        val debugger = MultiverseDebugger(connection, WasmBinary(File(wasmFile), binaryInfo.getOrThrow()), config.symbolicWdcliPath)
        println("Running $wdcliPath")
        debugger.pause()
        //debugger.addPrimitiveOverride("chip_digital_read", 0, 5)
        debugger.addPrimitiveOverride("chip_digital_write", 0, 5)
        debugger.snapshot()
        Thread.sleep(1000)
        debugger.close()
    }

    @Test
    fun `Test memory restore` () {
        runWithDebugger("prime/prime.wasm", true) {
            fun loadAndRestore(mutator: (s: WOODDumpResponse) -> Unit) {
                val snapshot = it.snapshotFull().second
                mutator(snapshot)
                it.loadSnapshot(snapshot)
                val after = it.snapshotFull().second
                //assertTrue(snapshot.memory!!.bytes.contentEquals(after.memory!!.bytes))
                for (i in snapshot.memory!!.bytes.indices) {
                    if (snapshot.memory!!.bytes[i] != after.memory!!.bytes[i]) {
                        println("${snapshot.memory!!.bytes.slice(i - 5 ..< i + 5)}")
                        println("${after.memory!!.bytes.slice(i - 5 ..< i + 5)}")
                    }
                    assertEquals(snapshot.memory!!.bytes[i], after.memory!!.bytes[i], "Position $i")
                }
            }

            loadAndRestore {}
            loadAndRestore {
                it.memory!!.bytes[100] = 5
                it.memory!!.bytes[7] = 3
            }
            val random = Random(42)
            loadAndRestore { snapshot ->
                repeat(20) {
                    snapshot.memory!!.bytes[random.nextInt(snapshot.memory!!.bytes.size)] = random.nextInt(0xff).toByte()
                }
                //Random.nextBytes(it.memory!!.bytes)
            }
            loadAndRestore { snapshot ->
                repeat(1000) {
                    snapshot.memory!!.bytes[random.nextInt(snapshot.memory!!.bytes.size)] = random.nextInt(0xff).toByte()
                }
            }
            loadAndRestore {
                random.nextBytes(it.memory!!.bytes)
            }
        }
    }

    @Test
    fun `Run length test`() {
        assertEquals("01050200010103050107", compressRLE("0500000105050507"))
        assertEquals("020502000101030502060103", compressRLE("0505000001050505060603"))

        println(compressRLE(listOf("00", "00", "00", "00", "00"), 3))
    }
}
