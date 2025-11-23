package be.ugent.topl.mio.ui

import be.ugent.topl.mio.DebuggerConfig
import com.fazecast.jSerialComm.SerialPort
import com.formdev.flatlaf.extras.FlatSVGIcon
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox

class PortBox(defaultPort: String? = null) : Box(BoxLayout.X_AXIS) {
    private val portComboBox = JComboBox<String>().apply {
        setAlignmentX(CENTER_ALIGNMENT)
        //maximumSize = Dimension(250, 500)
        for (port in SerialPort.getCommPorts()) {
            addItem(port.systemPortPath)
        }
        if (defaultPort != null) {
            selectedItem = defaultPort
        }
    }
    init {
        add(portComboBox)
        add(JButton(FlatSVGIcon(javaClass.getResource("/refresh.svg"))).apply {
            addActionListener {
                val currentItem = portComboBox.selectedItem as String
                portComboBox.removeAllItems()
                for (port in SerialPort.getCommPorts()) {
                    portComboBox.addItem(port.systemPortPath) // TODO: We can use the device name CONFIG_USB_DEVICE_PRODUCT
                }
                portComboBox.selectedItem = currentItem
            }
        })
    }

    val selectedPort: String?
        get() = portComboBox.selectedItem as String?
}