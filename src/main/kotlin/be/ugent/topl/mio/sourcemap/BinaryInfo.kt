import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.*

data class WasmInfo(
    val choicepoints: Set<Int>,
    val after_choicepoints: Set<Int>,
    val primitive_calls: Set<Int>,
    val after_primitive_calls: Set<Int>,
    val primitive_fidx_mapping: List<String>
)
data class WasmBinary(val file: File, val metadata: WasmInfo)

fun getBinaryInfo(wdcliPath: String, wasmFile: String): WasmInfo {
    println("Get binary info using $wdcliPath")
    val process = ProcessBuilder(wdcliPath, wasmFile, "--no-socket", "--dump-info").redirectErrorStream(true).start()
    val lineScanner = Scanner(process.inputStream)
    val lines = mutableListOf<String>()
    while (lineScanner.hasNextLine()) {
        val currentLine = lineScanner.nextLine()
        lines.add(currentLine)
        if (currentLine.startsWith("{\"")) {
            val objectMapper = ObjectMapper()
            objectMapper.registerKotlinModule()
            process.destroy()
            return objectMapper.readValue(currentLine, WasmInfo::class.java)
        }
    }
    process.destroy()
    println("Output:")
    for (line in lines) {
        println(line)
    }
    throw Exception("Failed to get info")
}
