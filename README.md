# Rinha de Backend 2026 — Java + SoNoForevis

Submission for [zanfranceschi/rinha-de-backend-2026](https://github.com/zanfranceschi/rinha-de-backend-2026): a real-time fraud scoring API running under a tight resource cap (1 CPU / 350 MB total across all containers).

## What it does

`POST /fraud-score` receives a payment transaction and returns how likely it is to be fraudulent:

```json
{ "approved": true, "fraud_score": 0.2 }
```

The score is computed by finding the 5 nearest neighbors of the transaction in a reference dataset of ~3 million labeled transactions and counting how many are marked as fraud. `fraud_score = frauds_in_top5 / 5`. `approved = fraud_score < 0.6`.

## Architecture

```
client → :9999 → SoNoForevis (load balancer)
                      │
              SCM_RIGHTS FD passing
              over Unix Domain Sockets
                      │
           ┌──────────┴──────────┐
         api1 (JVM)           api2 (JVM)
```

The load balancer ([SoNoForevis](https://github.com/jairoblatt/SoNoForevis)) does not proxy bytes. It `accept()`s the TCP connection and hands the raw file descriptor to one of the API workers via `sendmsg()` ancillary data (`SCM_RIGHTS`). Each worker receives the FD and serves HTTP directly on it — no intermediate copy, no extra hop.

## Technologies

### Load balancer — SoNoForevis (Rust)

A Rust-based TCP load balancer that uses `io_uring` for I/O and passes raw connection FDs to workers over Unix Domain Sockets. Zero-copy between LB and API.

### API — Java 25 (HotSpot)

No framework. Every layer is hand-rolled to fit within the 160 MB per-container limit.

**Foreign Function & Memory API (FFM)** — The standard JDK does not expose `recvmsg()` ancillary data, which is required to receive the FDs from SoNoForevis. The API calls `recvmsg(2)` directly via JDK 25's FFM (`java.lang.foreign.Linker`), parses the `cmsghdr` struct to extract the integer FD, then reads and writes HTTP over it using raw `read`/`write` syscalls — also via FFM. No JNI, no reflection.

**KD-Tree nearest-neighbor index** — The ~3M reference vectors are organized into a KD-tree at Docker build time (a dedicated Dockerfile stage runs `BuildIndex`, which reads `references.json.gz`, encodes every vector, and writes a binary `index.bin`). At runtime the index is memory-mapped off-heap, invisible to the GC.

**Fixed-point encoding** — All 14 feature dimensions are stored as `short` (int16) scaled by 10,000 (`s = round(f × 10000)`). This cuts the index from 168 MB (float32) to ~96 MB (int16 + 2 padding shorts per point for SIMD alignment) and keeps the distance calculation in integer arithmetic.

**Vector API (SIMD)** — The L2 distance kernel uses `jdk.incubator.vector` (`ShortVector` → `IntVector` widening) to compute squared differences across the 14 dimensions using AVX2 instructions on the target hardware (Haswell-era Mac Mini).

**Hand-written JSON and HTTP** — No Jackson, no Gson. The request parser reads directly from a `MemorySegment`; responses are pre-built as byte arrays at startup (only 6 possible outputs for fraud counts 0–5), so the hot path does zero allocation.

## Resource limits

| Container | CPU | Memory |
|-----------|-----|--------|
| lb        | 0.15 | 30 MB |
| api1      | 0.425 | 160 MB |
| api2      | 0.425 | 160 MB |
| **Total** | **1.0** | **350 MB** |

## Running

```bash
docker compose up --build
```

`GET http://localhost:9999/ready` returns 200 once the KD-tree index is loaded and the API is serving.

## Scoring

The challenge scores on two axes (total ∈ [-6000, +6000]):

- **Latency** (+1000 per 10× improvement in p99, ceiling +3000 at ≤1 ms)
- **Detection accuracy** (weighted error rate; HTTP errors cost far more than wrong answers)

The implementation prioritizes never dropping or erroring on a request — a wrong score is always cheaper than a 5xx or timeout.
