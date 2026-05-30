# Rinha de Backend 2026 — Java + custom C load balancer

Solution for [zanfranceschi/rinha-de-backend-2026](https://github.com/zanfranceschi/rinha-de-backend-2026).
API in Java, fronted by a custom C load balancer (`lb/`) built with epoll.

## Architecture

```
client → :9999 → lb (C, epoll, single-thread)
                   ├─ SCM_RIGHTS over /run/sockets/api1.ctrl ──▶ api1 (JVM)
                   └─ SCM_RIGHTS over /run/sockets/api2.ctrl ──▶ api2 (JVM)
```

Each API owns its UDS file (`bind`/`listen` on `/run/sockets/apiN.ctrl`). The LB connects to the APIs' ctrl sockets at startup and maintains **one persistent ctrl connection per worker**. On each TCP accept the LB picks a worker (round-robin), calls `sendmsg(SCM_RIGHTS)` to hand the raw TCP fd to it in a single syscall, then closes its own copy. The LB never reads or writes HTTP bytes. Each API calls `recvmsg` to recover the TCP fd and serves HTTP/1.1 directly on it.

### Control-plane protocol (LB → API)

```
sendmsg: [1-byte inline payload 'F'][cmsghdr SCM_RIGHTS: int tcp_fd]
```

The 1-byte payload satisfies the kernel's `iovlen > 0` requirement. The cmsghdr layout on Linux x86_64 is 16-byte header (cmsg_len, cmsg_level, cmsg_type) + 4-byte fd at offset +16, padded to 24 bytes (`CMSG_SPACE(sizeof(int))`).

### LB startup ordering

The LB blocks synchronously (50 ms retry loop) until `connect()` to all worker ctrl sockets succeeds. Only then does it call `bind()`/`listen()` on `:9999`. This eliminates the startup race: by the time the first TCP accept can fire, all workers are live. No healthchecks or container-level coordination needed.

### LB worker FSM

Each worker slot is either **connected** (`ctrl_fd ≥ 0`) or **disconnected** (pending reconnect via timerfd). On TCP accept, if no worker is connected, LB writes 503 and closes. Cross-wiring is structurally impossible — each TCP fd lives in exactly one worker's process for its lifetime.

### Components

- **lb**: built from `./lb` (Alpine + musl static build → `scratch` image). Single-thread epoll loop. Env: `PORT=9999`, `WORKERS=/run/sockets/api1.ctrl,/run/sockets/api2.ctrl`. No `security_opt` required (SCM_RIGHTS over UDS is not blocked by default Docker seccomp).
- **api1, api2**: identical Java services. Each binds its ctrl UDS at startup, accepts the LB's persistent connection, then loops: `recvmsg(tcp_fd)` → parse HTTP/1.1 → fraud-score / ready → write response → `close(tcp_fd)`. Env: `API_CTRL_SOCK=/run/sockets/api{1,2}.ctrl`.

### Resource budget (hard cap, sum of all containers ≤ 1 CPU / 350 MB)

- lb: `0.15` CPU, `30 MB`
- api1: `0.425` CPU, `160 MB`
- api2: `0.425` CPU, `160 MB`

Shared named Docker volume `sockets` mounted at `/run/sockets` in all containers.

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

## Java service design

- **JDK**: **Eclipse Temurin JDK 25 HotSpot** (runtime image `eclipse-temurin:25-jre`). GraalVM native-image was benchmarked and lost to JIT on the fraud-score hot path — HotSpot is the current choice. AppCDS and GC tuning are levers still available if needed: `-XX:+UseCompactObjectHeaders` (JEP 519, final in JDK 25) saves 8 B/object; `-XX:+UseSerialGC` for cleanest pause profile; `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` (JEP 521) if allocation spikes from JSON parsing hurt p99.
- **No framework**. Hand-rolled HTTP/1.1 parser and response writer; only two routes. Spring/Quarkus/Micronaut blow the memory budget.
- **Event loop**: single thread per API container (bind ctrl UDS → accept LB → recvmsg loop). CPU budget is ~0.4 vCPU — one thread is right. Virtual threads are *not* the right tool here.
- **FFM I/O**: `Api.java` uses JDK 25 Foreign Function & Memory API (`Linker` downcalls) for `socket`/`bind`/`listen`/`accept4`/`recvmsg`/`unlink`/`read`/`write`/`close`. `recvmsg` recovers the TCP fd from `cmsghdr` at offset +16 (`MSG_CMSG_CLOEXEC` flag). Pre-allocated `msghdr`/`iovec`/`cbuf` structs are reused across requests; `msg_controllen` is reset to 24 before each call (kernel overwrites it). 5-cycle warm-up loop at startup JIT-compiles the stubs.
- **JSON**: hand-written parsers — `JsonReader` (request body from `MemorySegment`) and `JsonStream` (streaming GZIP reader at build time). No external library; eliminates allocation and reflection.
- **Response pre-building**: all 6 possible responses (`fraudCount` 0–5) are encoded to bytes at startup and written directly — zero allocation on the hot path.
- **Module flags**: `--enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector`.
- **Vector search**:
  - Index built **at Docker image build time** by `rinha.index.BuildIndex` (in the `indexer/` Maven module). Bundled as `/data/index.bin` in the runtime image. Never built at API startup.
  - Fixed-point encoding: `s = (short) Math.round(f * 10000)`. Clamped dims `[0,1]` → `[0, 10000]`; null sentinel (`-1`) → `-10000`. Exact, no quantization loss.
  - Storage layout: each point occupies **16 shorts** (14 dims + 2 padding for SIMD alignment), little-endian.
  - Points and centroids are **memory-mapped off-heap** (`FileChannel.map` into `Arena.ofAuto`) — invisible to GC. Cluster offsets and labels are on-heap.
  - Index binary format: 40-byte header (`RNHA` magic, version 3, point count, dims=14, K, maxClusterSize, 4 reserved ints), then: centroids (`K × 16 × 2` bytes), cluster offsets (`(K+1) × 4` bytes, LE ints), points (`n × 16 × 2` bytes, cluster-ordered), labels (`n` bytes, cluster-ordered).
  - **IVF (Inverted File Index)** with k-means clustering. Build: K=2048 clusters, up to 10 Lloyd iterations, multi-threaded assignment, seeded RNG. At query time: find nearest `nprobe=4` centroids (tunable via `-Divf.nprobe`), then brute-force exact top-5 over only those clusters. Approximate search — excellent recall in practice, p99 ≈ 0.82 ms.
  - **Distance kernel**: `ShortVector.SPECIES_256` (AVX2 256-bit) — subtract, widen short→int, square, combine pairs, widen int→long, reduce. Used for both centroid scan and cluster point scan. Long accumulator prevents overflow (`max 20000² × 16 lanes`).
  - Top-5 maintained as a bounded max-heap (size 5); centroid candidates as a bounded max-heap (size nprobe). Both reused across requests (single-threaded, static arrays).

## Scoring shape (what to optimize for)

Total ∈ [-6000, +6000], two independent components:

- **Latency**: +1000 per 10× improvement in observed p99. Ceiling +3000 at ≤1 ms p99; floor -3000 if p99 > 2000 ms. → optimize the hot path mercilessly.
- **Detection**: weighted error rate. HTTP errors >> false negatives > false positives. Hard -3000 if failure rate > 15%. → **never 5xx**, never timeout. A wrong answer is cheaper than a dropped request.

Implication: under load, degrade gracefully (return *something* in budget) rather than erroring.

## Repo layout

```
.
├── docker-compose.yml
├── lb/
│   ├── Dockerfile              # alpine build → scratch runtime (static musl binary)
│   ├── Makefile
│   └── src/lb.c                # single-file epoll LB: TCP accept, SCM_RIGHTS fd handoff, ctrl reconnect FSM
├── api/
│   ├── Dockerfile              # single build stage (installs indexer → builds api) → indexer stage → runtime JRE
│   ├── pom.xml                 # test dep on br.rinha:indexer:0.1.0 (test scope only)
│   └── src/
│       ├── main/java/rinha/
│       │   ├── Api.java                # FFM SCM_RIGHTS receiver: bind ctrl UDS, recvmsg(tcp_fd) loop, HTTP/1.1 direct
│       │   ├── IvfLoader.java          # background mmap loader, /ready state
│       │   ├── FraudScoreHandler.java  # vector encoding, IVF search, response
│       │   ├── Normalizer.java         # normalization constants & MCC risk table
│       │   └── ReadyHandler.java       # GET /ready responses
│       └── main/resources/
│           ├── normalization.json
│           └── mcc_risk.json
├── indexer/
│   ├── pom.xml                 # standalone Maven module, no external deps
│   └── src/main/java/rinha/index/
│       └── BuildIndex.java     # offline: k-means IVF build, writes index.bin (version 3)
├── tests/
│   ├── run.sh                  # docker compose up → wait ready → correctness → anti_mix → perf → down
│   ├── correctness.py          # wire-level correctness against test-data.json; writes expected.json
│   ├── anti_mix.py             # 10 × 256 concurrent requests; detects LB response cross-wiring
│   ├── perf.sh                 # wrk p99 thresholds (target <5 ms, hard fail >15 ms)
│   └── post.lua                # wrk script with a real fraud-score request body
└── participants/
    └── carlsonsantana.json     # submission metadata
```

Branches: `main` = source; `submission` = artifacts with `docker-compose.yml` at root.

## docker-compose

See `docker-compose.yml` at repo root for the authoritative version. Key points:

- `api1`, `api2` start first (no `depends_on`). Each binds its own `/run/sockets/apiN.ctrl` UDS listen socket at JVM startup (before IVF loads). Built from `./api`. Data files bundled into the image. Env `API_CTRL_SOCK` names the ctrl socket.
- `lb`: `depends_on: api1/api2: service_started`. Blocks in a 50 ms retry loop until `connect()` to each ctrl socket succeeds, then opens `:9999`. Env `WORKERS` lists ctrl socket paths.
- Resource limits: lb `0.15 CPU / 30 MB`; each api `0.425 CPU / 160 MB`. Total ≤ 1 CPU / 350 MB.
- No `security_opt` anywhere — default Docker seccomp profile is sufficient (epoll + standard UDS, no io_uring).

Network must be `bridge` (no `host`, no `privileged`). Images must be public linux/amd64.

## Tests

Run end-to-end: `./tests/run.sh`

- **correctness.py**: sends every entry from `api/src/test/resources/test-data.json` over the wire and checks `{approved, fraud_score}`. Writes `tests/expected.json` on success.
- **anti_mix.py**: 10 rounds × 256 concurrent `asyncio` requests; verifies each response matches the expected for the input that was sent. Detects LB response cross-wiring.
- **perf.sh**: wrk 30 s, 64 connections — p99 target <5 ms, hard fail >15 ms.

## Constraints to keep in front of mind

- Test machine: **Mac Mini Late 2014, 2.6 GHz, 8 GB, Ubuntu 24.04**. Don't assume modern AVX-512; Haswell-era AVX2 is the ceiling.
- No kernel version constraint from the LB (epoll + SCM_RIGHTS are universal). Ubuntu 24.04 is fine. SCM_RIGHTS over UDS does not require `security_opt` — default Docker seccomp allows `sendmsg`/`recvmsg`.
- "Using test payloads as reference or for fraud lookup is not allowed." Index is from the bundled `references.json.gz` only.
- Deadline: **2026-06-05 23:59:59 UTC-3**. Trigger official test via a GitHub issue containing `rinha/test`.

## Milestones (all done)

1. ~~Bare HTTP server on a normal TCP socket, `/ready` + `/fraud-score` returning a constant.~~
2. ~~Load `references.json.gz`, fixed-point encoding, KD-tree index, real fraud_score.~~
3. ~~FFM `recvmsg` shim; FD-receive loop on `/run/apiN.ctrl`.~~
4. ~~Wire SoNoForevis, two replicas, docker-compose end-to-end.~~
5. ~~Vector API SIMD on distance kernel.~~
6. ~~Replace KD-Tree with IVF k-means (K=2048, nprobe=4); p99 ≈ 0.82 ms, 148/148 correctness.~~
7. ~~Replace SoNoForevis with custom C epoll LB (byte-proxy, no SCM_RIGHTS, no security_opt).~~
8. ~~Revert LB transport to SCM_RIGHTS fd-passing; APIs bind ctrl UDS, LB connects. Eliminates startup race — LB blocks until workers connected before opening :9999.~~

## Remaining optimization levers

- GC tuning: `-XX:+UseSerialGC` or generational Shenandoah; `-XX:+UseCompactObjectHeaders`.
- AppCDS to cut JIT warm-up latency on cold start.
- Heap sizing: explicit `-Xmx` to avoid JVM over-reserving in 160 MB container.
- IVF tuning: increase `nprobe` for better recall at cost of latency; increase K for smaller clusters (faster scan); OPQ/PQ residual compression if memory becomes tight.
- Centroid scan (K=2048 × 16 shorts) is a flat SIMD sweep — already cache-friendly. Cluster scan (nprobe × avg_cluster_size ≈ 4 × 1500 points) is the hot inner loop; pre-sorting clusters by access frequency could improve cache warmth.
- LB backpressure: `sendmsg` EAGAIN (ctrl socket buffer full) responds 503. Raise `SO_SNDBUF` on the ctrl socket if this triggers under sustained load before adding queueing complexity.

## Useful upstream pointers

- Challenge spec (EN): `docs/en/README.md` and `docs/en/DETECTION_RULES.md` in the rinha repo.
