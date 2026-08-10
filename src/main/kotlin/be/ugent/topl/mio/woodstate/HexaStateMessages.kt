package be.ugent.topl.mio.woodstate

import kotlin.math.floor

/**
 * Currently a direct port from WOODState.ts
 */
class HexaStateMessages(val messageSize: Int) {
    private val maxMessageSize = messageSize
    private var messages = mutableListOf<String>()
    private var currentMsg = ""

    // Header data
    private val nrBytesForPayloadSize = 4 * 2 // tells how big the payload is. Times 2 for hexa
    private val nrBytesForInterruptKind = InterruptTypes.interruptLoadSnapshot.toString().length // already in hexa
    private val headerSize = this.nrBytesForInterruptKind + this.nrBytesForPayloadSize

    // Footer data
    private val nrBytesForContinuation = 1 * 2 // 1 byte to tell whether all state is transferred. Times 2 for hexa
    private val terminatorChar = " \n"
    private val footerSize = this.nrBytesForContinuation + this.terminatorChar.length
    private val maxPayloadSize = this.maxMessageSize - this.headerSize - this.footerSize

    private fun enoughSpace(spaceNeeded: Int): Boolean {
        return this.getFreeSpace() >= spaceNeeded
    }

    fun howManyFit(headerSize: Int, payloads: List<String>): Int {
        var amount = 0
        var payload: String = ""
        for (element in payloads) {
            payload += element
            if (!this.enoughSpace(payload.length + headerSize)) {
                break
            }
            amount++
        }
        return amount
    }

    private fun validatePayload(payload: String) {
        if (this.maxPayloadSize < payload.length) {
            var errmsg = "Payload size exceeds maxPayload Size of ${this.maxPayloadSize}"
            errmsg += "(= maxMessageSize ${this.maxMessageSize} - header/footer ${this.headerSize + this.footerSize})."
            errmsg += "Either increase maxMessageSize or split payload."
            throw Error(errmsg)
        }
        if (payload.length % 2 != 0) {
            throw Error("Payload is not even. Got length ${this.currentMsg.length}")
        }
        val regexHexa = Regex("([0-9A-Fa-f]{2})*")
        if (!regexHexa.matches(payload)) {
            throw Error("Payload should only contain hexa chars, payload = \"$payload\"")
        }
    }

    fun getFreeSpace(): Int {
        return this.maxPayloadSize - this.currentMsg.length
    }

    fun addPayload(payload: String) {
        this.validatePayload(payload)
        if (!this.enoughSpace(payload.length)) {
            this.forceNewMessage()
        }
        this.currentMsg = "${this.currentMsg}${payload}"
        val s = this.currentMsg.length + this.headerSize + this.footerSize
        if (s > this.maxMessageSize) {
            throw Error("Exceeded max size is ${s} > ${this.maxMessageSize}")
        }
    }

    fun forceNewMessage() {
        this.messages.add(this.currentMsg)
        this.currentMsg = ""
    }

    fun getMessages(): List<String> {
        if (this.currentMsg != "") {
            this.messages = this.messages.toMutableList()
            this.messages.add(this.currentMsg)
            this.currentMsg = ""
        }

        val amountMessages = this.messages.size
        val lastChar = this.terminatorChar
        return this.messages.mapIndexed { msgIdx, payload ->
            val size = floor(payload.length / 2.0).toInt()
            val sizeHexa = HexaEncoder.serializeUInt32BE(size)
            val done = if ((msgIdx + 1) == amountMessages) "01" else "00"
            val msg = "${InterruptTypes.interruptLoadSnapshot}${sizeHexa}${payload}${done}${lastChar}"
            if (msg.length % 2 != 0) {
                throw Error("WoodState: Hexa message not even")
            }
            if (msg.length > this.maxMessageSize) {
                throw Error("msg ${msgIdx} is ${msg.length} > ${this.maxMessageSize}")
            }
            msg
        }
    }
}

enum class InterruptTypes(val hexStr: String) {
    // Remote debugging messages
    interruptRUN("01"),
    interruptHALT("02"),
    interruptPAUSE("03"),
    interruptSTEP("04"),
    interruptBPAdd("06"),
    interruptBPRem("07"),
    interruptInspect("09"),
    interruptDUMP("10"),
    interruptDUMPLocals("11"),
    interruptDUMPFull("12"),
    interruptReset("13"),
    interruptUPDATEFun("20"),
    interruptUPDATELocal("21"),
    interruptUPDATEModule("22"),
    interruptUPDATEGlobal("23"),
    interruptUPDATEStackValue("24"),

    interruptINVOKE("40"),
    // Pull debugging messages
    interruptSnapshot("60"),
    interruptLoadSnapshot("62"),
    interruptMonitorProxies("63"),
    interruptProxyCall("64"),
    interruptProxify("65"),
    // Push debugging messages
    interruptDUMPAllEvents("70"),
    interruptDUMPEvents("71"),
    interruptPOPEvent("72"),
    interruptPUSHEvent("73"),
    interruptDUMPCallbackmapping("74"),
    interruptRecvCallbackmapping("75");

    override fun toString(): String = hexStr
}
