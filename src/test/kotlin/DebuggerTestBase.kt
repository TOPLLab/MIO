import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.debugger.Debugger
import java.io.File

abstract class DebuggerTestBase {
    protected val config = DebuggerConfig()
    protected val wdcliPath: String = config.wdcliPath

    fun getFile(path: String): File {
        return File(javaClass.getResource("/$path")?.file ?: path)
    }

    protected fun <T> runWithDebugger(file:String, emulator: Boolean = false, action: (Debugger) -> T): T {
        val wasmFile = getFile(file)
        val wasmFilePath = wasmFile.path
        val connection = if (emulator) ProcessConnection(wdcliPath, wasmFilePath, "--no-socket", "--paused", workingDir = wasmFile.parentFile) else SerialConnection(config.port ?: throw RuntimeException("Port was not configured!"))
        val debugger = createDebugger(connection, wasmFilePath)
        if (!emulator) {
            debugger.updateModule(getFile(file).absolutePath)
        }
        val x = action(debugger)
        debugger.close()
        return x
    }

    open fun createDebugger(connection: Connection, wasmFilePath: String): Debugger {
        return Debugger(connection)
    }
}
