#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TESTS="$REPO/tests"

cleanup() {
    echo ""
    echo "=== Tearing down ==="
    docker compose -f "$REPO/docker-compose.yml" down -v --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

echo "=== Building images ==="
docker compose -f "$REPO/docker-compose.yml" build

echo "=== Starting stack ==="
docker compose -f "$REPO/docker-compose.yml" up -d

echo "=== Waiting for /ready (up to 120s) ==="
for i in $(seq 1 240); do
    if curl -fsS -o /dev/null http://localhost:9999/ready 2>/dev/null; then
        echo "Ready after ${i} x 0.5s"
        break
    fi
    if [ "$i" -eq 240 ]; then
        echo "ERROR: /ready never returned 2xx after 120s"
        docker compose -f "$REPO/docker-compose.yml" logs
        exit 1
    fi
    sleep 0.5
done

FAILED=0

echo ""
echo "=== Correctness test ==="
python3 "$TESTS/correctness.py" || FAILED=1

echo ""
echo "=== Anti-mix test ==="
python3 "$TESTS/anti_mix.py" || FAILED=1

echo ""
echo "=== Performance test ==="
bash "$TESTS/perf.sh" || FAILED=1

echo ""
if [ "$FAILED" -eq 0 ]; then
    echo "All tests PASSED"
else
    echo "One or more tests FAILED"
    exit 1
fi
