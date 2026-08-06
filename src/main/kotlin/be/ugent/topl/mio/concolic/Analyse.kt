package be.ugent.topl.mio.concolic

import be.ugent.topl.mio.debugger.MultiverseNode
import be.ugent.topl.mio.woodstate.WOODState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.*

data class SymbolicValueMapping(val primitive: String, val args: List<Int>, val value: Int, val time_step: Int, val paths: List<SymbolicValueMapping>) {
    override fun toString(): String {
        return "$value"
        //return "$primitive($arg) = $value"
    }
}

data class ConcolicAnalysisResult(val paths: List<SymbolicValueMapping>) {
    fun getPathCount(): Int {
        return getPathCount(paths)
    }
    private fun getPathCount(paths: List<SymbolicValueMapping>): Int {
        if (paths.isEmpty()) {
            return 1
        }

        var count = 0
        for (path in paths) {
            count += getPathCount(path.paths)
        }
        return count
    }
}

fun analyse(wdcliPath: String, wasmFile: String, jsonSnapshot: String?, maxInstructions: Int = 50, maxSymbolicVariables: Int = -1, maxIterations: Int = -1, stopPc: Int = -1): ConcolicAnalysisResult {
    val command = if (jsonSnapshot != null) {
        val woodState = WOODState.fromLine(jsonSnapshot)
        val messages = woodState.toBinary(io = false, overrides = false).map { it.trim('\n', ' ') }
        for (msg in messages) {
            println("\"$msg\"")
        }
        listOf(wdcliPath, wasmFile, "--no-socket", "--mode", "concolic", "--snapshot", *messages.toTypedArray(), "end", "--max-instructions", "$maxInstructions", "--max-symbolic-variables", "$maxSymbolicVariables", "--max-iterations", "$maxIterations", "--stop-at-pc", "$stopPc")
    }
    else {
        listOf(wdcliPath, wasmFile, "--no-socket", "--mode", "concolic", "--max-instructions", "$maxInstructions", "--max-symbolic-variables", "$maxSymbolicVariables", "--max-iterations", "$maxIterations", "--stop-at-pc", "$stopPc")
    }

    println("Running command: ${command.joinToString(" ") }")
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lineScanner = Scanner(process.inputStream)
    val lines = mutableListOf<String>()
    var trap = false
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
            if (trap) {
                process.destroy()
                throw Exception("Execution resulted in a trap!" + lines.joinToString("\n"))
            }
            return ConcolicAnalysisResult(result.paths.sortedBy { it.value })
        }
        if (currentLine.startsWith("Trap:")) {
            trap = true
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
    val node = MultiverseNode(
        primitive = paths.first().primitive,
        arg = paths.first().args,
        instrExecuted = paths.first().time_step - currentTimeStep
    )

    for (path in paths) {
        if (path.paths.isNotEmpty()) {
            node.addChild(processPaths(path.paths, paths.first().time_step + 1), path.value)
        }
    }

    return node
}
