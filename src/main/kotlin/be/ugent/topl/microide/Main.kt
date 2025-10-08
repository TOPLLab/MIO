package be.ugent.topl.microide

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.connections.ProcessConnection
import be.ugent.topl.mio.connections.SerialConnection
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.ui.setupFlatLafTheme
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemInfo
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
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
    if (config.lightMode) System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua")
    else System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    val window = MainWindow()
    window.isVisible = true
}

class MainWindow(private val filename: String = "microide.ts") : JFrame("WARDuino Micro IDE - $filename") {
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
        val theme =
            if (!FlatLaf.isLafDark()) Theme.load(javaClass.getResourceAsStream("/light.xml"))
            else Theme.load(javaClass.getResourceAsStream("/dark.xml"))
        theme.apply(textArea)
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
                    conn.write("02\n".toByteArray(Charset.defaultCharset()))
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
                    if (config.useEmulator) {
                        connection = ProcessConnection(config.wdcliPath, "test.wasm", "--no-socket")
                    }
                    else {
                        val newConn = SerialConnection(config.port!!)
                        val debugger = Debugger(newConn)
                        debugger.updateModule("test.wasm")
                        debugger.run()
                        debugger.close()

                        connection = newConn
                    }
                    icon = stopIcon
                    toolTipText = "Stop"
                }
            }
        }

        thread {
            var addBuffer = ""
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
                        println(str)
                        addBuffer += str
                    }

                }

                if (addBuffer.length > 1000 || System.currentTimeMillis() - lastTime > 500) {
                    lastTime = System.currentTimeMillis()
                    errorPane.text += addBuffer
                    addBuffer = ""
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
        errorPane.foreground = Color.RED
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
