package rinha.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

public final class BuildIndex {

    private static final int DIMS        = 14;
    private static final int DIMS_STORED = 16; // 14 dims + 2 zero shorts for 16-lane SIMD alignment
    private static final int SCALE       = 10_000;
    private static final int MAGIC       = ('R') | ('N' << 8) | ('H' << 16) | ('A' << 24);
    private static final int VERSION     = 2;
    private static final int HEADER_BYTES = 32;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: BuildIndex <references.json.gz> <out.bin>");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);

        long t0 = System.nanoTime();
        System.out.println("[index] parsing " + in);
        Loaded loaded = parse(in);
        long t1 = System.nanoTime();
        System.out.printf("[index] parsed %,d points (%.2fs)%n",
            loaded.n, (t1 - t0) / 1e9);

        System.out.println("[index] building KD-Tree");
        Tree tree = build(loaded);
        long t2 = System.nanoTime();
        System.out.printf("[index] built tree (%.2fs)%n", (t2 - t1) / 1e9);

        System.out.println("[index] writing " + out);
        long bytes = write(out, loaded, tree);
        long t3 = System.nanoTime();
        System.out.printf("[index] wrote %,d bytes (%.2fs, total %.2fs)%n",
            bytes, (t3 - t2) / 1e9, (t3 - t0) / 1e9);
    }

    // ---------- parse ----------

    static final class Loaded {
        short[] points;
        byte[] labels;
        int n;
    }

    static Loaded parse(Path in) throws IOException {
        try (InputStream raw = Files.newInputStream(in);
             GZIPInputStream gz = new GZIPInputStream(raw, 1 << 16);
             JsonStream js = new JsonStream(gz)) {

            js.expect('[');
            short[] points = new short[1 << 20];
            byte[] labels = new byte[1 << 16];
            int pointsCap = points.length;
            int labelsCap = labels.length;
            int n = 0;

            while (true) {
                int c = js.peekSkipWs();
                if (c == ']') { js.next(); break; }
                if (n > 0) js.expect(',');

                if (n + 1 > labelsCap) {
                    labelsCap <<= 1;
                    byte[] nl = new byte[labelsCap];
                    System.arraycopy(labels, 0, nl, 0, n);
                    labels = nl;
                }
                if ((n + 1) * DIMS > pointsCap) {
                    pointsCap <<= 1;
                    short[] np = new short[pointsCap];
                    System.arraycopy(points, 0, np, 0, n * DIMS);
                    points = np;
                }

                readRecord(js, points, n * DIMS, labels, n);
                n++;

                if ((n % 500_000) == 0) {
                    System.out.printf("[index]   %,d%n", n);
                }
            }

            Loaded r = new Loaded();
            r.points = points;
            r.labels = labels;
            r.n = n;
            return r;
        }
    }

    private static void readRecord(JsonStream js, short[] points, int pOff,
                                   byte[] labels, int lIdx) throws IOException {
        js.expect('{');
        int got = 0;
        boolean first = true;
        while (true) {
            int c = js.peekSkipWs();
            if (c == '}') { js.next(); break; }
            if (!first) js.expect(',');
            first = false;
            String key = js.readString();
            js.expect(':');
            if ("vector".equals(key)) {
                js.expect('[');
                for (int d = 0; d < DIMS; d++) {
                    if (d > 0) js.expect(',');
                    points[pOff + d] = encode(js.readNumber());
                }
                js.expect(']');
                got |= 1;
            } else if ("label".equals(key)) {
                String v = js.readString();
                if ("fraud".equals(v))      labels[lIdx] = 1;
                else if ("legit".equals(v)) labels[lIdx] = 0;
                else throw new IOException("record " + lIdx + " unknown label: " + v);
                got |= 2;
            } else {
                js.skipValue();
            }
        }
        if (got != 3) throw new IOException("record " + lIdx + " missing vector or label");
    }

    static short encode(double v) {
        long s = Math.round(v * SCALE);
        if (s > Short.MAX_VALUE || s < Short.MIN_VALUE) {
            throw new IllegalArgumentException("out-of-range value: " + v);
        }
        return (short) s;
    }

    // ---------- KD-Tree build ----------

    static final class Tree {
        int[] permutation; // permutation[nodeId] = original point index
        byte[] axis;
        int[] left;
        int[] right;
        int root;
    }

    static Tree build(Loaded data) {
        int n = data.n;
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;

        Tree t = new Tree();
        t.permutation = new int[n];
        t.axis = new byte[n];
        t.left = new int[n];
        t.right = new int[n];

        Builder b = new Builder(data.points, idx, t);
        t.root = b.buildNode(0, n - 1, 0);
        if (b.nextId != n) {
            throw new IllegalStateException("tree size mismatch: " + b.nextId + " != " + n);
        }
        return t;
    }

    static final class Builder {
        final short[] points;
        final int[] idx;
        final Tree t;
        int nextId;

        Builder(short[] points, int[] idx, Tree t) {
            this.points = points;
            this.idx = idx;
            this.t = t;
        }

        int buildNode(int lo, int hi, int depth) {
            if (lo > hi) return -1;
            int axis = depth % DIMS;
            int mid = (lo + hi) >>> 1;
            quickselect(lo, hi, mid, axis);
            int id = nextId++;
            t.axis[id] = (byte) axis;
            t.permutation[id] = idx[mid];
            t.left[id] = buildNode(lo, mid - 1, depth + 1);
            t.right[id] = buildNode(mid + 1, hi, depth + 1);
            return id;
        }

        void quickselect(int lo, int hi, int k, int axis) {
            while (lo < hi) {
                int p = partition(lo, hi, axis);
                if (k <= p) hi = p;
                else lo = p + 1;
            }
        }

        int partition(int lo, int hi, int axis) {
            int mid = (lo + hi) >>> 1;
            short pivot = medianOf3(
                axisVal(idx[lo], axis),
                axisVal(idx[mid], axis),
                axisVal(idx[hi], axis));
            int i = lo - 1, j = hi + 1;
            while (true) {
                do i++; while (axisVal(idx[i], axis) < pivot);
                do j--; while (axisVal(idx[j], axis) > pivot);
                if (i >= j) return j;
                int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
            }
        }

        short axisVal(int origIdx, int axis) {
            return points[origIdx * DIMS + axis];
        }

        static short medianOf3(short a, short b, short c) {
            if (a > b) { short t = a; a = b; b = t; }
            if (b > c) { short t = b; b = c; c = t; }
            if (a > b) { short t = a; a = b; b = t; }
            return b;
        }
    }

    // ---------- write ----------

    static long write(Path out, Loaded data, Tree tree) throws IOException {
        int n = data.n;
        long pointsBytes = (long) n * DIMS_STORED * 2;
        long labelsBytes = n;
        long axisBytes = n;
        long childBytes = (long) n * 4;
        long total = HEADER_BYTES + pointsBytes + labelsBytes + axisBytes + 2 * childBytes;

        try (FileChannel ch = FileChannel.open(out,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putInt(n);
            header.putInt(DIMS);
            header.putInt(tree.root);
            header.putInt(0).putInt(0).putInt(0);
            header.flip();
            drain(ch, header);

            ByteBuffer pBuf = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.LITTLE_ENDIAN);
            int pointsPerChunk = pBuf.capacity() / (DIMS_STORED * 2);
            for (int i = 0; i < n; ) {
                int end = Math.min(n, i + pointsPerChunk);
                pBuf.clear();
                for (int j = i; j < end; j++) {
                    int orig = tree.permutation[j];
                    int base = orig * DIMS;
                    for (int d = 0; d < DIMS; d++) pBuf.putShort(data.points[base + d]);
                    pBuf.putShort((short) 0); // SIMD padding lane 14
                    pBuf.putShort((short) 0); // SIMD padding lane 15
                }
                pBuf.flip();
                drain(ch, pBuf);
                i = end;
            }

            ByteBuffer lBuf = ByteBuffer.allocateDirect(1 << 16);
            for (int i = 0; i < n; ) {
                int end = Math.min(n, i + lBuf.capacity());
                lBuf.clear();
                for (int j = i; j < end; j++) lBuf.put(data.labels[tree.permutation[j]]);
                lBuf.flip();
                drain(ch, lBuf);
                i = end;
            }

            drain(ch, ByteBuffer.wrap(tree.axis));
            writeInts(ch, tree.left);
            writeInts(ch, tree.right);

            long written = ch.position();
            if (written != total) {
                throw new IOException("write size mismatch: " + written + " != " + total);
            }
            return written;
        }
    }

    private static void writeInts(FileChannel ch, int[] arr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        int perChunk = buf.capacity() / 4;
        for (int i = 0; i < arr.length; ) {
            int end = Math.min(arr.length, i + perChunk);
            buf.clear();
            for (int j = i; j < end; j++) buf.putInt(arr[j]);
            buf.flip();
            drain(ch, buf);
            i = end;
        }
    }

    private static void drain(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    // ---------- minimal streaming JSON reader ----------

    static final class JsonStream implements AutoCloseable {
        private final InputStream in;
        private final byte[] buf = new byte[1 << 16];
        private int pos, end;
        private boolean eof;

        JsonStream(InputStream in) { this.in = in; }

        int peek() throws IOException { ensure(1); return eof ? -1 : (buf[pos] & 0xff); }
        int next() throws IOException { ensure(1); return eof ? -1 : (buf[pos++] & 0xff); }

        int peekSkipWs() throws IOException { skipWs(); return peek(); }

        void expect(char c) throws IOException {
            skipWs();
            int b = next();
            if (b != c) throw new IOException("expected '" + c + "' got " + describe(b));
        }

        void skipWs() throws IOException {
            while (true) {
                ensure(1);
                if (eof) return;
                byte b = buf[pos];
                if (b == ' ' || b == '\n' || b == '\r' || b == '\t') pos++;
                else return;
            }
        }

        String readString() throws IOException {
            skipWs();
            if (next() != '"') throw new IOException("expected string");
            StringBuilder sb = new StringBuilder();
            while (true) {
                ensure(1);
                if (eof) throw new IOException("unterminated string");
                byte b = buf[pos++];
                if (b == '"') return sb.toString();
                if (b == '\\') {
                    ensure(1);
                    byte e = buf[pos++];
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'n':  sb.append('\n'); break;
                        case 't':  sb.append('\t'); break;
                        case 'r':  sb.append('\r'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        default: throw new IOException("unsupported escape \\" + (char) e);
                    }
                } else {
                    sb.append((char) (b & 0xff));
                }
            }
        }

        double readNumber() throws IOException {
            skipWs();
            StringBuilder sb = new StringBuilder(16);
            while (true) {
                ensure(1);
                if (eof) break;
                byte b = buf[pos];
                if (b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E'
                        || (b >= '0' && b <= '9')) {
                    sb.append((char) b);
                    pos++;
                } else break;
            }
            if (sb.length() == 0) throw new IOException("expected number");
            return Double.parseDouble(sb.toString());
        }

        boolean readBool() throws IOException {
            skipWs();
            int b = peek();
            if (b == 't') { expectLit("true");  return true;  }
            if (b == 'f') { expectLit("false"); return false; }
            throw new IOException("expected bool got " + describe(b));
        }

        void expectLit(String s) throws IOException {
            for (int i = 0; i < s.length(); i++) {
                int b = next();
                if (b != s.charAt(i)) throw new IOException("expected '" + s + "'");
            }
        }

        void skipValue() throws IOException {
            skipWs();
            int b = peek();
            if (b == '"') { readString(); return; }
            if (b == 't' || b == 'f') { readBool(); return; }
            if (b == 'n') { expectLit("null"); return; }
            if (b == '{') { skipObject(); return; }
            if (b == '[') { skipArray(); return; }
            if (b == '-' || (b >= '0' && b <= '9')) { readNumber(); return; }
            throw new IOException("can't skip value starting with " + describe(b));
        }

        private void skipObject() throws IOException {
            expect('{');
            if (peekSkipWs() == '}') { next(); return; }
            while (true) {
                readString();
                expect(':');
                skipValue();
                int b = peekSkipWs();
                next();
                if (b == '}') return;
                if (b != ',') throw new IOException("expected , or } got " + describe(b));
            }
        }

        private void skipArray() throws IOException {
            expect('[');
            if (peekSkipWs() == ']') { next(); return; }
            while (true) {
                skipValue();
                int b = peekSkipWs();
                next();
                if (b == ']') return;
                if (b != ',') throw new IOException("expected , or ] got " + describe(b));
            }
        }

        private void ensure(int min) throws IOException {
            if (end - pos >= min) return;
            if (eof) return;
            if (pos > 0) {
                int rem = end - pos;
                if (rem > 0) System.arraycopy(buf, pos, buf, 0, rem);
                end = rem;
                pos = 0;
            }
            while (end < buf.length && end - pos < min) {
                int got = in.read(buf, end, buf.length - end);
                if (got < 0) { eof = true; return; }
                end += got;
            }
        }

        private static String describe(int b) {
            return b < 0 ? "EOF" : ("'" + (char) b + "' (0x" + Integer.toHexString(b) + ")");
        }

        @Override public void close() throws IOException { in.close(); }
    }
}

