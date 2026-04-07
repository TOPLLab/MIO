package be.ugent.topl.mio;

import be.ugent.topl.mio.debugger.Debugger;
import be.ugent.topl.mio.sourcemap.SourceMap;
import be.ugent.topl.mio.woodstate.Frame;
import be.ugent.topl.mio.woodstate.WOODDumpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static be.ugent.topl.mio.sourcemap.DwarfSourceMapKt.getDwarfSourcemap;

public class GdbStub {
    private final Debugger debugger;
    private final String binaryLocation;
    private OutputStream out;
    private final Logger logger = LoggerFactory.getLogger(GdbStub.class);
    private final SourceMap debugSourceMap; // Remove later, lldb doesn't need this.

    public GdbStub(Debugger debugger, String binaryLocation) {
        this.debugger = debugger;
        this.binaryLocation = binaryLocation;
        this.debugSourceMap = getDwarfSourcemap(binaryLocation);

        debugger.getBreakpointsListeners().add((pc) -> {
            try {
                logger.info("Stopped at breakpoint {}", pc);
                sendPacket(out, "S05");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private String toHex(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);

        for (int i = 0; i < length; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append(HEX[b >>> 4]);
            sb.append(HEX[b & 0x0F]);
        }

        return sb.toString();
    }

    private String toHex(long data, int maxLen, boolean bigEndian) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < maxLen; i++) {
            long b = (data >> i * 8) & 0xff;
            if (!bigEndian) {
                b = (data >> (maxLen - i - 1) * 8) & 0xff;
            }
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String toHex(long data) {
        return toHex(data, 8, true);
    }

    private long toWasmAddr(long addr) {
        // We use the upper bits of the address to indicate the type, being part of the object (0) or part of the wasm memory (1).
        return addr | 0x1L << 63;
    }

    private long getAddrType(long addr) {
        return addr >>> 63;
    }

    private long stripAddrType(long addr) {
        return addr & Long.MAX_VALUE;
    }

    private String getTriple(String s) {
        String hex = s.chars()
                .mapToObj(c -> String.format("%02x", c))
                .reduce("", String::concat);
        return hex;
    }

    public WOODDumpResponse getCurrentState() {
        return debugger.getCheckpoints().getLast().getSnapshot();
    }

    public void start(int port) throws IOException {
        debugger.pause();

        byte[] wasmData = Files.readAllBytes(Path.of(binaryLocation));

        ServerSocket server = new ServerSocket(port);
        logger.info("Waiting for LLDB connection on port {}...", port);
        Socket sock = server.accept();
        logger.info("LLDB connected! {}", sock.getInetAddress());

        InputStream in = sock.getInputStream();
        out = sock.getOutputStream();

        boolean attached = true;

        while (attached) {
            String pkt = recvPacket(in, out);
            if (pkt == null) {
                logger.info("Connection closed");
                break;
            }
            logger.trace("<- {}", pkt);

            if (pkt.startsWith("m")) {
                pkt = pkt.substring(1);
                String[] memArgs = pkt.split(",");
                long pos = Long.parseUnsignedLong(memArgs[0], 16);
                long addrType = getAddrType(pos);
                pos = stripAddrType(pos);
                long len = Long.parseUnsignedLong(memArgs[1], 16);
                logger.info("Read memory {} bytes from {} with addr type = {}", len, pos, addrType);

                // TODO: Hack to temporarily prevent lldb from using breakpoints when stepping
                //  https://github.com/llvm/llvm-project/issues/189960
                /*if (addrType == 0) {
                    sendPacket(out, "E01");
                    continue;
                }*/

                byte[] memory = wasmData;
                if (addrType == 1) {
                    logger.info("Reading from wasm linear memory");
                    memory = getCurrentState().getMemory().getBytes();
                }
                else {
                    //pos -= codeSection.offset;
                }

                // The reply may contain fewer addressable memory units than requested if the server was reading from a trace frame memory and was able to read only part of the region of memory.
                if (pos >= memory.length || pos < 0) {
                    logger.warn("Pos {} out of bounds for length {}", pos, memory.length);
                    sendPacket(out, "E01");
                    continue;
                }

                StringBuilder result = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    // TODO: read from pos + i
                    // also think about little vs big endian
                    int index = (int) pos + i;
                    if (index >= memory.length) {
                        logger.info("Stop reading, out of bounds");
                        break;
                    }
                    int b = memory[index];
                    result.append(String.format("%02x", b & 0xFF));
                }
                sendPacket(out, result.toString());
                continue;
            }
            else if (pkt.startsWith("qSupported")) {
                sendPacket(out, "qXfer:libraries:read+;vContSupported-;wasm+;ReverseStep+;");
                continue;
            }
            else if (pkt.startsWith("qXfer:libraries:read")) {
                // https://github.com/v8/v8/blob/main/src/debug/wasm/gdb-server/gdb-remote-util.h#L51
                // For LLDB debugging, an address in a Wasm module code space is represented
                // with 64 bits, where the first 32 bits identify the module id:
                // +--------------------+--------------------+
                // |     module_id      |       offset       |
                // +--------------------+--------------------+
                //  <----- 32 bit -----> <----- 32 bit ----->
                // Offset 0, module id 0
                sendPacket(out, String.format("l<library-list><library name=\"%s\"><section address=\"0x00000000\"/></library></library-list>", new File(binaryLocation).getAbsolutePath()));
                continue;
            }
            // TODO: We can probably remove this:
            else if (pkt.startsWith("Hc")) {
                sendPacket(out, "OK");
                continue;
            }
            else if (pkt.startsWith("qWasmLocal:")) {
                String[] args = pkt.substring("qWasmLocal:".length()).split(";");
                int frameIdx =  Integer.parseInt(args[0]);
                int localIdx =  Integer.parseInt(args[1]);
                logger.info("Reading local {} from frame {}", localIdx, frameIdx);
                Frame frame = getCallStack(getCurrentState()).get(frameIdx);
                logger.trace("{}", getCallStack(getCurrentState()));
                logger.trace("{}", frame);
                logger.trace("{}", getCurrentState().getStack());
                int fp = frame.getFp();
                long value = getCurrentState().getStack().get(fp + localIdx + 1).getValue();
                sendPacket(out, toHex(toWasmAddr(value))); // If a pointer on the stack is an address it will be clear it's a wasm memory pointer.
                continue;
            }
            else if (pkt.startsWith("qRegisterInfo0")) {
                sendPacket(out, "name:pc;alt-name:pc;bitsize:64;offset:0;encoding:uint;format:hex;set:General Purpose Registers;gcc:16;dwarf:16;generic:pc;");
                continue;
            }
            else if (pkt.startsWith("p0")) {
                sendPacket(out, toHex(getCurrentState().getPc()));
                continue;
            }
            else if (pkt.startsWith("Z")) {
                String[] args = pkt.substring(1).split(",");
                int type = Integer.parseInt(args[0]);
                long addr = Long.parseUnsignedLong(args[1], 16);
                int kind = Integer.parseInt(args[2]);
                logger.info("Add breakpoint on {}", addr);
                debugger.addBreakpoint((int) addr);

                try {
                    logger.info("Add breakpoint at {}:{}", debugSourceMap.getSourceFileName((int) addr), debugSourceMap.getLineForPc((int) addr));
                } catch(Exception _) {}

                // A remote target shall return an empty string for an unrecognized breakpoint or watchpoint packet type.
                sendPacket(out, "OK");
                continue;
            }
            else if (pkt.startsWith("z")) {
                // TODO: Fix code duplication
                String[] args = pkt.substring(1).split(",");
                int type = Integer.parseInt(args[0]);
                long addr = Long.parseUnsignedLong(args[1], 16);
                int kind = Integer.parseInt(args[2]);
                logger.info("Remove breakpoint on {}", addr);
                debugger.removeBreakpoint((int) addr);

                try {
                    logger.info("Remove breakpoint at {}:{}", debugSourceMap.getSourceFileName((int) addr), debugSourceMap.getLineForPc((int) addr));
                } catch(Exception _) {}

                // A remote target shall return an empty string for an unrecognized breakpoint or watchpoint packet type.
                sendPacket(out, "OK");
                continue;
            }
            else if (pkt.startsWith("stackInfo")) {
                //debugger.stepBack(1, wasmData);
                sendPacket(out, getCurrentState().getStack().toString());
                continue;
            }

            switch (pkt) {
                /*case "QStartNoAckMode":
                    sendPacket(out, "OK");
                    break;*/
                case "qHostInfo":
                    sendPacket(out, "vendor:wamr;ostype:wasi;arch:wasm32;endian:little;ptrsize:4;");
                    break;
                case "qProcessInfo":
                    sendPacket(out, "pid:1;parent-pid:1;vendor:wamr;ostype:wasi;arch:wasm32;triple:" + getTriple("wasm32-unknown-unknown-wasm") + ";endian:little;ptrsize:4;");
                    //sendPacket(out, "pid:1;parent-pid:1;vendor:wamr;ostype:wasi;arch:wasm32;triple:7761736d33322d77616d722d776173692d7761736d;endian:little;ptrsize:4;");
                    break;
                case "qGetWorkingDir":
                    sendPacket(out, "/tmp");
                    break;
                case "qQueryGDBServer":
                    sendPacket(out, "PacketSize=4000");
                    break;
                case "qWasmCallStack:1": // Get the callstack for thread 1.
                    WOODDumpResponse state = getCurrentState();
                    String result = toHex(state.getPc());
                    for (int i = state.getCallstack().size() - 1; i >= 0; i--) {
                        // Only functions are real callstack elements:
                        Frame f = state.getCallstack().get(i);
                        if (f.getType() == 0) {
                            result += toHex(f.getRa());
                        }
                    }
                    sendPacket(out, result);

                    try {
                        logger.info("At {}:{}", debugSourceMap.getSourceFileName(state.getPc()), debugSourceMap.getLineForPc(state.getPc()));
                    } catch(Exception _) {}

                    break;
                case "qC": // Get thread id
                    sendPacket(out, "QC 1");
                    break;
                case "qfThreadInfo":
                    sendPacket(out, "m 1"); // Active threads start list
                    break;
                case "qsThreadInfo":
                    sendPacket(out, "l"); // End of list
                    break;
                case "?":
                    sendPacket(out, "S05"); // SIGTRAP
                    break;
                case "g":
                    sendPacket(out, encodeRegs());
                    break;
                case "s":
                    logger.info("Received step command from lldb");
                    debugger.stepInto();
                    sendStopPacket(out, "05");
                    break;
                case "bs":
                    debugger.stepBack(1, () -> null);
                    sendStopPacket(out, "05");
                    break;
                case "c":
                    logger.info("Continue execution");
                    debugger.run();
                    break;
                case "pause":
                    debugger.pause();
                    sendStopPacket(out, "02");
                    break;
                case "D":
                    attached = false;
                    logger.info("Detach from target");
                    debugger.close();
                    sendPacket(out, "OK");
                    break;
                default:
                    logger.warn("Unknown packet: {}", pkt);
                    sendPacket(out, "");
                    break;
            }
        }
    }

    private void sendStopPacket(OutputStream out, String signal) throws IOException {
        sendPacket(out, "T" + signal + "thread:1;name:warduino;thread-pcs:" + toHex(getCurrentState().getPc()) + ";00:" + toHex(getCurrentState().getPc()) + ";reason:trace");
    }

    private String recvPacket(InputStream in, OutputStream out) throws IOException {
        int c;

        // Wait for '$'
        do {
            c = in.read();
            if (c == -1) return null;
        } while (c != '$' && c != 0x03); // 0x03 == pause request

        if (c == 0x03) {
            return "pause";
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        // Read until '#'
        while ((c = in.read()) != '#') {
            if (c == -1) return null;
            payload.write(c);
        }

        // Read checksum
        int c1 = in.read();
        int c2 = in.read();
        if (c1 == -1 || c2 == -1) return null;

        int received = Integer.parseInt("" + (char)c1 + (char)c2, 16);
        byte[] data = payload.toByteArray();
        int computed = checksum(data);

        if (received == computed) {
            out.write('+'); // ACK
            out.flush();
            return new String(data);
        } else {
            out.write('-'); // NAK
            out.flush();
            return null;
        }
    }

    private void sendPacket(OutputStream out, String payload) throws IOException {
        byte[] data = payload.getBytes();
        int sum = checksum(data);

        String pkt = "$" + payload + "#" + String.format("%02x", sum);
        out.write(pkt.getBytes());
        out.flush();
        logger.trace("-> {}", pkt);
    }

    private int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum = (sum + (b & 0xFF)) & 0xFF;
        }
        return sum;
    }

    private String encodeRegs() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%08x", getCurrentState().getPc()));
        return sb.toString();
    }

    private List<Frame> getCallStack(WOODDumpResponse state) {
        List<Frame> callStack = new ArrayList<Frame>();
        for (int i = state.getCallstack().size() - 1; i >= 0; i--) {
            Frame f = state.getCallstack().get(i);
            if (f.getType() == 0) {
                callStack.add(state.getCallstack().get(i));
            }
        }
        return callStack;
    }
}
