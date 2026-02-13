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
        val stack = mutableListOf(graph.rootNode)
        while (stack.isNotEmpty()) {
            val currentNode = stack.last()
            if (currentNode.children.isEmpty()) {
                cachedHeights[currentNode] = 1
            }
            // We already calculated the height of our children -> calculate our height!
            else if (cachedHeights.contains(currentNode.children.first())) {
                var heightSum = 0
                for (child in currentNode.children) {
                    heightSum += cachedHeights[child]!!
                }
                cachedHeights[currentNode] = heightSum
            }
            if (cachedHeights.containsKey(currentNode)) {
                stack.removeLast()
            }
            stack.addAll(currentNode.children)
        }
        println("Finished calculating leaf counts ${System.currentTimeMillis() - time} ms elapsed")
    }

    fun getLeafCount(node: MultiverseNode): Int {
        return cachedHeights[node]!!
    }
}