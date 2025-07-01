package be.ugent.topl.mio.ui

import WasmBinary
import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.debugger.*
import be.ugent.topl.mio.sourcemap.SourceMap
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemInfo
import getBinaryInfo
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.IconRowHeader
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import kotlin.concurrent.thread


class InteractiveDebugger(
    connection: Connection,
    symbolicWdcliPath: String,
    private val sourceMapping: SourceMap? = null,
    private val wasmFile: String = "/home/maarten/Documents/School/Thesis/thesis-git/wardbg/simple-sym-test.wasm",
    private val config: DebuggerConfig
) : JFrame("WARDuino Debugger") {
    private val binaryInfo = getBinaryInfo(symbolicWdcliPath, File(wasmFile).absolutePath)
    private val debugger = MultiverseDebugger(
        connection,
        WasmBinary(File(wasmFile), binaryInfo),
        symbolicWdcliPath,
        false,
        this::onGraphUpdate,
        this::onMockingUpdate,
        this::onHitBreakpoint
    )
    private val pauseButton = JButton().apply {
        toolTipText = "Pause/Continue"
    }
    private val stepBackButton = JButton().apply {
        toolTipText = "Step back one instruction"
    }
    private val stepOverButton = JButton().apply {
        toolTipText = "Step over one instruction"
    }
    private val stepIntoButton = JButton().apply {
        toolTipText = "Step a single instruction"
    }
    private val stepLineButton = JButton().apply {
        toolTipText = "Step to the next line"
    }
    private val stepBackLineButton = JButton().apply {
        toolTipText = "Step to the previous line"
    }
    private val flashButton = JButton().apply {
        toolTipText = "Upload module to microcontroller"
    }
    private val pausedOnlyButtons = listOf(stepBackButton, stepOverButton, stepIntoButton, stepLineButton, stepBackLineButton)
    private var paused = false

    private val textArea = RSyntaxTextArea()
    private val scrollPane = RTextScrollPane(textArea, true)
    private val multiversePanel = MultiversePanel(debugger, debugger.graph, config) {
        updateStepBackButton()
        updatePcLabel()
        println("Update UI state")
    }
    private val watchWindow = WatchWindow()

    private val debugBlue = if (!FlatLaf.isLafDark()) Color(0, 122, 204) else Color(117, 190, 255)
    private val debugGreen = if (!FlatLaf.isLafDark()) Color(89, 158, 94) else Color(136, 207, 131)
    private val continueIcon = FlatSVGIcon(javaClass.getResource("/debug-continue.svg"))
    init {
        continueIcon.colorFilter = FlatSVGIcon.ColorFilter()
        continueIcon.colorFilter.add( Color.black, debugGreen, debugGreen)
    }
    private val pauseIcon = FlatSVGIcon(javaClass.getResource("/debug-pause.svg"))
    private val breakpointIcon = FlatSVGIcon(javaClass.getResource("/debug-breakpoint-small.svg"))
    init {
        breakpointIcon.colorFilter = FlatSVGIcon.ColorFilter()
        breakpointIcon.colorFilter.add( Color.black, Color(229, 20, 1), Color(229, 20, 1))
    }
    private var currentFileName = sourceMapping?.getSourceFile(0)

    init {
        debugger.startReading()
        debugger.pause()
        debugger.setSnapshotPolicy(Debugger.SnapshotPolicy.Checkpointing())
    }

    init {
        FlatSVGIcon.ColorFilter.getInstance()
            .add( Color.black, debugBlue, debugBlue)
        updateEnabledButtons()
        pauseButton.icon = pauseIcon
        pauseButton.addActionListener {
            if (paused) {
                debugger.run()
                pauseButton.icon = pauseIcon
            } else {
                debugger.pause()
                pauseButton.icon = FlatSVGIcon(continueIcon)
            }
            paused = !paused
            updateEnabledButtons()
            updatePcLabel()
        }
        stepBackButton.icon = FlatSVGIcon(javaClass.getResource("/debug-step-back.svg"))
        stepBackButton.addActionListener {
            println("Step back")
            //debugger.stepBack()
            debugger.stepBack(1, binaryInfo) {}
            updateStepBackButton()
            updatePcLabel()
        }
        stepOverButton.icon = FlatSVGIcon(javaClass.getResource("/debug-step-over.svg"))
        stepOverButton.addActionListener {
            println("Step over")
            debugger.stepOver()
            updateStepBackButton()
            updatePcLabel()
        }
        stepIntoButton.icon = FlatSVGIcon(javaClass.getResource("/debug-step-into.svg"))
        stepIntoButton.addActionListener {
            println("Step into")
            debugger.stepInto()
            updateStepBackButton()
            updatePcLabel()
        }
        stepLineButton.icon = FlatSVGIcon(javaClass.getResource("/debug-step-into.svg"))
        stepLineButton.addActionListener {
            if (sourceMapping == null) {
                stepLineButton.isEnabled = false
                return@addActionListener
            }

            println("Step line")
            var startLine = -1
            try {
                startLine = sourceMapping.getLineForPc(debugger.checkpoints.last()!!.snapshot.pc!!)
            } catch(re: RuntimeException) {
                System.err.println("WARNING: " + re.message)
            }
            debugger.stepUntil {
                try {
                    sourceMapping.getLineForPc(it.pc!!) != startLine
                } catch(re: RuntimeException) {
                    System.err.println("WARNING: " + re.message)
                    false
                }
            }
            updateStepBackButton()
            updatePcLabel()
        }
        stepBackLineButton.icon = FlatSVGIcon(javaClass.getResource("/debug-step-out.svg"))
        stepBackLineButton.addActionListener {
            if (sourceMapping == null) {
                stepBackLineButton.isEnabled = false
                return@addActionListener
            }

            println("Step back line")
            var startLine = -1
            try {
                startLine = sourceMapping.getLineForPc(debugger.checkpoints.last()!!.snapshot.pc!!)
            } catch(re: RuntimeException) {
                System.err.println("WARNING: " + re.message)
            }
            debugger.stepBackUntil(binaryInfo) {
                try {
                    sourceMapping.getLineForPc(it.pc!!) != startLine
                } catch(re: RuntimeException) {
                    System.err.println("WARNING: " + re.message)
                    false
                }
            }
            updateStepBackButton()
            updatePcLabel()
        }
        flashButton.icon = FlatSVGIcon(javaClass.getResource("/debug-update-module.svg"))
        flashButton.addActionListener {
            /*val dialog = JFileChooser()
            if (dialog.showDialog(this, "Upload") == JFileChooser.APPROVE_OPTION) {
                debugger.updateModule(dialog.selectedFile.absolutePath)
            }*/
            debugger.updateModule(wasmFile)
        }
        val toolBar = JToolBar()
        toolBar.isFloatable = true
        if (config.macIntegratedToolbar && SystemInfo.isMacFullWindowContentSupported) {
            toolBar.isFloatable = false
            rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            rootPane.putClientProperty(
                FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING,
                FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM
            )

            val placeholder = JPanel()
            placeholder.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac")
            val toolBarPanel = JPanel(BorderLayout())
            toolBarPanel.add(placeholder, BorderLayout.WEST)
            toolBarPanel.add(toolBar, BorderLayout.CENTER)

            contentPane.add(toolBarPanel, BorderLayout.NORTH)
        }
        toolBar.add(pauseButton)
        toolBar.add(stepBackButton)
        toolBar.add(stepOverButton)
        toolBar.add(stepIntoButton)
        toolBar.addSeparator()
        toolBar.add(stepBackLineButton)
        toolBar.add(stepLineButton)
        toolBar.addSeparator()
        toolBar.add(flashButton)
        toolBar.addSeparator()
        if (config.checkpointHistory) {
            toolBar.add(JButton(FlatSVGIcon(javaClass.getResource("/history.svg"))).apply {
                toolTipText = "Checkpoint history"
                addActionListener {
                    val frame = CheckpointVisualiser(debugger.checkpoints, binaryInfo, debugger)
                    frame.isVisible = true
                }
            })
        }

        val consoleWindow = ConsoleWindow(debugger)
        val consoleToggle = JToggleButton(FlatSVGIcon(javaClass.getResource("/console.svg"))).apply {
            addActionListener {
                consoleWindow.isVisible = model.isSelected
            }
        }
        consoleWindow.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                if (consoleToggle.model.isSelected) {
                    consoleToggle.model.isSelected = false
                }
            }
        })
        toolBar.add(consoleToggle)

        val theme =
            if (!FlatLaf.isLafDark()) Theme.load(javaClass.getResourceAsStream("/light.xml"))
            else Theme.load(javaClass.getResourceAsStream("/dark.xml"))
        theme.apply(textArea)
        // Increase font size:
        //textArea.font = textArea.font.deriveFont(textArea.font.size + 1.0f + 10)
        textArea.isEditable = false
        textArea.text = sourceMapping?.getSourceFile(0) ?: "Source mapping unavailable"
        textArea.syntaxEditingStyle = sourceMapping?.getStyle() ?: SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86
        textArea.popupMenu.add(JMenuItem("Disassemble").apply {
            addActionListener {
                val command = listOf("wasm-objdump", "-d", wasmFile)
                println("Running command: ${command.joinToString(" ") }")
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                process.waitFor()
                val result = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
                val frame = JFrame("Disassembly")
                val disassemblyTextArea = RSyntaxTextArea(result)
                disassemblyTextArea.isEditable = false
                frame.add(RTextScrollPane(disassemblyTextArea))
                frame.minimumSize = Dimension(200, 200)
                frame.preferredSize = Dimension(400, 600)
                frame.isVisible = true

                // Highlight line
                val pc = debugger.checkpoints.last()?.snapshot?.pc
                val lines = result.lines()
                for (lineIndex in lines.indices) {
                    val line = lines[lineIndex]
                    if (line.trim().startsWith(String.format("%06x", pc))) {
                        println(line)
                        disassemblyTextArea.addLineHighlight(lineIndex, if (!FlatLaf.isLafDark()) Color(255, 255, 186, 255) else Color(207, 207, 131, 75))
                    }
                }
            }
        })
        scrollPane.isIconRowHeaderEnabled = true
        scrollPane.gutter.iconRowHeaderInheritsGutterBackground = true
        if (sourceMapping != null) {
            scrollPane.gutter.isBookmarkingEnabled = true
            scrollPane.gutter.bookmarkIcon = breakpointIcon

            for (component in scrollPane.gutter.components) {
                if (component is IconRowHeader) {
                    component.addMouseListener(object : MouseListener {
                        override fun mouseClicked(me: MouseEvent) {
                            val line = textArea.getLineOfOffset(textArea.viewToModel2D(me.point))
                            try {
                                val addr = sourceMapping.getPcForLine(line + 1, currentFileName!!)
                                if (component.getTrackingIcons(line).isNotEmpty()) {
                                    debugger.addBreakpoint(addr)
                                } else {
                                    debugger.removeBreakpoint(addr)
                                }
                            } catch (e: Exception) {
                                component.toggleBookmark(line)
                            }
                        }

                        override fun mousePressed(p0: MouseEvent?) {}

                        override fun mouseReleased(p0: MouseEvent?) {}

                        override fun mouseEntered(p0: MouseEvent?) {}

                        override fun mouseExited(p0: MouseEvent?) {}
                    })
                }
            }
        }
        /*for (choicepoint in binaryInfo.choicepoints) {
            debugger.addBreakpoint(choicepoint)
        }*/

        val verticalPanel = JPanel()
        verticalPanel.layout = BorderLayout()
        if (!SystemInfo.isMacFullWindowContentSupported || !config.macIntegratedToolbar) {
            verticalPanel.add(toolBar, BorderLayout.NORTH)
        }

        val horizontalSplitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, multiversePanel)
        horizontalSplitPane.resizeWeight = 0.6
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, horizontalSplitPane, JScrollPane(watchWindow))
        splitPane.resizeWeight = 0.8
        verticalPanel.add(splitPane, BorderLayout.CENTER)
        //verticalPanel.add(scrollPane, BorderLayout.CENTER)

        add(verticalPanel)

        minimumSize = Dimension(250, 250)
        preferredSize = Dimension(700, 500)
        pack()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(p0: WindowEvent?) {
                debugger.close()
            }
        })
        defaultCloseOperation = EXIT_ON_CLOSE
        isVisible = true
    }

    private fun updateEnabledButtons() {
        pausedOnlyButtons.forEach { it.isEnabled = paused }
        multiversePanel.isEnabled = paused
        if (paused) {
            updateStepBackButton()
        }
    }

    private fun updateStepBackButton() {
        stepBackButton.isEnabled = debugger.checkpoints.size > 1
        stepBackLineButton.isEnabled = debugger.checkpoints.size > 1
    }

    private fun updatePcLabel() {
        if (!paused) {
            textArea.removeAllLineHighlights()
            watchWindow.clear()
            return
        }

        //val snapshot = debugger.currentSnapshot!!
        val snapshot = debugger.checkpoints.last()!!.snapshot
        //val snapshot = debugger.snapshotFull().second
        watchWindow.update(snapshot)

        if (sourceMapping == null)
            return

        updateBreakPoints(snapshot)

        val pc = snapshot.pc!!
        try {
            val lineNumber = sourceMapping.getLineForPc(pc)
            if (sourceMapping.getSourceFile(pc) != textArea.text)
                textArea.text = sourceMapping.getSourceFile(pc)
            currentFileName = sourceMapping.getSourceFileName(pc)
            if (textArea.syntaxEditingStyle != sourceMapping.getStyle()) {
                textArea.syntaxEditingStyle = sourceMapping.getStyle()
            }
            textArea.removeAllLineHighlights()
            textArea.addLineHighlight(lineNumber - 1, if (!FlatLaf.isLafDark()) Color(255, 255, 186, 255) else Color(207, 207, 131, 75))

            val lineY = textArea.yForLine(lineNumber - 1)
            if (lineY < scrollPane.verticalScrollBar.value || lineY + 20 > scrollPane.verticalScrollBar.value + scrollPane.height) {
                try {
                    scrollPane.verticalScrollBar.value = textArea.yForLine((lineNumber - 11).coerceAtLeast(0))
                }
                catch (_: Exception) {}
            }
        } catch(iae : RuntimeException) {
            /*
             * Mostly happens on end and else instructions because there is no line number returned for these
             * instructions when using wat2wasm.
             */
            System.err.println("WARNING: " + iae.message)
        }
    }

    private fun updateBreakPoints(snapshot: WOODDumpResponse) {
        if (sourceMapping == null)
            return;

        val breakpoints = snapshot.breakpoints!!
        scrollPane.gutter.removeAllTrackingIcons()
        for (breakPointPc in breakpoints) {
            scrollPane.gutter.addLineTrackingIcon(sourceMapping.getLineForPc(breakPointPc) - 1, breakpointIcon, "Breakpoint")
        }
    }

    private fun onHitBreakpoint(pc: Int) {
        println("onPause pc = ${pc.toString(16)}")
        paused = true
        pauseButton.icon = continueIcon
        updateEnabledButtons()
        updatePcLabel()
    }

    private fun onGraphUpdate() {
        multiversePanel.graphChanged()
    }

    private fun onMockingUpdate() {
        multiversePanel.onMockingChanged()
    }
}

interface MultiverseAction {
    fun doAction()
}

class OverrideAction(val debugger: Debugger, val node: PrimitiveNode, val index: Int) : MultiverseAction {
    override fun doAction() {
        debugger.addPrimitiveOverride(node.primitive, node.arg, node.values[index])
    }

}

class ContinueForAction(val debugger: Debugger, var n: Int) : MultiverseAction {
    override fun doAction() {
        debugger.continueFor(n)
    }
}

class MultiversePanel(private val multiverseDebugger: MultiverseDebugger, graph: MultiverseGraph, config: DebuggerConfig, stateChanged: () -> Unit) : JPanel() {
    private val graphPanel = GraphPanel(graph)
    private val mockPanel = OverridesPanel()
    private val concolicButton = JButton("Suggest interesting paths")
    private var maxInstructions = 50
    private val concolicOptionsButton = JButton().apply {
        val gearIcon = FlatSVGIcon(MultiverseDebugger::javaClass.javaClass.getResource("/settings-gear.svg"))
        gearIcon.colorFilter = FlatSVGIcon.ColorFilter()
        gearIcon.colorFilter.add(Color.black, UIManager.getDefaults().getColor("Button.foreground"), UIManager.getDefaults().getColor("Button.foreground"))
        icon = gearIcon
        addActionListener {
            JOptionPane.showInputDialog("Instruction limit", maxInstructions)?.let {
                maxInstructions = it.toInt()
            }
        }
    }
    private val customButton = JButton("Mock").apply {
        isEnabled = false
    }
    private val followButton = JButton(if (config.concolic) "Slide" else "Jump").apply {
        isEnabled = false
    }
    init {
        layout = BorderLayout()
        //add(JScrollPane(graphPanel))
        val scrollpane = JScrollPane(graphPanel)
        graphPanel.associatedScrollPane = scrollpane
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollpane, mockPanel).apply {
            resizeWeight = 0.7
        })
        //add(OverridesPanel(), BorderLayout.EAST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            if (config.concolic) {
                add(concolicOptionsButton)
                add(concolicButton)
            }
            add(customButton)
            add(followButton)
        }, BorderLayout.SOUTH)

        concolicButton.addActionListener {
            val w = BlockingWindow(null, "Analysing program")
            w.location = Point(this.location.x + this.width/2 - w.width/2, this.location.y + this.height/2 - w.height/2)
            w.run({ multiverseDebugger.predictFuture(maxInstructions) }) { graphChanged ->
                if (!graphChanged) {
                    JOptionPane.showMessageDialog(this, "No future branching paths could be found")
                }
            }
        }

        graphPanel.addSelectionListener {
            followButton.isEnabled = graphPanel.selectedValue != null
        }

        followButton.addActionListener {
            followButton.isEnabled = false
            thread {
                multiverseDebugger.printCheckpoints(multiverseDebugger.wasmBinary.metadata)

                val backwardPath = graphPanel.selectedPath!!.first.toMutableList()
                multiverseDebugger.stepBack(backwardPath.size, multiverseDebugger.wasmBinary.metadata) {
                    graphPanel.completedPath.add(backwardPath.removeFirst())
                    graphPanel.repaint()
                    if (multiverseDebugger.checkpoints.last() != null)
                        stateChanged()
                }

                val forwardPath = graphPanel.selectedPath!!.second
                val actionPath = mutableListOf<MultiverseAction>()
                for (i in 1..<forwardPath.size) {
                    val a = forwardPath[i - 1]
                    val b = forwardPath[i]
                    val index = a.children.indexOf(b)
                    if (a is PrimitiveNode) {
                        actionPath.add(OverrideAction(multiverseDebugger, a, index))
                    }
                    if (actionPath.isEmpty() || actionPath.last() is OverrideAction) {
                        actionPath.add(ContinueForAction(multiverseDebugger, 0))
                    }
                    val lastAction = actionPath.last() as ContinueForAction
                    lastAction.n++
                }
                for (action in actionPath) {
                    action.doAction()
                }
                graphPanel.completedPath.addAll(forwardPath.subList(0, forwardPath.size - 1))
                graphPanel.repaint()
                //Thread.sleep(1000)
                stateChanged()
                //debugger.continueFor(forwardPath.size - 1)

                graphPanel.completedPath.add(forwardPath.last())
                graphPanel.repaint()

                //multiverseDebugger.graph.currentNode = forwardPath.last()
                graphPanel.completedPath.clear()
                graphPanel.selectedNodes.clear()
                graphPanel.repaint()

                multiverseDebugger.printCheckpoints(multiverseDebugger.wasmBinary.metadata)

                graphPanel.clearSelection()
            }
        }

        customButton.addActionListener {
            val currentNode = multiverseDebugger.graph.currentNode
            val mainPanel = JPanel()
            val primitiveNameTextField = JComboBox<String>()
            multiverseDebugger.wasmBinary.metadata.primitive_fidx_mapping.forEach {
                primitiveNameTextField.addItem(it)
            }
            mainPanel.add(primitiveNameTextField)
            mainPanel.add(JLabel("("))
            val argTextField = JTextField()
            mainPanel.add(argTextField)
            mainPanel.add(JLabel(") = "))
            val returnValueTextField = JTextField()
            mainPanel.add(returnValueTextField)

            if (currentNode is PrimitiveNode) {
                argTextField.isEnabled = false
                argTextField.text = currentNode.arg.toString()
                primitiveNameTextField.isEnabled = false
                primitiveNameTextField.selectedItem = currentNode.primitive
            }

            val relations = if (File(config.configDir + "/program.constraints").exists()) ConstraintParser.parseFile("test.constraints") else listOf()

            fun handleRelations() {
                returnValueTextField.isEnabled = true
                for (relation in relations) {
                    if (primitiveNameTextField.selectedItem == relation.override.primName &&
                        argTextField.text == relation.override.arg.toString()
                    ) {
                        val state = multiverseDebugger.getCurrentState()
                        val realValue = state.io!!.find { it.key == relation.io.key }?.value
                        if (realValue != null && relation.comp(realValue, relation.io.value)) {
                            returnValueTextField.text = relation.override.returnValue.toString()
                            returnValueTextField.isEnabled = false
                        }
                    }
                }
            }

            // Re-check relations when changing primitive or argument.
            primitiveNameTextField.addActionListener {
                handleRelations()
            }
            argTextField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) {
                    handleRelations()
                }

                override fun removeUpdate(e: DocumentEvent?) {
                    handleRelations()
                }

                override fun changedUpdate(e: DocumentEvent?) {
                    handleRelations()
                }
            })

            val pauseIcon = ImageIcon(javaClass.getResource("/logo-small.png"))

            val allOptions = arrayOf("Create range", "Mock", "Cancel")
            val optionPane = JOptionPane(mainPanel, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION).apply {
                //options = options1.toTypedArray()
                val rangeButton = JButton(allOptions[0])
                val mockButton = JButton(allOptions[1])
                val cancelButton = JButton(allOptions[2])
                options = arrayOf(rangeButton, mockButton, cancelButton)

                rangeButton.isEnabled = currentNode is PrimitiveNode

                rangeButton.addActionListener {
                    this.value = rangeButton.text
                }
                mockButton.addActionListener {
                    this.value = mockButton.text
                }
                cancelButton.addActionListener {
                    this.value = cancelButton.text
                }
            }
            val dialog = optionPane.createDialog(null, "Mock primitive call")
                        dialog.setIconImage(pauseIcon.image)
            dialog.isVisible = true
            val x = optionPane.value
            if (x == allOptions[1]) {
                val primitiveName = primitiveNameTextField.selectedItem as String
                val arg = argTextField.text.toInt()
                val returnValue = (returnValueTextField.text as String).toInt()
                multiverseDebugger.addPrimitiveOverride(primitiveName, arg, returnValue)
            } else if (x == allOptions[0]) {
                showPathRangeWindow()
            }
        }
    }

    private fun showPathRangeWindow() {
        val myPanel = JPanel()
        myPanel.add(JLabel("min:"))
        val minTextField = JTextField()
        myPanel.add(minTextField)
        myPanel.add(Box.createHorizontalStrut(15))
        myPanel.add(JLabel("max:"))
        val maxTextField = JTextField()
        myPanel.add(maxTextField)
        myPanel.add(JLabel("step:"))
        val stepTextField = JTextField()
        myPanel.add(stepTextField)

        if (JOptionPane.showConfirmDialog(
                null, myPanel,
                "Choose path range", JOptionPane.OK_CANCEL_OPTION
            ) == JOptionPane.OK_OPTION
        ) {
            val min = minTextField.text.toInt()
            val max = maxTextField.text.toInt()
            val step = stepTextField.text.toInt()
            for (x in min..max step step) {
                multiverseDebugger.createNewPath(x, false)
            }
        }
    }

    fun graphChanged() {
        graphPanel.repaint()
        //customButton.isEnabled = multiverseDebugger.graph.currentNode is PrimitiveNode
    }

    fun onMockingChanged() {
        mockPanel.clear()
        for (primMocks in multiverseDebugger.overrides) {
            for (argResultPair in primMocks.value) {
                mockPanel.addItem(primMocks.key, listOf(argResultPair.key), argResultPair.value) {
                    multiverseDebugger.removePrimitiveOverride(primMocks.key, argResultPair.key)
                }
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        concolicOptionsButton.isEnabled = enabled
        concolicButton.isEnabled = enabled
        //customButton.isEnabled = enabled && multiverseDebugger.graph.currentNode is PrimitiveNode
        customButton.isEnabled = enabled
        followButton.isEnabled = enabled
    }
}

class WatchWindow : JTable() {
    private val tableModel = DefaultTableModel()
    init {
        model = tableModel
        tableModel.setColumnIdentifiers(arrayOf("Name", "Type", "Value"))
        setDefaultEditor(Any::class.java, null)
    }

    fun update(snapshot: WOODDumpResponse) {
        tableModel.rowCount = 0
        tableModel.addRow(arrayOf("pc", "i32", String.format("0x%x", snapshot.pc)))
        for (global in snapshot.globals!!) {
            tableModel.addRow(arrayOf("global ${global.idx}", global.type, global.value))
        }
        if (snapshot.callstack!!.isNotEmpty()) {
            tableModel.addRow(arrayOf("fp", "i32", snapshot.callstack.last().fp))
        }
        val stack = snapshot.stack!!
        for (stackElement in stack) {
            tableModel.addRow(arrayOf("stack[${stackElement.idx}]", stackElement.type, stackElement.value))
        }
    }

    fun clear() {
        tableModel.rowCount = 0
    }
}
