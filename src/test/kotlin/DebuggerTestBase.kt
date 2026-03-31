import be.ugent.topl.mio.DebuggerConfig
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
        val connection = if (emulator) ProcessConnection(wdcliPath, getFile(file).path, "--no-socket") else SerialConnection(config.port ?: throw RuntimeException("Port was not configured!"))
        val debugger = Debugger(connection)
        if (!emulator) {
            debugger.pause()
            debugger.reset()
            //debugger.updateModule(getFile(file).absolutePath)
        } else {
            debugger.pause()
        }
        val x = action(debugger)
        debugger.close()
        return x
    }
}
