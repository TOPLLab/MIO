package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.sourcemap.AsSourceMapping
import be.ugent.topl.mio.sourcemap.getDwarfSourcemap
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemFileChooser
import java.awt.Dimension
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException
import java.util.*
import javax.swing.*
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

data class PortElement(val portPath: String, val portInfo: String) {
    override fun toString(): String {
        if (portInfo == "Unknown")
            return portPath
        return "$portInfo ($portPath)"
    }
}

open class StartScreen(config: DebuggerConfig) : AboutScreen(config) {
    init {
        setLocationRelativeTo(null)
        defaultCloseOperation = EXIT_ON_CLOSE
    }

    fun updatePortOptions(comboBox: JComboBox<PortElement>) {
        comboBox.removeAllItems()
        for (port in SerialPort.getCommPorts()) {
            comboBox.addItem(PortElement(port.systemPortPath, port.portDescription))
        }
    }

    /**
     * If the config file specifies a port to use, use that one. Otherwise, try to find a WARDuino microcontroller.
     */
    fun determinePreferredOption(comboBox: JComboBox<PortElement>) {
        if (config.port != null) {
            comboBox.selectedItem = config.port
        }
        else {
            var i = 0
            while (i < comboBox.itemCount && !comboBox.getItemAt(i).portInfo.startsWith("WARDuino")) {
                i++
            }
            if (i < comboBox.itemCount) {
                comboBox.selectedItem = comboBox.getItemAt(i)
            }
        }
    }

    override fun addOptions(mainPanel: JPanel) {
        val portComboBox = JComboBox<PortElement>().apply {
            prototypeDisplayValue = PortElement("", "   ")
            setAlignmentX(CENTER_ALIGNMENT)
            maximumSize = Dimension(Integer.MAX_VALUE, 500)
            updatePortOptions(this)
            determinePreferredOption(this)
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                    val currentItem = selectedItem as PortElement
                    updatePortOptions(this@apply)
                    selectedItem = currentItem
                }

                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}

                override fun popupMenuCanceled(e: PopupMenuEvent?) {}
            })
        }
        val portBox = Box.createHorizontalBox()
        portBox.border = BorderFactory.createEmptyBorder(10, 20, 0, 20)
        portBox.add(JLabel("Port: "))
        portBox.add(portComboBox)
        portBox.add(JButton(FlatSVGIcon(javaClass.getResource("/refresh.svg"))).apply {
            addActionListener {
                val currentItem = portComboBox.selectedItem as PortElement
                updatePortOptions(portComboBox)
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
                    if(!startDebugger(chooser.selectedFile, emulatorCheckbox.isSelected, (portComboBox.selectedItem as PortElement).portPath)) {
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
            try {
                val sourceMapping = getDwarfSourcemap(binary.path)
                InteractiveDebugger(connection, sourceMapping, binary.path, config = config)
                return true
            } catch(e: IOException) {
                JOptionPane.showMessageDialog(this, "Could not obtain DWARF debug info from \"${binary}\". Sourcemaps (.map) are also supported but no valid sourcemap was found for this binary.\n\nDetails:\n${e.message}\n", "Failed to get debug info", JOptionPane.ERROR_MESSAGE)
                return false
            }
        }
        val sourceMapping = AsSourceMapping(File(binary.path + ".map").readText())
        InteractiveDebugger(connection, sourceMapping, binary.path, config = config)
        return true
    }
}
