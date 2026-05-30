package rinha;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

public class Api {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static MethodHandle h(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
            LIBC.find(name).orElseThrow(() -> new IllegalStateException("missing: " + name)),
            desc);
    }

    private static final MethodHandle socket  = h("socket",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle connect = h("connect",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle read    = h("read",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle write   = h("write",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle close   = h("close",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final int AF_UNIX     = 1;
    private static final int SOCK_STREAM = 1;

    public static void main(String[] args) throws Throwable {
        String lbSock    = System.getenv().getOrDefault("LB_SOCK",    "/run/sockets/api1.sock");
        String indexFile = System.getenv().getOrDefault("INDEX_FILE", "/data/index.bin");
        String normFile  = System.getenv().getOrDefault("NORM_FILE",  "/data/normalization.json");
        String mccFile   = System.getenv().getOrDefault("MCC_FILE",   "/data/mcc_risk.json");

        Normalizer norm = Normalizer.load(Path.of(normFile), Path.of(mccFile));
        System.out.println("[api] normalizer loaded");

        IvfLoader loader = new IvfLoader(Path.of(indexFile));
        loader.startLoading();

        FraudScoreHandler fraudHandler = new FraudScoreHandler(loader, norm);

        try (Arena arena = Arena.ofConfined()) {
            /* build sockaddr_un for the LB socket path */
            MemorySegment addr = arena.allocate(110);
            addr.set(JAVA_SHORT, 0, (short) AF_UNIX);
            byte[] pb = lbSock.getBytes();
            MemorySegment.copy(pb, 0, addr, JAVA_BYTE, 2, pb.length);
            int addrLen = 2 + pb.length + 1;

            /* pre-allocated buffers reused across requests */
            MemorySegment reqBuf  = arena.allocate(4096);
            MemorySegment lenBuf  = arena.allocate(4);
            MemorySegment fraudBuf = arena.allocate(512);
            MemorySegment readyOkSeg  = allocSeg(arena, ReadyHandler.LOADED_RESPONSE);
            MemorySegment readyErrSeg = allocSeg(arena, ReadyHandler.NOT_LOADED_RESPONSE);

            /* warm JIT stubs before real traffic */
            for (int w = 0; w < 5; w++) {
                read.invoke(-1, reqBuf, 1L);
                write.invoke(-1, reqBuf, 0L);
                connect.invoke(-1, addr, addrLen);
            }
            System.out.println("[api] FFM stubs warmed");

            while (true) {
                int uds = (int) socket.invoke(AF_UNIX, SOCK_STREAM, 0);
                if (uds < 0) { sleepMs(50); continue; }
                int rc = (int) connect.invoke(uds, addr, addrLen);
                if (rc < 0) {
                    close.invoke(uds);
                    sleepMs(50);
                    continue;
                }
                System.out.println("[api] connected to " + lbSock);
                serve(uds, reqBuf, lenBuf, fraudBuf, readyOkSeg, readyErrSeg, loader, fraudHandler);
                close.invoke(uds);
                System.out.println("[api] disconnected from LB, reconnecting…");
                sleepMs(50);
            }
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static MemorySegment allocSeg(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    /**
     * Serve requests from the LB over a persistent UDS connection.
     * Each iteration: read 4-byte LE length, read payload, process, write 4-byte LE length + response.
     * Returns when the connection is lost (n == 0 or error).
     */
    private static void serve(int uds,
                              MemorySegment reqBuf,
                              MemorySegment lenBuf,
                              MemorySegment fraudBuf,
                              MemorySegment readyOkSeg,
                              MemorySegment readyErrSeg,
                              IvfLoader loader,
                              FraudScoreHandler fraudHandler) throws Throwable {
        while (true) {
            /* read 4-byte length prefix */
            if (!readFully(uds, lenBuf, 4)) return;
            long reqLen = Integer.toUnsignedLong(readLE32(lenBuf));
            if (reqLen == 0 || reqLen > 4096) return;

            /* read request body */
            if (!readFully(uds, reqBuf, (int) reqLen)) return;

            /* dispatch */
            MemorySegment resp;
            long respLen;
            byte firstByte = reqBuf.get(JAVA_BYTE, 0);
            if (firstByte == 'P') {
                byte[] r = fraudHandler.handle(reqBuf, (int) reqLen);
                MemorySegment.copy(r, 0, fraudBuf, JAVA_BYTE, 0, r.length);
                resp    = fraudBuf;
                respLen = r.length;
            } else {
                resp    = loader.isLoaded() ? readyOkSeg : readyErrSeg;
                respLen = resp.byteSize();
            }

            /* write 4-byte length prefix then response */
            writeLE32(lenBuf, (int) respLen);
            if (!writeFully(uds, lenBuf, 4)) return;
            if (!writeFully(uds, resp, respLen)) return;
        }
    }

    private static boolean readFully(int fd, MemorySegment buf, long need) throws Throwable {
        long have = 0;
        while (have < need) {
            long n = (long) read.invoke(fd, buf.asSlice(have), need - have);
            if (n <= 0) return false;
            have += n;
        }
        return true;
    }

    private static boolean writeFully(int fd, MemorySegment buf, long len) throws Throwable {
        long off = 0;
        while (off < len) {
            long n = (long) write.invoke(fd, buf.asSlice(off), len - off);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }

    private static int readLE32(MemorySegment buf) {
        return (buf.get(JAVA_BYTE, 0) & 0xFF)
             | ((buf.get(JAVA_BYTE, 1) & 0xFF) << 8)
             | ((buf.get(JAVA_BYTE, 2) & 0xFF) << 16)
             | ((buf.get(JAVA_BYTE, 3) & 0xFF) << 24);
    }

    private static void writeLE32(MemorySegment buf, int v) {
        buf.set(JAVA_BYTE, 0, (byte)(v));
        buf.set(JAVA_BYTE, 1, (byte)(v >> 8));
        buf.set(JAVA_BYTE, 2, (byte)(v >> 16));
        buf.set(JAVA_BYTE, 3, (byte)(v >> 24));
    }
}
