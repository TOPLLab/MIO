import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.debugger.Debugger
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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

    protected fun withUncaughtHandler(maxMs: Int = 5000, f: () -> Unit) {
        val exception = AtomicReference<Throwable?>()

        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            exception.set(throwable)
        }

        try {
            val t = Thread {
                f()
            }
            t.start()

            val deadline = System.currentTimeMillis() + maxMs

            while (t.isAlive && exception.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            exception.get()?.let { throw it }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        }
    }
}
