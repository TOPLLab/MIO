package be.ugent.topl.mio

import java.io.FileInputStream
import java.util.*

class DebuggerConfig {
    private val properties = Properties()
    companion object {
        val configDir = "${System.getenv("HOME")}/.rcmd"
    }
    init {
        properties.load(FileInputStream("$configDir/debugger.properties"))
    }
    val wdcliPath = properties.getProperty("wdcli")
    val symbolicWdcliPath = properties.getProperty("wdcli-symbolic", wdcliPath)
    val port: String? = properties.getProperty("port")
    val useEmulator = properties.getProperty("useEmulator", "false") == "true"
    val uiScale = properties.getProperty("uiScale", "1")
    val lightMode = properties.getProperty("lightMode", "true") == "true"
    val macIntegratedToolbar = properties.getProperty("mac.integratedToolbar", "false") == "true"
    val concolic = properties.getProperty("concolic", "false") == "true"
    val checkpointHistory = properties.getProperty("checkpointHistory", "false") == "true"

    val warduinoDir: String? = properties.getProperty("warduinoDir")
    val fqbn: String? = properties.getProperty("fqbn")
}
