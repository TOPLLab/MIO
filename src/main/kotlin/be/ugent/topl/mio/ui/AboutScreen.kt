package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.formdev.flatlaf.util.SystemInfo
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Image
import javax.swing.*


open class AboutScreen(protected val config: DebuggerConfig, w: Int = 400, h: Int = 300) : JFrame() {
    init {
        configureTheme()
        setSize(w, h)
        minimumSize = Dimension(400, 300)
        val mainPanel = JPanel()
        mainPanel.setLayout(BoxLayout(mainPanel, BoxLayout.Y_AXIS))
        mainPanel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        mainPanel.add(Box.createVerticalGlue())
        mainPanel.add(JLabel(ImageIcon(ImageIcon(this.javaClass.getResource("/warduino-logo.png")).image.getScaledInstance(100, 100, Image.SCALE_SMOOTH))).apply {
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

    private fun configureTheme() {
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
                        isVisible = true
                    }
                }
            }
        }
        if (config.lightMode) {
            if (SystemInfo.isMacOS) FlatMacLightLaf.setup()
            else FlatIntelliJLaf.setup()
        }
        else {
            if (SystemInfo.isMacOS) {
                FlatLaf.registerCustomDefaultsSource( "themes")
                FlatMacDarkLaf.setup()
            }
            else FlatDarkLaf.setup()
        }
    }
}
