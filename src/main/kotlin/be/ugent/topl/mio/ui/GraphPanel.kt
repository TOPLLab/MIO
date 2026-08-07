package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.MultiverseGraph
import be.ugent.topl.mio.debugger.MultiverseNode
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.util.UIScale
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
    private val borderColour = if (!FlatLaf.isLafDark()) Color(195, 195, 195) else Color(70, 76, 80)
    private val primaryColour = UIManager.getDefaults().getColor("Panel.foreground")
    private val backgroundColour = UIManager.getDefaults().getColor("CheckBox.icon.background")
    private val secondaryColour = UIManager.getDefaults().getColor("Button.default.background") //javax.swing.UIManager.getDefaults().getColor("Button.default.focusColor")
    val barHeight = UIManager.getInt("ScrollBar.width")
    private val green = if (!FlatLaf.isLafDark()) Color(89, 158, 94) else Color(136, 207, 131)
    private val d = 20
    private val detEdgeLength = 30
    private val collapsedDetEdgeLength = 100
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
    var allowSelection = true
    private var scaleFactor = UIScale.getUserScaleFactor().toDouble()

    data class NodeLocation(val node: MultiverseNode, val instructionOffset: Int)
    data class Node(val x: Int, val y: Int, val w: Int, val h: Int, val value: NodeLocation)

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
        g2.font = g2.font.deriveFont(g2.font.size / UIScale.getUserScaleFactor())

        renderedWidth = 0
        drawPaths(g, graph.rootNode)

        g2.translate(xOffset, yOffset)
        g2.scale(1/scaleFactor, 1/scaleFactor)

        drawMiniMap(g2)
    }

    fun drawMiniMap(g: Graphics2D) {
        // Zoom str
        val scalePercentage = scaleFactor / UIScale.getUserScaleFactor().toDouble()
        if (scalePercentage != 1.0) {
            val zoomStr = "${(scalePercentage * 100).roundToInt()}%"
            val zoomStrWidth = getFontMetrics(g.font).stringWidth(zoomStr)
            g.color = Color(100, 100, 100, 150)
            g.drawString(zoomStr, width - zoomStrWidth - 5, 10 + 15)
        }

        g.color = UIManager.getColor("ScrollBar.track")
        g.fillRect(0, 0, width,barHeight)
        g.color = when (draggingScrollBar) {
            MouseState.None -> UIManager.getColor("ScrollBar.thumb")
            MouseState.Hover -> UIManager.getColor("ScrollBar.hoverThumbColor")
            MouseState.Pressed -> UIManager.getColor("ScrollBar.pressedThumbColor")
        }
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
    }

    private fun scrollBarPosition(): Int {
        return ((xOffset.toDouble()/renderedWidth) * width).toInt()
    }

    private fun scrollBarWidth(): Int {
        return max(((width/scaleFactor)*width/renderedWidth).toInt(), 5)
    }

    fun saveImage(filename: String, scale: Double = 4.0) {
        val renderedWidth = (renderedWidth * scale).toInt()
        val renderedHeight = (renderedHeight * scale).toInt()
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
        g.scale(scale, scale)
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
        val result = drawGraph(g, rootNode, x = xStart + 5, yPadding)
        renderedHeight = result.second + yPadding

        //val result = Triple(Point(0, 0), 0, 0)
        drawNode(g, NodeLocation(graph.rootNode, 0),result.first)
    }

    private fun setLineColor(g: Graphics2D, sourceNode: MultiverseNode, destNode: MultiverseNode, nodeOffset: Int = -1) {
        g.color = borderColour
        if (selectedNodes.contains(sourceNode) && selectedNodes.contains(destNode)) {
            if (!inSelectedPath(NodeLocation(destNode, nodeOffset))) {
                return
            }

            g.color = secondaryColour
            if (completedPath.contains(destNode)) {
                g.color = green
            }
        }
    }

    /**
     * This function determines if a certain point in [offsetNode] at [instructionOffset] is part of the selected path or not.
     *
     * @return A boolean indicating if it is part of the selected path or not.
     */
    private fun inSelectedPath(offsetNode: NodeLocation): Boolean {
        if (selectedValue == null || !selectedNodes.contains(offsetNode.node)) {
            return false
        }

        // Only works in one particular direction, not with stepping back?
        if (offsetNode.node == selectedValue!!.node && offsetNode.instructionOffset >= selectedValue!!.instructionOffset) {
            return false
        }

        if (offsetNode.node == graph.currentNode &&
            (selectedValue!!.node != offsetNode.node || selectedValue!!.instructionOffset > graph.instructionOffset) &&
            offsetNode.instructionOffset < graph.instructionOffset) {
            return false
        }

        return true
    }

    private fun drawGraph(g: Graphics2D, node: MultiverseNode, x: Int = 0, y: Int = 0): Triple<Point, Int, Int> {
        //println("Draw subgraph at $x")
        val newPoints = mutableListOf<Point>()
        var currentHeight = 0
        //val collapsed = true && graph.currentNode != node // TODO: make this a node property
        //val collapsed = true
        val collapsed = true
        val count = if (collapsed) min(1, node.totalInstrExecuted) else node.totalInstrExecuted
        val edgeLength = if (collapsed) collapsedDetEdgeLength else detEdgeLength
        val widthConsumed = count * (edgeLength + d)
        for (child in node.children) {
            val result = drawGraph(g, child, x + widthConsumed + child.edgeLength, y + currentHeight)
            currentHeight += result.second
            newPoints.add(result.first)
            // Some paths may be longer than others, the longest one is the width of the full graph.
            renderedWidth = Integer.max(renderedWidth, result.third)
            // Draw the first node of these paths
            // Before: --O--O
            // After: O--O--O
            drawNode(g, NodeLocation(child, 0), result.first)
        }
        currentHeight = Integer.max(40, currentHeight)

        // Connect to the subgraphs.
        g.color = borderColour
        for (i in newPoints.indices) {
            val point = Point(x + widthConsumed, y + currentHeight / 2 - d / 2)
            setLineColor(g, node, node.children[i])
            curvedLine(point.x, point.y + d/2, newPoints[i].x, newPoints[i].y + d/2, g, if (i < node.values.size) "${node.values[i]}" else null)
            g.color = borderColour

            // Draw label for the previous point (Primitive name + arguments)
            //Before:
            //           -----O----O
            //After:  read(5)
            //           -----O----O
            val textWidth = g.fontMetrics.stringWidth(node.children[i].displayName)/2
            g.color = textColour
            g.drawString(node.children[i].displayName, point.x + d/2 - textWidth, point.y - 5)
        }

        // We have drawn all the children and the connecting edges, now draw ourselves.
        // Draw all trailing deterministic instructions.
        //println("Draw ${count} trailing nodes at $x")
        var currentX = x
        repeat(count) { offset ->
            val prevPoint = Point(currentX, y + currentHeight / 2 - d / 2)
            currentX += edgeLength + d
            val point = Point(currentX, y + currentHeight / 2 - d / 2)
            g.color = borderColour
            // If the node itself is part of the selected path it will get a color if the offset is below the selected
            // offset.
            setLineColor(g, node, node, offset)
            if (collapsed) {
                curvedLine(prevPoint.x + d, prevPoint.y + d/2, point.x, point.y + d/2, g, "${node.totalInstrExecuted} instr", borderColour)
            }
            else {
                curvedLine(prevPoint.x + d, prevPoint.y + d/2, point.x, point.y + d/2, g)
            }
            var instructionOffset = if (collapsed) node.totalInstrExecuted else offset + 1
            drawNode(g, NodeLocation(node, instructionOffset), point)
        }

        val prevPoint = Point(x, y + currentHeight / 2 - d / 2)
        return Triple(prevPoint, currentHeight, currentX)

        /*val point = Point(x, y + currentHeight / 2 - d / 2)
        val textWidth = g.fontMetrics.stringWidth(node.displayName)/2
        g.color = textColour
        g.drawString(node.displayName, point.x + d/2 - textWidth, point.y - 5)

        // TODO: Remove, debug only
        val instrCountStr = "#instrs = ${node.instrExecuted}"
        g.drawString(instrCountStr, point.x + d/2 - g.fontMetrics.stringWidth(instrCountStr)/2, point.y + d + g.fontMetrics.getStringBounds(instrCountStr, g).height.toInt())

        drawNode(g, node, point)

        // Connect the newly drawn node with all children.
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
        // Returns the new point, the vertical height used and the horizontal height used.
        return Triple(point, Integer.max(spacing, currentHeight), node.edgeLength + d + 5)*/
    }

    /**
     * Draw the node itself, depending on if it is the current node, selected or completed, it will have different
     * colors.
     */
    private fun drawNode(g: Graphics2D, node: NodeLocation, point: Point) {
        //println("Draw node at $point")
        g.color = borderColour
        g.fillOval(point.x, point.y, d, d)
        g.color = backgroundColour
        g.fillOval(point.x + 1, point.y + 1, d - 2, d - 2)
        if (node == selectedValue) {
            g.color = secondaryColour
            g.fillOval(point.x, point.y, d, d) // Outer blue circle
            g.color = backgroundColour
            g.fillOval(point.x + 2, point.y + 2, d - 4, d - 4) // Inner white circle
            g.color = secondaryColour
            g.fillOval(point.x + 4, point.y + 4, d - 8, d - 8) // Inner blue circle
            g.color = primaryColour
        } else if (inSelectedPath(node)) {
            g.color = secondaryColour
            /*if (completedPath.contains(node)) {
                g.color = green
                if (node == completedPath.last()) {
                    lastCompleted = Node(point.x, point.y, d, d, node)
                }
            }*/
            g.fillOval(point.x, point.y, d, d)
            g.color = primaryColour
        } else if (node.node === graph.currentNode && node.instructionOffset == graph.instructionOffset) {
            currentNode = Node(point.x, point.y, d, d, GraphPanel.NodeLocation(graph.currentNode, graph.instructionOffset))
            g.color = secondaryColour
            g.fillOval(point.x, point.y, d, d)
            g.color = primaryColour
        }

        // Update nodes for selection
        nodes.add(Node(point.x, point.y, d, d, node))
    }

    private fun curvedLine(x1: Int, y1: Int, x2: Int, y2: Int, g: Graphics2D, str: String? = null, textColour: Color = this.textColour): Path2D {
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

    val selectedValue: NodeLocation?
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
    var reset = true
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

        //selectedPath = graph.rootNode.findPath(graph.currentNode, selectedValue!!)
        if (graph.currentNode.findPath(selectedValue!!.node).isEmpty() ||
            (graph.currentNode == selectedValue!!.node && graph.instructionOffset > selectedValue!!.instructionOffset)) {
            selectedPath = Pair(listOf(), graph.rootNode.findPath(selectedValue!!.node))
            reset = true
        }
        else {
            selectedPath = Pair(listOf(), graph.currentNode.findPath(selectedValue!!.node))
            reset = false
        }
        selectedNodes = selectedPath!!.first.toMutableSet()
        selectedNodes.addAll(selectedPath!!.second.toSet())

        selectionListeners.forEach { it() }
        repaint()
    }

    override fun mousePressed(p0: MouseEvent) {
        //println("Mouse pressed")
        startPos = p0.point
    }

    enum class MouseState {
        None,
        Hover,
        Pressed
    }
    private var draggingScrollBar = MouseState.None
    override fun mouseReleased(e: MouseEvent) {
        //println("Mouse released")
        if (draggingScrollBar == MouseState.Pressed) {
            draggingScrollBar = MouseState.None
            repaint()
        }
        if (e.button != MouseEvent.BUTTON1) {
            mouseClicked(e)
            e.consume()
            return
        }
    }

    override fun mouseEntered(e: MouseEvent) {}

    override fun mouseExited(e: MouseEvent) {
        if (draggingScrollBar == MouseState.Hover) {
            draggingScrollBar = MouseState.None
            repaint()
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        //println("Mouse dragged")
        if (e.button != MouseEvent.BUTTON1) {
            e.consume()
            return
        }

        val delta = Point(e.x - startPos.x, e.y - startPos.y)
        startPos = Point(e.x, e.y)

        val pos = scrollBarPosition()
        val w = scrollBarWidth()
        val dragging = draggingScrollBar == MouseState.Pressed
        if (e.y < barHeight || dragging) {
            if ((e.x >= pos && e.x < pos + w) || dragging) {
                xOffset += ((delta.x.toDouble()/width) * renderedWidth).roundToInt()
                draggingScrollBar = MouseState.Pressed
                repaint()
            }
            e.consume()
            return
        }

        //println("" + e.x + " " + e.y)
        xOffset -= (delta.x / scaleFactor).toInt()
        yOffset -= (delta.y / scaleFactor).toInt()
        repaint()
    }

    override fun mouseMoved(e: MouseEvent) {
        // Scrollbar hover
        val pos = scrollBarPosition()
        val w = scrollBarWidth()
        if (e.y < barHeight && e.x >= pos && e.x < pos + w) {
            draggingScrollBar = MouseState.Hover
            repaint()
            e.consume()
            return
        } else if (draggingScrollBar == MouseState.Hover) {
            draggingScrollBar = MouseState.None
            repaint()
        }

        // Use hand cursor for clicking on nodes.
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

    override fun mouseWheelMoved(e: MouseWheelEvent) {
        val oldScaleFactor = scaleFactor
        val adjustment = e.wheelRotation / 20.0
        scaleFactor -= adjustment
        scaleFactor = max(0.1, scaleFactor)

        val oldH = height/oldScaleFactor
        val newH = height/scaleFactor
        val deltaH = (newH - oldH)
        yOffset -= (deltaH/2).toInt()

        val oldW = width/oldScaleFactor
        val newW = width/scaleFactor
        val deltaW = (newW - oldW)
        xOffset -= (deltaW/2).toInt()

        repaint()
    }
}