package be.ugent.topl.mio.connections

import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread

class ProcessConnection(vararg command: String, private val name: String ="ProcessConnection") : Connection {
    private val process = ProcessBuilder(*command).start()
    private val buffers = ConcurrentLinkedDeque<ByteArray>()
    /*private val buffer = ByteArray(4096)
    private var bytesAvailable = 0*/
    var lastTime = System.currentTimeMillis()
    init {
        thread {
            // If we don't read the output from the process, the process will start to block once there is too much unprocessed output so we must consume it as quickly as possible.
            while (process.isAlive) {
                val buffer = ByteArray(4096)
                val read = process.inputStream.read(buffer)
                val buf2 = ByteArray(read)
                buffer.copyInto(buf2, 0, 0, read)
                buffers.add(buf2)
            }
        }
    }

    override fun bytesAvailable(): Int {
        //return process.inputStream.available()
        val buf = buffers.peekFirst()
        if (buf == null) {
            //println("No data " + buffers.size + " available")
            return 0
        }

        if (System.currentTimeMillis() - lastTime > 1000) {
            println("Data " + buffers.size + " available")
            println("Avg buf size ${buffers.map { it.size }.average()}")
            lastTime = System.currentTimeMillis()
        }

        return buf.size
    }

    override fun read(buf: ByteArray): Int {
        /*if (!process.isAlive && process.inputStream.available() == 0)
            throw RuntimeException("The process ($name) is no longer alive. Exit code ${process.exitValue()}")
        return process.inputStream.read(buf)*/
        //buffer.copyInto(buf)
        val x = buffers.removeFirst()
        x.copyInto(buf)
        return x.size
    }

    override fun write(buf: ByteArray) {
        process.outputStream.write(buf)
        process.outputStream.flush()
    }

    override fun close() {
        process.destroy()
    }
}
