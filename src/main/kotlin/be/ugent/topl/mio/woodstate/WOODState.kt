package be.ugent.topl.mio.woodstate

import WasmInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.util.*


enum class ExecutionStateType(val hexStr: String) {
    pcState("01"),
    breakpointState("02"),
    callstackState("03"),
    globalsState("04"),
    tableState("05"),
    memState("06"),
    branchingTableState("07"),
    stackState("08"),
    callbacksState("09"),
    eventsState("0a"),
    errorState("0b"), //TODO: ??? Doesn't exist in the VM?
    ioState("0b"),
    overridesState("0c");

    val length = hexStr.length

    override fun toString(): String = hexStr
}

val FRAME_FUNC_TYPE = 0
val FRAME_INITEXPR_TYPE = 1
val FRAME_BLOCK_TYPE = 2
val FRAME_LOOP_TYPE = 3
val FRAME_IF_TYPE = 4
val FRAME_PROXY_GUARD_TYPE = 254
val FRAME_CALLBACK_GUARD_TYPE = 255

data class WasmStackValue(
    val idx: Int,
    val type: String,
    val value: Long
)

data class CallbackMapping(
    val callbackid: String,
    val tableIndexes: List<Int>
)

data class InterruptEvent(
    val topic: String,
    val payload: String
)

data class IOState(
    val key: String,
    val output: Boolean,
    val value: Int
)

data class Frame(
    val type: Int,
    val fidx: String,
    val sp: Int,
    val fp: Int,
    val block_key: Int,
    val ra: Int,
    val idx: Int
)

data class Table(
    val max: Int,
    val init: Int,
    val elements: List<Int>,
)

class RunLengthEncodingDeserializer : JsonDeserializer<ByteArray>() {
    @Throws(IOException::class, JsonProcessingException::class)
    override fun deserialize(jsonParser: JsonParser, deserializationContext: DeserializationContext): ByteArray {
        var totalCount = 0
        val node: JsonNode = jsonParser.codec.readTree(jsonParser)
        val list = node.toList().map { it.asInt() }

        for (i in list.indices step 2) {
            totalCount += list[i + 1]
        }
        assert(0 == totalCount % 0x10000) {
            System.err.println("Amount of bytes should be a multiple of the WebAssembly page size. Reconstructed count = $totalCount.")
        }

        val bytes = ByteArray(totalCount)
        var currentByteArrayIndex = 0
        for (i in list.indices step 2) {
            val data = list[i]
            val count = list[i + 1]
            for (y in 0 until count) {
                bytes[currentByteArrayIndex] = data.toByte()
                currentByteArrayIndex++
            }
        }
        return bytes
    }
}

data class Memory(
    val pages: Int,
    val max: Int,
    val init: Int,
    @JsonDeserialize(using = RunLengthEncodingDeserializer::class)
    val bytes: ByteArray
)

data class BRTable(
    val size: String,
    val labels: List<Int>
)

data class PrimitiveOverride(
    val fidx: Int,
    val arg: Int,
    val return_value: Int,
) {
    constructor(
        metadata: WasmInfo,
        primitiveName: String,
        arg: Int,
        returnValue: Int
    ) : this(metadata.primitive_fidx_mapping.indexOf(primitiveName), arg, returnValue)

    fun getPrimitiveName(metadata: WasmInfo): String = metadata.primitive_fidx_mapping[fidx]
}

data class WOODDumpResponse(
    val pc: Int?,
    val pc_error: Int?,
    val exception_msg: String?,
    var breakpoints: List<Int>?,
    val stack: List<WasmStackValue>?,
    val callstack: List<Frame>?,
    val globals: List<WasmStackValue>?,
    val table: Table?,
    val memory: Memory?,
    val br_table: BRTable?,
    val callbacks: List<CallbackMapping>?,
    val events: List<InterruptEvent>?,
    val io: List<IOState>?,
    val overrides: List<PrimitiveOverride>?
)

data class Checkpoint(
    val instructions_executed: Int,
    val fidx_called: Int?,
    val args: List<Int>?,
    val snapshot: WOODDumpResponse,
    val returns: List<Int>?
)

class WOODState(woodResponse: WOODDumpResponse) {
    private val unparsedJSON = ""
    public val callbacks = ""
    private val woodResponse: WOODDumpResponse = woodResponse

    fun getState(): WOODDumpResponse {
        return this.woodResponse
    }

    fun toBinary(maxInterruptSize: Int = 4000, io: Boolean=true, overrides: Boolean=true): List<String> {
        val stateMessages = HexaStateMessages(maxInterruptSize)

        // Allocation Message
        this.serialiseAllocationMessage(stateMessages)
        stateMessages.forceNewMessage()

        // State Messages
        serializePC(stateMessages)
        serializeException(stateMessages)
        serializeBPs(stateMessages)
        serializeStack(stateMessages)
        serializeTable(stateMessages)
        serializeCallstack(stateMessages)
        serializeGlobals(stateMessages)
        serializeCallbacksMapping(stateMessages)
        serializeMemory(stateMessages)
        serializeBrTable(stateMessages)
        if (io) serializeIO(stateMessages)
        if (overrides) serializeOverrides(stateMessages)

        return stateMessages.getMessages()
    }

    // Helper methods

    private fun serializeBPs(stateMsgs: HexaStateMessages) {
        // |      Header       |        Breakpoints
        // | BPState  | Nr BPS |     BP1          | BP2 | ...
        // |  2 bytes |   1*2  | serializePointer |
        val bps = this.woodResponse.breakpoints
        if (bps == null) {
            return
        }
        println("==============")
        println("Breakpoints")
        println("--------------")
        val ws = this
        val nrBytesUsedForAmountBPs = 1 * 2
        val headerSize = ExecutionStateType.breakpointState.hexStr.length + nrBytesUsedForAmountBPs
        var breakpoints = bps.map{ bp -> ws.serializePointer(bp) }
        while (breakpoints.size != 0) {
            val fits = stateMsgs.howManyFit(headerSize, breakpoints)
            if (fits == 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            val bps = breakpoints.slice(0 ..< fits).joinToString("")
            val amountBPs = HexaEncoder.serializeUInt8(fits)
            println("Breakpoints: amount=${breakpoints.size}")
            val payload = "${ExecutionStateType.breakpointState}${amountBPs}${bps}"
            stateMsgs.addPayload(payload)
            breakpoints = breakpoints.slice(fits ..< breakpoints.size)
        }
    }

    private fun serializeStack(stateMsgs: HexaStateMessages) {
        // |          Header           |       StackValues
        // | StackState | Nr StackVals |     V1         | V2 | ...
        // |  2 bytes   |      2*2     | serializeValue |
        if (this.woodResponse.stack == null) {
            return
        }
        println("==============")
        println("STACK")
        println("--------------")
        println("Total Stack length ${this.woodResponse.stack.size}")

        val ws = this
        var stack = this.woodResponse.stack.map{ v -> serializeValue(v) }
        val nrBytesUsedForAmountVals = 2 * 2
        val headerSize = ExecutionStateType.stackState.length + nrBytesUsedForAmountVals
        while (stack.size != 0) {
            val fit = stateMsgs.howManyFit(headerSize, stack)
            if (fit == 0) {
                stateMsgs.forceNewMessage()
            }
            val amountVals = HexaEncoder.serializeUInt16BE(fit)
            val vals = stack.slice(0 ..< fit).joinToString("")
            val payload = "${ExecutionStateType.stackState}${amountVals}${vals}"
            stateMsgs.addPayload(payload)
            stack = stack.slice(fit ..< stack.size)
            println("msg: AmountStackValues ${fit}")
        }
    }

    private fun serializeTable(stateMsgs: HexaStateMessages) {
        // |          Header          |       Elements
        // | TableState | Nr Elements |    elem  1  | elem 2 | ...
        // |  2 bytes   |   4*2       |  4*2 bytes  |
        if (this.woodResponse.table == null) {
            return
        }
        println("==============")
        println("TABLE")
        println("--------------")
        var elements = this.woodResponse.table.elements.map{ HexaEncoder.serializeUInt32BE(it) }
        println("Total Elements ${this.woodResponse.table.elements.size}")
        val nrBytesUsedForAmountElements = 4 * 2
        val headerSize = ExecutionStateType.tableState.length + nrBytesUsedForAmountElements
        while (elements.size != 0) {
            val fit = stateMsgs.howManyFit(headerSize, elements)
            if (fit === 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            val amountElements = HexaEncoder.serializeUInt32BE(fit)
            val elems = elements.slice(0 ..< fit).joinToString("")
            val el_str = this.woodResponse.table.elements .slice(0 ..< fit).map{ e -> e.toString() }.joinToString(", ")
            println("msg: amountElements ${fit} elements ${el_str}")
            val payload = "${ExecutionStateType.tableState}${amountElements}${elems}"
            stateMsgs.addPayload(payload)
            elements = elements .slice(fit ..< elements.size)
        }
    }

    private fun serializeCallstack(stateMsgs: HexaStateMessages) {
        // |           Header           |              Frames
        // | CallstackState | Nr Frames |   Frame 1      | Frame 2 | ...
        // |    2 bytes     |  2*2bytes | serializeFrame |
        if (this.woodResponse.callstack == null) {
            return
        }
        println("==============")
        println("CallStack")
        println("--------------")
        println("Total Frames ${this.woodResponse.callstack.size}")

        var frames = this.woodResponse.callstack.map{ f -> serializeFrame(f) }
        val nrBytesUsedForAmountFrames = 2 * 2
        val headerSize = ExecutionStateType.callstackState.length + nrBytesUsedForAmountFrames
        while (frames.isNotEmpty()) {
            val fit = stateMsgs.howManyFit(headerSize, frames)
            if (fit == 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            val amountFrames = HexaEncoder.serializeUInt16BE(fit)
            val fms = frames.slice(0 ..< fit).joinToString("")
            println("msg: amountFrames=${fit}")
            val payload = "${ExecutionStateType.callstackState}${amountFrames}${fms}"
            stateMsgs.addPayload(payload)
            frames = frames .slice(fit ..< frames.size)
        }
    }

    private fun serializeGlobals(stateMsgs: HexaStateMessages) {
        // |        Header          |       Globals
        // | GlobalState |  Nr Vals |     V1         | V2 | ...
        // |  2 bytes    | 4*2bytes | serializeValue |
        if (this.woodResponse.globals == null) {
            return
        }
        println("==============")
        println("GLOBALS")
        println("--------------")

        println("Total Globals ${this.woodResponse.globals.size}")
        val ws = this
        var globals = this.woodResponse.globals.map{ v -> serializeValue(v) }
        val nrBytesNeededForAmountGlbs = 4 * 2
        val headerSize = ExecutionStateType.globalsState.length + nrBytesNeededForAmountGlbs
        while (globals.size != 0) {
            val fit = stateMsgs.howManyFit(headerSize, globals)
            if (fit === 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            val amountGlobals = HexaEncoder.serializeUInt32BE(fit)
            val glbs = globals .slice(0 ..< fit).joinToString("")
            val payload = "${ExecutionStateType.globalsState}${amountGlobals}${glbs}"
            stateMsgs.addPayload(payload)
            globals = globals .slice(fit ..< globals.size)
            println("msg: AmountGlobals ${fit}")
        }
    }

    private fun serializeMemory(stateMsgs: HexaStateMessages) {
        // |        Header                          | Memory Bytes
        // | MemState | Mem Start Idx | Mem End Idx |  byte 1   | byte 2|
        // |  2 bytes |    4*2 bytes  |  4*2 bytes  | 1*2 bytes | ....
        if (this.woodResponse.memory == null) {
            return
        }
        println("==============")
        println("Memory")
        println("--------------")
        val sizeHeader = ExecutionStateType.memState.length + 4 * 2 + 4 * 2 + 4 * 2
        /*val testArray = ByteArray(65536 / 2)
        Random(42).nextBytes(testArray)
        var bytes = testArray.map{ b -> HexFormat.of().formatHex(byteArrayOf(b)) }*/
        var bytes = this.woodResponse.memory.bytes.map{ b -> HexFormat.of().formatHex(byteArrayOf(b)) }
        println("Total Memory Bytes ${this.woodResponse.memory.bytes.size}")
        var startMemIdx = 0
        var endMemIdx = 0
        while (bytes.isNotEmpty()) {
            // Step 1. Check how much space we still have.
            val freeSpace = stateMsgs.getFreeSpace() - sizeHeader
            println("Free space = $freeSpace")
            // Step 2. Compress the remaining bytes so that the compressed output is at most length freeSpace
            val (compressed, consumed) = compressRLE(bytes, freeSpace)
            println("Compressed = ${compressed.size}, consumed = $consumed")

            compressed.forEach {
                if (it.length != 2) throw Exception()
            }
            println(stateMsgs.howManyFit(sizeHeader, compressed))
            if (stateMsgs.howManyFit(sizeHeader, compressed) != compressed.size) {
                throw Exception("Invalid")
            }

            endMemIdx = startMemIdx + consumed - 1 // End is inclusive
            val bytesHexa = compressed.joinToString("")
            val startMemIdxHexa = HexaEncoder.serializeUInt32BE(startMemIdx)
            val endMemIdxHexa = HexaEncoder.serializeUInt32BE(endMemIdx)
            val count = HexaEncoder.serializeUInt32BE(bytesHexa.length/2)
            val payload = "${ExecutionStateType.memState}${startMemIdxHexa}${endMemIdxHexa}${count}${bytesHexa}"
            println("Start position = $startMemIdx $startMemIdxHexa ${startMemIdx + consumed - 1} $endMemIdxHexa")
            println("Bytes payload: $payload")
            stateMsgs.addPayload(payload)
            startMemIdx = endMemIdx + 1

            bytes = bytes.slice(consumed ..< bytes.size)

            if (bytes.isNotEmpty()) {
                stateMsgs.forceNewMessage() // TODO: What if we are out of bytes, then we should probably not make a new message, this is not really ideal with this bein the same as the while loop condition
            }
        }
    }

    private fun serializeBrTable(stateMsgs: HexaStateMessages) {
        // |                    Header           |        Labels
        // | BR_TblState |  StartIdx |  EndIdx   | label 1   | label 2|
        // |  2 bytes    | 2*2 bytes | 2*2 bytes | 4*2 bytes | ....
        if (this.woodResponse.br_table == null) {
            return
        }
        println("==============")
        println("BRTable")
        println("--------------")
        println("Total Labels ${this.woodResponse.br_table.labels.size}")

        var elements = this.woodResponse.br_table.labels.map{ HexaEncoder.serializeUInt32BE(it) }
        val sizeHeader = ExecutionStateType.branchingTableState.length + 2 * 2 + 2 * 2
        var startTblIdx = 0
        var endTblIdx = 0
        while (startTblIdx < this.woodResponse.br_table.labels.size) {
            var fit = stateMsgs.howManyFit(sizeHeader, elements)
            if (fit === 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            endTblIdx = startTblIdx + fit - 1
            val elems = elements .slice(0 ..< fit).joinToString("")
            val startTblIdxHexa = HexaEncoder.serializeUInt16BE(startTblIdx)
            val endTblIdxHexa = HexaEncoder.serializeUInt16BE(endTblIdx)
            val payload = "${ExecutionStateType.branchingTableState}${startTblIdxHexa}${endTblIdxHexa}${elems}"
            stateMsgs.addPayload(payload)
            println("msg: startTblIdx=${startTblIdx} endTblIdx=${endTblIdx}")
            startTblIdx = endTblIdx + 1

            elements = elements .slice(fit ..< elements.size)
        }
    }

    private fun serializePC(stateMsgs: HexaStateMessages) {
        // |  PCState Header | PC
        // |     2 bytes     | serializePointer
        if (this.woodResponse.pc == null) {
            return
        }
        println("==========")
        println("PC")
        println("----------")
        val ser = this.serializePointer(this.woodResponse.pc)
        println("PC: pc=${this.woodResponse.pc}")
        val payload = "${ExecutionStateType.pcState}${ser}"
        stateMsgs.addPayload(payload)
    }

    private fun serialiseAllocationMessage(stateMsgs: HexaStateMessages) {
        val wr = this.woodResponse
        if (wr.globals == null || wr.table == null || wr.memory == null) {
            throw Error("cannot serialise Allocaton Message when state is missing")
        }

        println("==============")
        println("Allocate MSG")
        println("--------------")

        // Globals

        val gblsAmountHex = HexaEncoder.serializeUInt32BE(wr.globals.size)
        println("Globals: total=${wr.globals.size}")
        val globals = "${ExecutionStateType.globalsState}${gblsAmountHex}"

        // Table
        val tblInitHex = HexaEncoder.serializeUInt32BE(wr.table.init)
        val tblMaxHex = HexaEncoder.serializeUInt32BE(wr.table.max)
        val tblSizeHex = HexaEncoder.serializeUInt32BE(wr.table.elements.size)
        val tbl = "${ExecutionStateType.tableState}${tblInitHex}${tblMaxHex}${tblSizeHex}"

        println("Table:  init=${wr.table.init} max=${wr.table.max} size=${wr.table.elements.size}")
        // Memory
        val memInitHex = HexaEncoder.serializeUInt32BE(wr.memory.init)
        val memMaxHex = HexaEncoder.serializeUInt32BE(wr.memory.max)
        val memPagesHex = HexaEncoder.serializeUInt32BE(wr.memory.pages)
        val mem = "${ExecutionStateType.memState}${memMaxHex}${memInitHex}${memPagesHex}"
        println("Mem: max=${wr.memory.max} init=${wr.memory.init}  pages=${wr.memory.pages}")
        val payload = "${globals}${tbl}${mem}"

        stateMsgs.addPayload(payload)
    }

    private fun serializePointer(addr: Int): String {
        // | Pointer   |
        // | 4*2 bytes |
        return HexaEncoder.serializeUInt32BE(addr)
    }

    private fun serializeFrame(frame: Frame): String {
        // | Frame type | StackPointer | FramePointer |   Return Adress  | FID or Block ID
        // |  1*2 bytes |   4*2bytes   |   4*2bytes   | serializePointer | 4*2bytes or serializePointer
        val validTypes = listOf(FRAME_FUNC_TYPE, FRAME_INITEXPR_TYPE, FRAME_BLOCK_TYPE, FRAME_LOOP_TYPE, FRAME_IF_TYPE, FRAME_PROXY_GUARD_TYPE, FRAME_CALLBACK_GUARD_TYPE)

        if (validTypes.indexOf(frame.type) == -1) {
            throw Error("received unknown frame type ${frame.type}")
        }
        val type = HexaEncoder.serializeUInt8(frame.type)
        val bigEndian = true
        val sp = HexaEncoder.serializeInt32(frame.sp, bigEndian)
        val fp = HexaEncoder.serializeInt32(frame.fp, bigEndian)
        val ra = this.serializePointer(frame.ra)
        var rest = ""
        var res_str = ""; //TODO remove
        if (frame.type == FRAME_FUNC_TYPE) {
            val fidxInt = frame.fidx.slice(2 ..< frame.fidx.length).toInt(16)
            rest = HexaEncoder.serializeUInt32BE(fidxInt)
            res_str = "fun_idx=${fidxInt}"
        }
        else if (frame.type == FRAME_PROXY_GUARD_TYPE || frame.type == FRAME_CALLBACK_GUARD_TYPE) {
            // Nothing has to happen
        }
        else {
            rest = this.serializePointer(frame.block_key)
            res_str = "block_key=${frame.block_key}"
        }
        println("Frame: type=${frame.type} sp=${frame.sp} fp=${frame.fp} ra=${frame.ra} ${res_str}")
        return "${type}${sp}${fp}${ra}${rest}"
    }

    private fun serializeException(stateMsgs: HexaStateMessages) {
        if (this.woodResponse.pc_error == null) {
            return
        }
        println("==========")
        println("PC_ERROR")
        println("----------")
        val pcError = this.serializePointer(this.woodResponse.pc_error)
        var exceptionMsg = ""
        var exceptionMsgSize = 0
        if (this.woodResponse.exception_msg != null && this.woodResponse.exception_msg != "") {
            exceptionMsg = this.woodResponse.exception_msg
            exceptionMsgSize = exceptionMsg.length
        }
        println("PC_ERROR: pc_error=${this.woodResponse.pc_error} exception_msg(#${exceptionMsgSize} chars)=${exceptionMsg}")
        val sizeInHexa = HexaEncoder.serializeUInt32BE(exceptionMsgSize)
        val msgInHexa = HexaEncoder.serializeString(exceptionMsg)
        val payload = "${ExecutionStateType.errorState}${pcError}${sizeInHexa}${msgInHexa}"
        stateMsgs.addPayload(payload)
    }

    private fun serializeCallbacksMapping(stateMsgs: HexaStateMessages) {
        // | Mappings type | amountMapings | CallbackMapping |   Return Adress  | FID or Block ID
        // |  1*2 bytes |   4*2bytes   |   4*2bytes   | serializePointer | 4*2bytes or serializePointer
        // callbacks": [{"interrupt_37": [1]}, {"interrupt_39": [2]}]

        if (this.woodResponse.callbacks == null) {
            return
        }
        println("==============")
        println("CallbackMapping")
        println("--------------")
        println("Total Mappings ${this.woodResponse.callbacks.size}")

        val ws = this
        var mappings = this.woodResponse.callbacks.map{ f -> ws.serializeCallbackMapping(f) }
        val nrBytesUsedForAmountMappings = 2 * 2
        val headerSize = ExecutionStateType.callbacksState.length + nrBytesUsedForAmountMappings
        while (mappings.size != 0) {
            val fit = stateMsgs.howManyFit(headerSize, mappings)
            if (fit == 0) {
                stateMsgs.forceNewMessage()
                continue
            }
            val amountMappings = HexaEncoder.serializeUInt32BE(fit)
            val fms = mappings .slice(0 ..< fit).joinToString("")
            println("msg: amountMappings=${fit}")
            val payload = "${ExecutionStateType.callbacksState}${amountMappings}${fms}"
            stateMsgs.addPayload(payload)
            mappings = mappings .slice(fit ..< mappings.size)
        }
    }

    private fun serializeCallbackMapping(mapping: CallbackMapping): String {
        // | size CallbackID | CallbackID | Number TableIndeces | TableIndex | TableIndex | ....
        // |  4 * 2 bytes    |   ....     |   4*2bytes          | 4*2bytes   |
        val sizeCallbackID = HexaEncoder.serializeUInt32BE(mapping.callbackid.length)
        val callbackIDInHexa = HexaEncoder.serializeString(mapping.callbackid)
        val tableIndeces = mapping.tableIndexes.map{ tidx -> HexaEncoder.serializeUInt32BE(tidx) }
        val tableIndecesSize = HexaEncoder.serializeUInt32BE(tableIndeces.size)
        return "${sizeCallbackID}${callbackIDInHexa}${tableIndecesSize}${tableIndeces}"
    }


    fun serializeRFCall(functionId: Int, args: List<WasmStackValue>): String {
        val ws = this
        val ignoreType = false
        val fidxHex = HexaEncoder.serializeUInt32BE(functionId)
        val argsHex = args.map{ v -> serializeValue(v, ignoreType) }.joinToString("")
        return "${InterruptTypes.interruptProxyCall}${fidxHex}${argsHex}"
    }

    private fun serializeIO(stateMsgs: HexaStateMessages) {
        if (woodResponse.io == null) {
            return
        }
        println("==============")
        println("IO")
        println("--------------")
        for (ioState in woodResponse.io) {
            println(HexaEncoder.serializeString(ioState.key) + HexaEncoder.serializeString("\u0000"))
        }
        serializeList(stateMsgs, ExecutionStateType.ioState, woodResponse.io) {
            HexaEncoder.serializeString(it.key) + HexaEncoder.serializeString("\u0000") + HexaEncoder.serializeBool(it.output) + HexaEncoder.serializeUInt32BE(it.value)
        }
    }

    private fun serializeOverrides(stateMsgs: HexaStateMessages) {
        if (woodResponse.overrides == null) {
            return
        }
        println("==============")
        println("Overrides")
        println("--------------")
        println("Found ${woodResponse.overrides.size} active overrides.")
        serializeList(stateMsgs, ExecutionStateType.overridesState, woodResponse.overrides) {
            HexaEncoder.serializeUInt32BE(it.fidx) + HexaEncoder.serializeUInt32BE(it.arg) + HexaEncoder.serializeUInt32BE(it.return_value)
        }
    }

    /**
     * A helper function to serialize lists of data, we first serialize the length of the list as a uint8. After the
     * count we just put each element. The elements of a list are serialized using the serializeElement method.
     *
     * So we just send the type of data we are sending the count and then the elements themselves.
     *
     * | Header            | Elements
     * | Type    | Count   | Element 1             | Element 2             | ...
     * | 2 bytes | 2 bytes | serializeElement(el1) | serializeElement(el2) | ...
     */
    private fun <T> serializeList(stateMsgs: HexaStateMessages, execState: ExecutionStateType, list: List<T>, serializeElement: (T) -> String) {
        if (list.size >= 256) {
            System.err.println("WARNING: count might not fit!")
        }
        val elementCount = HexaEncoder.serializeUInt8(list.size)
        val headerSize = execState.length + elementCount.length
        var serializedElements = list.map { serializeElement(it) }

        // Send an empty list.
        if (serializedElements.isEmpty()) {
            stateMsgs.addPayload("${execState}${elementCount}")
        }

        while (serializedElements.isNotEmpty()) {
            val fitCount = stateMsgs.howManyFit(headerSize, serializedElements)
            if (fitCount == 0) {
                stateMsgs.forceNewMessage()
                continue
            }

            val partialListPayload = serializedElements.slice(0 ..< fitCount).joinToString("")
            serializedElements = serializedElements.slice(fitCount ..< serializedElements.size)
            val payload = "${execState}${HexaEncoder.serializeUInt8(fitCount)}${partialListPayload}"
            println("execState = $execState")
            println("elementCount = $elementCount")
            println("partialListPayload = $partialListPayload")
            stateMsgs.addPayload(payload)
        }
    }

    companion object {
        fun serializeValue(value: WasmStackValue, includeType: Boolean = true): String {
            // |   Type      |       value       |
            // | 1 * 2 bytes |  4*2 or 8*2 bytes |
            var type = -1
            var v = ""
            var type_str = ""

            if (value.type == "i32" || value.type === "I32") {
                if (value.value < 0) {
                v = HexaEncoder.serializeInt32LE(value.value.toInt())
            }
                else {
                v = HexaEncoder.serializeUInt32LE(value.value.toInt())
            }
                type = 0
                type_str = "i32"
            }
            else if (value.type == "i64" || value.type == "I64") {
                if (value.value < 0) {
                v = HexaEncoder.serializeBigUInt64LE(value.value)
            }
                else {
                v = HexaEncoder.serializeBigUInt64LE(value.value)
            }
                type = 1
                type_str = "i64"
            }
            else if (value.type == "f32" || value.type == "F32") {
                v = HexaEncoder.serializeFloatLE(value.value.toFloat())
                type = 2
                type_str = "f32"
            }
            else if (value.type == "f64" || value.type == "F64") {
                v = HexaEncoder.serializeDoubleLE(value.value.toDouble())
                type = 3
                type_str = "f64"
            }
            else {
                throw Error("Got unexisting stack Value type ${value.type} value ${value.value}")
            }
            println("Value: type=${type_str}(idx ${type}) val=${value.value}")
            if (includeType) {
                val typeHex = HexaEncoder.serializeUInt8(type)
                return "${typeHex}${v}"
            }
            else {
                return v
            }
        }

        fun serializeStackValueUpdate(value: WasmStackValue): String {
            val stackIDx = HexaEncoder.convertToLEB128(value.idx)
            val valueHex = HexaEncoder.convertToLEB128(value.value.toInt())
            return "${InterruptTypes.interruptUPDATEStackValue}${stackIDx}${valueHex}"
        }

        fun serializeGlobalValueUpdate(value: WasmStackValue): String {
            val globalIDX = HexaEncoder.convertToLEB128(value.idx)
            val valueHex = HexaEncoder.convertToLEB128(value.value.toInt())
            return "${InterruptTypes.interruptUPDATEGlobal}${globalIDX}${valueHex}"
        }

        fun parseSnapshot(line: String): WOODDumpResponse {
            val trimmed = line.trimEnd()
            val objectMapper = ObjectMapper()
            objectMapper.registerKotlinModule()
            return objectMapper.readValue(trimmed, WOODDumpResponse::class.java)
        }

        fun fromLine(line: String): WOODState {
            val trimmed = line.trimEnd()
            val wr: WOODDumpResponse = parseSnapshot(trimmed)
            return WOODState(wr)
        }
    }
}
