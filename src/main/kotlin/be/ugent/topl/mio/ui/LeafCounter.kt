package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.MultiverseGraph
import be.ugent.topl.mio.debugger.MultiverseNode

class LeafCounter(private val graph: MultiverseGraph) {
    private val cachedHeights = mutableMapOf<MultiverseNode, Int>()

    // TODO: Maybe it only needs to update changed nodes.
    fun calculateLeafCounts() {
        println("Calculating leaf counts")
        val time = System.currentTimeMillis()
        cachedHeights.clear()
        val completedNodes = mutableSetOf<MultiverseNode>()
        val stack = mutableListOf(graph.rootNode)
        while (stack.isNotEmpty()) {
            val currentNode = stack.last()
            if (currentNode.children.isEmpty()) {
                //cachedHeights[currentNode] = 1
                completedNodes.add(currentNode)
            }
            // We already calculated the height of our children -> calculate our height!
            else if (completedNodes.contains(currentNode.children.first())) {
                var heightSum = 0
                for (child in currentNode.children) {
                    heightSum += cachedHeights.getOrDefault(child, 1)
                }
                cachedHeights[currentNode] = heightSum
                completedNodes.add(currentNode)
            }
            if (completedNodes.contains(currentNode)) {
                stack.removeLast()
            }
            stack.addAll(currentNode.children)
        }
        println("Finished calculating leaf counts ${System.currentTimeMillis() - time} ms elapsed")
    }

    fun getLeafCount(node: MultiverseNode): Int {
        return cachedHeights.getOrDefault(node, 1)
    }
}