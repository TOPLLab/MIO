package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.DeterministicPrimitiveNode
import be.ugent.topl.mio.debugger.MultiverseGraph
import be.ugent.topl.mio.debugger.MultiverseNode
import be.ugent.topl.mio.debugger.PrimitiveNode
import com.formdev.flatlaf.FlatLaf
import java.awt.*
import java.awt.event.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class GraphPanel(private val graph: MultiverseGraph) : JPanel(),
    MouseListener, MouseMotionListener, MouseWheelListener {
    private var selectionListeners = mutableListOf<() -> Unit>()
    init {
        addMouseListener(this)
        addMouseMotionListener(this)
        addMouseWheelListener(this)
    }
    private val textColour = UIManager.getDefaults().getColor("RadioButton.foreground")
    //private val borderColour = Color(125, 125, 125)
    private val borderColour = UIManager.getDefaults().getColor("CheckBox.icon.borderColor")
    private val primaryColour = UIManager.getDefaults().getColor("Panel.foreground")
    private val backgroundColour = UIManager.getDefaults().getColor("CheckBox.icon.background")
    private val secondaryColour = UIManager.getDefaults().getColor("Button.default.background") //javax.swing.UIManager.getDefaults().getColor("Button.default.focusColor")
    val barHeight = UIManager.getInt("ScrollBar.width")
    private val green = if (!FlatLaf.isLafDark()) Color(89, 158, 94) else Color(136, 207, 131)
    private val d = 20
    private val hSpace = 100
    private var renderedHeight = 100
    private var renderedWidth = 100
    private val nodes = mutableListOf<Node>()
    var selectedNode: Node? = null
        private set
    private var currentNode: Node? = null
    private var lastCompleted: Node? = null

    // Panning
    private var startPos = Point(0, 0)
    var associatedScrollPane: JScrollPane? = null
    var allowSelection = true

    data class Node(val x: Int, val y: Int, val w: Int, val h: Int, val value: MultiverseNode)

    override fun getPreferredSize(): Dimension {
        return Dimension((renderedWidth * scaleFactor).toInt(), (renderedHeight * scaleFactor).toInt())
    }

    var xOffset = 0
    var yOffset = 0
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.stroke = BasicStroke(2.0f)

        g2.scale(scaleFactor, scaleFactor)
        g2.translate(-xOffset, -yOffset)

        renderedWidth = 0
        drawPaths(g, graph.rootNode)

        g2.translate(xOffset, yOffset)
        g2.scale(1/scaleFactor, 1/scaleFactor)

        drawMiniMap(g2)
    }

    fun drawMiniMap(g: Graphics2D) {
        //val scale = min(100.0/renderedHeight, width.toDouble()/renderedWidth)
        val scale = min(100.0/renderedHeight, (100.0 * width/height)/renderedWidth)
        //g.drawString("camera pos = ($xOffset, $yOffset)", 5, 10)

        val graphWidth = (renderedWidth  * scale).roundToInt()
        val offset = width - graphWidth

        // Zoom str
        if (scaleFactor != 1.0) {
            val zoomStr = "${(scaleFactor * 100).roundToInt()}%"
            val zoomStrWidth = getFontMetrics(g.font).stringWidth(zoomStr)
            g.color = Color(100, 100, 100, 150)
            g.drawString(zoomStr, width - zoomStrWidth - 5, 10 + 15)
        }

        g.color = UIManager.getColor("ScrollBar.track")
        g.fillRect(0, 0, width,barHeight)
        g.color = if(draggingScrollBar) UIManager.getColor("ScrollBar.pressedThumbColor") else UIManager.getColor("ScrollBar.thumb")
        g.fillRect(scrollBarPosition(), 0, scrollBarWidth(), barHeight)

        selectedNode?.let { node ->
            val xPos = (node.x.toDouble()/renderedWidth) * width
            g.color = secondaryColour
            g.fillRect(xPos.toInt(), 0, 3, barHeight)
        }

        currentNode?.let { node ->
            val xPos = (node.x.toDouble()/renderedWidth) * width
            g.color = Color.black
            g.fillRect(xPos.toInt(), 0, 3, barHeight)
        }

        lastCompleted?.let { node ->
            val xPos = (node.x.toDouble()/renderedWidth) * width
            g.color = green
            g.fillRect(xPos.toInt(), 0, 3, barHeight)
        }

        // Graph rectangle
        /*g.color = Color(100, 100, 100, 50)
        val rectangle = Rectangle(offset, 0, graphWidth, (renderedHeight * scale).roundToInt())
        g.fillRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
        g.color = Color(150, 150, 255, 150)
        val oldClip = g.clip // If we don't do this the component will be able to draw outside of itself.
        g.clip = rectangle
        val cameraPosX = (xOffset * scale).roundToInt()
        val cameraPosY = (yOffset * scale).roundToInt()
        val cameraWidth = (width/scaleFactor  * scale).roundToInt()
        val cameraHeight = (height/scaleFactor * scale).roundToInt()
        g.fillRect(offset + cameraPosX, cameraPosY, cameraWidth, cameraHeight)
        g.color = Color(150, 150, 255, 255)
        g.drawRect(offset + cameraPosX, cameraPosY, cameraWidth, cameraHeight)
        g.clip = oldClip*/
        /*g.color = Color(200, 100, 100, 255)
        g.drawString("C", cameraPosX, cameraPosY + 10)*/
        /*g.scale(scale, scale)
        drawPaths(g, graph.rootNode)
        g.scale(1/scale, 1/scale)*/
    }

    private fun scrollBarPosition(): Int {
        return ((xOffset.toDouble()/renderedWidth) * width).toInt()
    }

    private fun scrollBarWidth(): Int {
        return max(((width/scaleFactor)*width/renderedWidth).toInt(), 5)
    }

    fun saveImage(filename: String) {
        println("Full graph size $renderedWidth x $renderedHeight")
        val imageSize = 30000
        var imageWidth = min(imageSize, renderedWidth)
        var imageHeight = min(imageSize, renderedHeight)
        if (renderedWidth * renderedHeight < Integer.MAX_VALUE) {
            imageWidth = renderedWidth
            imageHeight = renderedHeight
        }
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            stroke = BasicStroke(2.0f)
        }
        g.color = backgroundColour
        g.fillRect(0, 0, renderedWidth, renderedHeight)
        println("Drawing multiverse tree...")
        drawPaths(g, graph.rootNode)
        println("Finished drawing, writing to file...")
        g.dispose()
        ImageIO.write(image, "png", File(filename))
        println("Finished writing")
    }

    private fun drawPaths(g: Graphics2D, rootNode: MultiverseNode) {
        val xStart = g.fontMetrics.stringWidth(rootNode.displayName)/2
        val yPadding = 15
        renderedHeight = drawGraph(g, rootNode, x = xStart + 5, yPadding).second + yPadding
    }

    private fun drawGraph(g: Graphics2D, node: MultiverseNode, x: Int = 0, y: Int = 0): Pair<Point, Int> {
        val newPoints = mutableListOf<Point>()
        var currentHeight = 0
        for (child in node.children) {
            val l = if (child.edgeLength > node.edgeLength && child is PrimitiveNode) child.edgeLength else node.edgeLength
            val result = drawGraph(g, child, x + l, y + currentHeight)
            currentHeight += result.second
            newPoints.add(result.first)
            renderedWidth = Integer.max(renderedWidth, x + node.edgeLength + d + 5)
        }

        currentHeight = Integer.max(40, currentHeight)

        val point = Point(x, y + currentHeight / 2 - d / 2)
        val textWidth = g.fontMetrics.stringWidth(node.displayName)/2
        g.color = textColour
        if (node is DeterministicPrimitiveNode) {
            g.drawString(node.displayName, point.x + node.edgeLength/2 - textWidth, point.y - 5)
        }
        else {
            g.drawString(node.displayName, point.x - textWidth, point.y - 5)
        }
        g.color = borderColour
        g.fillOval(point.x, point.y, d, d)
        g.color = backgroundColour
        g.fillOval(point.x + 1, point.y + 1, d - 2, d - 2)
        if (node === selectedValue) {
            g.color = secondaryColour
            g.fillOval(point.x, point.y, d, d) // Outer blue circle
            g.color = backgroundColour
            g.fillOval(point.x + 2, point.y + 2, d - 4, d - 4) // Inner white circle
            g.color = secondaryColour
            g.fillOval(point.x + 4, point.y + 4, d - 8, d - 8) // Inner blue circle
            g.color = primaryColour
        } else if (selectedNodes.contains(node)) {
            g.color = secondaryColour
            if (completedPath.contains(node)) {
                g.color = green
                if (node == completedPath.last()) {
                    lastCompleted = Node(point.x, point.y, d, d, node)
                }
            }
            g.fillOval(point.x, point.y, d, d)
            g.color = primaryColour
        } else if (node === graph.currentNode) {
            currentNode = Node(point.x, point.y, d, d, graph.currentNode)
            g.color = secondaryColour
            g.fillOval(point.x, point.y, d, d)
            g.color = primaryColour
        }
        g.color = borderColour
        for (i in newPoints.indices) {
            if (selectedNodes.contains(node) && selectedNodes.contains(node.children[i])) {
                g.color = secondaryColour
                if (completedPath.contains(node.children[i]))
                    g.color = green
            }
            curvedLine(point.x + d, point.y + d/2, newPoints[i].x, newPoints[i].y + d/2, g, if (i < node.values.size) "${node.values[i]}" else null)
            g.color = borderColour
        }
        nodes.add(Node(point.x, point.y, d, d, node))

        val spacing = Integer.max(40, currentHeight)
        return Pair(point, Integer.max(spacing, currentHeight))
    }

    private fun curvedLine(x1: Int, y1: Int, x2: Int, y2: Int, g: Graphics2D, str: String? = null): Path2D {
        val cx = x1 + (x2-x1)/2.toDouble()
        val cy = y1 + (y2-y1)/2.toDouble()

        val path = Path2D.Double()
        path.moveTo(x1.toDouble(), y1.toDouble())

        val yDiff = (y2 - y1) / 2

        val cpx = cx - 10
        val cpy = cy - yDiff
        val cpx2 = cx + 10
        val cpy2 = cy + yDiff
        /*if (y1 > y2) {
            path.curveTo(cpx, cpy2, cpx2, cpy, x2.toDouble(), y2.toDouble())
        } else {*/
        path.curveTo(cpx, cpy, cpx2, cpy2, x2.toDouble(), y2.toDouble())
        //}

        g.draw(path)
        if (str != null) {
            val textWidth = g.fontMetrics.stringWidth(str)
            val bounds = g.fontMetrics.getStringBounds(str, g)
            val textHeight = font.createGlyphVector(g.fontMetrics.fontRenderContext, str).visualBounds.height
            //g.fillOval((cx - 5).toInt(), (cy - 5).toInt(), 10, 10)
            val oldColor = g.color
            g.color = UIManager.getDefaults().getColor("Panel.background")
            //g.color = Color.RED
            val padding = 4
            g.fillRoundRect((cx - textWidth/2).toInt() - padding/2, (cy + textHeight/2).toInt() - textHeight.toInt() - padding/2, bounds.width.toInt() + padding, textHeight.toInt() + padding, 10, 10)
            g.color = textColour
            g.drawString(str, (cx - textWidth/2).toInt(), (cy + textHeight/2).toInt())
        }

        /*g.fillOval(x1, y1, 10, 10)
        g.fillOval((x1 + (x2-x1)/2.toDouble()).toInt(), (y1 + (y2-y1)/2.toDouble()).toInt(), 10, 10)*/
        //g.fillOval((x1 + (x2-x1).toDouble()).toInt(), (y1 + (y2-y1).toDouble()).toInt(), 10, 10)

        return path
    }

    val selectedValue: MultiverseNode?
        get() = selectedNode?.value

    fun addSelectionListener(listener: () -> Unit) {
        selectionListeners.add(listener)
    }

    fun clearSelection() {
        selectedPath = null
        selectedNodes.clear()
        selectedNode = null
        lastCompleted = null
    }

    var selectedNodes = mutableSetOf<MultiverseNode>()
    var selectedPath: Pair<List<MultiverseNode>, List<MultiverseNode>>? = null
    var completedPath = mutableSetOf<MultiverseNode>()
    override fun mouseClicked(e: MouseEvent) {
        if (e.button == MouseEvent.BUTTON3) {
            JPopupMenu().apply {
                val saveItem = JMenuItem("Save as image").apply {
                    addActionListener {
                        val chooser = JFileChooser()
                        chooser.fileFilter = FileNameExtensionFilter("png", "png")
                        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                            var filename = chooser.selectedFile.absolutePath
                            if (!filename.endsWith(".png")) {
                                filename += ".png"
                            }
                            saveImage(filename)
                        }
                    }
                }
                add(saveItem)

            }.show(this, e.x, e.y)
            return
        }

        if (e.y < barHeight) {
            xOffset = ((e.x.toDouble()/width) * renderedWidth).roundToInt() - width/2
            repaint()
            return
        }

        if (!allowSelection) {
            return
        }

        val x = e.x/scaleFactor + xOffset
        val y = e.y/scaleFactor + yOffset
        for (node in nodes) {
            if (x > node.x && y > node.y && x < node.x + node.w && y < node.y + node.h) {
                selectedNode = node
            }
        }
        if (selectedNode == null) return

        println(graph.rootNode.findPath(graph.currentNode, selectedValue!!))
        selectedPath = graph.rootNode.findPath(graph.currentNode, selectedValue!!)
        selectedNodes = selectedPath!!.first.toMutableSet()
        selectedNodes.addAll(selectedPath!!.second.toSet())

        selectionListeners.forEach { it() }
        repaint()
    }

    override fun mousePressed(p0: MouseEvent) {
        println("Mouse pressed")
        startPos = p0.point
    }

    private var draggingScrollBar = false
    override fun mouseReleased(e: MouseEvent) {
        println("Mouse released")
        if (draggingScrollBar) {
            draggingScrollBar = false
            repaint()
        }
        if (e.button != MouseEvent.BUTTON1) {
            mouseClicked(e)
            e.consume()
            return
        }
    }

    override fun mouseEntered(p0: MouseEvent) {}

    override fun mouseExited(p0: MouseEvent) {}

    override fun mouseDragged(e: MouseEvent) {
        println("Mouse dragged")
        if (e.button != MouseEvent.BUTTON1) {
            e.consume()
            return
        }

        val delta = Point(e.x - startPos.x, e.y - startPos.y)
        startPos = Point(e.x, e.y)

        val pos = scrollBarPosition()
        val w = scrollBarWidth()
        if (e.y < barHeight || draggingScrollBar) {
            if ((e.x >= pos && e.x < pos + w) || draggingScrollBar) {
                xOffset += ((delta.x.toDouble()/width) * renderedWidth).roundToInt()
                draggingScrollBar = true
                repaint()
            }
            e.consume()
            return
        }

        println("" + e.x + " " + e.y)
        /*associatedScrollPane?.horizontalScrollBar?.value -= delta.x
        associatedScrollPane?.verticalScrollBar?.value -= delta.y*/
        xOffset -= (delta.x / scaleFactor).toInt()
        yOffset -= (delta.y / scaleFactor).toInt()
        repaint()
    }

    override fun mouseMoved(e: MouseEvent) {
        cursor = Cursor(Cursor.DEFAULT_CURSOR)
        var hit = false
        for (node in nodes) {
            val x = e.x/scaleFactor + xOffset
            val y = e.y/scaleFactor + yOffset
            if (x > node.x && y > node.y && x < node.x + node.w && y < node.y + node.h) {
                hit = true
                break
            }
        }
        cursor = if(hit) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
    }

    private var scaleFactor = 1.0

    override fun mouseWheelMoved(e: MouseWheelEvent) {
        val oldScaleFactor = scaleFactor
        val adjustment = e.wheelRotation / 20.0
        scaleFactor -= adjustment
        scaleFactor = kotlin.math.max(0.1, scaleFactor)
        /*val oldW = height * oldScaleFactor
        val newW = height * scaleFactor
        val delta = (newW - oldW)/scaleFactor
        yOffset += (delta/2).toInt()*/

        val oldH = height/oldScaleFactor
        val newH = height/scaleFactor
        val deltaH = (newH - oldH)
        yOffset -= (deltaH/2).toInt()

        val oldW = width/oldScaleFactor
        val newW = width/scaleFactor
        val deltaW = (newW - oldW)
        xOffset -= (deltaW/2).toInt()

        /*associatedScrollPane?.horizontalScrollBar?.value = ((associatedScrollPane?.horizontalScrollBar?.value!! / oldScaleFactor) * scaleFactor).toInt()
        associatedScrollPane?.verticalScrollBar?.value = ((associatedScrollPane?.verticalScrollBar?.value!! / oldScaleFactor) * scaleFactor).toInt()*/
        println("Scale = $scaleFactor")
        repaint()

        /*associatedScrollPane?.verticalScrollBar?.revalidate()
        associatedScrollPane?.horizontalScrollBar?.revalidate()*/
    }
}