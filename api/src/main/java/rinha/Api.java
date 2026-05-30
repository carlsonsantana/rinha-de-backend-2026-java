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
    private static final MethodHandle bind    = h("bind",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle listen  = h("listen",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle accept  = h("accept",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle recvmsg = h("recvmsg",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle read    = h("read",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle write   = h("write",
        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle close   = h("close",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle unlink  = h("unlink",
        FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final int AF_UNIX     = 1;
    private static final int SOCK_STREAM = 1;
    private static final int SOL_SOCKET  = 1;
    private static final int SCM_RIGHTS  = 1;

    private static final byte[] DEFAULT_RESPONSE = (
        "HTTP/1.1 501 Not Implemented\r\n" +
        "Content-Length: 0\r\n" +
        "Connection: close\r\n" +
        "\r\n"
    ).getBytes();

    private static final byte GET_READY_PREFIX    = "GET /ready".getBytes()[0];
    private static final byte POST_FRAUD_PREFIX   = "POST /fraud-score".getBytes()[0];

    public static void main(String[] args) throws Throwable {
        String ctrlPath  = System.getenv().getOrDefault("CTRL_SOCK",   "/run/api.ctrl");
        String indexFile = System.getenv().getOrDefault("INDEX_FILE",  "/data/index.bin");
        String normFile  = System.getenv().getOrDefault("NORM_FILE",   "/data/normalization.json");
        String mccFile   = System.getenv().getOrDefault("MCC_FILE",    "/data/mcc_risk.json");

        Normalizer norm = Normalizer.load(Path.of(normFile), Path.of(mccFile));
        System.out.println("[api] normalizer loaded");

        IvfLoader loader = new IvfLoader(Path.of(indexFile));
        loader.startLoading();

        FraudScoreHandler fraudHandler = new FraudScoreHandler(loader, norm);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathC = arena.allocateFrom(ctrlPath);
            unlink.invoke(pathC);

            int srv = (int) socket.invoke(AF_UNIX, SOCK_STREAM, 0);
            if (srv < 0) throw new RuntimeException("socket() failed");

            MemorySegment addr = arena.allocate(110);
            addr.set(JAVA_SHORT, 0, (short) AF_UNIX);
            byte[] pb = ctrlPath.getBytes();
            MemorySegment.copy(pb, 0, addr, JAVA_BYTE, 2, pb.length);
            int addrLen = 2 + pb.length + 1;

            if ((int) bind.invoke(srv, addr, addrLen) < 0) throw new RuntimeException("bind() failed");
            if ((int) listen.invoke(srv, 16) < 0)          throw new RuntimeException("listen() failed");

            System.out.println("[api] listening on " + ctrlPath);

            MemorySegment defaultSeg  = allocSeg(arena, DEFAULT_RESPONSE);
            MemorySegment readyOkSeg  = allocSeg(arena, ReadyHandler.LOADED_RESPONSE);
            MemorySegment readyErrSeg = allocSeg(arena, ReadyHandler.NOT_LOADED_RESPONSE);
            // Max fraud response is ~200 bytes; 512 is a safe upper bound.
            MemorySegment fraudBuf    = arena.allocate(512);
            MemorySegment reqBuf      = arena.allocate(4096);

            MemorySegment iovBuf = arena.allocate(1);
            MemorySegment iov    = arena.allocate(16);
            iov.set(ADDRESS, 0, iovBuf);
            iov.set(JAVA_LONG, 8, 1L);

            MemorySegment cmsg = arena.allocate(24);
            MemorySegment msg  = arena.allocate(56);

            // Drive JIT to compile FFM call sites before the first real request.
            // recvmsg(-1,...) returns EBADF immediately — no blocking, just warms the stub.
            for (int w = 0; w < 5; w++) {
                recvmsg.invoke(-1, msg, 0);
                read.invoke(-1, reqBuf, 1L);
                write.invoke(-1, reqBuf, 0L);
            }
            System.out.println("[api] FFM stubs warmed");

            while (true) {
                int ctrl = (int) accept.invoke(srv, MemorySegment.NULL, MemorySegment.NULL);
                if (ctrl < 0) { System.err.println("[api] accept failed"); continue; }
                serve(ctrl, msg, iov, cmsg, reqBuf,
                      defaultSeg, readyOkSeg, readyErrSeg, fraudBuf,
                      loader, fraudHandler);
                close.invoke(ctrl);
            }
        }
    }

    private static MemorySegment allocSeg(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static boolean isPostFraud(MemorySegment buf) {
        return buf.get(JAVA_BYTE, 0) == POST_FRAUD_PREFIX;
    }

    private static void serve(int ctrl, MemorySegment msg, MemorySegment iov,
                              MemorySegment cmsg, MemorySegment reqBuf,
                              MemorySegment defaultSeg, MemorySegment readyOkSeg,
                              MemorySegment readyErrSeg, MemorySegment fraudBuf,
                              IvfLoader loader, FraudScoreHandler fraudHandler) throws Throwable {
        while (true) {
            msg.fill((byte) 0);
            msg.set(ADDRESS,   16, iov);
            msg.set(JAVA_LONG, 24, 1L);
            msg.set(ADDRESS,   32, cmsg);
            msg.set(JAVA_LONG, 40, cmsg.byteSize());
            cmsg.fill((byte) 0);

            long n = (long) recvmsg.invoke(ctrl, msg, 0);
            if (n <= 0) return;

            int level = cmsg.get(JAVA_INT, 8);
            int type  = cmsg.get(JAVA_INT, 12);
            if (level != SOL_SOCKET || type != SCM_RIGHTS) {
                System.err.println("[api] unexpected cmsg level=" + level + " type=" + type);
                continue;
            }
            int fd = cmsg.get(JAVA_INT, 16);

            long bytesRead = (long) read.invoke(fd, reqBuf, reqBuf.byteSize());
            if (bytesRead <= 0) { close.invoke(fd); continue; }

            MemorySegment resp;
            long respLen;

            if (isPostFraud(reqBuf)) {
                byte[] r = fraudHandler.handle(reqBuf, (int) bytesRead);
                MemorySegment.copy(r, 0, fraudBuf, JAVA_BYTE, 0, r.length);
                resp    = fraudBuf;
                respLen = r.length;
            } else {
                resp    = loader.isLoaded() ? readyOkSeg : readyErrSeg;
                respLen = resp.byteSize();
            }

            write.invoke(fd, resp, respLen);
            close.invoke(fd);
        }
    }
}
