package be.ugent.topl.mio.debugger

import WasmBinary
import be.ugent.topl.mio.concolic.analyse
import be.ugent.topl.mio.concolic.processPaths
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.woodstate.Checkpoint
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import be.ugent.topl.mio.woodstate.WasmStackValue

class MultiverseGraph(var rootNode: MultiverseNode = MultiverseNode(), var currentNode: MultiverseNode = rootNode) {
    fun replaceCurrentNode(newNode: MultiverseNode) {
        if (currentNode == rootNode) {
            rootNode = newNode
            currentNode = newNode
            return
        }

        currentNode.parent!!.children.remove(currentNode)
        currentNode.parent!!.addChild(newNode)
        currentNode = newNode
    }

    fun replaceNode(node: MultiverseNode, newNode: MultiverseNode) {
        if (node == rootNode) {
            rootNode = newNode
        } else {
            node.parent!!.children.remove(node)
            node.parent!!.addChild(newNode)
        }

        for (child in node.children) {
            newNode.addChild(child)
        }
        val existingValues = newNode.values.toList()
        newNode.values.clear()
        newNode.values.addAll(node.values)
        for (value in existingValues) {
            if (!newNode.values.contains(value)) {
                newNode.values.add(value)
            }
        }
    }

    fun removeLastNode(): MultiverseNode {
        val last = currentNode.parent
        last!!.children.remove(currentNode)
        currentNode = last
        return currentNode
    }

    fun reset() {
        currentNode = MultiverseNode()
        rootNode = currentNode
    }
}

open class MultiverseNode(val children: MutableList<MultiverseNode> = mutableListOf(), val values: MutableList<Int> = mutableListOf(), var parent: MultiverseNode? = null) {
    open val displayName: String
        get() = ""

    open val edgeLength: Int
        get() = 30

    private fun findPath(n: MultiverseNode, path: MutableList<MultiverseNode>): Boolean {
        if (this == n)
            return true

        for (node in children) {
            path.add(node)
            val result = node.findPath(n, path)
            if (result)
                return true
            path.remove(node)
        }
        return false
    }

    fun findPath(n: MultiverseNode): MutableList<MultiverseNode> {
        val path = mutableListOf(this)
        if (!findPath(n, path)) return mutableListOf()
        return path
    }

    fun findPath(start: MultiverseNode, end: MultiverseNode): Pair<List<MultiverseNode>, List<MultiverseNode>> {
        val pathA = findPath(start)
        val pathB = findPath(end)
        var shortendPathA = pathA.toMutableList()
        var shortendPathB = pathB.toMutableList()
        if (pathA.size > pathB.size) {
            shortendPathA = pathA.subList(0, pathB.size)
        } else {
            shortendPathB = pathB.subList(0, pathA.size)
        }
        for (i in shortendPathA.size -1 downTo 0) {
            if (shortendPathA[i] == shortendPathB[i]) {
                return Pair(pathA.subList(i + 1, pathA.size).reversed(), pathB.subList(i, pathB.size))
            }
        }
        throw IllegalStateException("There should always be a lowest common ancestor between two nodes in a tree!")
    }

    fun addChild(n: MultiverseNode) {
        children.add(n)
        n.parent = this
    }

    fun removeAllChildren() {
        children.clear()
        values.clear()
    }

    open fun nextNode(overrides: Map<String,Map<Int, Int>>): MultiverseNode {
        return children[0]
    }

    fun nextNode(overrides: Map<String, Map<Int, Int>>, n: Int): MultiverseNode {
        var dest = this
        for (i in 0 ..< n) {
            dest = dest.nextNode(overrides)
        }
        return dest
    }

    open fun nextNode(stackValue: WasmStackValue): MultiverseNode {
        return children[0]
    }
}

class DeterministicPrimitiveNode(val primitive: String, val args: List<Int>, children: MutableList<MultiverseNode> = mutableListOf(), values: MutableList<Int> = mutableListOf()) : MultiverseNode(children, values) {
    override val displayName: String
        get() = "$primitive(${args.joinToString(", ")})"

    override val edgeLength: Int
        get() = 130 + children.size * 10
}

class PrimitiveNode(val primitive: String, val arg: Int, children: MutableList<MultiverseNode> = mutableListOf(), values: MutableList<Int> = mutableListOf()) : MultiverseNode(children, values) {
    override val displayName: String
        get() = "$primitive($arg)"

    override val edgeLength: Int
        get() = 135

    override fun nextNode(overrides: Map<String,Map<Int, Int>>): MultiverseNode {
        val returnValue = overrides[primitive]?.get(arg)
        if (returnValue != null) {
            return children[values.indexOf(returnValue)]
        }
        throw Exception("Unknown destination!")
    }

    override fun nextNode(stackValue: WasmStackValue): MultiverseNode {
        return children[values.indexOf(stackValue.value.toInt())]
    }
}

class MultiverseDebugger(
    connection: Connection,
    val wasmBinary: WasmBinary,
    private val symbolicWdcliPath: String,
    start: Boolean = true,
    private val graphUpdated: () -> Unit = {},
    private val mockingUpdated: () -> Unit = {},
    onHitBreakpoint: (Int) -> Unit = {}
) : Debugger(connection, start, onHitBreakpoint) {
    val graph = MultiverseGraph()
    private var len = 0
    val overrides = mutableMapOf<String, MutableMap<Int, Int>>()

    // TODO: Remove, just for testing
    init {
        //pause()
    }

    override fun stepInto() {
        super.stepInto()
        println("Step!")

        // If a checkpoint is received an a new node is created then we will be at that node and then it won't have children
        /*if (graph.currentNode.children.isNotEmpty()) {
            // TODO: What if no override is set, how do we know where to go?
            val primitiveResult = getCurrentState().stack!!.last()
            graph.currentNode.values.indexOf(primitiveResult.value.toInt())
            graph.currentNode = graph.currentNode.nextNode(primitiveResult)
            //graph.currentNode = graph.currentNode.nextNode(overrides)
            graphUpdated()
        }*/
        printCheckpoints(wasmBinary.metadata)
    }

    override fun stepOver() {
        super.stepOver()
        /*val startSize = checkpoints.size
        val startNode = graph.currentNode
        val pathExists = startNode.children.isNotEmpty()
        super.stepOver()
        // TODO: What if the path only exists partially?
        // TODO: VM doesn't take snapshots on step over currently.
        if (pathExists) {
            val instructionsExecuted = checkpoints.size - startSize
            println(instructionsExecuted)
            val dest = startNode.nextNode(overrides, instructionsExecuted)
            graph.currentNode = dest
        }*/
    }

    override fun stepBack(n: Int, stepDone: () -> Unit) {
        var destinationNode = graph.currentNode
        for (i in 0 ..< n) {
            destinationNode = destinationNode.parent!!
        }
        super.stepBack(n, stepDone)

        graph.currentNode = destinationNode
        graphUpdated()
    }

    override fun continueFor(n: Int) {
        /*var destinationNode = graph.currentNode
        for (i in 0 ..< n) {
            destinationNode = destinationNode.nextNode(overrides)
        }*/
        super.continueFor(n)

        //graph.currentNode = destinationNode
        graphUpdated()
    }

    override fun run() {
        // TODO: Improve so you can walk along the existing graph instead of destroying it
        //graph.currentNode.removeAllChildren()
        super.run()
    }

    override fun reset() {
        super.reset()
        graph.currentNode = graph.rootNode
        graphUpdated()
    }

    override fun addPrimitiveOverride(primName: String, arg: Int, returnValue: Int) {
        super.addPrimitiveOverride(primName, arg, returnValue)
        if (!overrides.containsKey(primName))
            overrides[primName] = mutableMapOf()
        overrides[primName]!![arg] = returnValue
        mockingUpdated()
    }

    override fun removePrimitiveOverride(primName: String, arg: Int) {
        super.removePrimitiveOverride(primName, arg)
        overrides[primName]?.remove(arg)
        mockingUpdated()
    }

    fun createNewPath(returnValue: Int, override: Boolean = true) {
        val currentNode = graph.currentNode
        if (currentNode is PrimitiveNode) {
            currentNode.values.add(returnValue)
            currentNode.addChild(MultiverseNode())
            graphUpdated()
            if (override) {
                addPrimitiveOverride(currentNode.primitive, currentNode.arg, returnValue)
            }
        }
    }

    /**
     * When restoring snapshots, the overrides can change. Because of this, we need to update our overrides mapping in
     * the debugger so that the paths followed when stepping forward still correctly match the paths the VM will follow.
     */
    override fun loadSnapshot(snapshot: WOODDumpResponse) {
        super.loadSnapshot(snapshot)
        if (snapshot.overrides != null) {
            overrides.clear()
            for (snapshotOverrides in snapshot.overrides) {
                // Ignore overrides that don't appear in this program. Maybe they were for another program that we hot loaded.
                if (snapshotOverrides.fidx >= wasmBinary.metadata.primitive_fidx_mapping.size)
                    continue

                val primitiveName = wasmBinary.metadata.primitive_fidx_mapping[snapshotOverrides.fidx]
                if (!overrides.containsKey(primitiveName)) {
                    overrides[primitiveName] = mutableMapOf()
                }
                overrides[primitiveName]?.set(snapshotOverrides.arg, snapshotOverrides.return_value)
            }
            mockingUpdated()
        }
    }

    override fun checkpointsUpdated() {
        super.checkpointsUpdated()
        val newCheckpoints = checkpoints.subList(0, checkpoints.size)
        val change = newCheckpoints.size - len
        len = newCheckpoints.size

        for (i in len - change ..< len) {
            // A multiverse graph always starts as just a single node, we already have a first node so no need to add it.
            if (i != 0) {
                addNode(newCheckpoints[i])
            }
            else {
                graph.replaceCurrentNode(newNodeFromCheckpoint(newCheckpoints[i]))
            }
        }

        val checkpoint = newCheckpoints.last()
        if (graph.currentNode.children.isEmpty() && change > 0 && checkpoint?.fidx_called != null) {
            val newNode = if (isAfterChoicePoint(checkpoint.snapshot.pc!!)) {
                PrimitiveNode(wasmBinary.metadata.primitive_fidx_mapping[checkpoint.fidx_called], checkpoint.args!![0]).apply {
                    values.add(checkpoint.returns!!.first())
                }
            } else {
                DeterministicPrimitiveNode(wasmBinary.metadata.primitive_fidx_mapping[checkpoint.fidx_called], checkpoint.args!!)
            }

            if (graph.currentNode.parent != null) {
                graph.replaceNode(graph.currentNode.parent!!, newNode)
            } else {
                // Current node has no parent -> it is the root node!
                graph.currentNode = newNode
                graph.rootNode = newNode
            }
        }

        graphUpdated()
    }

    private fun addNode(checkpoint: Checkpoint?) {
        // Don't add new nodes if we are walking on an existing graph section.
        if (graph.currentNode.children.isNotEmpty()) {
            if (checkpoint != null && isAfterChoicePoint(checkpoint.snapshot.pc!!)) {
                val stackValue = WasmStackValue(0, "I32", checkpoint.returns!!.first().toLong())
                val intValue = stackValue.value.toInt()
                if (graph.currentNode is PrimitiveNode) {
                    if (!graph.currentNode.values.contains(intValue)) {
                        val newNode = MultiverseNode()
                        graph.currentNode.values.add(intValue)
                        graph.currentNode.addChild(newNode)
                        graph.currentNode = newNode
                    }
                    else {
                        graph.currentNode = graph.currentNode.nextNode(stackValue)
                    }
                }
            }
            else if (graph.currentNode.children.size == 1) {
                graph.currentNode = graph.currentNode.children.first()
            }
            else {
                throw RuntimeException("Don't know where to go! ${graph.currentNode.displayName}")
            }
            return
        }

        val newNode = newNodeFromCheckpoint(checkpoint)
        graph.currentNode.addChild(newNode)
        graph.currentNode = newNode
    }

    private fun newNodeFromCheckpoint(checkpoint: Checkpoint?): MultiverseNode {
        //  TODO: We need to know if we are after a choicepoint
        var newNode = MultiverseNode()
        // TODO: Enable this again once the VM gives us information about the argument being used
        /*if (checkpoint != null && isChoicePoint(checkpoint.pc!!)) {
            newNode = PrimitiveNode("non-deterministic-primitive", 0)
            newNode.values.add(0)
        }*/
        return newNode
    }

    private fun isChoicePoint(pc: Int): Boolean {
        return pc in wasmBinary.metadata.choicepoints
    }

    private fun isAfterChoicePoint(pc: Int): Boolean {
        return pc in wasmBinary.metadata.after_choicepoints
    }

    fun predictFuture(maxInstructions: Int = 50, maxSymbolicVariables: Int = -1, maxIterations: Int = -1, stopPc: Int = -1): Boolean {
        val result = analyse(
            symbolicWdcliPath,
            wasmBinary.file.absolutePath,
            snapshot(),
            maxInstructions,
            maxSymbolicVariables,
            maxIterations,
            stopPc
        )
        if (result.paths.isEmpty()) {
            return false
        }

        val concolicGraphRoot = processPaths(result.paths)
        // Remove current future and add newly predicted future, otherwise you will get a split timeline between the
        // previously predicted future and the newly predicted future.
        graph.replaceCurrentNode(concolicGraphRoot)
        graphUpdated()
        return true
    }

    // TODO: Remove/move/improve
    fun getCurrentState(): WOODDumpResponse {
        return checkpoints.last()!!.snapshot
    }
}

