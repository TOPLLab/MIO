package be.ugent.topl.mio.sourcemap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.formdev.flatlaf.util.SystemInfo
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class DwarfLineMapping(val addr: Int, val file: String, val row: Int, val col: Int)

class DwarfSourceMap(
    private val pcToSource: Map<Int, DwarfLineMapping>,
    private val sourceToPc: Map<Pair<String, Int>, DwarfLineMapping>
): SourceMap {
    private var mostRecentFile = getSourceFile(0)

    override fun getLineForPc(pc: Int): Int {
        return pcToSource[pc]?.row ?: throw RuntimeException("No mapping for this pc")
    }

    override fun getPcForLine(line: Int, filename: String): Int {
        return sourceToPc[Pair(filename, line)]?.addr ?: throw RuntimeException("No mapping for this line")
    }

    override fun getSourceFile(pc: Int): String {
        if (pcToSource.containsKey(pc)) {
            return File(pcToSource[pc]!!.file).readText()
        }
        return File(pcToSource.iterator().next().value.file).readText()
    }

    override fun getSourceFileName(pc: Int): String {
        mostRecentFile = pcToSource[pc]!!.file
        return mostRecentFile
    }

    override fun getStyle(): String {
        return when {
            mostRecentFile.endsWith(".c") -> SyntaxConstants.SYNTAX_STYLE_C
            mostRecentFile.endsWith(".cpp") -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS
            mostRecentFile.endsWith(".go") -> SyntaxConstants.SYNTAX_STYLE_GO
            mostRecentFile.endsWith(".rs") -> SyntaxConstants.SYNTAX_STYLE_RUST
            else -> SyntaxConstants.SYNTAX_STYLE_RUST
        }
    }
}

fun getDwarfSourcemap(wasmFilename: String): SourceMap {
    val process = ProcessBuilder(
        if (SystemInfo.isMacOS) "./dwarf-line-mapping-mac" else "./dwarf-line-mapping",
        wasmFilename
    ).redirectErrorStream(true).start()

    val pcToSource = mutableMapOf<Int, DwarfLineMapping>()
    val sourceToPc = mutableMapOf<Pair<String, Int>, DwarfLineMapping>()
    val objectMapper = ObjectMapper().registerKotlinModule()
    BufferedReader(InputStreamReader(process.inputStream)).useLines {
        it.forEach { line ->
            println(line)
            val lineMapping = objectMapper.readValue(line, DwarfLineMapping::class.java)
            pcToSource[lineMapping.addr] = lineMapping
            sourceToPc[Pair(lineMapping.file, lineMapping.row)] = lineMapping
        }
    }
    return DwarfSourceMap(pcToSource, sourceToPc)
}

fun main() {
    val mapping = getDwarfSourcemap("/home/maarten/Documents/Projects/dwarf-rust-test/target/wasm32-unknown-unknown/release/dwarf_rust_test.wasm")
    println(mapping.getPcForLine(10, "/home/maarten/Documents/Projects/dwarf-rust-test/src/lib.rs"))
    println(mapping)
}
