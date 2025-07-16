package be.ugent.topl.mio.debugger

import WasmInfo
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.woodstate.Checkpoint
import be.ugent.topl.mio.woodstate.HexaEncoder
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import be.ugent.topl.mio.woodstate.WOODState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.Closeable
import java.io.File
import java.util.*
import kotlin.concurrent.thread
import kotlin.streams.toList

open class Debugger(private val connection: Connection, start: Boolean = true, private val onHitBreakpoint: (Int) -> Unit = {}) : Closeable, AutoCloseable {
    private val requestQueue: Queue<Int> = LinkedList()
    var printListener: ((String) -> Unit)? = null
    private val messageQueue = MessageQueue {
        for (msg in it) {
            if (msg.startsWith("EMU: ")) {
                this.printListener?.invoke(msg.substring(5))
            }
        }
    }
    private val readThread  = thread(start) {
        while (!Thread.currentThread().isInterrupted) {
            while (connection.bytesAvailable() == 0) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            val readBuffer = ByteArray(connection.bytesAvailable())
            connection.read(readBuffer)
            messageQueue.push(String(readBuffer), true)

            while (true) {
                val checkpointMessage = messageQueue.search {
                    val match = Regex("CHECKPOINT (.*)").matchEntire(it.trimEnd('\r')) ?: throw Exception()
                    return@search match
                }
                if (checkpointMessage == null)
                    break;

                val payloadStr = checkpointMessage.second.groups[1]!!.value

                try {
                    val checkpoint = ObjectMapper().registerKotlinModule().readValue(payloadStr, Checkpoint::class.java)
                    //println(checkpoint)

                    if (checkpoint.instructions_executed == 0 && checkpoints.size > 0) {
                        if (checkpoint.snapshot.pc != checkpoints.last()!!.snapshot.pc) {
                            throw RuntimeException("Received a checkpoint with a different pc but with 0 executed instructions since the last checkpoint!")
                        }
                        System.err.println("WARNING: Received a checkpoint that we already have!")
                        continue
                    }

                    for (i in 0..< checkpoint.instructions_executed - 1) {
                        checkpoints.add(null)
                    }
                    /*if (checkpoints.isNotEmpty() && checkpoints.last()?.pc == checkpoint.snapshot.pc && checkpoint.instructions_executed == 0) {
                        println("Duplicate checkpoint!")
                        throw Exception("Error")
                        continue
                    }*/
                    checkpoints.add(checkpoint)

                    checkpointsUpdated()
                } catch(e: Exception) {
                    println("ERROR!")
                    println(payloadStr)
                    println(e)
                    println("")
                    checkpoints.clear()
                }
            }
            messageQueue.pushDone()

            // Handle breakpoints after receiving checkpoints so we have the correct state.
            if (!commandBreakpoint) {
                // Check for "AT address!"
                val searchAtResult = messageQueue.search {
                    val match = Regex("AT ([0-9]+)!").matchEntire(it.trimEnd('\r')) ?: throw Exception()
                    return@search match.groups[1]!!.value.toInt()
                }
                if (searchAtResult != null) {
                    /*
                     * Run the callback in a separate thread, this allows the callback function to make use of debugger
                     * functions that wait until a message is received. If we don't use a separate thread, the execution
                     * of this function would block the current thread, but this thread is responsible for reading
                     * incoming messages, so the request would never be completed.
                     */
                    thread {
                        onHitBreakpoint(searchAtResult.second)
                    }
                }
            }
        }
    }
    val checkpoints = mutableListOf<Checkpoint?>()
    private val stateListeners = mutableListOf<(WOODDumpResponse) -> Unit>()
    private var commandBreakpoint = false

    init {
        Runtime.getRuntime().addShutdownHook(thread(false) {
            println("Closing debugger connection...")
            close()
        })
    }

    fun startReading() {
        readThread.start()
    }

    override fun close() {
        readThread.interrupt()
        readThread.join()
        connection.close()
    }

    fun repl() {
        while (true) {
            print("> ")
            if (handleCommand(readln())) {
                break;
            }
        }
    }

    fun handleCommand(str: String): Boolean {
        if (str == "exit") {
            return true
        }
        try {
            val splitStr = str.split(" ")
            val funcName = splitStr[0]

            val argTypes = mutableListOf<Class<*>>()
            for (i in 1 until splitStr.size) {
                if (splitStr[i].startsWith('"')) {
                    argTypes.add(String::class.java)
                }
                else {
                    argTypes.add(Int::class.java)
                }
            }

            val method = javaClass.getMethod(funcName, *argTypes.toTypedArray())

            val argList = mutableListOf<Any>()
            for (i in 0 until method.parameterCount) {
                val param = method.parameters[i]
                if (param.type == String::class.java) {
                    val arg = splitStr[i + 1]
                    argList.add(arg.subSequence(1,arg.length - 1))
                } else {
                    argList.add(splitStr[i + 1].toInt())
                }
            }

            method.invoke(this, *argList.toTypedArray())
        } catch (_: NoSuchMethodException) {
            println("Sending \"$str\"")
            val write = "${str}\n".toByteArray()
            connection.write(write)
        }
        return false
    }

    private fun send(code: Int, payload: String = "") {
        val str = String.format("%02d$payload\n", code)
        requestQueue.add(code)
        print("Sending $str")
        val write = str.toByteArray()
        connection.write(write)
    }

    private fun sendRaw(message: String) {
        print("Sending $message")
        val write = message.toByteArray()
        connection.write(write)
    }

    open fun run() {
        send(1)
    }
    fun halt() = send(2)
    fun pause() {
        send(3)
        messageQueue.waitForResponse("PAUSE!")
    }
    open fun stepInto() {
        //snapshotStack.add(currentSnapshot!!)
        send(4)
        messageQueue.waitForResponse("STEP!")
        //currentSnapshot = snapshotFull().second
    }
    open fun stepOver() {
        commandBreakpoint = true
        send(5)
        messageQueue.waitForResponse {
            if (it != "STEP!" && it.matches(Regex("AT [0-9]+!")))
                throw Exception()
        }
        commandBreakpoint = false
    }
    fun stepUntil(cond: (WOODDumpResponse) -> Boolean) {
        stepInto()
        while (!cond(checkpoints.last()!!.snapshot)) {
            stepInto()
        }
    }

    private fun canStepBack(): Boolean {
        return checkpoints.size > 1
    }

    fun stepBackUntil(binaryInfo: WasmInfo, cond: (WOODDumpResponse) -> Boolean) {
        stepBack(1, binaryInfo) {}
        while (!cond(checkpoints.last()!!.snapshot)) {
            if (!canStepBack()) {
                System.err.println("WARNING: Can't go back further!")
                return
            }
            stepBack(1, binaryInfo)
        }
    }

    fun step(n: Int) {
        for (i in 0 ..< n) {
            send(4)
            messageQueue.waitForResponse("STEP!")
        }
    }

    fun printCheckpoints(binaryInfo: WasmInfo? = null) {
        println("Checkpoints:")
        for (checkpoint in checkpoints) {
            if (checkpoint == null) {
                println("|")
            }
            else {
                print("* pc = ${checkpoint.snapshot.pc}")
                if (binaryInfo != null) {
                    if (checkpoint.snapshot.pc in binaryInfo.primitive_calls) {
                        print(" CALL Primitive")
                    }
                    if (checkpoint.snapshot.pc in binaryInfo.after_primitive_calls) {
                        print(" After primitive, should restore")
                    }
                }
                println()
            }
        }
        println("count = ${checkpoints.size}")
    }

    open fun stepBack(n: Int, binaryInfo: WasmInfo, stepDone: () -> Unit = {}) {
        if (n == 0) {
            return
        }

        val currentState = checkpoints.removeLast() // Remove current state, we don't need to restore this, we are already in this state.
        val nSnapshots = checkpoints.subList(checkpoints.size - n, checkpoints.size).toList()
        for (checkpoint in nSnapshots.reversed()) {
            if (checkpoint != null && (checkpoint.snapshot.pc in binaryInfo.after_primitive_calls || nSnapshots.first() == checkpoint)) {
            //if (snapshot != null) {
                println("Snapshot to ${checkpoint.snapshot.pc}")
                val s = checkpoint.snapshot
                s.breakpoints = currentState?.snapshot?.breakpoints // The current state can be null if the data about this checkpoint was removed.
                loadSnapshot(s)
            }
            stepDone()
        }
        for (i in 0 ..< n - 1) {
            checkpoints.removeLast()
        }

        // Restore the last snapshot and step forward
        // Find the last snapshot before the desired point, restore that snapshot and then step forward to the desired point.
        if (nSnapshots.first() == null) {
            var stepForward = 0
            for (checkpoint in checkpoints.reversed()) {
                if (checkpoint != null) {
                    println("Jumping to ${checkpoint.snapshot.pc}")
                    val s = checkpoint.snapshot
                    s.breakpoints = currentState?.snapshot?.breakpoints
                    loadSnapshot(s)
                    break
                }
                stepForward++
            }
            // Remove old null checkpoints
            for (i in 0 ..< stepForward) {
                checkpoints.removeLast()
            }
            // Step forward to the desired point (which will also add back snapshots onto the snapshot stack)
            // We do this without breakpoints because we don't want these to interrupt the forward execution.
            withoutBreakpoints {
                internalContinueFor(stepForward)
            }
        }

        // Results:
        checkpointsUpdated()
    }

    open fun checkpointsUpdated() {
        val currentState = checkpoints.last()
        if (currentState == null) {
            return
        }

        for (listener in stateListeners) {
            listener(currentState.snapshot)
        }
    }

    fun registerCurrentStateListener(listener: (WOODDumpResponse) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeCurrentStateListener(listener: (WOODDumpResponse) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addBreakpoint(address: Int) {
        send(6, String.format("%08x", address))
        messageQueue.waitForResponse("BP $address!")

        val s = checkpoints.last()!!.snapshot
        s.breakpoints = s.breakpoints!!.toMutableList() + address
    }
    fun enableBreakpoints(breakpoints: List<Int>) {
        for (breakpoint in breakpoints) {
            addBreakpoint(breakpoint)
        }
    }
    fun removeBreakpoint(address: Int) {
        send(7, String.format("%08x", address))
        messageQueue.waitForResponse("BP $address!")

        val s = checkpoints.last()!!.snapshot
        s.breakpoints = s.breakpoints!!.toMutableList() - address
    }
    fun disableAllBreakpoints(): List<Int> {
        val breakpointsStart = checkpoints.last()!!.snapshot.breakpoints?: emptyList()
        for (breakpoint in breakpointsStart) {
            removeBreakpoint(breakpoint)
        }
        return breakpointsStart
    }
    fun withoutBreakpoints(f: () -> Unit) {
        val breakpoints = disableAllBreakpoints()
        f()
        enableBreakpoints(breakpoints)
    }
    private fun internalContinueFor(n: Int) {
        //Thread.sleep(n * 1L)
        val startLen = checkpoints.size
        send(8, String.format("%08x", n))
        //messageQueue.waitForResponse("DONE!")
        messageQueue.searchForResponse {
            if (it.trimEnd('\r') != "DONE!") throw Exception()
            it
        }
        /*while (checkpoints.size < startLen + n) {
            println("Wait a bit (${checkpoints.size}, ${startLen + n})")
            Thread.sleep(200)
        }*/
        println("continueFor done!")
    }
    open fun continueFor(n: Int) = internalContinueFor(n)
    fun inspect(vararg states: ExecutionState): WOODDumpResponse {
        var payload = String.format("%04x", states.size)
        for (state in states) {
            payload += String.format("%02x", state.ordinal + 1)
        }
        send(9, payload)
        return messageQueue.waitForResponse {
            val objectMapper = ObjectMapper()
            objectMapper.registerKotlinModule()
            objectMapper.readValue(it, WOODDumpResponse::class.java)
        }.second
    }
    fun dumpVMState() = send(10)
    fun dumpLocals() = send(11)
    fun dumpStateAndLocals() = send(12)
    open fun reset() {
        val firstState = checkpoints.first()
        checkpoints.clear()
        checkpoints.add(firstState)
        send(13)
        checkpointsUpdated()
    }

    fun snapshot(): String {
        send(60)
        return messageQueue.waitForResponse {
            WOODState.fromLine(it)
        }.first
    }
    fun snapshotFull(): Pair<String, WOODDumpResponse> {
        send(60)
        return messageQueue.waitForResponse {
            WOODState.parseSnapshot(it)
        }
    }
    fun loadSnapshot(payload: String) {
        loadSnapshot(WOODState.parseSnapshot(payload))
    }
    open fun loadSnapshot(snapshot: WOODDumpResponse) {
        val woodState = WOODState(snapshot)
        val messages = woodState.toBinary()
        println(messages)
        for (message in messages) {
            sendRaw(message)
            if (message != messages.last()) {
                messageQueue.waitForResponse("ack!")
            }
            else {
                messageQueue.waitForResponse("done!")
            }
        }
    }

    open fun addPrimitiveOverride(primName: String, arg: Int, returnValue: Int) {
        val primNameSerialised = primName.chars().toList().joinToString("") { c: Int -> String.format("%02x", c) } + "00"
        val payload = primNameSerialised + String.format("%08x", arg) + String.format("%08x", returnValue)
        send(80, payload)
    }

    open fun removePrimitiveOverride(primName: String, arg: Int) {
        val primNameSerialised = primName.chars().toList().joinToString("") { c: Int -> String.format("%02x", c) } + "00"
        val payload = primNameSerialised + String.format("%08x", arg)
        send(81, payload)
    }

    fun updateModule(wasmFilename: String) {
        val bytes = File(wasmFilename).readBytes()
        sendRaw("22${HexaEncoder.convertToLEB128(bytes.size)}" + HexFormat.of().formatHex(bytes) + "\n")
        messageQueue.waitForResponse("CHANGE Module!")
    }

    sealed class SnapshotPolicy(private val code: Int) {
        open fun serialize(): String {
            return String.format("%02x", code)
        }

        class None : SnapshotPolicy(0) {
            override fun toString() = "No snapshotting"
        }
        class AtEveryInstruction()  : SnapshotPolicy(1) {
            override fun toString() = "Snapshot at every instruction"
        }
        data class Checkpointing(val interval: Int = 20) : SnapshotPolicy(2) {
            override fun serialize(): String {
                return super.serialize() + HexaEncoder.serializeUInt32BE(interval)
            }
        }
    }

    fun setSnapshotPolicy(policy: SnapshotPolicy) {
        sendRaw("61${policy.serialize()}\n")
        messageQueue.searchForResponse {
            "Interrupt: 61"
        }
    }
}

enum class ExecutionState {
    ProgramCounter,
    Breakpoints,
    Callstack,
    Globals,
    Table,
    Memory,
    BranchingTable,
    Stack,
    Callbacks,
    Events,
    IO
}
