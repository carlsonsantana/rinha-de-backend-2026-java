package rinha.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPInputStream;

public final class BuildIndex {

    private static final int DIMS        = 14;
    private static final int DIMS_STORED = 16; // 14 dims + 2 zero shorts for 16-lane SIMD alignment
    private static final int SCALE       = 10_000;
    private static final int MAGIC       = ('R') | ('N' << 8) | ('H' << 16) | ('A' << 24);
    private static final int VERSION     = 3;
    private static final int HEADER_BYTES = 40;

    private static final int  DEFAULT_K   = 2048;
    private static final int  MAX_ITERS   = 10;
    private static final long SEED        = 42L;

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

        int k = Integer.parseInt(System.getenv().getOrDefault("INDEX_K", Integer.toString(DEFAULT_K)));
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        System.out.printf("[index] k-means K=%d threads=%d%n", k, threads);

        Kmeans km = kmeans(loaded, k, threads);
        long t2 = System.nanoTime();
        System.out.printf("[index] built clusters (%.2fs)%n", (t2 - t1) / 1e9);

        System.out.println("[index] writing " + out);
        long bytes = write(out, loaded, km);
        long t3 = System.nanoTime();
        System.out.printf("[index] wrote %,d bytes (%.2fs, total %.2fs)%n",
            bytes, (t3 - t2) / 1e9, (t3 - t0) / 1e9);
    }

    // ---------- parse ----------

    static final class Loaded {
        short[] points; // packed: n × DIMS shorts
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

    // ---------- k-means (IVF) ----------

    static final class Kmeans {
        int k;
        short[] centroids;       // k × DIMS shorts (packed)
        int[]   clusterSize;     // k
        int[]   clusterOffset;   // k + 1 (prefix sum)
        int[]   permutation;     // n: cluster-order index → original index
    }

    static Kmeans kmeans(Loaded data, int k, int nThreads) {
        int n = data.n;
        Random rng = new Random(SEED);
        short[] points = data.points;

        // Init: k distinct random points via partial Fisher-Yates over a small swap table.
        // We don't materialize the full index array — just pick distinct ints.
        short[] centroids = new short[k * DIMS];
        boolean[] picked = new boolean[n];
        for (int c = 0; c < k; c++) {
            int idx;
            do { idx = rng.nextInt(n); } while (picked[idx]);
            picked[idx] = true;
            System.arraycopy(points, idx * DIMS, centroids, c * DIMS, DIMS);
        }
        picked = null;

        int[]  assignment = new int[n];
        Arrays.fill(assignment, -1);
        long[] sums   = new long[k * DIMS];
        int[]  counts = new int[k];

        for (int iter = 0; iter < MAX_ITERS; iter++) {
            int changes = assignParallel(points, n, centroids, k, assignment, nThreads);

            Arrays.fill(sums, 0L);
            Arrays.fill(counts, 0);
            for (int i = 0; i < n; i++) {
                int c = assignment[i];
                counts[c]++;
                int pBase = i * DIMS;
                int cBase = c * DIMS;
                for (int d = 0; d < DIMS; d++) sums[cBase + d] += points[pBase + d];
            }

            int empty = 0;
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) {
                    int rnd = rng.nextInt(n);
                    System.arraycopy(points, rnd * DIMS, centroids, c * DIMS, DIMS);
                    empty++;
                } else {
                    int cBase = c * DIMS;
                    int cnt = counts[c];
                    for (int d = 0; d < DIMS; d++) {
                        centroids[cBase + d] = (short)(sums[cBase + d] / cnt);
                    }
                }
            }

            System.out.printf("[index]   iter %d: changes=%,d empty=%d%n", iter, changes, empty);
            if (changes == 0 && empty == 0) {
                System.out.println("[index]   converged");
                break;
            }
        }

        // Final assignment (centroids may have moved one last time).
        assignParallel(points, n, centroids, k, assignment, nThreads);
        Arrays.fill(counts, 0);
        for (int i = 0; i < n; i++) counts[assignment[i]]++;

        int[] offset = new int[k + 1];
        for (int c = 0; c < k; c++) offset[c + 1] = offset[c] + counts[c];

        int[] cursors = new int[k];
        int[] permutation = new int[n];
        for (int i = 0; i < n; i++) {
            int c = assignment[i];
            permutation[offset[c] + cursors[c]++] = i;
        }

        // Report cluster balance.
        int min = Integer.MAX_VALUE, max = 0;
        long total = 0;
        for (int c = 0; c < k; c++) {
            if (counts[c] < min) min = counts[c];
            if (counts[c] > max) max = counts[c];
            total += counts[c];
        }
        System.out.printf("[index] cluster size: min=%d max=%d avg=%.1f%n",
            min, max, total / (double) k);

        Kmeans km = new Kmeans();
        km.k = k;
        km.centroids = centroids;
        km.clusterSize = counts;
        km.clusterOffset = offset;
        km.permutation = permutation;
        return km;
    }

    private static int assignParallel(short[] points, int n, short[] centroids, int k,
                                      int[] assignment, int nThreads) {
        if (nThreads <= 1) {
            return assignRange(points, 0, n, centroids, k, assignment);
        }
        int[] changes = new int[nThreads];
        Thread[] threads = new Thread[nThreads];
        int chunk = (n + nThreads - 1) / nThreads;
        for (int t = 0; t < nThreads; t++) {
            final int tid = t;
            final int lo = t * chunk;
            final int hi = Math.min(n, lo + chunk);
            if (lo >= hi) continue;
            threads[t] = new Thread(() ->
                changes[tid] = assignRange(points, lo, hi, centroids, k, assignment),
                "kmeans-" + tid);
            threads[t].start();
        }
        try {
            for (Thread th : threads) if (th != null) th.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        int total = 0;
        for (int c : changes) total += c;
        return total;
    }

    private static int assignRange(short[] points, int lo, int hi,
                                   short[] centroids, int k, int[] assignment) {
        int changes = 0;
        for (int i = lo; i < hi; i++) {
            int best = nearestCentroid(points, i * DIMS, centroids, k);
            if (assignment[i] != best) {
                assignment[i] = best;
                changes++;
            }
        }
        return changes;
    }

    private static int nearestCentroid(short[] points, int pOff, short[] centroids, int k) {
        int  best     = 0;
        long bestDist = Long.MAX_VALUE;
        for (int c = 0; c < k; c++) {
            int cOff = c * DIMS;
            long d = 0;
            for (int dim = 0; dim < DIMS; dim++) {
                int diff = points[pOff + dim] - centroids[cOff + dim];
                d += (long) diff * diff;
            }
            if (d < bestDist) {
                bestDist = d;
                best     = c;
            }
        }
        return best;
    }

    // ---------- write ----------

    static long write(Path out, Loaded data, Kmeans km) throws IOException {
        int n = data.n;
        int k = km.k;
        int maxClusterSize = 0;
        for (int c = 0; c < k; c++) {
            if (km.clusterSize[c] > maxClusterSize) maxClusterSize = km.clusterSize[c];
        }

        long centroidsBytes = (long) k * DIMS_STORED * 2;
        long offsetsBytes   = (long)(k + 1) * 4;
        long pointsBytes    = (long) n * DIMS_STORED * 2;
        long labelsBytes    = n;
        long total = HEADER_BYTES + centroidsBytes + offsetsBytes + pointsBytes + labelsBytes;

        try (FileChannel ch = FileChannel.open(out,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putInt(n);
            header.putInt(DIMS);
            header.putInt(k);
            header.putInt(maxClusterSize);
            // 16 bytes reserved
            header.putInt(0).putInt(0).putInt(0).putInt(0);
            header.flip();
            drain(ch, header);

            ByteBuffer cBuf = ByteBuffer.allocateDirect(1 << 17).order(ByteOrder.LITTLE_ENDIAN);
            int centsPerChunk = cBuf.capacity() / (DIMS_STORED * 2);
            for (int i = 0; i < k; ) {
                int end = Math.min(k, i + centsPerChunk);
                cBuf.clear();
                for (int j = i; j < end; j++) {
                    int base = j * DIMS;
                    for (int d = 0; d < DIMS; d++) cBuf.putShort(km.centroids[base + d]);
                    cBuf.putShort((short) 0);
                    cBuf.putShort((short) 0);
                }
                cBuf.flip();
                drain(ch, cBuf);
                i = end;
            }

            writeInts(ch, km.clusterOffset);

            ByteBuffer pBuf = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.LITTLE_ENDIAN);
            int pointsPerChunk = pBuf.capacity() / (DIMS_STORED * 2);
            for (int i = 0; i < n; ) {
                int end = Math.min(n, i + pointsPerChunk);
                pBuf.clear();
                for (int j = i; j < end; j++) {
                    int orig = km.permutation[j];
                    int base = orig * DIMS;
                    for (int d = 0; d < DIMS; d++) pBuf.putShort(data.points[base + d]);
                    pBuf.putShort((short) 0);
                    pBuf.putShort((short) 0);
                }
                pBuf.flip();
                drain(ch, pBuf);
                i = end;
            }

            ByteBuffer lBuf = ByteBuffer.allocateDirect(1 << 16);
            for (int i = 0; i < n; ) {
                int end = Math.min(n, i + lBuf.capacity());
                lBuf.clear();
                for (int j = i; j < end; j++) lBuf.put(data.labels[km.permutation[j]]);
                lBuf.flip();
                drain(ch, lBuf);
                i = end;
            }

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
