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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

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

// language=html
private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Multiverse Debugger</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@vscode/codicons/dist/codicon.css">
<style>
  :root {
    --bg: #1e1e1e; --fg: #d4d4d4; --panel-bg: #252526; --border: #3e3e42; --muted: #888;
    --btn-bg: #3a3d41; --btn-border: #555; --btn-hover-bg: #505357; --btn-hover-border: #777;
    --icon-hover: rgba(90,93,94,0.31);
    --input-bg: #1e1e1e; --input-border: #555;
    --chip-bg: #2d2d2d; --chip-rm: #888; --chip-rm-hover: #d4d4d4; --modal-bg: #2d2d2d;
    --scroll-track: rgba(255,255,255,0.06); --scroll-thumb: rgba(255,255,255,0.25); --scroll-thumb-hover: rgba(255,255,255,0.4);
    --edge: #6a6a6a; --det-label: #999; --ret-label: #b0b0b0; --node-label: #c0c0c0; --instr-text: #fff;
    --node-fill: #4a4a4a; --node-stroke: #888; --node-fill-cur: #1e6eb5; --node-stroke-cur: #6db3ff;
    --watch-row-border: #2a2a2a; --watch-row-hover: #2a2d2e; --watch-name: #9cdcfe; --watch-type: #4ec9b0;
    --icon-green: #89d185; --icon-blue: #75beff;
  }
  body.light {
    --bg: #ffffff; --fg: #1e1e1e; --panel-bg: #f3f3f3; --border: #e0e0e0; --muted: #6a6a6a;
    --btn-bg: #d4d4d4; --btn-border: #c6c6c6; --btn-hover-bg: #b8b8b8; --btn-hover-border: #aaaaaa;
    --icon-hover: rgba(0,0,0,0.08);
    --input-bg: #ffffff; --input-border: #cccccc;
    --chip-bg: #ffffff; --chip-rm: #6a6a6a; --chip-rm-hover: #1e1e1e; --modal-bg: #ffffff;
    --scroll-track: rgba(0,0,0,0.06); --scroll-thumb: rgba(0,0,0,0.2); --scroll-thumb-hover: rgba(0,0,0,0.35);
    --edge: #9a9a9a; --det-label: #666; --ret-label: #777; --node-label: #444; --instr-text: #222;
    --node-fill: #e0e0e0; --node-stroke: #999; --node-fill-cur: #1e6eb5; --node-stroke-cur: #1565c0;
    --watch-row-border: #eaeaea; --watch-row-hover: #f0f0f0; --watch-name: #001080; --watch-type: #267f99;
    --icon-green: #388a34; --icon-blue: #006ab1;
  }

  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg);
    color: var(--fg);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow: hidden;
  }
  #toolbar {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 7px 14px;
    background: var(--panel-bg);
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }
  #toolbar h1 { font-size: 13px; font-weight: 600; color: var(--fg); margin-right: 4px; }
  button {
    padding: 4px 11px;
    background: var(--btn-bg);
    border: 1px solid var(--btn-border);
    border-radius: 3px;
    color: var(--fg);
    font-size: 12px;
    cursor: pointer;
    user-select: none;
  }
  button:hover:not(:disabled) { background: var(--btn-hover-bg); border-color: var(--btn-hover-border); }
  button:disabled { opacity: 0.4; cursor: default; }
  button.icon-btn {
    padding: 4px 6px;
    font-size: 16px;
    line-height: 1;
    background: none;
    border: none;
    border-radius: 4px;
  }
  button.icon-btn:hover:not(:disabled) { background: var(--icon-hover); border: none; }
  #btn-reset .codicon { color: var(--icon-green); }
  #btn-step-back .codicon, #btn-step .codicon, #btn-step-line .codicon,
  #btn-mock .codicon, #btn-predict .codicon { color: var(--icon-blue); }
  #status { margin-left: auto; font-size: 11px; color: var(--muted); }

  #content { flex: 1; display: flex; overflow: hidden; }
  #canvas { flex: 1; overflow: hidden; position: relative; }
  svg { width: 100%; height: 100%; }
  #h-scroll {
    position: absolute; bottom: 6px; left: 10px; right: 10px; height: 6px;
    background: var(--scroll-track); border-radius: 3px; cursor: pointer;
  }
  #h-scroll-thumb {
    position: absolute; top: 0; height: 100%;
    background: var(--scroll-thumb); border-radius: 3px;
    cursor: grab; min-width: 20px; transition: background 0.1s;
  }
  #h-scroll-thumb:hover { background: var(--scroll-thumb-hover); }
  #h-scroll-thumb:active { cursor: grabbing; }

  #source-panel {
    width: 38%; display: flex; flex-direction: column;
    border-right: 1px solid var(--border); overflow: hidden;
  }
  #source-panel.hidden { display: none; }
  #source-header {
    padding: 5px 10px; background: var(--panel-bg); border-bottom: 1px solid var(--border);
    font-size: 11px; color: var(--muted);
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex-shrink: 0;
  }
  #source-editor { flex: 1; overflow: hidden; }
  .cur-line-bg  { background: rgba(212, 160, 23, 0.12) !important; }
  .cur-line-glyph { color: #d4a017; font-size: 16px; }
  .bp-glyph { color: #e51400; font-size: 16px; }

  .edge { fill: none; stroke: var(--edge); stroke-width: 1.5; }
  .det-label { font-size: 10px; fill: var(--det-label); }
  .ret-label { font-size: 10px; fill: var(--ret-label); }
  .node-label { font-size: 11px; fill: var(--node-label); }
  .instr-text { font-size: 10px; fill: var(--instr-text); }
  .node circle { fill: var(--node-fill); stroke: var(--node-stroke); stroke-width: 1.5; }
  .node.current circle { fill: var(--node-fill-cur); stroke: var(--node-stroke-cur); stroke-width: 2.5; }

  /* Status bar */
  #status-bar {
    display: flex; align-items: center; height: 22px;
    background: #007acc; flex-shrink: 0; font-size: 11px;
  }
  .status-item {
    display: flex; align-items: center; gap: 4px; padding: 0 8px; height: 100%;
    background: none; border: none; border-radius: 0;
    color: #fff; font-size: 11px; cursor: pointer; user-select: none;
  }
  .status-item:hover { background: rgba(255,255,255,0.12); }
  .status-item.active { background: rgba(0,0,0,0.18); }

  /* Console panel */
  #console-panel {
    flex-shrink: 0; height: 180px; display: flex; flex-direction: column;
    border-top: 1px solid var(--border); background: var(--bg);
  }
  #console-panel.hidden { display: none; }
  #console-output {
    flex: 1; overflow-y: auto; padding: 4px 10px;
    font-family: "SF Mono","Consolas","Menlo",monospace; font-size: 11px;
  }
  .console-line { color: var(--fg); white-space: pre-wrap; word-break: break-all; line-height: 1.5; }
  .console-line.stderr { color: #f48771; }

  /* Watch panel */
  #watch-panel {
    flex-shrink: 0; height: 180px; overflow: auto;
    border-top: 1px solid var(--border); background: var(--bg);
  }
  #watch-panel.hidden { display: none; }
  #watch-table { width: 100%; border-collapse: collapse; font-size: 11px; font-family: "SF Mono","Consolas","Menlo",monospace; }
  #watch-table thead th {
    position: sticky; top: 0; background: var(--panel-bg); color: var(--muted);
    font-weight: 400; text-align: left; padding: 3px 10px; border-bottom: 1px solid var(--border);
  }
  #watch-table tbody td {
    padding: 2px 10px; color: var(--fg); border-bottom: 1px solid var(--watch-row-border); white-space: nowrap;
  }
  #watch-table tbody td:first-child { color: var(--watch-name); }
  #watch-table tbody td:nth-child(2) { color: var(--watch-type); }
  #watch-table tbody tr:hover td { background: var(--watch-row-hover); }

  /* Mocks bar */
  #mocks-bar {
    display: flex; flex-wrap: wrap; gap: 6px; padding: 5px 14px;
    background: var(--panel-bg); border-bottom: 1px solid var(--border);
  }
  #mocks-bar:empty { display: none; }
  .mock-chip {
    display: flex; align-items: center; gap: 5px; padding: 2px 8px 2px 10px;
    background: var(--chip-bg); border: 1px solid var(--btn-border); border-radius: 12px; font-size: 11px;
  }
  .mock-chip .rm { background: none; border: none; color: var(--chip-rm); cursor: pointer; font-size: 14px; line-height: 1; padding: 0; }
  .mock-chip .rm:hover { color: var(--chip-rm-hover); }

  /* Mock modal */
  #mock-overlay {
    position: fixed; inset: 0; background: rgba(0,0,0,0.55);
    display: flex; align-items: center; justify-content: center; z-index: 100;
  }
  #mock-overlay.hidden { display: none; }
  #mock-modal {
    background: var(--modal-bg); border: 1px solid var(--border); border-radius: 6px;
    padding: 20px; min-width: 300px; display: flex; flex-direction: column; gap: 14px;
  }
  #mock-modal h3 { font-size: 13px; font-weight: 600; }
  .mf { display: flex; flex-direction: column; gap: 4px; }
  .mf label { font-size: 11px; color: var(--muted); }
  .mf select, .mf input {
    background: var(--input-bg); border: 1px solid var(--input-border); color: var(--fg);
    padding: 5px 8px; border-radius: 3px; font-size: 12px; width: 100%;
  }
  #mock-args-fields { display: flex; gap: 6px; }
  #mock-args-fields input { flex: 1; min-width: 0; }
  #mock-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
</head>
<body>
<div id="toolbar">
  <button class="icon-btn" id="btn-pause-continue" title="Continue"><i class="codicon codicon-debug-continue" style="color:var(--icon-green)"></i></button>
  <button class="icon-btn" id="btn-reset" title="Reset"><i class="codicon codicon-debug-restart"></i></button>
  <button class="icon-btn" id="btn-step-back" title="Step Back"><i class="codicon codicon-debug-step-back"></i></button>
  <button class="icon-btn" id="btn-step" title="Step"><i class="codicon codicon-debug-step-over"></i></button>
  <button class="icon-btn" id="btn-step-line" title="Step Line"><i class="codicon codicon-debug-step-into"></i></button>
  <button class="icon-btn" id="btn-mock" title="Mock"><i class="codicon codicon-worktree"></i></button>
  <button class="icon-btn" id="btn-predict" title="Suggest Paths"><i class="codicon codicon-sparkle"></i></button>
  <button class="icon-btn" id="btn-theme" title="Toggle theme" style="font-size:14px;margin-left:auto">Dark</button>
</div>
<div id="mocks-bar"></div>
<div id="content">
  <div id="source-panel" class="hidden">
    <div id="source-header">No source</div>
    <div id="source-editor"></div>
  </div>
  <div id="canvas"><svg id="svg"></svg><div id="h-scroll"><div id="h-scroll-thumb"></div></div></div>
</div>
<div id="console-panel" class="hidden">
  <div id="console-output"></div>
</div>
<div id="watch-panel" class="hidden">
  <table id="watch-table">
    <thead><tr><th>Name</th><th>Type</th><th>Value</th></tr></thead>
    <tbody id="watch-body"></tbody>
  </table>
</div>
<div id="status-bar">
  <button id="btn-watch-toggle" class="status-item" title="Toggle Watch Window"><i class="codicon codicon-variable"></i> Watch</button>
  <button id="btn-console-toggle" class="status-item" title="Toggle Console"><i class="codicon codicon-terminal"></i> Console</button>
</div>
<div id="mock-overlay" class="hidden">
  <div id="mock-modal">
    <h3>Add Mock</h3>
    <div class="mf">
      <label>Primitive</label>
      <select id="mock-prim-select"></select>
    </div>
    <div class="mf" id="mock-args-row" style="display:none">
      <label>Arguments</label>
      <div id="mock-args-fields"></div>
    </div>
    <div class="mf">
      <label>Return value</label>
      <input id="mock-return-value" type="number" placeholder="0">
    </div>
    <div id="mock-footer">
      <button id="btn-mock-cancel">Cancel</button>
      <button id="btn-mock-submit">Add Mock</button>
    </div>
  </div>
</div>
<script src="https://d3js.org/d3.v7.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/monaco-editor@0.52.0/min/vs/loader.js"></script>
<script>
'use strict';

// ── Graph ────────────────────────────────────────────────────────────────────

const svg = d3.select('#svg');
const g = svg.append('g');

const zoom = d3.zoom()
  .scaleExtent([0.05, 10])
  .on('zoom', e => {
    g.attr('transform', e.transform);
    currentTransform = e.transform;
    updateScrollbar();
  });
svg.call(zoom);

let busy = false;
let paused = true;
let zoomInitialized = false;
let currentTransform = d3.zoomIdentity;
let natW = 0;
let hasSourceMap = false;
let watchOpen = false;
let consoleOpen = false;
let currentFilename = null;
let currentLine = 0;
let breakpointDecorations = null;
let currentBreakpointLines = new Set();

// ── Theme ───────────────────────────────────────────────────────────────────
let currentTheme = localStorage.getItem('theme') || 'dark';
let monacoEditor = null;   // forward-declared; Monaco callback sets this

function applyTheme(theme) {
  currentTheme = theme;
  document.body.classList.toggle('light', theme === 'light');
  const btn = document.getElementById('btn-theme');
  if (btn) btn.textContent = theme === 'light' ? 'Dark' : 'Light️';
  if (monacoEditor) monaco.editor.setTheme(theme === 'light' ? 'vs' : 'vs-dark');
  localStorage.setItem('theme', theme);
}
applyTheme(currentTheme);

document.getElementById('btn-theme').addEventListener('click', () => {
  applyTheme(currentTheme === 'dark' ? 'light' : 'dark');
});

function setStatus(msg) { console.log('[status]', msg); }

function updateButtons() {
  ['btn-step', 'btn-step-back', 'btn-reset', 'btn-mock', 'btn-predict'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.disabled = busy || !paused;
  });
  document.getElementById('btn-step-line').disabled = busy || !paused || !hasSourceMap;
  document.getElementById('btn-pause-continue').disabled = busy;
}

function setBusy(val) {
  busy = val;
  updateButtons();
}

function setPaused(val) {
  paused = val;
  const btn = document.getElementById('btn-pause-continue');
  const icon = btn.querySelector('i');
  if (paused) {
    btn.title = 'Continue';
    icon.className = 'codicon codicon-debug-continue';
    icon.style.color = 'var(--icon-green)';
  } else {
    btn.title = 'Pause';
    icon.className = 'codicon codicon-debug-pause';
    icon.style.color = '';
  }
  updateButtons();
}

// ── Scrollbar indicator ──────────────────────────────────────────────────────

function updateScrollbar() {
  const canvas = document.getElementById('canvas');
  const track  = document.getElementById('h-scroll');
  const thumb  = document.getElementById('h-scroll-thumb');
  if (!thumb || natW === 0) return;

  const vw = canvas.clientWidth;
  const contentW = natW * currentTransform.k;
  const trackW = track.clientWidth;
  const visRatio = Math.min(1, vw / contentW);
  const thumbW = Math.max(20, visRatio * trackW);
  thumb.style.width = thumbW + 'px';

  if (visRatio >= 1) {
    thumb.style.left = '0px';
    thumb.style.opacity = '0.4';
    return;
  }
  thumb.style.opacity = '1';
  const maxScroll = contentW - vw;
  const posRatio = Math.max(0, Math.min(1, -currentTransform.x / maxScroll));
  thumb.style.left = (posRatio * (trackW - thumbW)) + 'px';
}

(function initScrollbarInteraction() {
  const track  = document.getElementById('h-scroll');
  const thumb  = document.getElementById('h-scroll-thumb');
  const canvas = document.getElementById('canvas');

  thumb.addEventListener('mousedown', e => {
    e.preventDefault();
    e.stopPropagation();
    const startClientX = e.clientX;
    const startLeft    = thumb.offsetLeft;
    const trackW  = track.clientWidth;
    const thumbW  = thumb.offsetWidth;
    const onMove = ev => {
      const newLeft = Math.max(0, Math.min(trackW - thumbW, startLeft + ev.clientX - startClientX));
      const posRatio = (trackW - thumbW) > 0 ? newLeft / (trackW - thumbW) : 0;
      const contentW = natW * currentTransform.k;
      const newX = -(posRatio * Math.max(0, contentW - canvas.clientWidth));
      svg.call(zoom.transform,
        d3.zoomIdentity.translate(newX, currentTransform.y).scale(currentTransform.k));
    };
    const onUp = () => {
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  });

  track.addEventListener('click', e => {
    if (e.target === thumb) return;
    const rect  = track.getBoundingClientRect();
    const thumbW = thumb.offsetWidth;
    const trackW = track.clientWidth;
    const posRatio = Math.max(0, Math.min(1,
      (e.clientX - rect.left - thumbW / 2) / Math.max(1, trackW - thumbW)));
    const contentW = natW * currentTransform.k;
    const newX = -(posRatio * Math.max(0, contentW - canvas.clientWidth));
    svg.call(zoom.transform,
      d3.zoomIdentity.translate(newX, currentTransform.y).scale(currentTransform.k));
  });
})();

// Expand each MultiverseNode into an (entry dot, choice circle) pair so the
// graph shows:  ○ ─── N instrs ─── ○(name) ─── = val ─── ○ ─── …
function expandGraph(rawData) {
  const childrenOf = {};
  for (const n of rawData.nodes) {
    if (n.parentId != null)
      (childrenOf[n.parentId] = childrenOf[n.parentId] || []).push(n);
  }
  const curNode  = rawData.nodes.find(n => n.id === rawData.currentNodeId);
  const atChoice = !curNode || curNode.totalInstrExecuted === 0
    || rawData.instructionOffset >= curNode.totalInstrExecuted;
  const expanded = [];
  for (const n of rawData.nodes) {
    const children       = childrenOf[n.id] || [];
    const choiceLabel    = children.length > 0 ? children[0].displayName : '';
    const parentChoiceId = n.parentId != null ? n.parentId + '_c' : null;
    if (n.totalInstrExecuted > 0) {
      // Entry dot: start of the deterministic segment.
      expanded.push({
        id: n.id + '_e', parentId: parentChoiceId,
        type: 'entry', edgeValue: n.edgeValue,
        totalInstrExecuted: n.totalInstrExecuted, displayName: '',
      });
      // Choice circle: the non-det call at the end of the segment.
      expanded.push({
        id: n.id + '_c', parentId: n.id + '_e',
        type: 'choice', edgeValue: null, totalInstrExecuted: 0,
        displayName: choiceLabel,
      });
    } else {
      // No det instructions: single choice circle directly under parent.
      expanded.push({
        id: n.id + '_c', parentId: parentChoiceId,
        type: 'choice', edgeValue: n.edgeValue, totalInstrExecuted: 0,
        displayName: choiceLabel,
      });
    }
  }
  return {
    nodes: expanded,
    currentNodeId:      atChoice ? rawData.currentNodeId + '_c' : rawData.currentNodeId + '_e',
    instructionOffset:  rawData.instructionOffset,
    currentTotal:       curNode?.totalInstrExecuted ?? 0,
    currentDisplayName: curNode?.displayName ?? '',
  };
}

function renderGraph(rawData) {
  lastGraphData = rawData;
  g.selectAll('*').remove();

  if (!rawData || !rawData.nodes || rawData.nodes.length === 0) {
    setStatus('Graph empty');
    return;
  }

  const data = expandGraph(rawData);

  let hierarchy;
  try {
    hierarchy = d3.stratify()
      .id(d => d.id)
      .parentId(d => d.parentId)
      (data.nodes);
  } catch (e) {
    setStatus('Layout error: ' + e.message);
    return;
  }

  d3.tree().nodeSize([100, 140])(hierarchy);

  // Compute natural content bounds (scale=1) for the scrollbar indicator.
  // In linkHorizontal layout: node SVG coords = (d.y, d.x).
  const desc = hierarchy.descendants();
  const ys = desc.map(d => d.y);
  const xs = desc.map(d => d.x);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const padH = 80, padV = 50;
  natW = (maxY - minY) + padH * 2;
  if (!zoomInitialized && desc.length > 0) {
    zoomInitialized = true;
    svg.call(zoom.transform, d3.zoomIdentity.translate(padH - minY, padV - minX));
  }
  updateScrollbar();

  // ── Edges ──────────────────────────────────────────────────────────────────
  g.selectAll('.edge')
    .data(hierarchy.links())
    .join('path')
    .attr('class', 'edge')
    .attr('d', d3.linkHorizontal().x(d => d.y).y(d => d.x));

  // Det count label on entry→choice edges.
  g.selectAll('.det-label')
    .data(hierarchy.links().filter(
      d => d.source.data.type === 'entry' && d.source.data.totalInstrExecuted > 0))
    .join('text')
    .attr('class', 'det-label')
    .attr('text-anchor', 'middle')
    .attr('x', d => (d.source.y + d.target.y) / 2)
    .attr('y', d => (d.source.x + d.target.x) / 2 - 7)
    .text(d => d.source.data.totalInstrExecuted + ' instrs');

  // Return value label on choice→entry edges.
  g.selectAll('.ret-label')
    .data(hierarchy.links().filter(
      d => d.source.data.type === 'choice'
        && d.target.data.edgeValue !== null && d.target.data.edgeValue !== undefined))
    .join('text')
    .attr('class', 'ret-label')
    .attr('text-anchor', 'middle')
    .attr('x', d => (d.source.y + d.target.y) / 2)
    .attr('y', d => (d.source.x + d.target.x) / 2 + 12)
    .text(d => d.target.data.edgeValue);

  // ── Nodes ──────────────────────────────────────────────────────────────────
  const R = 12;
  const isCur = d => d.data.id === data.currentNodeId;

  const nodeG = g.selectAll('.node')
    .data(hierarchy.descendants())
    .join('g')
    .attr('class', 'node')
    .attr('transform', d => 'translate(' + d.y + ',' + d.x + ')');

  nodeG.classed('current', isCur);

  nodeG.style('cursor', 'pointer')
    .on('click', (event, d) => {
      if (busy || !paused) return;
      if (d.data.id === data.currentNodeId) return;
      const rawId = d.data.id.replace(/_[ec]$/, '');
      const rawNode = rawData.nodes.find(n => n.id === rawId);
      if (!rawNode) return;
      const targetOffset = d.data.id.endsWith('_c') ? rawNode.totalInstrExecuted : 0;
      slideToNode(rawId, targetOffset);
    });

  nodeG.append('circle').attr('r', R);

  // displayName above choice circles (the non-det call name).
  nodeG.filter(d => d.data.type === 'choice' && d.data.displayName)
    .append('text')
    .attr('class', 'node-label')
    .attr('text-anchor', 'middle')
    .attr('dy', -(R + 6))
    .text(d => d.data.displayName);

  // Current-position progress indicator below the active node.
  nodeG.filter(isCur)
    .append('text')
    .attr('class', 'instr-text')
    .attr('text-anchor', 'middle')
    .attr('dy', R + 14)
    .text(rawData.instructionOffset + '/' + data.currentTotal);

  setStatus('At: ' + data.currentDisplayName
    + '  offset ' + rawData.instructionOffset + '/' + data.currentTotal);
}

// ── Monaco source viewer ─────────────────────────────────────────────────────

let pendingSource = null;
let decorationCollection = null;

require.config({ paths: { vs: 'https://cdn.jsdelivr.net/npm/monaco-editor@0.52.0/min/vs' } });
require(['vs/editor/editor.main'], () => {
  monacoEditor = monaco.editor.create(document.getElementById('source-editor'), {
    value: '',
    language: 'typescript',
    theme: currentTheme === 'light' ? 'vs' : 'vs-dark',
    readOnly: true,
    minimap: { enabled: false },
    glyphMargin: true,
    automaticLayout: true,
    scrollBeyondLastLine: false,
    fontSize: 12,
    renderLineHighlight: 'none',
  });
  decorationCollection = monacoEditor.createDecorationsCollection([]);
  breakpointDecorations = monacoEditor.createDecorationsCollection([]);
  // Apply current theme in case it was toggled before Monaco finished loading
  monaco.editor.setTheme(currentTheme === 'light' ? 'vs' : 'vs-dark');
  if (pendingSource) { applySource(pendingSource); pendingSource = null; }

  monacoEditor.onMouseDown(e => {
    const t = e.target;
    const GLYPH = monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN;
    const LINE_NO = monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS;
    if (t.type !== GLYPH && t.type !== LINE_NO) return;
    const line = t.position?.lineNumber;
    if (line) toggleBreakpoint(line);
  });
});

function langForFile(filename) {
  if (/\.tsx?$/.test(filename))      return 'typescript';
  if (/\.js$/.test(filename))        return 'javascript';
  if (/\.(c|cpp|h)$/.test(filename)) return 'cpp';
  return 'plaintext';
}

function applySource(data) {
  currentFilename = data.filename;
  currentLine = data.currentLine;
  const model = monacoEditor.getModel();
  monaco.editor.setModelLanguage(model, langForFile(data.filename));
  if (model.getValue() !== data.content) monacoEditor.setValue(data.content);

  if (data.currentLine > 0) {
    const atBreakpoint = currentBreakpointLines.has(data.currentLine);
    const glyphClass = atBreakpoint
      ? 'codicon codicon-debug-stackframe-active cur-line-glyph'
      : 'codicon codicon-debug-stackframe cur-line-glyph';
    decorationCollection.set([{
      range: new monaco.Range(data.currentLine, 1, data.currentLine, 1),
      options: { isWholeLine: true, className: 'cur-line-bg', glyphMarginClassName: glyphClass }
    }]);
    monacoEditor.revealLineInCenter(data.currentLine);
  } else {
    decorationCollection.set([]);
  }
}

function renderSource(data) {
  if (!data) return;
  hasSourceMap = true;
  updateButtons();
  const panel = document.getElementById('source-panel');
  panel.classList.remove('hidden');
  document.getElementById('source-header').textContent = data.filename;
  if (monacoEditor) applySource(data);
  else pendingSource = data;
}

async function fetchSource() {
  const r = await fetch('/api/source');
  if (r.status === 204) return null;
  if (!r.ok) return null;
  return r.json();
}

// ── Mocking ──────────────────────────────────────────────────────────────────

let primitives = [];
let lastGraphData = null;

async function fetchPrimitives() {
  const r = await fetch('/api/primitives');
  if (r.ok) primitives = await r.json();
}

async function fetchMocks() {
  const r = await fetch('/api/mocks');
  if (r.ok) renderMocks(await r.json());
}

function renderMocks(mocks) {
  const bar = document.getElementById('mocks-bar');
  bar.innerHTML = '';
  for (const mock of mocks) {
    const chip = document.createElement('div');
    chip.className = 'mock-chip';
    const lbl = document.createElement('span');
    lbl.textContent = mock.primName + '(' + mock.args.join(', ') + ') = ' + mock.returnValue;
    const btn = document.createElement('button');
    btn.className = 'rm';
    btn.textContent = '×';
    btn.title = 'Remove mock';
    btn.addEventListener('click', async () => {
      await fetch('/api/mocks', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(mock)
      });
      fetchMocks();
    });
    chip.append(lbl, btn);
    bar.appendChild(chip);
  }
}

// ── Watch window ─────────────────────────────────────────────────────────────

// ── Breakpoints ──────────────────────────────────────────────────────────────

async function fetchBreakpoints() {
  if (!hasSourceMap) return;
  try {
    const r = await fetch('/api/breakpoints');
    if (r.ok) renderBreakpoints(await r.json());
  } catch (_) {}
}

function renderBreakpoints(data) {
  if (!breakpointDecorations) return;
  currentBreakpointLines = new Set(data.lines);
  breakpointDecorations.set(
    data.lines
      .filter(line => line !== currentLine)
      .map(line => ({
        range: new monaco.Range(line, 1, line, 1),
        options: { glyphMarginClassName: 'codicon codicon-circle-filled bp-glyph' }
      }))
  );
  // Refresh the stackframe decoration now that breakpoint state is updated
  if (currentLine > 0 && decorationCollection) {
    const atBreakpoint = currentBreakpointLines.has(currentLine);
    const glyphClass = atBreakpoint
      ? 'codicon codicon-debug-stackframe-active cur-line-glyph'
      : 'codicon codicon-debug-stackframe cur-line-glyph';
    decorationCollection.set([{
      range: new monaco.Range(currentLine, 1, currentLine, 1),
      options: { isWholeLine: true, className: 'cur-line-bg', glyphMarginClassName: glyphClass }
    }]);
  }
}

async function toggleBreakpoint(line) {
  if (!currentFilename) return;
  const url = currentBreakpointLines.has(line) ? '/api/breakpoints/remove' : '/api/breakpoints/add';
  try {
    await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ line, filename: currentFilename })
    });
    await fetchBreakpoints();
  } catch (_) {}
}

async function fetchWatch() {
  if (!watchOpen) return;
  const body = document.getElementById('watch-body');
  if (!paused) { body.innerHTML = ''; return; }
  try {
    const r = await fetch('/api/watch');
    if (r.ok) renderWatch(await r.json());
  } catch (_) {}
}

function renderWatch(data) {
  const body = document.getElementById('watch-body');
  body.innerHTML = '';
  for (const e of data.entries) {
    const tr = document.createElement('tr');
    ['name', 'type', 'value'].forEach(k => {
      const td = document.createElement('td');
      td.textContent = e[k];
      tr.appendChild(td);
    });
    body.appendChild(tr);
  }
}

document.getElementById('btn-console-toggle').addEventListener('click', () => {
  consoleOpen = !consoleOpen;
  document.getElementById('console-panel').classList.toggle('hidden', !consoleOpen);
  document.getElementById('btn-console-toggle').classList.toggle('active', consoleOpen);
});

document.getElementById('btn-watch-toggle').addEventListener('click', () => {
  watchOpen = !watchOpen;
  document.getElementById('watch-panel').classList.toggle('hidden', !watchOpen);
  document.getElementById('btn-watch-toggle').classList.toggle('active', watchOpen);
  fetchWatch();
});

function updateArgFields(argCount, prefillValues, disabled) {
  const container = document.getElementById('mock-args-fields');
  container.innerHTML = '';
  for (let i = 0; i < argCount; i++) {
    const inp = document.createElement('input');
    inp.type = 'number';
    inp.placeholder = 'arg ' + i;
    inp.id = 'mock-arg-' + i;
    if (prefillValues && prefillValues[i] !== undefined) inp.value = prefillValues[i];
    if (disabled) inp.disabled = true;
    container.appendChild(inp);
  }
  document.getElementById('mock-args-row').style.display = argCount > 0 ? '' : 'none';
}

function showMockModal() {
  const sel = document.getElementById('mock-prim-select');
  sel.innerHTML = '';
  for (const p of primitives) {
    const opt = document.createElement('option');
    opt.value = p.name;
    opt.textContent = p.name;
    sel.appendChild(opt);
  }
  document.getElementById('mock-return-value').value = '';

  // If we're at a known choice point, pre-fill and lock the primitive + args.
  if (lastGraphData) {
    const curNode = lastGraphData.nodes.find(n => n.id === lastGraphData.currentNodeId);
    const children = lastGraphData.nodes.filter(n => n.parentId === lastGraphData.currentNodeId);
    const atCP = curNode
      && lastGraphData.instructionOffset >= curNode.totalInstrExecuted
      && children.length > 0;
    if (atCP) {
      const child = children[0];
      sel.value = child.primitive;
      sel.disabled = true;
      const prim = primitives.find(p => p.name === child.primitive);
      updateArgFields(prim ? prim.argCount : child.arg.length, child.arg, true);
      document.getElementById('mock-overlay').classList.remove('hidden');
      return;
    }
  }

  sel.disabled = false;
  const first = primitives[0];
  if (first) updateArgFields(first.argCount, null, false);
  document.getElementById('mock-overlay').classList.remove('hidden');
}

function closeMockModal() {
  document.getElementById('mock-overlay').classList.add('hidden');
  document.getElementById('mock-prim-select').disabled = false;
}

document.getElementById('mock-prim-select').addEventListener('change', e => {
  const prim = primitives.find(p => p.name === e.target.value);
  if (prim) updateArgFields(prim.argCount, null, false);
});
document.getElementById('btn-mock').addEventListener('click', showMockModal);
document.getElementById('btn-mock-cancel').addEventListener('click', closeMockModal);
document.getElementById('mock-overlay').addEventListener('click', e => {
  if (e.target.id === 'mock-overlay') closeMockModal();
});
document.getElementById('btn-mock-submit').addEventListener('click', async () => {
  const primName = document.getElementById('mock-prim-select').value;
  const prim = primitives.find(p => p.name === primName);
  const args = [];
  for (let i = 0; i < (prim ? prim.argCount : 0); i++) {
    const v = document.getElementById('mock-arg-' + i)?.value ?? '0';
    args.push(parseInt(v, 10));
  }
  const rv = document.getElementById('mock-return-value').value;
  if (rv === '') return;
  await fetch('/api/mocks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ primName, args, returnValue: parseInt(rv, 10) })
  });
  closeMockModal();
  fetchMocks();
});

// ── Init & actions ───────────────────────────────────────────────────────────

async function init() {
  try {
    await fetchPrimitives();
    const [graph, source] = await Promise.all([
      fetch('/api/graph').then(r => r.json()),
      fetchSource(),
      fetchMocks()
    ]);
    renderGraph(graph);
    renderSource(source);
    fetchWatch();
    fetchBreakpoints();
  } catch (e) {
    setStatus('Error: ' + e.message);
  }
}

async function slideToNode(nodeId, offset) {
  setBusy(true);
  try {
    const r = await fetch('/api/slide', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId, offset })
    });
    if (!r.ok) throw new Error(await r.text());
    const graph = await r.json();
    renderGraph(graph);
    const [source] = await Promise.all([fetchSource(), fetchMocks()]);
    renderSource(source);
    fetchWatch();
    fetchBreakpoints();
  } catch (e) {
    setStatus('Error: ' + e.message);
  }
  setBusy(false);
}

async function action(url) {
  if (busy) return;
  setBusy(true);
  setStatus('Running…');
  try {
    const r = await fetch(url, { method: 'POST' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const graph = await r.json();
    renderGraph(graph);
    const [source] = await Promise.all([fetchSource(), fetchMocks()]);
    renderSource(source);
    fetchWatch();
    fetchBreakpoints();
  } catch (e) {
    setStatus('Error: ' + e.message);
  }
  setBusy(false);
}

document.getElementById('btn-pause-continue').addEventListener('click', async () => {
  if (paused) {
    await fetch('/api/run', { method: 'POST' });
    setPaused(false);
    fetchWatch();
  } else {
    setBusy(true);
    try {
      const r = await fetch('/api/pause', { method: 'POST' });
      if (r.ok) {
        const graph = await r.json();
        renderGraph(graph);
        const [source] = await Promise.all([fetchSource(), fetchMocks()]);
        renderSource(source);
      }
    } catch (e) {
      setStatus('Error: ' + e.message);
    }
    setPaused(true);
    fetchWatch();
    fetchBreakpoints();
    setBusy(false);
  }
});

document.getElementById('btn-predict').addEventListener('click', () => action('/api/predict'));
document.getElementById('btn-step').addEventListener('click', () => action('/api/step'));
document.getElementById('btn-step-line').addEventListener('click', () => action('/api/step-line'));
document.getElementById('btn-step-back').addEventListener('click', () => action('/api/step-back'));
document.getElementById('btn-reset').addEventListener('click', () => action('/api/reset'));

init();

// ── Server-Sent Events ───────────────────────────────────────────────────────
const evtSource = new EventSource('/api/events');
evtSource.addEventListener('console', e => {
  const output = document.getElementById('console-output');
  const line = document.createElement('div');
  line.className = 'console-line';
  line.textContent = e.data;
  output.appendChild(line);
  output.scrollTop = output.scrollHeight;
});

evtSource.addEventListener('breakpoint', async () => {
  if (paused) return;
  setPaused(true);
  const graph = await fetch('/api/graph').then(r => r.json());
  renderGraph(graph);
  const [source] = await Promise.all([fetchSource(), fetchMocks()]);
  renderSource(source);
  fetchWatch();
  fetchBreakpoints();
});
</script>
</body>
</html>
""".trimIndent()

class WebDebugger(
    private val debugger: MultiverseDebugger,
    private val sourceMap: SourceMap? = null,
    private val port: Int = 8080,
    private val breakpointEvents: SharedFlow<Int> = MutableSharedFlow()
) {
    private val mapper = ObjectMapper().registerKotlinModule()
    private val consoleFlow = MutableSharedFlow<String>(extraBufferCapacity = 256)

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

    fun start() {
        println("Multiverse web debugger running at http://localhost:$port")
        debugger.printListener = { msg -> consoleFlow.tryEmit(msg) }
        embeddedServer(Netty, port = port) {
            install(SSE)
            routing {
                sse("/api/events") {
                    heartbeat {
                        period = 10.milliseconds
                        event = ServerSentEvent("heartbeat")
                    }
                    merge(
                        breakpointEvents.map { pc -> ServerSentEvent(data = pc.toString(16), event = "breakpoint") },
                        consoleFlow.map { msg -> ServerSentEvent(data = msg, event = "console") }
                    ).collect { send(it) }
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
                    try {
                        withContext(Dispatchers.IO) {
                            debugger.stepInto()
                        }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "step failed")
                    }
                }
                post("/api/predict") {
                    try {
                        withContext(Dispatchers.IO) { debugger.predictFuture() }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "predict failed")
                    }
                }
                post("/api/step-line") {
                    if (sourceMap == null) {
                        call.respond(HttpStatusCode.BadRequest, "no source map")
                        return@post
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val startLine = runCatching {
                                sourceMap.getLineForPc(debugger.requireCurrentState().pc!!)
                            }.getOrDefault(-1)
                            debugger.stepUntil { state ->
                                runCatching {
                                    sourceMap.getLineForPc(state.pc!!) != startLine
                                }.getOrDefault(false)
                            }
                        }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "step-line failed")
                    }
                }
                post("/api/step-back") {
                    try {
                        withContext(Dispatchers.IO) { debugger.stepBack(1) {} }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "step-back failed")
                    }
                }
                post("/api/reset") {
                    try {
                        withContext(Dispatchers.IO) {
                            debugger.reset()
                        }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "reset failed")
                    }
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
                    try {
                        withContext(Dispatchers.IO) { debugger.pause() }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "pause failed")
                    }
                }
                post("/api/slide") {
                    try {
                        val dto = mapper.readValue(call.receiveText(), SlideDto::class.java)
                        val targetNode = findNodeById(debugger.graph, dto.nodeId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, "node not found")
                        withContext(Dispatchers.IO) {
                            debugger.requireCurrentState(
                                debugger.requireCurrentState().breakpoints == null
                            )
                            debugger.slide(targetNode, dto.offset)
                        }
                        call.respondText(
                            mapper.writeValueAsString(serializeGraph(debugger.graph)),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, e.message ?: "slide failed")
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
        }.start(wait = true)
    }
}
