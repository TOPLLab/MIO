package be.ugent.topl.mio.debugger

import WasmBinary
import be.ugent.topl.mio.concolic.analyse
import be.ugent.topl.mio.concolic.processPaths
import be.ugent.topl.mio.connections.Connection
import be.ugent.topl.mio.ui.disassemble
import be.ugent.topl.mio.woodstate.Checkpoint
import be.ugent.topl.mio.woodstate.WOODDumpResponse
import be.ugent.topl.mio.woodstate.WasmStackValue
import kotlin.collections.iterator

class MultiverseGraph(var rootNode: MultiverseNode = MultiverseNode("main", listOf()), var currentNode: MultiverseNode = rootNode, var instructionOffset: Int = 0) {
    /**
     * Does a full replacement without keeping children or values. It just removes the old now and any descendants and
     * attaches the new node which can have existing children.
     */
    fun replaceCurrentNode(newNode: MultiverseNode) {
        if (currentNode == rootNode) {
            rootNode = newNode
            currentNode = newNode
            return
        }

        val childIndex = currentNode.parent!!.children.indexOf(currentNode)
        currentNode.parent!!.children[childIndex] = newNode
        currentNode = newNode
    }

    fun removeLastNode(): MultiverseNode {
        val last = currentNode.parent
        last!!.children.remove(currentNode)
        currentNode = last
        return currentNode
    }

    fun reset() {
        currentNode = MultiverseNode("main", listOf())
        rootNode = currentNode
    }

    fun isAtChoicePoint(): Boolean =
        instructionOffset == currentNode.totalInstrExecuted && currentNode.children.isNotEmpty()
}

open class MultiverseNode(
    val primitive: String,
    val arg: List<Int>,
    val children: MutableList<MultiverseNode> = mutableListOf(),
    val values: MutableList<Int> = mutableListOf(),
    var parent: MultiverseNode? = null,
    var totalInstrExecuted: Int = 0,
) {
    val checkpoints: MutableList<Checkpoint> = mutableListOf()

    val displayName: String
        get() = "$primitive(${arg.joinToString(", ")})"

    val edgeLength: Int
        get() = 25 + displayName.length * 8

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

    fun addChild(n: MultiverseNode, value: Int) {
        children.add(n)
        values.add(value)
        n.parent = this
    }

    fun removeAllChildren() {
        children.clear()
        values.clear()
    }

    fun nextNode(stackValue: WasmStackValue): MultiverseNode {
        return children[values.indexOf(stackValue.value.toInt())]
    }

    fun incDetInstrCount() {
        totalInstrExecuted++
    }

    override fun toString(): String {
        return "Node(displayName=\"${displayName}\", totalInstrExecuted = $totalInstrExecuted)"
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
    val overrides = mutableMapOf<String, MutableMap<List<Int>, Int>>()

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
        super.continueFor(n)
        graphUpdated()
    }

    override fun reset() {
        super.reset()
        graph.currentNode = graph.rootNode
        graph.instructionOffset = 0
        graphUpdated()
    }

    override fun addPrimitiveOverride(primName: String, args: List<Int>, returnValue: Int): Boolean {
        val result = super.addPrimitiveOverride(primName, args, returnValue)
        if (!overrides.containsKey(primName))
            overrides[primName] = mutableMapOf()
        overrides[primName]!![args] = returnValue
        mockingUpdated()
        return result
    }

    override fun removePrimitiveOverride(primName: String, args: List<Int>): Boolean {
        val result = super.removePrimitiveOverride(primName, args)
        overrides[primName]?.remove(args)
        mockingUpdated()
        return result
    }

    fun removeAllPrimitiveOverrides() {
        for (override in overrides.toMap()) {
            val argReturnValuePairs = override.value
            for (argSet in argReturnValuePairs.keys.toSet()) {
                removePrimitiveOverride(override.key, argSet)
            }
        }
    }

    fun createNewPath(returnValue: Int, override: Boolean = true) {
        val currentNode = graph.currentNode
        if (graph.isAtChoicePoint()) {
            currentNode.addChild(MultiverseNode(currentNode.children[0].primitive, currentNode.children[0].arg), returnValue)
            graphUpdated()
            if (override) {
                addPrimitiveOverride(currentNode.primitive, currentNode.arg, returnValue)
            }
        } else {
            println("WARNING: Not adding new path, current node is not a choice point")
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
                if (snapshotOverrides.fidx >= wasmBinary.metadata.primitives.size)
                    continue

                val primitiveName = wasmBinary.metadata.primitives[snapshotOverrides.fidx].name
                if (!overrides.containsKey(primitiveName)) {
                    overrides[primitiveName] = mutableMapOf()
                }
                overrides[primitiveName]?.set(snapshotOverrides.args, snapshotOverrides.return_value)
            }
            mockingUpdated()
        }
    }

    override fun checkpointsUpdated() {
        super.checkpointsUpdated()
        val newCheckpoints = checkpoints.subList(0, checkpoints.size)
        val change = newCheckpoints.size - len
        len = newCheckpoints.size

        println("$change instructions added, total = ${checkpoints.size}")

        for (i in len - change ..< len) {
            traverse(newCheckpoints[i], i)
        }

        graphUpdated()
    }

    private fun nonDet(checkpoint: Checkpoint?): Boolean =
        checkpoint != null && checkpoint.fidx_called != null && checkpoint.returns!!.isNotEmpty()

    /**
     * Follow the graph if the nodes/edges are already present, otherwise add them. Also add metadata to the existing
     * or new graph such a which functions were executed.
     */
    private fun traverse(checkpoint: Checkpoint?, depth: Int) {
        // Process one checkpoint
        if (graph.instructionOffset == graph.currentNode.totalInstrExecuted) {
            // If non-deterministic add a new node or follow an existing edge. The destination node becomes the new node.
            if (nonDet(checkpoint)) {
                if (graph.currentNode.children.isNotEmpty() && graph.currentNode.values.indexOf(checkpoint!!.returns!!.first()) != -1) {
                    // Path already exists, follow it.
                    val index = graph.currentNode.values.indexOf(checkpoint!!.returns!!.first())
                    graph.currentNode = graph.currentNode.children[index]
                }
                else {
                    // Path does not exist yet, create a new path.
                    val newNode = MultiverseNode(wasmBinary.metadata.primitives[checkpoint!!.fidx_called!!].name, checkpoint.args!!)
                    graph.currentNode.addChild(newNode, checkpoint.returns!!.first())
                    graph.currentNode = newNode
                    addCheckpointToCurrentNode(checkpoint)
                }
                graph.instructionOffset = 0
            }
            // If deterministic, increment our counter.
            else {
                // New deterministic instructions
                graph.currentNode.incDetInstrCount()
                graph.instructionOffset++
                addCheckpointToCurrentNode(checkpoint)
            }
        } else {
            // Follow existing deterministic path
            graph.instructionOffset++
        }
    }

    /**
     * Should only be called when creating new parts, we should not store checkpoints for old sections that we traverse again.
     * TODO: Will the counters be wrong if we are navigating partially an existing part and then create a partially a new part?
     */
    fun addCheckpointToCurrentNode(checkpoint: Checkpoint?) {
        if (checkpoint != null) {
            graph.currentNode.checkpoints.add(checkpoint)
            var instructionCount = -graph.currentNode.checkpoints.first().instructions_executed // This one created the node.
            for (checkpoint in graph.currentNode.checkpoints) {
                instructionCount += checkpoint.instructions_executed
            }
            if (instructionCount > graph.currentNode.totalInstrExecuted) {
                throw Error("This should not happen")
            }
        }
    }

    fun findValidSnapshot(checkPointNode: MultiverseNode, targetNode: MultiverseNode, targetInstructionOffset: Int): Pair<WOODDumpResponse, Int>? {
        var currentOffset = 0
        println(checkPointNode.checkpoints.map { "Checkpoint(function_called = ${if(it.fidx_called != null) wasmBinary.metadata.primitives[it.fidx_called] else null}, instructions_executed = ${it.instructions_executed})" }.joinToString())
        for (checkpoint in checkPointNode.checkpoints) {
            if (checkpoint != checkPointNode.checkpoints.first())
                currentOffset += checkpoint.instructions_executed
            println("Current offset = $currentOffset in node $checkPointNode")
            // TODO: Use a function from woodstate to say if it's restorable
            if (checkpoint.snapshot.memory != null) {
                if (checkPointNode == targetNode) {
                    if(targetInstructionOffset >= currentOffset)
                        return Pair(checkpoint.snapshot, currentOffset)
                }
                else {
                    return Pair(checkpoint.snapshot, currentOffset)
                }
            }
        }
        return null
    }

    fun determineForwardPathNonDescendant(targetNode: MultiverseNode, targetInstructionOffset: Int): List<MultiverseNode> {
        // rCMD policy:
        /*reset() // The current node is earlier in time or in a different branch so we reset the execution.
        return graph.rootNode.findPath(targetNode)*/

        val path = mutableListOf<MultiverseNode>() // visited path, should be inverted to go forwards
        var currentNode = targetNode
        var restorePoint = findValidSnapshot(currentNode, targetNode, targetInstructionOffset)
        while (restorePoint == null && currentNode.parent != null) {
            path.addFirst(currentNode)
            currentNode = currentNode.parent!!
            restorePoint = findValidSnapshot(currentNode, targetNode, targetInstructionOffset)
        }
        path.addFirst(currentNode)
        if (currentNode.parent == null) {
            // We went all the way to the rootnode and found no snapshots -> reset!
            reset()
        }
        else {
            // We found a valid snapshot to restore on the path back.
            loadSnapshot(restorePoint!!.first)
            graph.currentNode = currentNode
            graph.instructionOffset = restorePoint.second
            if (graph.instructionOffset > graph.currentNode.totalInstrExecuted) {
                throw IllegalStateException("The instructionOffset cannot be larger than the maximum instruction count in the current node!")
            }
        }
        return path
    }

    fun determineForwardPathDescendant(cNode: MultiverseNode, targetNode: MultiverseNode, targetInstructionOffset: Int): List<MultiverseNode> {
        // rCMD policy:
        /*reset() // The current node is earlier in time or in a different branch so we reset the execution.
        return graph.rootNode.findPath(targetNode)*/

        val path = mutableListOf<MultiverseNode>() // visited path, should be inverted to go forwards
        var currentNode = targetNode
        var restorePoint = findValidSnapshot(currentNode, targetNode, targetInstructionOffset)
        while (restorePoint == null && currentNode != cNode) {
            path.addFirst(currentNode)
            currentNode = currentNode.parent!!
            restorePoint = findValidSnapshot(currentNode, targetNode, targetInstructionOffset)
        }
        path.addFirst(currentNode)
        if (currentNode == cNode) {
            // We went all the way to the current node, now we just need to walk forward.
        }
        else {
            // We found a valid snapshot to restore on the path back.
            loadSnapshot(restorePoint!!.first)
            graph.currentNode = currentNode
            graph.instructionOffset = restorePoint.second
        }
        return path
    }

    /**
     * Navigate to [targetNode] at [targetInstructionOffset] in the multiverse graph. If this state is earlier in time
     * or in a different branch, the execution will first reset and then re-execute from the root node.
     */
    fun slide(targetNode: MultiverseNode, targetInstructionOffset: Int) {
        logger.info("Slide to node = $targetNode, offset = $targetInstructionOffset")
        logger.info("Current node = ${graph.currentNode}, offset = ${graph.instructionOffset}")
        // Determine path.
        val forwardPath = if (graph.currentNode.findPath(targetNode).isEmpty() ||
            (graph.currentNode == targetNode && graph.instructionOffset > targetInstructionOffset)) {
            logger.info("Target is not a descendant of the current node")
            // In this path we first restore or reset and then go forward/
            determineForwardPathNonDescendant(targetNode, targetInstructionOffset)
        }
        else {
            logger.info("Target is a descendant of the current node")
            // In this path we could go forward from the current node or we restore a closer snapshot and then go forward.
            //graph.currentNode.findPath(targetNode)
            determineForwardPathDescendant(graph.currentNode, targetNode, targetInstructionOffset)
        }
        logger.info("Forward path (len = ${forwardPath.size}) = $forwardPath")
        logger.info("Current node = ${graph.currentNode}, offset = ${graph.instructionOffset}")


        // Perform actual slide operation. We do this without breakpoints because we don't want the VM to stop in the
        // middle of the slide operation. The user wants to go to a particular node, not stop in between on random
        // re-executed instructions.
        withoutBreakpoints {
            var continueCount = targetInstructionOffset
            if (forwardPath.size == 1 && targetNode == graph.currentNode) {
                continueCount = targetInstructionOffset - graph.instructionOffset
            }
            if (forwardPath.size > 1) {
                val stepCount = forwardPath[0].totalInstrExecuted - graph.instructionOffset
                continueFor(stepCount)
                for (i in 1 ..< forwardPath.size - 1) {
                    val valueIndex = forwardPath[i-1].children.indexOf(forwardPath[i])
                    println("pc = 0x${getCurrentState().pc!!.toString(16)}")
                    //println(disassemble(wasmBinary.file.path))
                    addPrimitiveOverride(forwardPath[i].primitive, forwardPath[i].arg, forwardPath[i - 1].values[valueIndex])
                    if (!wasmBinary.metadata.primitive_calls.contains(getCurrentState().pc)) {
                        println(disassemble(wasmBinary.file.absolutePath))
                        println(graph.instructionOffset != graph.currentNode.totalInstrExecuted)
                        throw Error("Not at choice point according to pc = 0x${getCurrentState().pc!!.toString(16)}")
                    }
                    if (graph.instructionOffset != graph.currentNode.totalInstrExecuted) {
                        throw Error("Not at choice point, distance to choicepoint = ${graph.currentNode.totalInstrExecuted - graph.instructionOffset}")
                    }
                    continueFor(1 + forwardPath[i].totalInstrExecuted)
                }
                val valueIndex = forwardPath[forwardPath.size - 2].children.indexOf(forwardPath[forwardPath.size - 1])
                addPrimitiveOverride(forwardPath.last().primitive, forwardPath.last().arg, forwardPath[forwardPath.size - 2].values[valueIndex])
                println("pc = 0x${getCurrentState().pc!!.toString(16)}")
                //println(disassemble(wasmBinary.file.path))
                if (graph.instructionOffset != graph.currentNode.totalInstrExecuted) {
                    throw Error("Not at choice point, distance to choicepoint = ${graph.currentNode.totalInstrExecuted - graph.instructionOffset}")
                }
                continueCount += 1
            }
            continueFor(continueCount)
        }

        if (targetNode != graph.currentNode || targetInstructionOffset != graph.instructionOffset) {
            throw Error("Incorrect slide end destination, nodes equal = ${targetNode != graph.currentNode}, offset difference = ${graph.instructionOffset - targetInstructionOffset}, current = ${graph.instructionOffset}, expected = ${targetInstructionOffset}, max instr = ${graph.currentNode.totalInstrExecuted}")
        }
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
            repeat(maxInstructions) {
                graph.currentNode.incDetInstrCount()
            }
            graphUpdated()
            return true
        }

        // Pass the instructionOffset so the already present deterministic instructions are kept. It is negative so that
        // these are added extra instead of being forward in time. We basically start graph.instructionOffset
        // instructions in the past.
        val concolicGraphRoot = processPaths(result.paths, -graph.instructionOffset)
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

