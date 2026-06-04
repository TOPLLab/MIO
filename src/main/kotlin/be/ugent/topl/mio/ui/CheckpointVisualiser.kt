package be.ugent.topl.mio.ui

import WasmInfo
import be.ugent.topl.mio.debugger.Debugger
import be.ugent.topl.mio.woodstate.Checkpoint
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer


class CheckpointVisualiser(checkpoints: List<Checkpoint?>, binaryInfo: WasmInfo, debugger: Debugger) : JFrame("Checkpoints") {
    init {
        setSize(640, 480)
        minimumSize = Dimension(350, 100)
        val root = DefaultMutableTreeNode()
        for (t in checkpoints.indices) {
            val checkpoint = checkpoints[t]
            if (checkpoint != null) {
                val checkPointNode = CheckpointNode(t, binaryInfo, checkpoint)
                checkPointNode.add(DefaultMutableTreeNode("pc = 0x${checkpoint.snapshot.pc?.toString(16)}"))
                checkPointNode.add(DefaultMutableTreeNode("io = ${ObjectMapper().writer().writeValueAsString(checkpoint.snapshot.io)}"))
                checkPointNode.add(DefaultMutableTreeNode("memory = ${checkpoint.snapshot.memory}"))
                val neoPixelState = getNeoPixelColors(checkpoint.snapshot, "n")
                if (neoPixelState.size == 64) {
                    val neoPixelNode = DefaultMutableTreeNode("NeoPixel state")
                    neoPixelNode.add(NeoPixelNode(neoPixelState))
                    checkPointNode.add(neoPixelNode)
                }
                val neoPixelBufferState = getNeoPixelColors(checkpoint.snapshot, "b")
                if (neoPixelBufferState.size == 64) {
                    val neoPixelNode = DefaultMutableTreeNode("NeoPixel buffer")
                    neoPixelNode.add(NeoPixelNode(neoPixelBufferState))
                    checkPointNode.add(neoPixelNode)
                }
                root.add(checkPointNode)
            }
        }
        val tree = JTree(root)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    val path = tree.getPathForLocation(e.x, e.y)

                    if (path != null) {
                        tree.selectionPath = path
                        val node = path.lastPathComponent
                        if (node is CheckpointNode) {
                            val popupMenu = JPopupMenu()
                            val restoreItem = JMenuItem("Restore (Dangerous)")
                            restoreItem.addActionListener {
                                debugger.loadSnapshot(node.checkpoint.snapshot)
                            }
                            popupMenu.add(restoreItem)
                            popupMenu.show(tree, e.x, e.y)
                        }
                    }
                }
            }
        })

        val renderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?,
                value: Any?,
                sel: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ): Component {
                val default = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

                if (value is NeoPixelNode) {
                    return GridExample(value.colors)
                }

                return default
            }
        }
        tree.cellRenderer = renderer

        add(JScrollPane(tree))
    }

    fun getNeoPixelColors(snapshot: WOODDumpResponse, key: String): List<Color> {
        val colors = mutableListOf<Color>()
        for (ioElement in snapshot.io!!) {
            if (ioElement.key.startsWith(key)) {
                val pos = ioElement.key.substring(key.length).toInt()
                val r = ioElement.value.ushr(16) and 0xff
                val g = ioElement.value.ushr(8) and 0xff
                val b = ioElement.value and 0xff
                //println("$pos $r $g $b")
                colors.add(Color(r, g, b))
            }
        }
        return colors
    }

    fun strNeoPixelState(snapshot: WOODDumpResponse): String {
        val colors = getNeoPixelColors(snapshot, "n")
        /*for (row in 0 ..< 8) {
            for (col in 0 ..< 8) {
                val c = colors[row * 8 + col]
                print("${c!!.red.toString().padStart(3, ' ')} ${c!!.green.toString().padStart(3, ' ')} ${c!!.blue.toString().padStart(3, ' ')} | ")
            }
            println()
        }*/
        var result = ""
        for (row in 0 ..< 8) {
            for (col in 0 ..< 8) {
                val c = colors[row * 8 + col]
                if (c!!.red > 0 || c.green > 0 || c.blue > 0) {
                    result += "x"
                }
                else {
                    result += "."
                }
            }
            result += "\n"
        }
        return result
    }
}

data class NeoPixelNode(val colors: List<Color>) : DefaultMutableTreeNode("NeoPixel")
data class CheckpointNode(val t: Int, val binaryInfo: WasmInfo, val checkpoint: Checkpoint) : DefaultMutableTreeNode() {
    init {
        var str = "Checkpoint t = $t"
        if (checkpoint.fidx_called != null) {
            str += ", fidx = ${checkpoint.fidx_called}, ${binaryInfo.primitives[checkpoint.fidx_called].name}(${checkpoint.args?.joinToString(",")})"
        }
        userObject = str
    }

    override fun toString(): String {
        return userObject as String
    }
}

class GridExample(private val colors: List<Color>) : JPanel() {
    private val s = 32
    private val spacing = 2

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        for (row in 0..< 8) {
            for (col in 0..< 8) {
                g.color = colors[row * 8 + col]
                g.fillRoundRect(col * (s + spacing), row * (s + spacing), s, s, 5, 5)
            }
        }
    }

    override fun getPreferredSize(): Dimension {
        return Dimension((s + spacing) * 8, (s + spacing) * 8)
    }
}
