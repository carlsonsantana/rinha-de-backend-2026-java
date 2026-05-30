package rinha;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public final class FraudScoreHandler {

    private static final int DIMS        = 14;
    private static final int DIMS_STORED = 16; // 14 dims + 2 SIMD padding shorts per node
    private static final int SCALE       = 10_000;

    private static final VectorSpecies<Short>   S_S256 = ShortVector.SPECIES_256;
    private static final VectorSpecies<Integer> S_I256 = IntVector.SPECIES_256;
    private static final VectorSpecies<Long>    S_L256 = LongVector.SPECIES_256;

    // All 6 possible responses pre-built at class load: fraud count 0..5 → score 0.0..1.0.
    // Zero allocation on the hot path.
    static final byte[][] RESPONSES = new byte[6][];
    static {
        for (int frauds = 0; frauds <= 5; frauds++) {
            double  score    = frauds / 5.0;
            boolean approved = score < 0.6;
            String body   = "{\"approved\":" + approved + ",\"fraud_score\":" + score + "}";
            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                          + body.length() + "\r\nConnection: close\r\n\r\n";
            byte[] h = header.getBytes(), b = body.getBytes();
            byte[] r = new byte[h.length + b.length];
            System.arraycopy(h, 0, r, 0, h.length);
            System.arraycopy(b, 0, r, h.length, b.length);
            RESPONSES[frauds] = r;
        }
    }

    static final byte[] HTTP_503 = (
        "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    ).getBytes();

    private static final byte[] HTTP_500 = (
        "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    ).getBytes();

    // How many centroids to probe per query. Higher → better recall, slower.
    private static final int NPROBE = Integer.parseInt(
        System.getProperty("ivf.nprobe", "4"));

    private final IvfLoader  loader;
    private final Normalizer norm;

    // Top-5 max-heap (worst dist at index 0); reused across calls (single-threaded).
    private static final long[] dists = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
    private static final int[]  nodes = new int[5];

    // Top-NPROBE max-heap for centroid selection.
    private static final long[] probeDists = new long[NPROBE];
    private static final int[]  probeIds   = new int[NPROBE];

    public FraudScoreHandler(IvfLoader loader, Normalizer norm) {
        this.loader = loader;
        this.norm   = norm;
    }

    public byte[] handle(MemorySegment reqBuf, int bytesRead) {
        if (!loader.isLoaded()) return HTTP_503;
        try {
            return process(reqBuf, bytesRead);
        } catch (Exception e) {
            System.err.println("[fraud] " + e);
            return HTTP_500;
        }
    }

    private byte[] process(MemorySegment reqBuf, int bytesRead) throws IOException {
        int bodyStart = findBody(reqBuf, bytesRead);
        if (bodyStart < 0) throw new IOException("no HTTP body separator");

        ParsedRequest p = parseRequest(new JsonReader(reqBuf, bodyStart, bytesRead));
        short[] query   = computeVector(p);

        IvfLoader.IvfData ivf = loader.getContent();
        return RESPONSES[countFrauds(ivf, query)];
    }

    // ── body location ───────────────────────────────────────────────────────

    private static int findBody(MemorySegment seg, int len) {
        for (int i = 0; i < len - 3; i++) {
            if (seg.get(JAVA_BYTE, i)   == '\r'
             && seg.get(JAVA_BYTE, i+1) == '\n'
             && seg.get(JAVA_BYTE, i+2) == '\r'
             && seg.get(JAVA_BYTE, i+3) == '\n') return i + 4;
        }
        return -1;
    }

    // ── vector computation ──────────────────────────────────────────────────

    private short[] computeVector(ParsedRequest p) {
        OffsetDateTime txTime = OffsetDateTime.parse(p.txRequestedAt);

        double d0 = Normalizer.clamp(p.txAmount / norm.maxAmount);
        double d1 = Normalizer.clamp(p.txInstallments / norm.maxInstallments);
        double d2 = p.customerAvgAmount > 0.0
            ? Normalizer.clamp((p.txAmount / p.customerAvgAmount) / norm.amountVsAvgRatio)
            : 0.0;
        double d3 = txTime.getHour() / 23.0;
        double d4 = (txTime.getDayOfWeek().getValue() - 1) / 6.0;

        double d5, d6;
        if (p.hasLastTx) {
            OffsetDateTime lastTime = OffsetDateTime.parse(p.lastTxTimestamp);
            long minutes = Duration.between(lastTime, txTime).toMinutes();
            d5 = Normalizer.clamp(minutes / norm.maxMinutes);
            d6 = Normalizer.clamp(p.lastTxKmFromCurrent / norm.maxKm);
        } else {
            d5 = -1.0;
            d6 = -1.0;
        }

        double d7  = Normalizer.clamp(p.kmFromHome / norm.maxKm);
        double d8  = Normalizer.clamp(p.customerTxCount24h / norm.maxTxCount24h);
        double d9  = p.isOnline    ? 1.0 : 0.0;
        double d10 = p.cardPresent ? 1.0 : 0.0;
        double d11 = p.unknownMerchant ? 1.0 : 0.0;
        double d12 = norm.mccRisk(p.merchantMcc);
        double d13 = Normalizer.clamp(p.merchantAvgAmount / norm.maxMerchantAvgAmount);

        return new short[]{
            enc(d0), enc(d1), enc(d2),  enc(d3),
            enc(d4), enc(d5), enc(d6),  enc(d7),
            enc(d8), enc(d9), enc(d10), enc(d11),
            enc(d12), enc(d13), 0, 0   // last 2 are SIMD padding lanes
        };
    }

    private static short enc(double v) {
        return (short) Math.round(v * SCALE);
    }

    // ── IVF approximate top-5 search ────────────────────────────────────────

    private static int countFrauds(IvfLoader.IvfData ivf, short[] query) {
        for (int i = 0; i < 5; i++)      dists[i]      = Long.MAX_VALUE;
        for (int i = 0; i < NPROBE; i++) probeDists[i] = Long.MAX_VALUE;

        // 1. Find nearest NPROBE centroids.
        MemorySegment centroids = ivf.centroids();
        int k = ivf.k();
        for (int c = 0; c < k; c++) {
            long base = (long) c * DIMS_STORED * 2;
            long d    = distSqSimd(query, centroids, base);
            probePush(d, c);
        }

        // 2. Brute-force top-5 over the points in those clusters.
        int[]         offset = ivf.clusterOffset();
        MemorySegment points = ivf.points();
        for (int p = 0; p < NPROBE; p++) {
            int c   = probeIds[p];
            int lo  = offset[c];
            int hi  = offset[c + 1];
            long byteOff = (long) lo * DIMS_STORED * 2;
            for (int node = lo; node < hi; node++, byteOff += DIMS_STORED * 2) {
                long d = distSqSimd(query, points, byteOff);
                topPush(d, node);
            }
        }

        // 3. Count frauds among the top-5 (labels are cluster-ordered too).
        byte[] labels = ivf.labels();
        int frauds = 0;
        for (int i = 0; i < 5; i++) {
            if (labels[nodes[i]] == 1) frauds++;
        }
        return frauds;
    }

    private static long distSqSimd(short[] query, MemorySegment data, long byteOffset) {
        ShortVector q    = ShortVector.fromArray(S_S256, query, 0);
        ShortVector p    = ShortVector.fromMemorySegment(S_S256, data, byteOffset,
                                                         ByteOrder.LITTLE_ENDIAN);
        ShortVector diff = q.sub(p); // diff ∈ [-20000, 20000], fits in short

        // Widen short→int before squaring: diff² ≤ 4×10⁸, fits in int
        IntVector dLo = (IntVector) diff.castShape(S_I256, 0); // lanes 0–7
        IntVector dHi = (IntVector) diff.castShape(S_I256, 1); // lanes 8–15
        IntVector sqLo = dLo.mul(dLo);
        IntVector sqHi = dHi.mul(dHi);

        // Combine pairs before widening to long: sqLo[i]+sqHi[i] ≤ 8×10⁸ < INT_MAX
        IntVector sqTotal = sqLo.add(sqHi);

        // Widen int→long: sum of 8 lanes ≤ 8×8×10⁸=6.4×10⁹ overflows int, needs long
        LongVector lo = (LongVector) sqTotal.castShape(S_L256, 0);
        LongVector hi = (LongVector) sqTotal.castShape(S_L256, 1);
        return lo.add(hi).reduceLanes(VectorOperators.ADD);
    }

    // Max-heap capacity 5: dists[0] is always the largest of the kept top-5.
    private static void topPush(long dist, int nodeId) {
        if (dist < dists[0]) {
            dists[0] = dist;
            nodes[0] = nodeId;
            int i = 0;
            while (true) {
                int l = (i << 1) | 1, r = l + 1, max = i;
                if (l < 5 && dists[l] > dists[max]) max = l;
                if (r < 5 && dists[r] > dists[max]) max = r;
                if (max == i) break;
                long td = dists[i]; dists[i] = dists[max]; dists[max] = td;
                int  tn = nodes[i]; nodes[i] = nodes[max]; nodes[max] = tn;
                i = max;
            }
        }
    }

    // Max-heap capacity NPROBE: probeDists[0] is the worst centroid distance kept.
    private static void probePush(long dist, int centroidId) {
        if (dist < probeDists[0]) {
            probeDists[0] = dist;
            probeIds[0]   = centroidId;
            int i = 0;
            while (true) {
                int l = (i << 1) | 1, r = l + 1, max = i;
                if (l < NPROBE && probeDists[l] > probeDists[max]) max = l;
                if (r < NPROBE && probeDists[r] > probeDists[max]) max = r;
                if (max == i) break;
                long td = probeDists[i]; probeDists[i] = probeDists[max]; probeDists[max] = td;
                int  ti = probeIds[i];   probeIds[i]   = probeIds[max];   probeIds[max]   = ti;
                i = max;
            }
        }
    }

    // ── request parsing ─────────────────────────────────────────────────────

    private record ParsedRequest(
        double  txAmount,
        double  txInstallments,
        String  txRequestedAt,
        double  customerAvgAmount,
        double  customerTxCount24h,
        String  merchantMcc,
        double  merchantAvgAmount,
        boolean unknownMerchant,
        boolean isOnline,
        boolean cardPresent,
        double  kmFromHome,
        boolean hasLastTx,
        String  lastTxTimestamp,
        double  lastTxKmFromCurrent
    ) {}

    private static ParsedRequest parseRequest(JsonReader jr) throws IOException {
        double  txAmount = 0, txInstallments = 0;
        String  txRequestedAt = null;
        double  customerAvgAmount = 0, customerTxCount24h = 0;
        List<String> knownMerchants = List.of();
        String  merchantId = null, merchantMcc = null;
        double  merchantAvgAmount = 0;
        boolean isOnline = false, cardPresent = false;
        double  kmFromHome = 0;
        boolean hasLastTx = false;
        String  lastTxTimestamp = null;
        double  lastTxKmFromCurrent = 0;

        jr.expect('{');
        String key;
        while ((key = jr.nextKey()) != null) {
            switch (key) {
                case "transaction" -> {
                    jr.expect('{');
                    String k;
                    while ((k = jr.nextKey()) != null) switch (k) {
                        case "amount"       -> txAmount       = jr.readNumber();
                        case "installments" -> txInstallments = jr.readNumber();
                        case "requested_at" -> txRequestedAt  = jr.readString();
                        default             -> jr.skipValue();
                    }
                    jr.expect('}');
                }
                case "customer" -> {
                    jr.expect('{');
                    String k;
                    while ((k = jr.nextKey()) != null) switch (k) {
                        case "avg_amount"      -> customerAvgAmount  = jr.readNumber();
                        case "tx_count_24h"    -> customerTxCount24h = jr.readNumber();
                        case "known_merchants" -> {
                            List<String> list = new ArrayList<>();
                            jr.expect('[');
                            while (jr.peek() != ']') {
                                if (jr.peek() == ',') { jr.consume(); continue; }
                                list.add(jr.readString());
                            }
                            jr.consume(); // ']'
                            knownMerchants = list;
                        }
                        default -> jr.skipValue();
                    }
                    jr.expect('}');
                }
                case "merchant" -> {
                    jr.expect('{');
                    String k;
                    while ((k = jr.nextKey()) != null) switch (k) {
                        case "id"         -> merchantId        = jr.readString();
                        case "mcc"        -> merchantMcc       = jr.readString();
                        case "avg_amount" -> merchantAvgAmount = jr.readNumber();
                        default           -> jr.skipValue();
                    }
                    jr.expect('}');
                }
                case "terminal" -> {
                    jr.expect('{');
                    String k;
                    while ((k = jr.nextKey()) != null) switch (k) {
                        case "is_online"    -> isOnline    = jr.readBoolean();
                        case "card_present" -> cardPresent = jr.readBoolean();
                        case "km_from_home" -> kmFromHome  = jr.readNumber();
                        default             -> jr.skipValue();
                    }
                    jr.expect('}');
                }
                case "last_transaction" -> {
                    if (jr.peek() == 'n') { jr.skipValue(); }
                    else {
                        hasLastTx = true;
                        jr.expect('{');
                        String k;
                        while ((k = jr.nextKey()) != null) switch (k) {
                            case "timestamp"       -> lastTxTimestamp     = jr.readString();
                            case "km_from_current" -> lastTxKmFromCurrent = jr.readNumber();
                            default                -> jr.skipValue();
                        }
                        jr.expect('}');
                    }
                }
                default -> jr.skipValue();
            }
        }

        boolean unknownMerchant = merchantId != null && !knownMerchants.contains(merchantId);
        return new ParsedRequest(
            txAmount, txInstallments, txRequestedAt,
            customerAvgAmount, customerTxCount24h,
            merchantMcc, merchantAvgAmount, unknownMerchant,
            isOnline, cardPresent, kmFromHome,
            hasLastTx, lastTxTimestamp, lastTxKmFromCurrent
        );
    }

    // ── minimal JSON reader over a MemorySegment ────────────────────────────

    private static final class JsonReader {
        private final MemorySegment seg;
        private int pos;
        private final int end;

        JsonReader(MemorySegment seg, int start, int end) {
            this.seg = seg;
            this.pos = start;
            this.end = end;
        }

        // Skips whitespace; returns the next char without consuming it (or -1 at end).
        int peek() {
            while (pos < end) {
                byte b = seg.get(JAVA_BYTE, pos);
                if (b == ' ' || b == '\t' || b == '\n' || b == '\r') pos++;
                else return b & 0xFF;
            }
            return -1;
        }

        void consume() { pos++; }

        void expect(char c) throws IOException {
            int got = peek();
            if (got != c) throw new IOException("expected '" + c + "' got '" + (char) got + "' at " + pos);
            pos++;
        }

        // Returns null when the object's '}' is next; does NOT consume it.
        String nextKey() throws IOException {
            int c = peek();
            if (c == ',') { pos++; c = peek(); }
            if (c == '}') return null;
            String key = readString();
            expect(':');
            return key;
        }

        String readString() throws IOException {
            expect('"');
            int start = pos;
            while (pos < end) {
                byte b = seg.get(JAVA_BYTE, pos);
                if (b == '\\') { pos += 2; continue; }
                if (b == '"')  break;
                pos++;
            }
            int len = pos - start;
            byte[] arr = new byte[len];
            MemorySegment.copy(seg, JAVA_BYTE, start, arr, 0, len);
            pos++; // closing '"'
            return new String(arr);
        }

        double readNumber() throws IOException {
            peek(); // skip whitespace; pos is now at first digit or '-'
            int start = pos;
            while (pos < end) {
                byte b = seg.get(JAVA_BYTE, pos);
                if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-') pos++;
                else break;
            }
            byte[] arr = new byte[pos - start];
            MemorySegment.copy(seg, JAVA_BYTE, start, arr, 0, arr.length);
            return Double.parseDouble(new String(arr));
        }

        boolean readBoolean() throws IOException {
            int c = peek();
            if (c == 't') { pos += 4; return true;  }
            if (c == 'f') { pos += 5; return false; }
            throw new IOException("expected boolean at " + pos);
        }

        void skipValue() throws IOException {
            int c = peek();
            switch (c) {
                case '"' -> readString();
                case '{' -> {
                    pos++;
                    String k;
                    while ((k = nextKey()) != null) skipValue();
                    pos++; // '}'
                }
                case '[' -> {
                    pos++;
                    while (peek() != ']') {
                        if (peek() == ',') { pos++; continue; }
                        skipValue();
                    }
                    pos++; // ']'
                }
                case 'n' -> pos += 4;
                case 't' -> pos += 4;
                case 'f' -> pos += 5;
                default  -> { // number
                    while (pos < end) {
                        byte b = seg.get(JAVA_BYTE, pos);
                        if ((b >= '0' && b <= '9') || b == '.' || b == 'e' || b == 'E' || b == '+' || b == '-') pos++;
                        else break;
                    }
                }
            }
        }
    }
}
