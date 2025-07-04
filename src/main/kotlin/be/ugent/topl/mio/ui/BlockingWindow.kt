package be.ugent.topl.mio.ui

import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import kotlin.concurrent.thread


class BlockingWindow(parent: JFrame?, actionTitle: String = "Please wait") : JDialog(parent, "Performing operation", false) {
    init {
        val p = JProgressBar()
        p.isIndeterminate = true
        val panel = JPanel().apply {
            border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
        }
        panel.layout = BorderLayout(3, 3)
        panel.add(p)
        panel.add(JLabel(actionTitle), BorderLayout.NORTH)
        add(panel)
        minimumSize = Dimension(200, 65)
        isResizable = false
        isVisible = false
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE)
    }
    private val t = Timer(1000) {
        isVisible = true
    }.apply {
        isRepeats = false
    }

    fun <T> run(action: () -> T, after: (T) -> Unit = {}) {
        thread {
            t.start()
            val r = action()
            //dispatchEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING))
            t.stop()
            isVisible = false
            dispose()
            after(r)
        }
    }
}
