package be.ugent.topl.mio.connections

class ProcessConnection(vararg command: String, private val name: String ="ProcessConnection") : Connection {
    private val process = ProcessBuilder(*command).start()

    override fun bytesAvailable(): Int {
        return process.inputStream.available()
    }

    override fun read(buf: ByteArray): Int {
        if (!process.isAlive && process.inputStream.available() == 0)
            throw RuntimeException("The process ($name) is no longer alive. Exit code ${process.exitValue()}")
        return process.inputStream.read(buf)
    }

    override fun write(buf: ByteArray) {
        process.outputStream.write(buf)
        process.outputStream.flush()
    }

    override fun close() {
        process.destroyForcibly()
    }
}
