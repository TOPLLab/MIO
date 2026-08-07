package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.Debugger
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
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
        val stayAbove = JCheckBox("Stay on top", false).apply {
            addActionListener {
                isAlwaysOnTop = isSelected
            }
        }
        val emuCheckBox = JCheckBox("EMU Only", true)
        val optionBox = Box.createHorizontalBox().apply {
            add(emuCheckBox)
            add(stayAbove)
        }
        add(optionBox, BorderLayout.SOUTH)
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
        isAlwaysOnTop = stayAbove.isSelected
    }
}
