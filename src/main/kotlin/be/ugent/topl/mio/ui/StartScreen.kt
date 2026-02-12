package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.sourcemap.AsSourceMapping
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemFileChooser
import java.awt.Dimension
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.util.*
import javax.swing.*

open class StartScreen(config: DebuggerConfig) : AboutScreen(config) {
    init {
        defaultCloseOperation = EXIT_ON_CLOSE
    }

    override fun addOptions(mainPanel: JPanel) {
        val portComboBox = JComboBox<String>().apply {
            setAlignmentX(CENTER_ALIGNMENT)
            maximumSize = Dimension(250, 500)
            for (port in SerialPort.getCommPorts()) {
                addItem(port.systemPortPath)
            }
            if (config.port != null) {
                selectedItem = config.port
            }
        }
        val portBox = Box.createHorizontalBox()
        portBox.add(portComboBox)
        portBox.add(JButton(FlatSVGIcon(javaClass.getResource("/refresh.svg"))).apply {
            addActionListener {
                val currentItem = portComboBox.selectedItem as String
                portComboBox.removeAllItems()
                for (port in SerialPort.getCommPorts()) {
                    portComboBox.addItem(port.systemPortPath) // TODO: We can use the device name CONFIG_USB_DEVICE_PRODUCT
                }
                portComboBox.selectedItem = currentItem
            }
        })
        mainPanel.add(portBox)
        val emulatorCheckbox = JCheckBox("Use emulator").apply {
            setAlignmentX(CENTER_ALIGNMENT)
            isSelected = config.useEmulator || config.port == null
        }
        mainPanel.add(emulatorCheckbox)
        val recentProperties = Properties()
        val recentConfig = DebuggerConfig.configDir + "/recent.properties"
        if (File(recentConfig).exists()) {
            recentProperties.load(FileInputStream(recentConfig))
        }
        val chooser = SystemFileChooser(recentProperties.getOrDefault("lastDir", "").toString()).apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = SystemFileChooser.FileNameExtensionFilter("WebAssembly binaries (.wasm)", "wasm")
        }
        mainPanel.add(JButton("Select program").apply {
            setAlignmentX(CENTER_ALIGNMENT)
            addActionListener {
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    recentProperties.setProperty("lastDir", chooser.selectedFile.parent)
                    recentProperties.store(FileWriter(recentConfig), null)
                    if(!startDebugger(chooser.selectedFile, emulatorCheckbox.isSelected, portComboBox.selectedItem as String?)) {
                        return@addActionListener
                    }
                    isVisible = false
                    dispose()
                }
            }
        })
    }

    private fun startDebugger(binary: File, emulator: Boolean, comPort: String?): Boolean {
        val connection = if (emulator) {
            ProcessConnection(config.wdcliPath, binary.path, "--no-socket", "--paused")
        }
        else {
            if (comPort == null) {
                JOptionPane.showMessageDialog(this, "Please select a port!", "Invalid port", JOptionPane.ERROR_MESSAGE)
                return false
            }
            SerialConnection(comPort)
        }
        val sourceMapFile = File(binary.path + ".map")
        if (!sourceMapFile.exists()) {
            JOptionPane.showMessageDialog(this, "File does not have an associated sourcemap (.map) file!", "Missing sourcemaps", JOptionPane.ERROR_MESSAGE)
            return false
        }
        val sourceMapping = AsSourceMapping(File(binary.path + ".map").readText())
        InteractiveDebugger(connection, config.symbolicWdcliPath, sourceMapping, binary.path, config = config)
        return true
    }
}
