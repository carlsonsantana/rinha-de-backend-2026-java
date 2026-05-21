package rinha;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

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

    private static final byte[] RESPONSE = (
        "HTTP/1.1 201 Created\r\n" +
        "Content-Length: 0\r\n" +
        "Connection: close\r\n" +
        "\r\n"
    ).getBytes();

    public static void main(String[] args) throws Throwable {
        String path = System.getenv().getOrDefault("CTRL_SOCK", "/sockets/api.ctrl");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathC = arena.allocateFrom(path);
            unlink.invoke(pathC);

            int srv = (int) socket.invoke(AF_UNIX, SOCK_STREAM, 0);
            if (srv < 0) throw new RuntimeException("socket() failed");

            MemorySegment addr = arena.allocate(110);
            addr.set(JAVA_SHORT, 0, (short) AF_UNIX);
            byte[] pb = path.getBytes();
            MemorySegment.copy(pb, 0, addr, JAVA_BYTE, 2, pb.length);
            int addrLen = 2 + pb.length + 1;

            if ((int) bind.invoke(srv, addr, addrLen) < 0) throw new RuntimeException("bind() failed");
            if ((int) listen.invoke(srv, 16) < 0)          throw new RuntimeException("listen() failed");

            System.out.println("[api] listening on " + path);

            MemorySegment respSeg = arena.allocate(RESPONSE.length);
            MemorySegment.copy(RESPONSE, 0, respSeg, JAVA_BYTE, 0, RESPONSE.length);
            MemorySegment reqBuf = arena.allocate(4096);

            MemorySegment iovBuf = arena.allocate(1);
            MemorySegment iov    = arena.allocate(16);
            iov.set(ADDRESS, 0, iovBuf);
            iov.set(JAVA_LONG, 8, 1L);

            MemorySegment cmsg = arena.allocate(24);
            MemorySegment msg  = arena.allocate(56);

            while (true) {
                int ctrl = (int) accept.invoke(srv, MemorySegment.NULL, MemorySegment.NULL);
                if (ctrl < 0) { System.err.println("[api] accept failed"); continue; }
                System.out.println("[api] lb connected");
                serve(ctrl, msg, iov, cmsg, reqBuf, respSeg);
                close.invoke(ctrl);
                System.out.println("[api] lb disconnected");
            }
        }
    }

    private static void serve(int ctrl, MemorySegment msg, MemorySegment iov,
                              MemorySegment cmsg, MemorySegment reqBuf,
                              MemorySegment respSeg) throws Throwable {
        long respLen = respSeg.byteSize();
        long reqCap  = reqBuf.byteSize();

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

            read.invoke(fd, reqBuf, reqCap);
            write.invoke(fd, respSeg, respLen);
            close.invoke(fd);
        }
    }
}
