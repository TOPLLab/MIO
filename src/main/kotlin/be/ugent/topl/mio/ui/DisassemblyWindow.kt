package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Color
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import javax.swing.JFrame

class DisassemblyWindow(debugger: Debugger, wasmFile: String) : JFrame("Disassembly") {
    private val disassemblyTextArea = RSyntaxTextArea(disassemble(wasmFile)).apply {
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86
        isEditable = false
    }
    private val scrollPane = RTextScrollPane(disassemblyTextArea).apply {
        isIconRowHeaderEnabled = true
        gutter.iconRowHeaderInheritsGutterBackground = true
    }
    private val lineIcon = FlatSVGIcon(javaClass.getResource("/debug-stackframe.svg"))

    init {
        add(scrollPane)
        minimumSize = Dimension(200, 200)
        preferredSize = Dimension(400, 600)
        isVisible = true

        highlightCurrentLine(debugger.getCurrentState())

        val listener = this::highlightCurrentLine
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                debugger.removeCurrentStateListener(listener)
            }
        })
        debugger.registerCurrentStateListener(listener)
    }

    private fun highlightCurrentLine(currentState: WOODDumpResponse) {
        // IDEA: Would maybe be fun to highlight the instructions associated with the current line with the same color.
        val pc = currentState.pc!!
        val lines = disassemblyTextArea.text.lines()
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            // TODO: Use binary search instead of just linearly searching the address
            if (line.trim().startsWith(String.format("%06x", pc))) {
                disassemblyTextArea.removeAllLineHighlights()
                disassemblyTextArea.addLineHighlight(lineIndex, if (!FlatLaf.isLafDark()) Color(255, 255, 186, 255) else Color(207, 207, 131, 75))

                scrollPane.gutter.removeAllTrackingIcons()
                scrollPane.gutter.addLineTrackingIcon(lineIndex, lineIcon, "Current pc")
            }
        }
    }
}

fun disassemble(wasmFile: String): String {
    try {
        val command = listOf("wasm-objdump", "-d", wasmFile)
        println("Running command: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val result = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        process.waitFor()
        val startString = "Code Disassembly:\n\n"
        return result.substring(result.indexOf(startString) + startString.length)
    } catch (e: IOException) {
        // Catch exception and add additional information.
        throw IOException("An error occurred when attempting to disassemble the program, make sure the The WebAssembly Binary Toolkit is installed on your system.\n" + e.message)
    }
}
