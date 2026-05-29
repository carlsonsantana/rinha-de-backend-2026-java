package rinha;

import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import rinha.index.BuildIndex;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FraudScorePerformanceTest {

    private static final int  N_CALLS      = 50;
    private static final long MAX_TOTAL_MS = 1_000L;
    private static final long SEED         = 42L;

    // Fixed reference time so generated payloads are reproducible across runs.
    private static final OffsetDateTime BASE_TIME =
        OffsetDateTime.parse("2026-01-15T12:00:00Z");

    private static final String[] MCCS = {
        "5411", "5812", "5732", "4814", "5921", "6011", "5999", "4111"
    };

    private FraudScoreHandler handler;
    private MemorySegment[]   payloads;
    private int[]             sizes;

    @BeforeAll
    void setUp() throws Exception {
        Path indexPath = ensureIndexBin();

        KdTreeLoader loader = new KdTreeLoader(indexPath);
        loader.startLoading();
        long deadlineNs = System.nanoTime() + 60_000_000_000L;
        while (!loader.isLoaded()) {
            Throwable err = loader.getLoadError();
            if (err != null) throw new IllegalStateException("kdtree load failed", err);
            if (System.nanoTime() > deadlineNs) fail("kdtree load timed out");
            Thread.sleep(50);
        }

        Normalizer norm = Normalizer.load(
            Path.of("src/main/resources/normalization.json"),
            Path.of("src/main/resources/mcc_risk.json"));

        handler = new FraudScoreHandler(loader, norm);

        Random rng = new Random(SEED);
        payloads = new MemorySegment[N_CALLS];
        sizes    = new int[N_CALLS];
        for (int i = 0; i < N_CALLS; i++) {
            byte[] req  = buildHttpRequest(rng);
            payloads[i] = MemorySegment.ofArray(req);
            sizes[i]    = req.length;
        }
    }

    @Test
    void fiftySequentialCallsCompleteUnderOneSecond() {
        long[] perCallNs = new long[N_CALLS];

        long startNs = System.nanoTime();
        for (int i = 0; i < N_CALLS; i++) {
            long t0 = System.nanoTime();
            byte[] resp = handler.handle(payloads[i], sizes[i]);
            perCallNs[i] = System.nanoTime() - t0;
            assertNotNull(resp, "call " + i + " returned null");
            assertNotSame(FraudScoreHandler.HTTP_503, resp, "call " + i + " got 503");
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        long[] sorted = perCallNs.clone();
        Arrays.sort(sorted);
        double firstMs = perCallNs[0] / 1e6;
        double minMs   = sorted[0] / 1e6;
        double p50Ms   = sorted[sorted.length / 2] / 1e6;
        double p99Ms   = sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6;
        double maxMs   = sorted[sorted.length - 1] / 1e6;

        System.out.printf(
            "[bench] %d sequential calls: total=%d ms avg=%.2f ms "
          + "first=%.2f ms min=%.2f ms p50=%.2f ms p99=%.2f ms max=%.2f ms%n",
            N_CALLS, elapsedMs, elapsedMs / (double) N_CALLS,
            firstMs, minMs, p50Ms, p99Ms, maxMs);

        assertTrue(elapsedMs < MAX_TOTAL_MS,
            "expected total < " + MAX_TOTAL_MS + " ms but was " + elapsedMs + " ms");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Path ensureIndexBin() throws Exception {
        Path indexPath = Path.of("target", "index.bin");
        if (Files.exists(indexPath) && Files.size(indexPath) > 0) return indexPath;

        Files.createDirectories(indexPath.getParent());

        Path refPath = Path.of("target", "test-references.json.gz");
        try (InputStream in = FraudScorePerformanceTest.class
                .getResourceAsStream("/references.json.gz")) {
            if (in == null) throw new IllegalStateException(
                "references.json.gz not on test classpath — "
              + "run `mvn install` in ../indexer first");
            Files.copy(in, refPath, StandardCopyOption.REPLACE_EXISTING);
        }

        BuildIndex.main(new String[]{refPath.toString(), indexPath.toString()});
        return indexPath;
    }

    private static byte[] buildHttpRequest(Random rng) {
        String  txTime    = BASE_TIME.minusHours(rng.nextInt(720)).toString();
        boolean hasLast   = rng.nextBoolean();
        String  lastTime  = BASE_TIME.minusHours(rng.nextInt(720) + 24).toString();

        StringBuilder body = new StringBuilder(512);
        body.append('{');
        body.append("\"transaction\":{")
            .append("\"amount\":").append(50 + rng.nextDouble() * 5_000).append(',')
            .append("\"installments\":").append(1 + rng.nextInt(12)).append(',')
            .append("\"requested_at\":\"").append(txTime).append('"')
            .append("},");
        body.append("\"customer\":{")
            .append("\"avg_amount\":").append(50 + rng.nextDouble() * 1_000).append(',')
            .append("\"tx_count_24h\":").append(rng.nextInt(20)).append(',')
            .append("\"known_merchants\":[\"m1\",\"m2\",\"m3\"]")
            .append("},");
        body.append("\"merchant\":{")
            .append("\"id\":\"m").append(rng.nextInt(10)).append("\",")
            .append("\"mcc\":\"").append(MCCS[rng.nextInt(MCCS.length)]).append("\",")
            .append("\"avg_amount\":").append(50 + rng.nextDouble() * 5_000)
            .append("},");
        body.append("\"terminal\":{")
            .append("\"is_online\":").append(rng.nextBoolean()).append(',')
            .append("\"card_present\":").append(rng.nextBoolean()).append(',')
            .append("\"km_from_home\":").append(rng.nextDouble() * 500)
            .append("},");
        if (hasLast) {
            body.append("\"last_transaction\":{")
                .append("\"timestamp\":\"").append(lastTime).append("\",")
                .append("\"km_from_current\":").append(rng.nextDouble() * 500)
                .append('}');
        } else {
            body.append("\"last_transaction\":null");
        }
        body.append('}');

        String bodyStr = body.toString();
        String request = "POST /fraud-score HTTP/1.1\r\n"
                       + "Host: localhost\r\n"
                       + "Content-Type: application/json\r\n"
                       + "Content-Length: " + bodyStr.length() + "\r\n"
                       + "\r\n"
                       + bodyStr;
        return request.getBytes(StandardCharsets.UTF_8);
    }
}
