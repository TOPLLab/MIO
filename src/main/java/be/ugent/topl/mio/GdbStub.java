package be.ugent.topl.mio;

import be.ugent.topl.mio.debugger.Debugger;
import be.ugent.topl.mio.sourcemap.SourceMap;
import be.ugent.topl.mio.woodstate.Checkpoint;
import be.ugent.topl.mio.woodstate.Frame;
import be.ugent.topl.mio.woodstate.WOODDumpResponse;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static be.ugent.topl.mio.sourcemap.DwarfSourceMapKt.getDwarfSourcemap;

public class GdbStub {
    private final Debugger debugger;
    private final String binaryLocation;
    private OutputStream out;
    private final SourceMap debugSourceMap; // Remove later, lldb doesn't need this.

    public GdbStub(Debugger debugger, String binaryLocation) {
        this.debugger = debugger;
        this.binaryLocation = binaryLocation;
        debugSourceMap = getDwarfSourcemap(binaryLocation);

        debugger.getBreakpointsListeners().add((pc) -> {
            try {
                sendPacket(out, "S05");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    // Dummy register file (16 x 32-bit)
    static int[] regs = new int[16];

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

    private String toHex(long data, int maxLen) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < maxLen; i++) {
            long b = (data >> i * 8) & 0xff;
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private String toHex(long data) {
        return toHex(data, 8);
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

    private byte[] getCodeSection(String filename) throws IOException {
        byte[] wasmBytes = Files.readAllBytes(Path.of(filename));
        int i = 8; // skip header

        while (i < wasmBytes.length) {
            int sectionId = wasmBytes[i++] & 0xFF;

            // LEB128 section size
            int size = 0;
            int shift = 0;
            int b;
            do {
                b = wasmBytes[i++] & 0xFF;
                size |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            if (sectionId == 10) { // code section
                return Arrays.copyOfRange(wasmBytes, i, i + size);
            }

            i += size;
        }
        return null;
    }

    private String getTriple(String s) {
        String hex = s.chars()
                .mapToObj(c -> String.format("%02x", c))
                .reduce("", String::concat);

        System.out.println(hex);
        return hex;
    }

    public WOODDumpResponse getCurrentState() {
        return debugger.getCheckpoints().getLast().getSnapshot();
    }

    public void start() throws IOException {
        //System.out.println(stripAddrType(0x800000000000fffcL)); // 65532
        debugger.pause();

        System.out.println(toHex(4508));
        byte[] rawCodeSection = getCodeSection(binaryLocation);

        for (int i = 0; i < regs.length; i++) {
            regs[i] = 0x11111111 * (i + 1);
        }

        ServerSocket server = new ServerSocket(1234);
        System.out.println("Waiting for GDB on port 1234...");
        Socket sock = server.accept();
        System.out.println("GDB connected!");

        InputStream in = sock.getInputStream();
        out = sock.getOutputStream();

        while (true) {
            String pkt = recvPacket(in, out);
            if (pkt == null) {
                System.out.println("GDB closed");
                break;
            }
            System.out.println("<- " + pkt);

            if (pkt.startsWith("m")) {
                pkt = pkt.substring(1);
                String[] memArgs = pkt.split(",");
                long pos = Long.parseUnsignedLong(memArgs[0], 16);
                long addrType = getAddrType(pos);
                pos = stripAddrType(pos);
                long len = Long.parseUnsignedLong(memArgs[1], 16);
                log("Read memory " + len + " bytes from " + pos + " with addr type = " + addrType);

                byte[] memory = rawCodeSection;
                if (addrType == 1) {
                    log("Reading from wasm linear memory");
                    memory = getCurrentState().getMemory().getBytes();
                }

                // The reply may contain fewer addressable memory units than requested if the server was reading from a trace frame memory and was able to read only part of the region of memory.
                if (pos + len >= memory.length) {
                    System.out.println("Out of bounds for length " + memory.length);
                    sendPacket(out, "E01");
                    continue;
                }

                StringBuilder result = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    // TODO: read from pos + i
                    // also think about little vs big endian
                    int index = (int) pos + i;
                    /*if (index >= memory.length) {
                        System.out.println("Stop reading, out of bounds");
                        break;
                    }*/
                    int b = memory[index];
                    result.append(String.format("%02x", b & 0xFF));
                    //result.append("00");
                }
                sendPacket(out, result.toString());

                // If memory invalid send E01
                //sendPacket(out, "E01");
                continue;
            }
            else if (pkt.startsWith("qSupported")) {
                sendPacket(out, "qXfer:libraries:read+;vContSupported-;wasm+;");
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
                log("Reading local " + localIdx + " from frame " + frameIdx);
                Frame frame = getCurrentState().getCallstack().get(getCurrentState().getCallstack().size() - frameIdx - 1);
                System.out.println(frame);
                System.out.println(getCurrentState().getStack());
                int fp = frame.getFp();
                long value = getCurrentState().getStack().get(fp + localIdx).getValue();
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
                log("Add breakpoint on " + addr);
                debugger.addBreakpoint((int) addr);

                try {
                    for (int i = 0; i < 8; i++) {
                        log("Breakpoint line " + debugSourceMap.getLineForPc((int) addr + (i-4) * 4) + " " + debugSourceMap.getSourceFileName((int) addr + (i-4) * 4));
                    }
                } catch(Exception e) {}

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
                log("Remove breakpoint on " + addr);
                debugger.removeBreakpoint((int) addr);

                try {
                    log("Breakpoint line " + debugSourceMap.getLineForPc((int) addr) + " " + debugSourceMap.getSourceFileName((int) addr));
                } catch(Exception e) {}

                // A remote target shall return an empty string for an unrecognized breakpoint or watchpoint packet type.
                sendPacket(out, "OK");
                continue;
            }

            switch (pkt) {
                case "QStartNoAckMode":
                    sendPacket(out, "OK");
                    break;
                case "qHostInfo":
                    //sendPacket(out, "triple:x86_64-pc-linux-gnu;endian:little;ptrsize:8;");
                    //sendPacket(out, "cputype:16777228;cpusubtype:3;ostype:darwin;vendor:apple;endian:little;ptrsize:8;hostname:hello;");
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
                    Checkpoint lastCheckpoint = debugger.getCheckpoints().getLast();
                    WOODDumpResponse state = getCurrentState();
                    long currentPc = (long) state.getPc();
                    String result = toHex(currentPc);
                    for (int i = 0; i < state.getCallstack().size() - 1; i++) {
                        result += toHex(0xdead); // TODO: Add other pc's in the callstack
                    }
                    log("Callstack size: " + state.getCallstack().size() + " pc = " + currentPc);
                    sendPacket(out, result);

                    try {
                        log("Current line " + debugSourceMap.getLineForPc(state.getPc()) + " " + debugSourceMap.getSourceFileName(state.getPc()));
                    } catch(Exception e) {}

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
                    log("Received step command from lldb");
                    debugger.stepInto();
                    //sendPacket(out, "T05thread:1;pc:" + toHex(getCurrentState().getPc()) + ";");
                    //sendPacket(out, "S05");
                    sendPacket(out, "T05thread:1;name:warduino;thread-pcs:" + toHex(getCurrentState().getPc()) + ";00:" + toHex(getCurrentState().getPc()) + ";reason:trace");
                    break;
                case "c":
                    // Pretend to run, then stop immediately
                    debugger.run();
                    //sendPacket(out, "S05");
                    break;

                default:
                    System.out.println("Unknown packet: " + pkt);
                    sendPacket(out, ""); // unsupported
                    break;
            }
        }
    }

    private void log(String s) {
        System.out.print("\u001b[36m");
        System.out.print("[GDBSTUB] ");
        System.out.println(s);
        System.out.print("\u001b[0m");
    }

    private String recvPacket(InputStream in, OutputStream out) throws IOException {
        int c;

        // Wait for '$'
        do {
            c = in.read();
            if (c == -1) return null;
        } while (c != '$');

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
        System.out.println("-> " + pkt);
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
        for (int r : regs) {
            sb.append(String.format("%08x", r));
        }
        return sb.toString();
    }
}
