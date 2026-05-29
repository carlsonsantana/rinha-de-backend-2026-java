package rinha;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FraudScoreCorrectnessTest {

    private static final Path TEST_DATA = Path.of("src/test/resources/test-data.json");
    private static final double SCORE_EPSILON = 1e-9;

    private FraudScoreHandler handler;
    private List<TestCase>    cases;

    @BeforeAll
    void setUp() throws Exception {
        handler = TestFixture.createHandler();
        cases   = loadCases(Files.readString(TEST_DATA));
        if (cases.isEmpty()) fail("no records loaded from " + TEST_DATA);
    }

    @Test
    void allRecordsMatchExpected() {
        List<String> failures = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            TestCase tc   = cases.get(i);
            byte[]   resp = handler.handle(tc.payload, tc.size);
            assertNotNull(resp, "record " + i + " (" + tc.id + "): null response");
            assertNotSame(FraudScoreHandler.HTTP_503, resp,
                "record " + i + " (" + tc.id + "): got 503");

            ParsedResponse pr = parseResponse(resp);
            boolean scoreMismatch    = Math.abs(pr.score - tc.expectedScore) > SCORE_EPSILON;
            boolean approvedMismatch = pr.approved != tc.expectedApproved;
            if (scoreMismatch || approvedMismatch) {
                failures.add(String.format(
                    "record %d (%s): got approved=%b score=%s, expected approved=%b score=%s",
                    i, tc.id, pr.approved, format(pr.score),
                    tc.expectedApproved, format(tc.expectedScore)));
            }
        }

        System.out.printf("[correctness] %d/%d matched%n",
            cases.size() - failures.size(), cases.size());

        if (!failures.isEmpty()) {
            StringBuilder msg = new StringBuilder(failures.size() + " mismatches:\n");
            int show = Math.min(failures.size(), 20);
            for (int i = 0; i < show; i++) msg.append(failures.get(i)).append('\n');
            if (failures.size() > show) msg.append("...\n");
            fail(msg.toString());
        }
    }

    // ── test-data parsing ───────────────────────────────────────────────────

    private record TestCase(
        String id,
        MemorySegment payload,
        int size,
        boolean expectedApproved,
        double  expectedScore
    ) {}

    private static List<TestCase> loadCases(String json) {
        List<TestCase> out = new ArrayList<>();
        int pos = json.indexOf('[');
        if (pos < 0) throw new IllegalStateException("test-data not an array");
        pos++;
        while (pos < json.length()) {
            pos = skipWsAndCommas(json, pos);
            if (pos >= json.length() || json.charAt(pos) == ']') break;
            if (json.charAt(pos) != '{') {
                throw new IllegalStateException("expected '{' at " + pos + " got '" + json.charAt(pos) + "'");
            }
            int recEnd = findMatchingBrace(json, pos);
            String rec = json.substring(pos, recEnd + 1);

            int reqStart = findKeyValueStart(rec, "\"request\":");
            if (rec.charAt(reqStart) != '{')
                throw new IllegalStateException("request value not an object");
            int reqEnd = findMatchingBrace(rec, reqStart);
            String requestJson = rec.substring(reqStart, reqEnd + 1);

            int eaStart  = findKeyValueStart(rec, "\"expected_approved\":");
            boolean expectedApproved = rec.startsWith("true", eaStart);

            int esStart  = findKeyValueStart(rec, "\"expected_fraud_score\":");
            double expectedScore = parseNumberAt(rec, esStart);

            String id = extractStringField(requestJson, "\"id\":");

            byte[] req = buildHttpRequest(requestJson);
            out.add(new TestCase(
                id, MemorySegment.ofArray(req), req.length,
                expectedApproved, expectedScore));

            pos = recEnd + 1;
        }
        return out;
    }

    private static int skipWsAndCommas(String s, int p) {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c) || c == ',') p++;
            else break;
        }
        return p;
    }

    private static int findKeyValueStart(String s, String key) {
        int k = s.indexOf(key);
        if (k < 0) throw new IllegalStateException("missing key " + key);
        int p = k + key.length();
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    // Returns the index of the matching '}' for the '{' at openIdx,
    // respecting string literals and backslash escapes.
    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (inStr) {
                if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("unmatched '{' at " + openIdx);
    }

    private static double parseNumberAt(String s, int start) {
        int p = start;
        while (p < s.length()) {
            char c = s.charAt(p);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') p++;
            else break;
        }
        return Double.parseDouble(s.substring(start, p));
    }

    private static String extractStringField(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) return "?";
        int p = k + key.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return "?";
        p++;
        int start = p;
        while (p < json.length() && json.charAt(p) != '"') p++;
        return json.substring(start, p);
    }

    // ── response parsing ────────────────────────────────────────────────────

    private record ParsedResponse(boolean approved, double score) {}

    private static ParsedResponse parseResponse(byte[] resp) {
        String s = new String(resp, StandardCharsets.UTF_8);
        int bodyStart = s.indexOf("\r\n\r\n");
        if (bodyStart < 0) throw new IllegalStateException("no body separator in response");
        String body = s.substring(bodyStart + 4);

        boolean approved;
        if      (body.contains("\"approved\":true"))  approved = true;
        else if (body.contains("\"approved\":false")) approved = false;
        else throw new IllegalStateException("no approved field in body: " + body);

        int k = body.indexOf("\"fraud_score\":");
        if (k < 0) throw new IllegalStateException("no fraud_score in body: " + body);
        int p = k + "\"fraud_score\":".length();
        double score = parseNumberAt(body, p);
        return new ParsedResponse(approved, score);
    }

    // ── request building ────────────────────────────────────────────────────

    private static byte[] buildHttpRequest(String bodyJson) {
        byte[] bodyBytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        String header = "POST /fraud-score HTTP/1.1\r\n"
                      + "Host: localhost\r\n"
                      + "Content-Type: application/json\r\n"
                      + "Content-Length: " + bodyBytes.length + "\r\n"
                      + "\r\n";
        byte[] hb  = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[hb.length + bodyBytes.length];
        System.arraycopy(hb,        0, out, 0,         hb.length);
        System.arraycopy(bodyBytes, 0, out, hb.length, bodyBytes.length);
        return out;
    }

    private static String format(double v) {
        return v == Math.floor(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }
}
