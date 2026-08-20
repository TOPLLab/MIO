package be.ugent.topl.mio.ui

import be.ugent.topl.mio.debugger.MultiverseDebugger
import be.ugent.topl.mio.debugger.MultiverseGraph
import be.ugent.topl.mio.debugger.MultiverseNode
import be.ugent.topl.mio.sourcemap.SourceMap
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

data class GraphNodeDto(
    val id: String,
    val primitive: String,
    val arg: List<Int>,
    val displayName: String,
    val totalInstrExecuted: Int,
    val parentId: String?,
    val edgeValue: Int?
)

data class GraphDto(
    val nodes: List<GraphNodeDto>,
    val currentNodeId: String,
    val instructionOffset: Int
)

data class SourceDto(
    val content: String,
    val currentLine: Int,
    val filename: String
)

data class PrimitiveDto(val name: String, val argCount: Int)
data class MockDto(val primName: String, val args: List<Int>, val returnValue: Int)
data class SlideDto(val nodeId: String, val offset: Int)
data class WatchEntryDto(val name: String, val type: String, val value: String)
data class WatchDto(val entries: List<WatchEntryDto>)
data class BreakpointLineDto(val line: Int, val filename: String)
data class BreakpointsResponseDto(val lines: List<Int>)

fun findNodeById(graph: MultiverseGraph, targetId: String): MultiverseNode? {
    var counter = 0
    fun visit(node: MultiverseNode): MultiverseNode? {
        val id = (counter++).toString()
        if (id == targetId) return node
        for (child in node.children) {
            val result = visit(child)
            if (result != null) return result
        }
        return null
    }
    return visit(graph.rootNode)
}

fun serializeGraph(graph: MultiverseGraph): GraphDto {
    var counter = 0
    val ids = mutableMapOf<MultiverseNode, String>()
    val nodes = mutableListOf<GraphNodeDto>()

    fun visit(node: MultiverseNode, parentId: String?, edgeValue: Int?) {
        val id = (counter++).toString()
        ids[node] = id
        nodes.add(GraphNodeDto(id, node.primitive, node.arg, node.displayName, node.totalInstrExecuted, parentId, edgeValue))
        for (i in node.children.indices) {
            visit(node.children[i], id, node.values.getOrNull(i))
        }
    }

    visit(graph.rootNode, null, null)
    return GraphDto(nodes, ids[graph.currentNode] ?: "0", graph.instructionOffset)
}

private val INDEX_HTML: String =
    WebDebugger::class.java.getResource("/debugger.html")!!.readText()


class WebDebugger(
    private val debugger: MultiverseDebugger,
    private val sourceMap: SourceMap? = null,
    private val port: Int = 8080,
    private val breakpointEvents: SharedFlow<Int> = MutableSharedFlow()
) {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val consoleFlow = MutableSharedFlow<String>(extraBufferCapacity = 256)

    private suspend fun RoutingContext.respondWithGraph(errorMessage: String, operation: suspend () -> Unit) {
        try {
            withContext(Dispatchers.IO) { operation() }
            call.respondText(
                mapper.writeValueAsString(serializeGraph(debugger.graph)),
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, e.message ?: errorMessage)
        }
    }

    private fun currentSourceDto(): SourceDto? {
        val map = sourceMap ?: return null
        val pc  = debugger.currentState?.pc ?: return null
        return try {
            val filename = map.getSourceFileName(pc)
            val content  = map.getSourceFile(pc)
            // getLineForPc throws NullPointerException when the PC has no source-map entry
            val line = runCatching { map.getLineForPc(pc) }.getOrDefault(0)
            SourceDto(content, line, filename)
        } catch (_: Exception) {
            null
        }
    }

    fun start(wait: Boolean = true) {
        println("Multiverse web debugger running at http://localhost:$port")
        debugger.printListener = { msg -> consoleFlow.tryEmit(msg) }
        embeddedServer(Netty, port = port) {
            install(SSE)
            routing {
                sse("/api/events") {
                    heartbeat {
                        period = 10.seconds
                        event = ServerSentEvent("heartbeat")
                    }
                    try {
                        merge(
                            breakpointEvents.map { pc -> ServerSentEvent(data = pc.toString(16), event = "breakpoint") },
                            consoleFlow.map { msg -> ServerSentEvent(data = msg, event = "console") }
                        ).collect { send(it) }
                    } catch (e: ChannelWriteException) {
                        // client disconnected mid-write; nothing to do
                    } catch (e: ClosedWriteChannelException) {
                        // client disconnected mid-write; nothing to do
                    }
                }
                get("/") {
                    call.respondText(INDEX_HTML, ContentType.Text.Html)
                }
                get("/api/graph") {
                    call.respondText(
                        mapper.writeValueAsString(serializeGraph(debugger.graph)),
                        ContentType.Application.Json
                    )
                }
                get("/api/source") {
                    if (sourceMap == null) {
                        call.respond(HttpStatusCode.NoContent)
                        return@get
                    }
                    val dto = currentSourceDto()
                    if (dto == null) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respondText(mapper.writeValueAsString(dto), ContentType.Application.Json)
                    }
                }
                post("/api/step") {
                    respondWithGraph("step failed") { debugger.stepInto() }
                }
                post("/api/predict") {
                    respondWithGraph("predict failed") { debugger.predictFuture() }
                }
                post("/api/step-line") {
                    if (sourceMap == null) {
                        call.respond(HttpStatusCode.BadRequest, "no source map")
                        return@post
                    }
                    respondWithGraph("step-line failed") {
                        val startLine = runCatching {
                            sourceMap.getLineForPc(debugger.requireCurrentState().pc!!)
                        }.getOrDefault(-1)
                        debugger.stepUntil { state ->
                            runCatching {
                                sourceMap.getLineForPc(state.pc!!) != startLine
                            }.getOrDefault(false)
                        }
                    }
                }
                post("/api/step-back") {
                    respondWithGraph("step-back failed") { debugger.stepBack(1) {} }
                }
                post("/api/reset") {
                    respondWithGraph("reset failed") { debugger.reset() }
                }
                get("/api/primitives") {
                    val dtos = debugger.wasmBinary.metadata.primitives
                        .filter { it.return_types.isNotEmpty() }
                        .map { PrimitiveDto(it.name, it.arg_types.size) }
                    call.respondText(mapper.writeValueAsString(dtos), ContentType.Application.Json)
                }
                get("/api/watch") {
                    try {
                        val snapshot = withContext(Dispatchers.IO) {
                            debugger.requireCurrentState(true)
                        }
                        val entries = mutableListOf<WatchEntryDto>()
                        snapshot.pc?.let {
                            entries.add(WatchEntryDto("pc", "i32", "0x%x".format(it)))
                        }
                        for (g in snapshot.globals ?: emptyList()) {
                            entries.add(WatchEntryDto("global ${g.idx}", g.type, g.value.toString()))
                        }
                        if (snapshot.callstack?.isNotEmpty() == true) {
                            entries.add(WatchEntryDto("fp", "i32", snapshot.callstack.last().fp.toString()))
                        }
                        for (s in snapshot.stack ?: emptyList()) {
                            val displayValue = when (s.type.uppercase()) {
                                "F32" -> Float.fromBits(s.value.toInt()).toString()
                                "F64" -> Double.fromBits(s.value).toString()
                                else  -> s.value.toString()
                            }
                            entries.add(WatchEntryDto("stack[${s.idx}]", s.type, displayValue))
                        }
                        call.respondText(
                            mapper.writeValueAsString(WatchDto(entries)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "watch failed")
                    }
                }
                get("/api/mocks") {
                    val dtos = debugger.overrides.flatMap { (primName, argsMap) ->
                        argsMap.map { (args, retVal) -> MockDto(primName, args, retVal) }
                    }
                    call.respondText(mapper.writeValueAsString(dtos), ContentType.Application.Json)
                }
                post("/api/mocks") {
                    val mock = mapper.readValue(call.receiveText(), MockDto::class.java)
                    withContext(Dispatchers.IO) {
                        debugger.addPrimitiveOverride(mock.primName, mock.args, mock.returnValue)
                    }
                    call.respond(HttpStatusCode.OK)
                }
                delete("/api/mocks") {
                    val mock = mapper.readValue(call.receiveText(), MockDto::class.java)
                    withContext(Dispatchers.IO) {
                        debugger.removePrimitiveOverride(mock.primName, mock.args)
                    }
                    call.respond(HttpStatusCode.OK)
                }
                post("/api/run") {
                    withContext(Dispatchers.IO) {
                        debugger.removeAllPrimitiveOverrides()
                        debugger.run()
                    }
                    call.respond(HttpStatusCode.OK)
                }
                post("/api/pause") {
                    respondWithGraph("pause failed") { debugger.pause() }
                }
                post("/api/slide") {
                    val dto = mapper.readValue(call.receiveText(), SlideDto::class.java)
                    val targetNode = findNodeById(debugger.graph, dto.nodeId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, "node not found")
                    respondWithGraph("slide failed") {
                        debugger.requireCurrentState(
                            debugger.requireCurrentState().breakpoints == null
                        )
                        debugger.slide(targetNode, dto.offset)
                    }
                }
                get("/api/breakpoints") {
                    if (sourceMap == null) {
                        call.respondText(mapper.writeValueAsString(BreakpointsResponseDto(emptyList())), ContentType.Application.Json)
                        return@get
                    }
                    val state = withContext(Dispatchers.IO) {
                        debugger.requireCurrentState(debugger.currentState?.breakpoints == null)
                    }
                    val lines = state.breakpoints?.mapNotNull { pc ->
                        try { sourceMap.getLineForPc(pc).takeIf { it > 0 } } catch (_: Exception) { null }
                    } ?: emptyList()
                    call.respondText(mapper.writeValueAsString(BreakpointsResponseDto(lines)), ContentType.Application.Json)
                }
                post("/api/breakpoints/add") {
                    if (sourceMap == null) return@post call.respond(HttpStatusCode.BadRequest, "No source map")
                    val dto = mapper.readValue(call.receiveText(), BreakpointLineDto::class.java)
                    try {
                        val pc = sourceMap.getPcForLine(dto.line, dto.filename)
                        withContext(Dispatchers.IO) { debugger.addBreakpoint(pc) }
                        call.respond(HttpStatusCode.OK)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, "No mapping for line ${dto.line}")
                    }
                }
                post("/api/breakpoints/remove") {
                    if (sourceMap == null) return@post call.respond(HttpStatusCode.BadRequest, "No source map")
                    val dto = mapper.readValue(call.receiveText(), BreakpointLineDto::class.java)
                    val state = withContext(Dispatchers.IO) { debugger.requireCurrentState(false) }
                    val pc = state.breakpoints?.find { bp ->
                        try { sourceMap.getLineForPc(bp) == dto.line } catch (_: Exception) { false }
                    } ?: return@post call.respond(HttpStatusCode.BadRequest, "No breakpoint at line ${dto.line}")
                    withContext(Dispatchers.IO) { debugger.removeBreakpoint(pc) }
                    call.respond(HttpStatusCode.OK)
                }
            }
        }.start(wait = wait)
    }
}
