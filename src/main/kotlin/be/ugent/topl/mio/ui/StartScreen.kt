package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.sourcemap.AsSourceMapping
import be.ugent.topl.mio.sourcemap.getDwarfSourcemap
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemFileChooser
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
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

data class ProjectItem(val name: String, val path: String)

class ProjectCardRenderer : ListCellRenderer<ProjectItem> {
    private val panel = object : JPanel(BorderLayout(10, 5)) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
    }
    private val nameLabel = JLabel().apply {
        font = Font(font.name, Font.BOLD, 14)
    }
    private val pathLabel = JLabel().apply {
        font = Font(font.name, Font.PLAIN, 11)
        foreground = Color.GRAY
    }
    private val wrapperPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        // Keep spacing between cards and a small right inset near the viewport edge.
        border = BorderFactory.createEmptyBorder(4, 0, 4, 4)
    }

    private val textPanel = Box.createVerticalBox().apply {
        add(nameLabel)
        add(Box.createVerticalStrut(3))
        add(pathLabel)
        add(Box.createVerticalGlue())
    }

    private fun ellipsize(text: String, label: JLabel, maxWidth: Int): String {
        if (maxWidth <= 0) return text
        val metrics = label.getFontMetrics(label.font)
        if (metrics.stringWidth(text) <= maxWidth) return text

        val ellipsis = "..."
        val ellipsisWidth = metrics.stringWidth(ellipsis)
        if (ellipsisWidth >= maxWidth) return ellipsis

        // Keep the end of the path visible and trim from the front.
        var start = 0
        while (start < text.length && metrics.stringWidth(text.substring(start)) + ellipsisWidth > maxWidth) {
            start++
        }
        return if (start >= text.length) ellipsis else ellipsis + text.substring(start)
    }

    override fun getListCellRendererComponent(
        list: JList<out ProjectItem>,
        value: ProjectItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        nameLabel.text = value.name
        pathLabel.text = ellipsize(value.path, pathLabel, list.fixedCellWidth)

        panel.apply {
            removeAll()
            add(textPanel, BorderLayout.CENTER)
            background = when {
                isSelected -> UIManager.getDefaults().getColor("List.selectionBackground")
                else -> UIManager.getDefaults().getColor("Panel.background")
            }
            nameLabel.foreground = when {
                isSelected -> UIManager.getDefaults().getColor("List.selectionForeground")
                else -> UIManager.getColor("List.foreground")
            }
            pathLabel.foreground = when {
                isSelected -> Color.lightGray
                else -> Color.gray
            }
        }

        wrapperPanel.removeAll()
        wrapperPanel.add(panel, BorderLayout.CENTER)
        return wrapperPanel
    }
}

open class StartScreen(config: DebuggerConfig) : AboutScreen(config) {
    override fun addTrailingGlue(): Boolean = false

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
        val recentProperties = Properties()
        val recentConfig = DebuggerConfig.configDir + "/recent.properties"
        if (File(recentConfig).exists()) {
            recentProperties.load(FileInputStream(recentConfig))
        }

        // Clear the mainPanel and reorganize the layout
        mainPanel.removeAll()

        val rightColumnWidth = 250
        val recentCardWidth = 208
        val recentListBackground = UIManager.getDefaults().getColor("List.background")

        // Create main horizontal container
        val mainHorizontalBox = Box.createHorizontalBox()

        // Left panel - narrow column with logo, title, and controls
        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        }

        // Add some top spacing
        leftPanel.add(Box.createVerticalStrut(10))

        // Logo
        leftPanel.add(JLabel(FlatSVGIcon(javaClass.getResource("/MIO_Logo2.svg"))).apply {
            alignmentX = 0.5f
        })
        leftPanel.add(Box.createVerticalStrut(12))

        // Title
        leftPanel.add(JLabel("MIO Debugger").apply {
            alignmentX = 0.5f
            putClientProperty("FlatLaf.style", "font: 180% \$semibold.font")
        })

        // Add subtitle
        leftPanel.add(JLabel("for WARDuino").apply {
            alignmentX = 0.5f
            putClientProperty("FlatLaf.style", "font: 120% \$light.font")
        })
        leftPanel.add(Box.createVerticalStrut(20))

        val controlWidth = 350
        val controlHeight = 32

        val portComboBox = JComboBox<PortElement>().apply {
            prototypeDisplayValue = PortElement("", "   ")
            preferredSize = Dimension(controlWidth - 70, controlHeight)
            minimumSize = Dimension(controlWidth - 70, controlHeight)
            maximumSize = Dimension(controlWidth - 70, controlHeight)
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

        val refreshButton = JButton(FlatSVGIcon(javaClass.getResource("/refresh.svg"))).apply {
            preferredSize = Dimension(controlHeight, controlHeight)
            minimumSize = Dimension(controlHeight, controlHeight)
            maximumSize = Dimension(controlHeight, controlHeight)
            addActionListener {
                val currentItem = portComboBox.selectedItem as PortElement
                updatePortOptions(portComboBox)
                portComboBox.selectedItem = currentItem
            }
        }

        val portBox = JPanel(BorderLayout(3, 0)).apply {
            isOpaque = false
            alignmentX = 0.5f
            preferredSize = Dimension(controlWidth, controlHeight)
            minimumSize = Dimension(controlWidth, controlHeight)
            maximumSize = Dimension(controlWidth, controlHeight)
            add(portComboBox, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        leftPanel.add(portBox)
        leftPanel.add(Box.createVerticalStrut(3))

        val emulatorCheckbox = JCheckBox("Use emulator").apply {
            isSelected = config.useEmulator || config.port == null
            alignmentX = 0.5f
            preferredSize = Dimension(controlWidth, controlHeight)
            minimumSize = Dimension(controlWidth, controlHeight)
            maximumSize = Dimension(controlWidth, controlHeight)
            margin = Insets(0, 0, 0, 0)
            horizontalAlignment = SwingConstants.LEFT
            border = BorderFactory.createEmptyBorder()
            isBorderPainted = false
        }
        leftPanel.add(emulatorCheckbox)
        leftPanel.add(Box.createVerticalStrut(3))

        val chooser = SystemFileChooser(recentProperties.getOrDefault("lastDir", "").toString()).apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = SystemFileChooser.FileNameExtensionFilter("WebAssembly binaries (.wasm)", "wasm")
        }
        val openFileButton = JButton("Select program").apply {
            alignmentX = 0.5f
            preferredSize = Dimension(controlWidth, controlHeight)
            minimumSize = Dimension(controlWidth, controlHeight)
            maximumSize = Dimension(controlWidth, controlHeight)
            addActionListener {
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    recentProperties.setProperty("lastDir", chooser.selectedFile.parent)
                    recentProperties.store(FileWriter(recentConfig), null)
                    if(!startDebugger(chooser.selectedFile, emulatorCheckbox.isSelected, (portComboBox.selectedItem as PortElement).portPath, recentProperties, recentConfig)) {
                        return@addActionListener
                    }
                    isVisible = false
                    dispose()
                }
            }
        }
        val openFileBox = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = 0.5f
            preferredSize = Dimension(controlWidth, controlHeight)
            minimumSize = Dimension(controlWidth, controlHeight)
            maximumSize = Dimension(controlWidth, controlHeight)
            add(openFileButton)
        }
        leftPanel.add(openFileBox)
        leftPanel.add(Box.createVerticalGlue())

        // Right panel - recent programs
        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(rightColumnWidth, 0)
            maximumSize = Dimension(rightColumnWidth, Int.MAX_VALUE)
            background = recentListBackground
            isOpaque = true
        }

        // Load recent programs from properties
        val listModel = DefaultListModel<ProjectItem>()
        val recentProgramsStr = recentProperties.getOrDefault("recentPrograms", "").toString()
        if (recentProgramsStr.isNotEmpty()) {
            recentProgramsStr.split(";").forEach { program ->
                if (program.isNotEmpty()) {
                    val file = File(program)
                    val fileName = file.name
                    listModel.addElement(ProjectItem(fileName, program))
                }
            }
        }

        val recentList = object : JList<ProjectItem>(listModel) {
            override fun getToolTipText(event: MouseEvent): String? {
                val index = locationToIndex(event.point)
                if (index < 0) return null
                val bounds = getCellBounds(index, index) ?: return null
                if (!bounds.contains(event.point)) return null
                return model.getElementAt(index).path
            }
        }.apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ProjectCardRenderer()
            fixedCellWidth = recentCardWidth
            background = recentListBackground
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            ToolTipManager.sharedInstance().registerComponent(this)
            addMouseListener(object : MouseListener {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = this@apply.locationToIndex(e.point)
                        val file = File(listModel.getElementAt(index).path)
                        if(!startDebugger(file, emulatorCheckbox.isSelected, (portComboBox.selectedItem as PortElement).portPath, recentProperties, recentConfig)) {
                            this@apply.clearSelection()
                            return
                        }
                        isVisible = false
                        dispose()
                        e.consume()
                    }
                }

                override fun mousePressed(e: MouseEvent) {}
                override fun mouseReleased(e: MouseEvent) {}
                override fun mouseEntered(e: MouseEvent) {}
                override fun mouseExited(e: MouseEvent) {}
            })
        }

        val scrollPane = JScrollPane(recentList).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(rightColumnWidth, 0)
            maximumSize = Dimension(rightColumnWidth, Int.MAX_VALUE)
            viewport.background = recentListBackground
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }
        rightPanel.add(scrollPane)

        // Add both panels to horizontal box
        mainHorizontalBox.add(leftPanel)
        mainHorizontalBox.add(rightPanel)

        mainPanel.add(mainHorizontalBox)

        minimumSize = Dimension(650, 380)
        size = minimumSize
    }

    private fun startDebugger(binary: File, emulator: Boolean, comPort: String?, recentProperties: Properties? = null, recentConfig: String? = null): Boolean {
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
                // Save to recent programs if properties provided
                if (recentProperties != null && recentConfig != null) {
                    saveRecentProgram(binary.path, recentProperties, recentConfig)
                }
                return true
            } catch(e: IOException) {
                JOptionPane.showMessageDialog(this, "Could not obtain DWARF debug info from \"${binary}\". Sourcemaps (.map) are also supported but no valid sourcemap was found for this binary.\n\nDetails:\n${e.message}\n", "Failed to get debug info", JOptionPane.ERROR_MESSAGE)
                return false
            }
        }
        val sourceMapping = AsSourceMapping(File(binary.path + ".map").readText())
        InteractiveDebugger(connection, sourceMapping, binary.path, config = config)
        // Save to recent programs if properties provided
        if (recentProperties != null && recentConfig != null) {
            saveRecentProgram(binary.path, recentProperties, recentConfig)
        }
        return true
    }

    private fun saveRecentProgram(programPath: String, recentProperties: Properties, recentConfig: String) {
        // Get existing recent programs
        val recentProgramsStr = recentProperties.getOrDefault("recentPrograms", "").toString()
        val recentPrograms = if (recentProgramsStr.isEmpty()) mutableListOf() else recentProgramsStr.split(";").toMutableList()

        // Remove if already exists and re-add at the beginning
        recentPrograms.remove(programPath)
        recentPrograms.add(0, programPath)

        // Keep only last 10 programs
        val limitedPrograms = recentPrograms.take(10)

        // Save back to properties
        recentProperties.setProperty("recentPrograms", limitedPrograms.joinToString(";"))
        recentProperties.store(FileWriter(recentConfig), null)
    }
}
