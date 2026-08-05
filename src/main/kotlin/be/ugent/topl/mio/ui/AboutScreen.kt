package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import be.ugent.topl.mio.configureFlatLafTheme
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.util.SystemInfo
import com.formdev.flatlaf.util.UIScale
import java.awt.Desktop
import java.awt.Dimension
import javax.swing.*


open class AboutScreen(protected val config: DebuggerConfig) : JFrame() {
    init {
        val fullWindowContents = configureTheme()
        setSize(UIScale.scale(450), UIScale.scale(350) + if (!fullWindowContents) UIScale.scale(20) else 0)
        minimumSize = Dimension(UIScale.scale(450), UIScale.scale(350))
        val mainPanel = JPanel()
        mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.Y_AXIS))
        mainPanel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        mainPanel.add(Box.createVerticalGlue())
        mainPanel.add(JLabel(FlatSVGIcon(javaClass.getResource("/MIO_Logo2.svg"))).apply {
            setAlignmentX(CENTER_ALIGNMENT)
        })
        mainPanel.add(JLabel("MIO Debugger").apply {
            setAlignmentX(CENTER_ALIGNMENT)
            putClientProperty( "FlatLaf.style", "font: 250% \$semibold.font")
        })
        mainPanel.add(JLabel("for WARDuino").apply {
            setAlignmentX(CENTER_ALIGNMENT)
            putClientProperty( "FlatLaf.style", "font: 160% \$light.font")
        })
        addOptions(mainPanel)
        mainPanel.add(Box.createVerticalGlue())
        add(mainPanel)
    }

    protected open fun addOptions(mainPanel: JPanel) {
        mainPanel.add(JLabel("Copyright © 2023-2025 TOPL@Ghent University").apply {
            setAlignmentX(CENTER_ALIGNMENT)
        })
    }

    private fun configureTheme(): Boolean {
        if (SystemInfo.isMacFullWindowContentSupported) {
            rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
        if (SystemInfo.isMacOS) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler {
                    AboutScreen(config).apply {
                        setLocationRelativeTo(null)
                        isVisible = true
                    }
                }
            }
        }
        configureFlatLafTheme(config)
        return SystemInfo.isMacFullWindowContentSupported
    }
}
