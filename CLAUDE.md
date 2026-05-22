# Rinha de Backend 2026 — Java + SoNoForevis

Solution for [zanfranceschi/rinha-de-backend-2026](https://github.com/zanfranceschi/rinha-de-backend-2026).
API in Java, fronted by [jairoblatt/SoNoForevis](https://github.com/jairoblatt/SoNoForevis) as load balancer.

## Architecture

```
client → :9999 → SoNoForevis (TCP accept)
                   └─ SCM_RIGHTS over /run/api{1,2}.ctrl ──▶ api1 (JVM)
                                                          └▶ api2 (JVM)
```

SoNoForevis does **not** proxy bytes. It `accept()`s the TCP connection and passes the raw FD to one of the API workers via `sendmsg()` ancillary data over a Unix Domain Socket. Each API worker must `recvmsg()` on its `.ctrl` socket, recover the FD, and serve HTTP directly on it. This eliminates the load-balancer→upstream copy entirely.

### Components

- **lb (SoNoForevis)**: pre-built image or build from source with `RUSTFLAGS="-C target-cpu=haswell"`. Listens on `:9999`. Env: `PORT=9999`, `UPSTREAMS=/run/api1.ctrl,/run/api2.ctrl`, `WORKERS=1`.
- **api1, api2**: identical Java services. Each listens on its own `.ctrl` Unix socket, receives FDs, parses HTTP/1.1, runs fraud scoring, writes response, closes.

### Resource budget (hard cap, sum of all containers ≤ 1 CPU / 350 MB)

Starting point — tune after profiling:
- lb: `0.15` CPU, `30 MB`
- api1: `0.425` CPU, `160 MB`
- api2: `0.425` CPU, `160 MB`

Shared volume between containers for the `.ctrl` sockets (e.g. tmpfs mounted at `/run`).

## API contract

Port `9999`. JSON in, JSON out. No auth.

- `GET /ready` → 200 once vector index is loaded and warm.
- `POST /fraud-score` → `{ "approved": bool, "fraud_score": number }` where `fraud_score = frauds_in_top5 / 5` and `approved = fraud_score < 0.6`.

Payload fields and the 14-dim vector are defined in the challenge's `DETECTION_RULES.md`. Vector dims (in order):

```
0  amount               = clamp(tx.amount / max_amount)
1  installments         = clamp(tx.installments / max_installments)
2  amount_vs_avg        = clamp((tx.amount / customer.avg_amount) / amount_vs_avg_ratio)
3  hour_of_day          = hour(tx.requested_at) / 23
4  day_of_week          = dow(tx.requested_at) / 6
5  minutes_since_last_tx= clamp(min / max_minutes)   or -1 if null
6  km_from_last_tx      = clamp(km / max_km)         or -1 if null
7  km_from_home         = clamp(terminal.km_from_home / max_km)
8  tx_count_24h         = clamp(customer.tx_count_24h / max_tx_count_24h)
9  is_online            = 1 | 0
10 card_present         = 1 | 0
11 unknown_merchant     = 1 if merchant ∉ customer.known_merchants else 0
12 mcc_risk             = mcc_risk[mcc] ?? 0.5
13 merchant_avg_amount  = clamp(merchant.avg_amount / max_merchant_avg_amount)
```

Constants come from `normalization.json` and `mcc_risk.json`. Reference data is `references.json.gz` (≈3M labeled vectors). All three are fixed across test runs — load once at boot.

## The SCM_RIGHTS problem (critical)

JDK's `java.net` UDS support does **not** expose `recvmsg()` ancillary data. We need to pull a raw TCP FD out of a `cmsg` and turn it into something we can read/write HTTP on. Options, ranked for Java 25:

1. **Foreign Function & Memory API (final since JDK 22, mature in 25)** — call `recvmsg(2)` directly via a `Linker` downcall, parse the `cmsghdr`, recover the int FD. Convert to a `SocketChannel` either by writing the FD into a private `FileDescriptor` (reflective access to `sun.nio.ch.SocketChannelImpl`) or by mediating reads/writes through FFM `read`/`write` calls and serving HTTP off raw buffers. No build-time native step. **Default.**
2. **Tiny JNI shim** — ~100 LoC of C wrapping `recvmsg()`. Fallback if FFM reflection into `sun.nio.ch` proves brittle on JDK 25.
3. **Netty with native transport** — `EpollServerDomainSocketChannel` exists but has no built-in SCM_RIGHTS FD-receive path; still needs native code, and Netty itself eats into the RAM budget.

JVM flags required: `--enable-native-access=ALL-UNNAMED`. If reflecting into `sun.nio.ch`: `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`.

Whatever path: once we have the int FD, immediately set `O_NONBLOCK`, register with a `Selector`/epoll loop, and never block a thread per connection.

## Java service design

- **JDK**: **Java 25 LTS** (September 2025). Two viable builds:
  - **GraalVM for JDK 25 native-image** — preferred. Fastest cold start, lowest RSS, fits the 160 MB cap easily. FFM and Vector API both supported by native-image in this release line; verify your specific build (`native-image --version`).
  - **OpenJDK 25 HotSpot** with AppCDS + `-XX:+UseCompactObjectHeaders` (JEP 519, **final in JDK 25** — 8 bytes off every Java object, real win against the 160 MB cap) and a small GC: `-XX:+UseSerialGC` for the cleanest pause profile at this heap size, or `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521, generational Shenandoah went final in 25) if allocation-rate spikes from JSON parsing hurt p99.
- **No framework**. Hand-rolled HTTP/1.1 parser; we only handle two routes. Spring/Quarkus/Micronaut all blow the memory budget.
- **Event loop**: single thread per API container running an epoll `Selector` over the received FDs. CPU budget is ~0.4 vCPU — one busy loop thread is right. Virtual threads are *not* the right tool here (one connection, one allocation each, churn hurts).
- **JSON**: `dsl-json` or `jsoniter-scala` (compile-time codecs, no reflection, native-image friendly). Avoid Jackson.
- **Module flags** (HotSpot): `--enable-native-access=ALL-UNNAMED`, `--add-modules=jdk.incubator.vector`, plus `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` only if we end up wrapping the received FD as a `SocketChannel`.
- **Vector search**:
  - Index built **once at startup** from `references.json.gz`. Don't ship a pre-built index — challenge forbids using test payloads as reference, but bundled reference data is fine.
  - 3M × 14 × 4 bytes = 168 MB as flat float32 — half our per-container RAM. Reference vector components in `references.json.gz` have **at most 4 decimal places**, so store them as fixed-point `short` (int16) scaled by **10,000**: `s = round(f * 10000)`. All clamped dims are in `[0, 1]` → `[0, 10000]`; the `-1` null sentinel for dims 5/6 becomes `-10000`. Range fits comfortably in `short` (`[-32768, 32767]`). Storage: **3M × 14 × 2 bytes = 84 MB** as flat `short[]`, exact (no quantization loss), and SIMD-friendly via `ShortVector`.
    - Memory-map a packed off-heap buffer (`MappedByteBuffer` or `Arena.allocateShared`) — keeps it out of GC.
    - Share the index across api1/api2 via a read-only mmap of a file in the shared volume; build it in an init container.
    - At query time, scale the incoming 14-dim vector the same way (`(short) round(v * 10000)`) and do L2 distance in `int` (square of differences fits: max `20000² × 14 ≈ 5.6e9`, use `long` accumulator to be safe). No float math on the hot path.
  - Top-5 via **KD-Tree** over the 14-dim points. Exact nearest neighbors (matches reference behavior), `O(log n)` average per query versus `O(n)` brute force — the win on 3M points is large. Build once at boot from the flat point array; store the tree as parallel arrays (axis, split value as `short`, left/right indices) packed off-heap to avoid per-node object overhead.
  - Implementation notes: median-of-medians or quickselect at build time; bounded-priority-queue (max-heap of size 5) during search; prune subtrees when axis-distance exceeds current top-5 worst. SIMD via the Vector API (`jdk.incubator.vector`, `ShortVector.SPECIES_PREFERRED`) on the per-leaf L2 distance — widen to `int` lanes for the squared-difference accumulator.
  - 14 dims is near the edge where KD-Tree pruning degrades toward linear scan — measure. Fallbacks if pruning is ineffective: brute force with SIMD, or HNSW (jvector). Verify scoring parity against brute force before switching — neighbors must match closely or detection score tanks.

## Scoring shape (what to optimize for)

Total ∈ [-6000, +6000], two independent components:

- **Latency**: +1000 per 10× improvement in observed p99. Ceiling +3000 at ≤1 ms p99; floor -3000 if p99 > 2000 ms. → optimize the hot path mercilessly.
- **Detection**: weighted error rate. HTTP errors >> false negatives > false positives. Hard -3000 if failure rate > 15%. → **never 5xx**, never timeout. A wrong answer is cheaper than a dropped request.

Implication: under load, degrade gracefully (return *something* in budget) rather than erroring.

## Repo layout (target)

```
.
├── docker-compose.yml          # only on `submission` branch, at root
├── api/
│   ├── Dockerfile              # native-image build
│   ├── pom.xml or build.gradle.kts
│   └── src/main/java/...       # HTTP loop, parser, scorer, vector index, FFM bindings
├── lb/
│   └── Dockerfile              # builds SoNoForevis or pulls upstream image
├── data/
│   ├── references.json.gz
│   ├── mcc_risk.json
│   └── normalization.json
└── participants/
    └── carlsonsantana.json     # submission metadata
```

Branches: `main` = source; `submission` = artifacts with `docker-compose.yml` at root.

## docker-compose sketch

```yaml
services:
  api1:
    build: ./api
    environment:
      CTRL_SOCK: /run/api1.ctrl
      REFERENCES: /data/references.json.gz
    volumes:
      - sockets:/run
      - ./data:/data:ro
    deploy: { resources: { limits: { cpus: "0.425", memory: "160MB" } } }

  api2:
    build: ./api
    environment:
      CTRL_SOCK: /run/api2.ctrl
      REFERENCES: /data/references.json.gz
    volumes:
      - sockets:/run
      - ./data:/data:ro
    deploy: { resources: { limits: { cpus: "0.425", memory: "160MB" } } }

  lb:
    build: ./lb
    environment:
      PORT: "9999"
      UPSTREAMS: /run/api1.ctrl,/run/api2.ctrl
      WORKERS: "1"
    ports: ["9999:9999"]
    volumes: [ "sockets:/run" ]
    depends_on: [ api1, api2 ]
    deploy: { resources: { limits: { cpus: "0.15", memory: "30MB" } } }

volumes:
  sockets:
    driver_opts: { type: tmpfs, device: tmpfs }
```

Network must be `bridge` (no `host`, no `privileged`). Images must be public linux/amd64.

## Constraints to keep in front of mind

- Test machine: **Mac Mini Late 2014, 2.6 GHz, 8 GB, Ubuntu 24.04**. Don't assume modern AVX-512; Haswell-era AVX2 is the ceiling.
- Linux kernel ≥ 5.1 required by SoNoForevis (`io_uring`). Ubuntu 24.04 is fine.
- "Using test payloads as reference or for fraud lookup is not allowed." Index is from the bundled `references.json.gz` only.
- Deadline: **2026-06-05 23:59:59 UTC-3**. Trigger official test via a GitHub issue containing `rinha/test`.

## First-pass milestones

1. Bare HTTP server on a normal TCP socket (no LB), `/ready` + `/fraud-score` returning a constant. Verify k6 can hit it.
2. Load `references.json.gz`, convert each component to `short` via `round(f * 10000)`, build flat `short[]` index, brute-force top-5 with integer L2, real fraud_score. Verify detection score on a sample.
3. Native-image build; check startup time and RSS fit under caps.
4. JNI `recvmsg` shim; switch API from `ServerSocket` to FD-receive loop on `/run/apiN.ctrl`.
5. Wire SoNoForevis in front, two replicas, run k6 end-to-end.
6. Add Vector API SIMD (`ShortVector` → `IntVector` widening for squared diffs), profile hot path, tune GC / heap / `-Xmx`.

## Useful upstream pointers

- Challenge spec (EN): `docs/en/README.md` and `docs/en/DETECTION_RULES.md` in the rinha repo.
- SoNoForevis FD-passing reference: `src/fd.rs` in that repo; Kerrisk *The Linux Programming Interface* §61.13.3 (`scm_rights_recv.c`).
