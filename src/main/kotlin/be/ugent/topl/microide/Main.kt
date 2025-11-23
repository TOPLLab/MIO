package be.ugent.topl.microide

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.ui.PortBox
import be.ugent.topl.mio.ui.setupFlatLafTheme
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemInfo
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.*
import java.io.File
import java.nio.charset.Charset
import javax.swing.*
import javax.swing.text.DefaultCaret
import javax.swing.text.StyleContext
import kotlin.concurrent.thread

fun main() {
    /*val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        MainWindow(chooser.selectedFile.absolutePath).apply {
            isVisible = true
        }
    }*/

    val config = DebuggerConfig()

    // Set theme.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "WARDuino IDE") //WAMIDE
    if (config.lightMode) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua")
    else System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    // Open port selector.
    setupFlatLafTheme(config)
    val frame = JFrame("Select port")
    frame.minimumSize = Dimension(400, 150)
    frame.add(Box.createVerticalBox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        val portSelector = PortBox(config.port)
        add(Box.Filler(
            Dimension(0, 0),        // min
            Dimension(0, 0),        // preferred
            Dimension(0, Int.MAX_VALUE)   // max height → expands to fill all remaining space
        ))
        add(portSelector)
        add(Box.createVerticalStrut(5))
        val emulatorCheckbox = JCheckBox("Use emulator", config.useEmulator)
        add(Box.createHorizontalBox().apply {
            add(emulatorCheckbox)
            add(Box.createHorizontalGlue())
        })
        add(Box.Filler(
            Dimension(0, 0),        // min
            Dimension(0, 0),        // preferred
            Dimension(0, Int.MAX_VALUE)   // max height → expands to fill all remaining space
        ))
        add(Box.createHorizontalBox().apply {
            add(Box.createHorizontalGlue())
            add(JButton("Use").apply {
                addActionListener {
                    val window = MainWindow(portSelector.selectedPort!!, emulatorCheckbox.isSelected)
                    window.setLocationRelativeTo(frame)
                    window.isVisible = true
                    frame.isVisible = false
                    frame.dispose()
                }
            })
        })
    })
    frame.setLocationRelativeTo(null)
    frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
    frame.isVisible = true
}

class MainWindow(private val port: String, private val useEmulator: Boolean, private val filename: String = "microide.ts") : JFrame("WARDuino Micro IDE - $filename") {
    private val config = DebuggerConfig()
    private var connection: Connection? = null
    private val errorPane = JTextPane().apply {
        isEditable = false
    }
    private val textArea = RSyntaxTextArea()

    init {
        initTheme()

        minimumSize = Dimension(200, 200)
        preferredSize = Dimension(600, 400)
        defaultCloseOperation = EXIT_ON_CLOSE
        textArea.isEditable = true
        textArea.highlightCurrentLine = true
        textArea.tabsEmulated = true
        textArea.tabSize = 4
        val theme =
            if (!FlatLaf.isLafDark()) Theme.load(javaClass.getResourceAsStream("/light.xml"))
            else Theme.load(javaClass.getResourceAsStream("/dark.xml"))
        theme.apply(textArea)
        textArea.font = Font.createFont(Font.TRUETYPE_FONT,  this.javaClass.getResourceAsStream("/fonts/JetBrainsMono-2.304/fonts/variable/JetBrainsMono[wght].ttf")).deriveFont(13f)
        textArea.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT
        if (File(filename).exists()) {
            textArea.text = File(filename).readText(Charset.defaultCharset())
        }
        else {
            textArea.text = """
            @external("env", "chip_delay") 
            declare function delay(value: i32): void;
            @external("env", "chip_pin_mode") 
            declare function pinMode(pin: i32, mode: i32): void;
            @external("env", "chip_digital_write") 
            declare function digitalWrite(pin: i32, value: i32): void;
            @external("env", "print_int") 
            declare function printInt(v: i32): void;
            
            enum PinVoltage {
                LOW  = 0,
                HIGH = 1,
            }
    
            enum PinMode {
              INPUT = 0,
              OUTPUT = 2
            }
    
            enum Pin {
              powerSupply = 60,
              led1 = 45,
            }
    
            function setup(): void {
                // Configure and enable power supply pin (active low).
                pinMode(Pin.powerSupply, PinMode.OUTPUT);
                delay(500); // Wait for 500ms to avoid current spike.
                digitalWrite(Pin.powerSupply, PinVoltage.LOW);
            }
            
            export function main(): void {
                // Configure LED.
                pinMode(Pin.led1, PinMode.OUTPUT);
                
                while (true) {
                    digitalWrite(Pin.led1, PinVoltage.HIGH);
                    delay(500);
                    digitalWrite(Pin.led1, PinVoltage.LOW);
                    delay(500);
                }
            }
            """.trimIndent()
            save()
        }

        val mainPanel = JPanel(BorderLayout())
        val st = StyleContext.getDefaultStyleContext()
        errorPane.font = st.getFont(Font.MONOSPACED, Font.PLAIN, 13)
        val caret = errorPane.caret as DefaultCaret
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE)
        mainPanel.add(JSplitPane(JSplitPane.VERTICAL_SPLIT,RTextScrollPane(textArea), JScrollPane(errorPane)).apply {
            resizeWeight = 0.8
        }, BorderLayout.CENTER)
        val toolbar = JToolBar()
        if (SystemInfo.isMacOS) {
            if (SystemInfo.isMacFullWindowContentSupported) {
                getRootPane().putClientProperty("apple.awt.fullWindowContent", true)
                getRootPane().putClientProperty("apple.awt.transparentTitleBar", true)
            }

            val box = Box.createVerticalBox()
            box.add(Box.createVerticalStrut(25))
            box.add(toolbar)
            toolbar.alignmentX = LEFT_ALIGNMENT
            mainPanel.add(box, BorderLayout.NORTH)
        }
        else {
            mainPanel.add(toolbar, BorderLayout.NORTH)
        }
        val runIcon = ideaIcon("/run", config.lightMode)
        val stopIcon = ideaIcon("/stop", config.lightMode)
        val runButton = JButton(runIcon).apply {
            toolTipText = "Run"
            addActionListener {
                val conn = connection
                if (conn != null) {
                    println("Halt execution")
                    if (useEmulator) conn.write("02\n".toByteArray(Charset.defaultCharset()))
                    else conn.write("03\n".toByteArray(Charset.defaultCharset()))
                    /*val debugger = Debugger(conn)
                    debugger.pause()
                    debugger.close()*/
                    conn.close()
                    connection = null
                    icon = runIcon
                    toolTipText = "Run"
                }
                else {
                    if (!build()) {
                        return@addActionListener
                    }
                    errorPane.text = ""

                    errorPane.text = ""
                    errorPane.foreground = UIManager.getDefaults().getColor("Panel.foreground")
                    if (useEmulator) {
                        connection = ProcessConnection(config.wdcliPath, "test.wasm", "--no-socket")
                    }
                    else {
                        val newConn = SerialConnection(port)
                        val debugger = Debugger(newConn)
                        debugger.updateModule("test.wasm")
                        debugger.run()
                        debugger.close() // Also closes the connection so we re-open it again.

                        connection = SerialConnection(port)
                    }
                    icon = stopIcon
                    toolTipText = "Stop"
                }
            }
        }

        thread {
            //val outputBufferLimit = 40000
            val outputBufferLimit = 500
            var addBuffer = StringBuilder()
            var lastTime = 0L
            while (true) {
                val connection = connection
                if (connection == null) {
                    Thread.sleep(20)
                    continue
                }

                if (connection.bytesAvailable() > 0) {
                    val buffer = ByteArray(connection.bytesAvailable())
                    connection.read(buffer)
                    SwingUtilities.invokeLater {
                        val str = buffer.toString(Charset.defaultCharset())
                        //println(str)
                        addBuffer.append(str)
                    }

                }

                if (System.currentTimeMillis() - lastTime > 250) { //addBuffer.length > 1000 ||
                    lastTime = System.currentTimeMillis()
                    //println(addBuffer.length)
                    if (errorPane.text.length + addBuffer.length > outputBufferLimit) {
                        val remaining = outputBufferLimit - addBuffer.length
                        if (remaining <= 0) {
                            errorPane.text = addBuffer.substring(addBuffer.length - outputBufferLimit)
                        }
                        else {
                            val existing = errorPane.text.substring(errorPane.text.length-remaining)
                            errorPane.text = existing +  addBuffer
                        }
                    } else {
                        errorPane.text += addBuffer
                    }
                    addBuffer.setLength(0)
                }
            }
        }

        toolbar.add(JButton(ideaIcon("/save", config.lightMode)).apply {
            toolTipText = "Save"
            addActionListener {
                save()
            }
        })
        toolbar.add(JButton(ideaIcon("/build", config.lightMode)).apply {
            toolTipText = "Build"
            addActionListener {
                build()
            }
        })
        toolbar.add(runButton)
        toolbar.add(JButton(ideaIcon("/debug", config.lightMode)).apply {
            toolTipText = "Debug"
            //InteractiveDebugger()
        })

        add(mainPanel)
    }

    fun initTheme() {
        setupFlatLafTheme(config)
        errorPane.background = UIManager.getDefaults().getColor("Panel.background")
        errorPane.foreground = UIManager.getDefaults().getColor("Panel.foreground")
    }

    fun ideaIcon(name: String, ligtMode: Boolean): Icon {
        return FlatSVGIcon(
            if (ligtMode) javaClass.getResource("$name.svg")
            else javaClass.getResource("${name}_dark.svg"))
    }

    fun save() {
        File(filename).writeText(textArea.text)
    }

    fun build(): Boolean {
        save()
        errorPane.text = ""
        errorPane.foreground = Color(225, 28, 28)
        val process = ProcessBuilder("asc", "microide.ts", "-o", "test.wasm", "--disable", "mutable-globals", "--disable", "sign-extension", "--disable", "nontrapping-f2i", "--disable", "bulk-memory", "--sourceMap").redirectErrorStream(true).start()
        //thread {
        process.inputStream.bufferedReader().forEachLine {
            println(it)
            errorPane.text += it + "\n"
        }
        //}
        val exitCode = process.waitFor()
        if (exitCode == 0) {
            errorPane.foreground = Color(92, 160, 92)
            errorPane.text += "Compilation finished without errors"
        }
        println("Compilation finished with exit code $exitCode")
        return exitCode == 0
    }
}
