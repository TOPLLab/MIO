package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.Debugger
import java.awt.BorderLayout
import java.awt.Checkbox
import java.awt.Dimension
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.text.DefaultCaret
import javax.swing.text.StyleContext

class ConsoleWindow(debugger: Debugger) : JFrame("Console") {
    init {
        minimumSize = Dimension(300, 200)
        val textArea = JTextArea().apply {
            isEditable = false
        }
        val st = StyleContext.getDefaultStyleContext()
        textArea.font = st.getFont(Font.MONOSPACED, Font.PLAIN, 13)
        val scrollPane = JScrollPane(textArea)
        val caret = textArea.caret as DefaultCaret
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE)
        layout = BorderLayout()
        add(scrollPane, BorderLayout.CENTER)
        val emuCheckBox = JCheckBox("EMU Only", true)
        add(emuCheckBox, BorderLayout.SOUTH)
        debugger.printListener = { msg ->
            if (emuCheckBox.isSelected) {
                if (msg.startsWith("EMU: ")) {
                    textArea.text += msg.substring(5) + "\n"
                }
            }
            else {
                textArea.text += msg + "\n"
            }
        }
        isAlwaysOnTop = true
    }
}
