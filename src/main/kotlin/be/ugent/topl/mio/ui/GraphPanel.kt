package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.DeterministicPrimitiveNode
import be.ugent.topl.mio.debugger.MultiverseGraph
import be.ugent.topl.mio.debugger.MultiverseNode
import be.ugent.topl.mio.debugger.PrimitiveNode
import com.formdev.flatlaf.FlatLaf
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import java.awt.geom.Path2D
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.UIManager

class GraphPanel(private val graph: MultiverseGraph) : JPanel(),
    MouseListener, MouseMotionListener {
    private var selectionListeners = mutableListOf<() -> Unit>()
    init {
        addMouseListener(this)
        addMouseMotionListener(this)
    }
    private val textColour = UIManager.getDefaults().getColor("RadioButton.foreground")
    //private val borderColour = Color(125, 125, 125)
    private val borderColour = UIManager.getDefaults().getColor("CheckBox.icon.borderColor")
    private val primaryColour = UIManager.getDefaults().getColor("Panel.foreground")
    private val backgroundColour = UIManager.getDefaults().getColor("CheckBox.icon.background")
    private val secondaryColour = UIManager.getDefaults().getColor("Button.default.background") //javax.swing.UIManager.getDefaults().getColor("Button.default.focusColor")
    private val green = if (!FlatLaf.isLafDark()) Color(89, 158, 94) else Color(136, 207, 131)
    private val d = 20
    private val hSpace = 100
    private var renderedHeight = 500
    private var renderedWidth = 2000
    private val nodes = mutableListOf<Node>()
    var selectedNode: Node? = null
        private set

    // Panning
    private var startPos = Point(0, 0)
    var associatedScrollPane: JScrollPane? = null
    var allowSelection = true

    data class Node(val x: Int, val y: Int, val w: Int, val h: Int, val value: MultiverseNode)

    override fun getPreferredSize(): Dimension {
        return Dimension(renderedWidth, renderedHeight)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.stroke = BasicStroke(2.0f)

        drawPaths(g, width - 100, graph.rootNode)
        //drawGraphNew(g)
        println("Height ${leafCounter.getLeafCount(graph.rootNode)}")
    }

    val leafCounter = LeafCounter(graph)

    private fun drawGraphNew(g: Graphics2D) {
        drawNode(g, Point(10, 10), graph.rootNode)
        // Draw node in the middle of the children's height
    }

    private fun drawPaths(g: Graphics2D, width: Int, rootNode: MultiverseNode) {
        val xStart = g.fontMetrics.stringWidth(rootNode.displayName)/2
        val yPadding = 15
        renderedHeight = drawGraph(g, rootNode, x = xStart + 5, yPadding).second + yPadding
    }

    /*private fun drawGraphNonRecursive(g: Graphics2D, node: MultiverseNode, x: Int = 0, y: Int = 0) {
        val stack = mutableListOf(Triple<MultiverseNode, Int, Int>(node, x, y))
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val node = current.first
            val x = current.second
            val y = current.third

            val newPoints = mutableListOf<Point>()
            var currentHeight = 0
            for (child in node.children) {
                val l = if (child.edgeLength > node.edgeLength && child is PrimitiveNode) child.edgeLength else node.edgeLength
                stack.add(Triple(child, x + l, y + currentHeight))
                val result = drawGraph(g, child, x + l, y + currentHeight)
                currentHeight += result.second
                newPoints.add(result.first)
                renderedWidth = Integer.max(renderedWidth, x + node.edgeLength + 500)
            }

            currentHeight = Integer.max(40, currentHeight)

            val point = Point(x, y + currentHeight / 2 - d / 2)
            drawNodeAndEdges(g, point, node, newPoints)
            nodes.add(Node(point.x, point.y, d, d, node))

            val spacing = Integer.max(40, currentHeight)
            Pair(point, Integer.max(spacing, currentHeight))
        }
    }*/

    var collapsed = mutableMapOf<MultiverseNode, Boolean>()

    private fun drawGraph(g: Graphics2D, node: MultiverseNode, x: Int = 0, y: Int = 0): Pair<Point, Int> {
        if (node.children.size == 1 && node.displayName.isEmpty()) {
            val collapse = collapsed.getOrDefault(node, true)

            val stack = mutableListOf<MultiverseNode>()
            var x = x
            var currentNode = node
            while(currentNode.children.size == 1 && currentNode.displayName.isEmpty()) {
                if (!collapse) {
                    stack.add(currentNode)
                    x += currentNode.edgeLength
                }
                currentNode = currentNode.children.first()
            }
            // If collapsed, just put the first node on the render stack.
            if (collapse) {
                stack.add(node)
                x += node.edgeLength
            }

            val result = drawGraph(g, currentNode, x + currentNode.edgeLength, y)
            renderedWidth = Integer.max(renderedWidth, x + node.edgeLength + 500)
            var newPoint = result.first
            var currentHeight = result.second
            var point = Point(x, y + currentHeight / 2 - d / 2)
            currentHeight = Math.max(d, currentHeight)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                point = Point(x, y + currentHeight / 2 - d / 2)
                if (collapse) {
                    val textWidth = g.fontMetrics.stringWidth("+")
                    g.color = borderColour
                    g.drawString("+", point.x + d/2 - textWidth/2, point.y - 5)
                }
                drawNodeAndEdges(g, point, node, listOf(newPoint))
                nodes.add(Node(point.x, point.y, d, d, node))
                x -= node.edgeLength // Next node should be further forward
                newPoint = Point(x + node.edgeLength, y + currentHeight / 2 - d / 2)
            }

            return Pair(point, currentHeight)
        }

        val newPoints = mutableListOf<Point>()
        var currentHeight = 0
        for (child in node.children) {
            val l = if (child.edgeLength > node.edgeLength && child is PrimitiveNode) child.edgeLength else node.edgeLength
            val result = drawGraph(g, child, x + l, y + currentHeight)
            currentHeight += result.second
            newPoints.add(result.first)
            renderedWidth = Integer.max(renderedWidth, x + node.edgeLength + 500)
        }

        currentHeight = Integer.max(40, currentHeight)

        val point = Point(x, y + currentHeight / 2 - d / 2)
        drawNodeAndEdges(g, point, node, newPoints)
        nodes.add(Node(point.x, point.y, d, d, node))

        val spacing = Integer.max(40, currentHeight)
        return Pair(point, Integer.max(spacing, currentHeight))
    }

    /**
     * Draws one node and the edges to the children.
     */
    private fun drawNodeAndEdges(g: Graphics2D, point: Point, node: MultiverseNode, newPoints: List<Point>) {
        drawNode(g, point, node)
        for (i in newPoints.indices) {
            drawCurve(g, point, node, newPoints[i], node.children[i], i)
        }
    }

    private fun drawCurve(g: Graphics2D, point: Point, node: MultiverseNode, destinationPoint: Point, destinationNode: MultiverseNode, childIndex: Int) {
        if (selectedNodes.contains(node) && selectedNodes.contains(destinationNode)) {
            g.color = secondaryColour
            if (completedPath.contains(destinationNode)) {}
                g.color = green
        }
        curvedLine(point.x + d, point.y + d/2, destinationPoint.x, destinationPoint.y + d/2, g, if (childIndex < node.values.size) "${node.values[childIndex]}" else null)
        g.color = borderColour
    }

    private fun drawNode(g: Graphics2D, point: Point, node: MultiverseNode) {
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
            drawInnerSelectedNode(g, point)
        } else if (selectedNodes.contains(node)) {
            drawInnerPathNode(g, point, node)
        } else if (node === graph.currentNode) {
            drawInnerCurrentNode(g, point)
        }
        g.color = borderColour
    }

    private fun drawInnerSelectedNode(g: Graphics2D, point: Point) {
        g.color = secondaryColour
        g.fillOval(point.x, point.y, d, d) // Outer blue circle
        g.color = backgroundColour
        g.fillOval(point.x + 2, point.y + 2, d - 4, d - 4) // Inner white circle
        g.color = secondaryColour
        g.fillOval(point.x + 4, point.y + 4, d - 8, d - 8) // Inner blue circle
        g.color = primaryColour
    }

    private fun drawInnerPathNode(g: Graphics2D, point: Point, node: MultiverseNode) {
        g.color = secondaryColour
        if (completedPath.contains(node))
            g.color = green
        g.fillOval(point.x, point.y, d, d)
        g.color = primaryColour
    }

    private fun drawInnerCurrentNode(g: Graphics2D, point: Point) {
        g.color = secondaryColour
        g.fillOval(point.x, point.y, d, d)
        g.color = primaryColour
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
    }

    var selectedNodes = mutableSetOf<MultiverseNode>()
    var selectedPath: Pair<List<MultiverseNode>, List<MultiverseNode>>? = null
    var completedPath = mutableSetOf<MultiverseNode>()
    override fun mouseClicked(e: MouseEvent) {
        if (!allowSelection) {
            return
        }

        var selected: Node? = null
        for (node in nodes) {
            if (e.x > node.x && e.y > node.y && e.x < node.x + node.w && e.y < node.y + node.h) {
                selected = node
            }
        }
        if (selected == null) return

        if (e.button == MouseEvent.BUTTON3) {
            collapsed[selected.value] = !collapsed.getOrDefault(selected.value, true)
            repaint()
            return
        }

        selectedNode = selected

        //println(graph.rootNode.findPath(graph.currentNode, selectedValue!!))
        selectedPath = graph.rootNode.findPath(graph.currentNode, selectedValue!!)
        selectedNodes = selectedPath!!.first.toMutableSet()
        selectedNodes.addAll(selectedPath!!.second.toSet())

        selectionListeners.forEach { it() }
        repaint()
    }

    override fun mousePressed(p0: MouseEvent) {
        startPos = p0.point
    }

    override fun mouseReleased(p0: MouseEvent) {}

    override fun mouseEntered(p0: MouseEvent) {}

    override fun mouseExited(p0: MouseEvent) {}

    override fun mouseDragged(e: MouseEvent) {
        val delta = Point(e.x - startPos.x, e.y - startPos.y)
        associatedScrollPane?.horizontalScrollBar?.value -= delta.x
        associatedScrollPane?.verticalScrollBar?.value -= delta.y
    }

    override fun mouseMoved(e: MouseEvent) {
        cursor = Cursor(Cursor.DEFAULT_CURSOR)
        var hit = false
        for (node in nodes) {
            if (e.x > node.x && e.y > node.y && e.x < node.x + node.w && e.y < node.y + node.h) {
                hit = true
                break
            }
        }
        cursor = if(hit) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
    }
}