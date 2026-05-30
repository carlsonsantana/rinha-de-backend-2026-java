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
    private static final Linker       LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC   = LINKER.defaultLookup();

    private static MethodHandle h(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
            LIBC.find(name).orElseThrow(() -> new IllegalStateException("missing: " + name)),
            desc);
    }

    private static final MethodHandle socket   = h("socket",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle bind     = h("bind",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle listen   = h("listen",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle accept4  = h("accept4",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle recvmsg  = h("recvmsg",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle unlink   = h("unlink",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle read     = h("read",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle write    = h("write",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle close    = h("close",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final int AF_UNIX          = 1;
    private static final int SOCK_STREAM      = 1;
    private static final int SOCK_CLOEXEC     = 0x80000;
    private static final int SOL_SOCKET       = 1;
    private static final int SCM_RIGHTS       = 1;
    private static final int MSG_CMSG_CLOEXEC = 0x40000000;

    public static void main(String[] args) throws Throwable {
        String ctrlSock  = System.getenv().getOrDefault("API_CTRL_SOCK", "/run/sockets/api1.ctrl");
        String indexFile = System.getenv().getOrDefault("INDEX_FILE", "/data/index.bin");
        String normFile  = System.getenv().getOrDefault("NORM_FILE",  "/data/normalization.json");
        String mccFile   = System.getenv().getOrDefault("MCC_FILE",   "/data/mcc_risk.json");

        Normalizer norm = Normalizer.load(Path.of(normFile), Path.of(mccFile));
        System.out.println("[api] normalizer loaded");

        IvfLoader loader = new IvfLoader(Path.of(indexFile));
        loader.startLoading();

        FraudScoreHandler fraudHandler = new FraudScoreHandler(loader, norm);

        try (Arena arena = Arena.ofConfined()) {
            /* sockaddr_un for the ctrl socket path */
            byte[] pb   = ctrlSock.getBytes();
            MemorySegment addr = arena.allocate(110); /* sizeof(sockaddr_un) */
            addr.set(JAVA_SHORT, 0, (short) AF_UNIX);
            MemorySegment.copy(pb, 0, addr, JAVA_BYTE, 2, pb.length);
            int addrLen = 2 + pb.length + 1;

            /* null-terminated path for unlink — reuse addr+2 which is zero-terminated */
            MemorySegment pathSeg = addr.asSlice(2, pb.length + 1);

            /* request / response buffers */
            MemorySegment reqBuf      = arena.allocate(4096);
            MemorySegment fraudBuf    = arena.allocate(512);
            MemorySegment readyOkSeg  = allocSeg(arena, ReadyHandler.LOADED_RESPONSE);
            MemorySegment readyErrSeg = allocSeg(arena, ReadyHandler.NOT_LOADED_RESPONSE);

            /*
             * msghdr (56 B) + iovec (16 B) + cmsghdr+fd (24 B = CMSG_SPACE(sizeof(int)))
             * Layout on Linux x86_64:
             *   msghdr+0  msg_name (ptr)       = NULL
             *   msghdr+8  msg_namelen (int)     = 0
             *   msghdr+16 msg_iov (ptr)         → iov
             *   msghdr+24 msg_iovlen (size_t)   = 1
             *   msghdr+32 msg_control (ptr)     → cbuf
             *   msghdr+40 msg_controllen (size_t) — reset to 24 before each recvmsg
             *   iov+0     iov_base (ptr)        → dummy
             *   iov+8     iov_len (size_t)      = 8
             *   cbuf+0    cmsg_len (size_t)     written by kernel
             *   cbuf+8    cmsg_level (int)
             *   cbuf+12   cmsg_type (int)
             *   cbuf+16   fd (int)              ← read this after recvmsg
             */
            MemorySegment msgHdr = arena.allocate(56);
            MemorySegment iov    = arena.allocate(16);
            MemorySegment cbuf   = arena.allocate(24);
            MemorySegment dummy  = arena.allocate(8);

            iov.set(ADDRESS,   0, dummy);
            iov.set(JAVA_LONG, 8, 8L);
            msgHdr.set(ADDRESS,   0,  MemorySegment.NULL);
            msgHdr.set(JAVA_INT,  8,  0);
            msgHdr.set(ADDRESS,   16, iov);
            msgHdr.set(JAVA_LONG, 24, 1L);
            msgHdr.set(ADDRESS,   32, cbuf);
            /* msg_controllen at +40 is set before each recvmsg */

            /* warm JIT stubs before real traffic */
            for (int w = 0; w < 5; w++) {
                recvmsg.invoke(-1, msgHdr, 0);
                accept4.invoke(-1, MemorySegment.NULL, MemorySegment.NULL, 0);
                read.invoke(-1, reqBuf, 1L);
                write.invoke(-1, reqBuf, 0L);
            }
            System.out.println("[api] FFM stubs warmed");

            /* bind and listen on the ctrl socket so the LB can connect */
            unlink.invoke(pathSeg); /* best-effort; ignore failure */
            int listenFd = (int) socket.invoke(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
            if (listenFd < 0) throw new RuntimeException("[api] socket() failed");
            if ((int) bind.invoke(listenFd, addr, addrLen) < 0)
                throw new RuntimeException("[api] bind() failed: " + ctrlSock);
            if ((int) listen.invoke(listenFd, 4) < 0)
                throw new RuntimeException("[api] listen() failed");
            System.out.println("[api] listening on " + ctrlSock);

            /* main loop: accept the LB's one persistent ctrl connection */
            while (true) {
                int ctrl;
                do {
                    ctrl = (int) accept4.invoke(listenFd, MemorySegment.NULL, MemorySegment.NULL,
                                                SOCK_CLOEXEC);
                } while (ctrl < 0);
                System.out.println("[api] LB connected");
                serve(ctrl, msgHdr, cbuf, reqBuf, fraudBuf,
                      readyOkSeg, readyErrSeg, loader, fraudHandler);
                close.invoke(ctrl);
                System.out.println("[api] LB disconnected, waiting for reconnect");
            }
        }
    }

    private static void serve(int ctrl,
                               MemorySegment msgHdr,
                               MemorySegment cbuf,
                               MemorySegment reqBuf,
                               MemorySegment fraudBuf,
                               MemorySegment readyOkSeg,
                               MemorySegment readyErrSeg,
                               IvfLoader loader,
                               FraudScoreHandler fraudHandler) throws Throwable {
        while (true) {
            /* reset controllen — kernel overwrites it on each recvmsg */
            msgHdr.set(JAVA_LONG, 40, 24L);
            long n = (long) recvmsg.invoke(ctrl, msgHdr, MSG_CMSG_CLOEXEC);
            if (n <= 0) return; /* LB disconnected */

            /* recover the TCP fd from cmsg at offset +16 */
            int tcpFd = cbuf.get(JAVA_INT, 16);
            if (tcpFd < 0) return;

            /* read full HTTP request directly from TCP fd */
            int have = 0;
            int need = -1;
            boolean ok = false;
            outer:
            while (have < 4096) {
                long r = (long) read.invoke(tcpFd, reqBuf.asSlice(have), 4096L - have);
                if (r <= 0) break;
                have += (int) r;
                if (need < 0) need = parseRequestEnd(reqBuf, have);
                if (need > 0 && have >= need) { ok = true; break; }
            }

            if (ok) {
                MemorySegment resp;
                long respLen;
                if (reqBuf.get(JAVA_BYTE, 0) == 'P') {
                    byte[] r = fraudHandler.handle(reqBuf, need);
                    MemorySegment.copy(r, 0, fraudBuf, JAVA_BYTE, 0, r.length);
                    resp    = fraudBuf;
                    respLen = r.length;
                } else {
                    resp    = loader.isLoaded() ? readyOkSeg : readyErrSeg;
                    respLen = resp.byteSize();
                }
                writeFully(tcpFd, resp, respLen);
            }
            close.invoke(tcpFd); /* release our copy of the fd */
        }
    }

    /*
     * Find the total byte length of one HTTP request in buf[0..have).
     * Returns the length (headers + body) once fully buffered, or -1 if incomplete.
     */
    private static int parseRequestEnd(MemorySegment buf, int have) {
        /* locate \r\n\r\n */
        int hdrEnd = -1;
        for (int i = 3; i < have; i++) {
            if (buf.get(JAVA_BYTE, i - 3) == '\r' && buf.get(JAVA_BYTE, i - 2) == '\n'
             && buf.get(JAVA_BYTE, i - 1) == '\r' && buf.get(JAVA_BYTE, i)     == '\n') {
                hdrEnd = i + 1;
                break;
            }
        }
        if (hdrEnd < 0) return -1;
        if (buf.get(JAVA_BYTE, 0) == 'G') return hdrEnd; /* GET — no body */

        /* POST: scan for Content-Length header */
        for (int i = 0; i < hdrEnd - 15; i++) {
            if (matchCI(buf, i, "content-length:")) {
                int j = i + 15;
                while (j < hdrEnd && isBlank(buf.get(JAVA_BYTE, j))) j++;
                int cl = 0;
                while (j < hdrEnd) {
                    byte b = buf.get(JAVA_BYTE, j);
                    if (b < '0' || b > '9') break;
                    cl = cl * 10 + (b - '0');
                    j++;
                }
                return hdrEnd + cl;
            }
        }
        return -1;
    }

    private static boolean matchCI(MemorySegment buf, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            int b = buf.get(JAVA_BYTE, off + i) & 0xFF;
            if ((b | 0x20) != s.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isBlank(byte b) { return b == ' ' || b == '\t'; }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static MemorySegment allocSeg(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
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
}
