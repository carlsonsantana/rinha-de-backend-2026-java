#!/usr/bin/env bash
set -euo pipefail

TESTS_DIR="$(cd "$(dirname "$0")" && pwd)"
P99_TARGET_MS=5
P99_HARD_MS=15

if ! command -v wrk &>/dev/null; then
    echo "ERROR: wrk not found — install wrk to run perf tests"
    exit 1
fi

echo "=== Performance test (30s, 64 connections, 2 threads) ==="
OUTPUT=$(wrk -t2 -c64 -d30s --latency \
    -s "$TESTS_DIR/post.lua" \
    http://localhost:9999/fraud-score 2>&1)
echo "$OUTPUT"

# Extract p99 from wrk's latency table (format: "  99%    4.12ms")
P99_LINE=$(echo "$OUTPUT" | grep -E '^\s+99%' | head -1)
if [ -z "$P99_LINE" ]; then
    echo "ERROR: could not parse p99 from wrk output"
    exit 1
fi

# wrk prints latency in us/ms/s
P99_RAW=$(echo "$P99_LINE" | awk '{print $2}')
if echo "$P99_RAW" | grep -q 'us'; then
    P99_MS=$(echo "$P99_RAW" | sed 's/us//' | awk '{printf "%.3f", $1/1000}')
elif echo "$P99_RAW" | grep -q 'ms'; then
    P99_MS=$(echo "$P99_RAW" | sed 's/ms//')
elif echo "$P99_RAW" | grep -q 's'; then
    P99_MS=$(echo "$P99_RAW" | sed 's/s//' | awk '{printf "%.3f", $1*1000}')
else
    P99_MS="$P99_RAW"
fi

echo ""
echo "p99 latency: ${P99_MS}ms  (target <${P99_TARGET_MS}ms, hard limit <${P99_HARD_MS}ms)"

if awk "BEGIN{exit !($P99_MS >= $P99_HARD_MS)}"; then
    echo "FAIL: p99 ${P99_MS}ms exceeds hard limit ${P99_HARD_MS}ms"
    exit 1
elif awk "BEGIN{exit !($P99_MS >= $P99_TARGET_MS)}"; then
    echo "WARN: p99 ${P99_MS}ms exceeds soft target ${P99_TARGET_MS}ms"
    exit 0
else
    echo "PASS"
fi
