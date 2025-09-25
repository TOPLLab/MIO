package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.sourcemap.AsSourceMapping
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.util.*
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter


open class StartScreen(config: DebuggerConfig) : AboutScreen(config, 550, 400) {
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

        // Add history:
        val recentFile = File(DebuggerConfig.configDir + "/recent.json")
        val mapper = ObjectMapper()
        mapper.registerKotlinModule()
        val recent = if (recentFile.exists()) {
            mapper.readValue<Recent>(recentFile)
        } else {
            Recent(mutableListOf())
        }

        val recentProperties = Properties()
        val recentConfig = DebuggerConfig.configDir + "/recent.properties"
        if (File(recentConfig).exists()) {
            recentProperties.load(FileInputStream(recentConfig))
        }

        fun open(selectedFile: File) {
            recentProperties.setProperty("lastDir", selectedFile.parent)
            recentProperties.store(FileWriter(recentConfig), null)
            if(!startDebugger(selectedFile, emulatorCheckbox.isSelected, portComboBox.selectedItem as String?)) {
                JOptionPane.showMessageDialog(this, "Please select a port!", "Invalid port", JOptionPane.ERROR_MESSAGE)
                return
            }
            if (recent.recentFiles.last() != selectedFile.absolutePath) {
                recent.recentFiles.add(selectedFile.absolutePath)
                mapper.writeValue(recentFile, recent)
            }
            isVisible = false
            dispose()
        }

        mainPanel.add(JButton("Select program").apply {
            setAlignmentX(CENTER_ALIGNMENT)
            addActionListener {
                val chooser = JFileChooser(recentProperties.getOrDefault("lastDir", "").toString())
                chooser.fileSelectionMode = JFileChooser.FILES_ONLY
                chooser.fileFilter = FileNameExtensionFilter("WebAssembly binaries (.wasm)", "wasm")
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    open(chooser.selectedFile)
                }
            }
        })

        mainPanel.add(Box.createVerticalBox().apply {
            add(JLabel("Recent:"))
            val model = DefaultListModel<String>()
            for (file in recent.recentFiles) {
                model.addElement(file)
            }
            add(JScrollPane(JList(model).apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(evt: MouseEvent) {
                        val list = evt.getSource() as JList<*>
                        if (evt.getClickCount() == 2) {
                            val index = list.locationToIndex(evt.getPoint())
                            open(File(recent.recentFiles[index]))
                        }
                    }
                })
            }))
        })
    }

    private fun startDebugger(binary: File, emulator: Boolean, comPort: String?): Boolean {
        val connection = if (emulator) {
            ProcessConnection(config.wdcliPath, binary.path, "--no-socket", "--paused")
        }
        else {
            if (comPort == null) {
                return false
            }
            SerialConnection(comPort)
        }
        val sourceMapping = AsSourceMapping(File(binary.path + ".map").readText())
        InteractiveDebugger(connection, config.symbolicWdcliPath, sourceMapping, binary.path, config = config)
        return true
    }
}

data class Recent(val recentFiles: MutableList<String>)
