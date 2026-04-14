package be.ugent.topl.mio.ui

import com.formdev.flatlaf.util.SystemInfo
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import kotlin.concurrent.thread


class BlockingWindow(parent: JFrame?, actionTitle: String = "Please wait") : JDialog(parent, "Performing operation", false) {
    init {
        val p = JProgressBar()
        p.isIndeterminate = true
        val panel = JPanel().apply {
            val spacing = 10
            border = BorderFactory.createEmptyBorder(spacing, spacing, spacing, spacing)
        }
        panel.layout = BorderLayout(10, 10)
        panel.add(p)
        panel.add(JLabel(actionTitle), BorderLayout.NORTH)
        if (SystemInfo.isMacFullWindowContentSupported) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true)
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true)
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false)
            val box = Box.createVerticalBox()
            box.add(Box.createVerticalStrut(25))
            box.add(panel)
            add(box)
        }
        else {
            add(panel)
        }
        minimumSize = Dimension(225, 85)
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
