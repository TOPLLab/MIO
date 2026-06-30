package be.ugent.topl.mio

import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.debugger.Debugger
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import be.ugent.topl.mio.sourcemap.AsSourceMapping
import be.ugent.topl.mio.sourcemap.compileAndFlash
import be.ugent.topl.mio.sourcemap.compileWat
import be.ugent.topl.mio.sourcemap.getDwarfSourcemap
import be.ugent.topl.mio.ui.InteractiveDebugger
import be.ugent.topl.mio.ui.StartScreen
import com.formdev.flatlaf.util.SystemInfo
import java.io.File
import java.io.FileNotFoundException
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun expectNArguments(args: Array<String>, n : Int) {
    if (args.size < n) {
        println("Expected at least $n argument(s) but found ${args.size}")
        exitProcess(1)
    }
}

fun portRequired(config: DebuggerConfig) {
    if (config.port == null) {
        System.err.println("No port was specified in the configuration file!")
        exitProcess(1)
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        try {
            val config = DebuggerConfig()
            if (SystemInfo.isMacOS) {
                System.setProperty("apple.laf.useScreenMenuBar", "true")
                System.setProperty("apple.awt.application.name", "rCMD")
                System.setProperty("apple.awt.application.appearance", if (config.lightMode) "NSAppearanceNameAqua" else "NSAppearanceNameDarkAqua")

                System.setProperty("flatlaf.uiScale", config.uiScale)
            }
            else {
                System.setProperty("sun.java2d.uiScale", config.uiScale)
            }
            val startScreen = StartScreen(config)
            startScreen.isVisible = true
        } catch(_: FileNotFoundException) {
            JOptionPane.showMessageDialog(null, "Configuration file ${DebuggerConfig.configDir}/debugger.properties not found!\nPlease read the \"Configuration\" section of the documentation.", "Invalid configuration", JOptionPane.ERROR_MESSAGE)
        }
        return
    }
    expectNArguments(args, 1)
    val config = DebuggerConfig()
    when (args[0]) {
        "debug" -> {
            expectNArguments(args, 2)
            val watFilename = args[1]
            val connection =
                if (config.useEmulator) {
                    if (watFilename.endsWith(".wat"))
                        ProcessConnection(
                            config.wdcliPath,
                            "${File(watFilename).nameWithoutExtension}.wasm",
                            "--no-socket"
                        )
                    else {
                        expectNArguments(args, 3)
                        val wasmFilename = args[2]
                        ProcessConnection(config.wdcliPath, wasmFilename, "--no-socket")
                    }
                }
                else {
                    portRequired(config)
                    SerialConnection(config.port!!)
                }
            val sourceMapping =
                if (watFilename.endsWith(".wasm.map"))
                    AsSourceMapping(File(watFilename).readText())
                else if (watFilename == "dwarf")
                    getDwarfSourcemap(args[2])
                else
                    compileWat(watFilename)
            System.setProperty("sun.java2d.uiScale", config.uiScale)
            val lightMode = config.lightMode
            if (lightMode)
                FlatIntelliJLaf.setup()
            else
                FlatDarkLaf.setup()
            if (args.size == 2)
                InteractiveDebugger(connection, sourceMapping, config = config)
            else
                InteractiveDebugger(connection, sourceMapping, args[2], config = config)
        }
        "repl" -> {
            val connection =
                if (config.useEmulator) {
                    expectNArguments(args, 2)
                    val wasmFilename = args[1]
                    ProcessConnection(config.wdcliPath, wasmFilename, "--no-socket")
                } else {
                    portRequired(config)
                    SerialConnection(config.port!!)
                }
            val debugger = Debugger(connection)
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing())
            debugger.repl()
            debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.None())
            debugger.close()
        }
        "updateModule" -> {
            expectNArguments(args, 2)
            val wasmFilename = args[1]
            val connection =
                if (config.useEmulator) ProcessConnection(config.wdcliPath, wasmFilename, "--no-socket")
                else  {
                    portRequired(config)
                    SerialConnection(config.port!!)
                }
            val debugger = Debugger(connection)
            debugger.updateModule(args[1])
            debugger.close()
        }
        "run" -> {
            expectNArguments(args, 2)
            val wasmFilename = args[1]
            val connection =
                if (config.useEmulator) ProcessConnection(config.wdcliPath, wasmFilename, "--no-socket")
                else  {
                    portRequired(config)
                    SerialConnection(config.port!!)
                }
            val debugger = Debugger(connection)
            debugger.updateModule(args[1])
            debugger.run()
            debugger.repl()
            debugger.close()
        }
        "flash" -> {
            expectNArguments(args, 2)
            val watFilename = args[1]
            if (config.warduinoDir == null || config.fqbn == null) {
                System.err.println("The flash option requires warduinoDir and fqbn to be defined in the configuration file!")
                return
            }
            portRequired(config)
            compileAndFlash(
                config.warduinoDir,
                watFilename,
                config.fqbn,
                config.port!!
            )
        }
        else -> {
            println("Invalid option \"${args[0]}\"!")
            exitProcess(1)
        }
    }
}
