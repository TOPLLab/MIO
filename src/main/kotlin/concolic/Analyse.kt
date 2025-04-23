package concolic

import debugger.MultiverseNode
import debugger.PrimitiveNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import woodstate.WOODState
import java.util.*

data class SymbolicValueMapping(val primitive: String, val arg: Int, val value: Int, val time_step: Int, val paths: List<SymbolicValueMapping>) {
    override fun toString(): String {
        return "$value"
        //return "$primitive($arg) = $value"
    }
}

data class ConcolicAnalysisResult(val paths: List<SymbolicValueMapping>)

fun analyse(wdcliPath: String, wasmFile: String, jsonSnapshot: String, maxInstructions: Int = 50, maxSymbolicVariables: Int = -1, maxIterations: Int = -1, stopPc: Int = -1): ConcolicAnalysisResult {
    val woodState = WOODState.fromLine(jsonSnapshot)
    val messages = woodState.toBinary(io = false, overrides = false).map { it.trim('\n', ' ') }
    for (msg in messages) {
        println("\"$msg\"")
    }

    val command = listOf(wdcliPath, wasmFile, "--no-socket", "--mode", "concolic", "--snapshot", *messages.toTypedArray(), "end", "--max-instructions", "$maxInstructions", "--max-symbolic-variables", "$maxSymbolicVariables", "--max-iterations", "$maxIterations", "--stop-at-pc", "$stopPc")
    println("Running command: ${command.joinToString(" ") }")
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lineScanner = Scanner(process.inputStream)
    val lines = mutableListOf<String>()
    while (lineScanner.hasNextLine()) {
        val currentLine = lineScanner.nextLine()
        lines.add(currentLine)
        if (currentLine.startsWith("{\"paths\":")) {
            println(currentLine)
            val objectMapper = ObjectMapper()
            objectMapper.registerKotlinModule()
            process.destroy()
            val result = objectMapper.readValue(currentLine, ConcolicAnalysisResult::class.java)
            //process(result)
            return ConcolicAnalysisResult(result.paths.sortedBy { it.value })
        }
    }
    process.destroy()
    println("Output:")
    for (line in lines) {
        println(line)
    }
    println("Error occurred while running the following command: \"${command.joinToString(" ")}\"")
    throw Exception("Failed to get result from analysis")
}

fun process(r: ConcolicAnalysisResult): MultiverseNode {
    return processPaths(r.paths)
}

fun processPaths(paths: List<SymbolicValueMapping>, currentTimeStep: Int = 0): MultiverseNode {
    println("" + currentTimeStep + " " + paths[0].time_step)
    var currentNode: MultiverseNode
    val primitiveNode = PrimitiveNode(paths[0].primitive, paths[0].arg)
    val startNode = if (currentTimeStep != paths[0].time_step) {
        val startNode = MultiverseNode() // TODO: Maybe add a second node type being the deterministic instruction node
        currentNode = startNode
        for (i in currentTimeStep + 1..< paths[0].time_step) {
            val deterministicNode = MultiverseNode()
            currentNode.addChild(deterministicNode) // TODO: Maybe add a second node type being the deterministic instruction node)
            currentNode = deterministicNode
        }
        currentNode.addChild(primitiveNode)
        startNode
    }
    else {
        primitiveNode
    }
    currentNode = primitiveNode

    for (path in paths) {
        currentNode.values.add(path.value)
        if (path.paths.isNotEmpty()) {
            currentNode.addChild(processPaths(path.paths, paths[0].time_step + 1))
        }
        else {
            currentNode.addChild(MultiverseNode(parent=currentNode))
        }
    }
    return startNode
}
