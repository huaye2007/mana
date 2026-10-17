# Framework-wide gap analysis (2026-10-17)

**[English](2026-10-17-framework-gap-analysis.md)** | [简体中文](2026-10-17-framework-gap-analysis.zh-CN.md)

> Non-normative record. The component specifications hold the current contracts; this page records what a
> whole-repository review found, the evidence for each finding, and what it would take to close it. Nothing
> here changes a contract. Findings the previous review already decided are not reopened: see
> [2026-10-14 framework review](2026-10-14-framework-review.md).
>
> Fixed the same day, after this record was written: the §4.1 stale import and the Checkstyle source scope,
> so `mvn clean verify -Pquality` now passes for every module; the
> §5.1/§5.2 "finite admission" wording in both languages, where the term is now **finite call admission**,
> named in R-SEND-04 where the limit is specified; and the §5.3 `getAsync` claim. §6 items 1–3 are therefore
> done, and §5.5 records one related staleness found while fixing them. The findings are kept below as the
> record of what the review found.

## 1. Scope and method

All seven framework components (game-core, game-network, game-rpc, game-router, game-runtime, game-data,
game-spring) plus game-demo and game-benchmark were read against their two specification layers. The question
was not "which lines could be nicer" — the 2026-10-14 review covered that — but **what a team deploying this
framework would still have to build or risk themselves**.

Measured on this tree (commit `f6f555f`, JDK 25, Windows):

| Check | Command | Result |
| --- | --- | --- |
| Tests | `mvn -B -fae test` | **PASS** — 360 tests, 0 failures, 2 skipped (the env-gated `DatabaseIntegrationTest`), all 9 modules |
| Style gate | `mvn -B -Pquality verify` | **FAIL** — 1 violation, build stops at game-runtime |
| Static analysis | `mvn -B -Perrorprone clean compile` | **FAIL** — 121 unique findings, 75 at ERROR, game-data does not compile |
| Real database | `DatabaseIntegrationTest` | **NOT RUN** — gated on `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` |

Findings are grouped by what they cost: capability gaps (§2), data-path risk (§3), gates and build (§4),
specification integrity (§5). §6 proposes an order.

## 2. Capability gaps: what the framework does not provide

These are not defects against the specifications. Each one is either documented as out of scope or simply
absent. Together they decide how much of a production game backend is still the application's problem.

### 2.1 There is no cluster plane

Topology is hand-wired, twice, with manually assigned identity:

- `RpcNode.addPeer(int remoteNodeId, SocketAddress address, int slotCount)`
  ([RpcNode.java:192](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java#L192)) — the caller
  supplies both the peer's node ID and its address.
- `ServiceRouting.addRouter(int routerId)`
  ([ServiceRouting.java:76](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java#L76))
  declares a Router, and its own Javadoc says "the application connects to it separately with
  RpcNode.addPeer". Two registrations, two sources of truth, no reconciliation.
- `nodeId` is an `int` the operator must keep globally unique; nothing allocates, leases or validates it.
  A node that claims an ID already in use is a configuration error with no detection path.
- The address is whatever `SocketAddress` the application passes, and the recovery path reuses that same
  object ([RpcNode.java](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) `connect` →
  `client.connectAsync(peer.target, …)`). A resolved `InetSocketAddress` therefore pins one IP for the
  process lifetime, so a peer that moves (new pod, new host) is never reached again. Whether re-resolution
  happens depends on a detail of the caller's address construction that no specification states.

The RPC specification already names this: "TLS/WS RPC Builder and discovery remain unimplemented"
([OGBS-RPC-1.0.md:186](../ogbs/OGBS-RPC-1.0.md)). The consequence is wider than discovery: there is no node
metadata (service, version, zone, capacity), no membership view, no health propagation and no way to
announce "I am draining" to the cluster. Scale-out and rolling restart need a control plane the framework
neither provides nor specifies.

### 2.2 Routing stops at the node ID — location resolution is absent

The Router relays by exact target. `ServiceRouting`'s own contract is explicit: "Targets are exact Node IDs;
resolving a business key (a player, a room) to a Node belongs to the application."

What exists: `DIRECT` (one node ID), `BROADCAST` (every node of a service attached to the chosen Router),
`RESPONSE`. What does not exist anywhere in the framework:

- an entity → node ownership registry (which node holds player 10001 right now),
- a shard map or consistent hash over a service,
- "call any node of service X" with load balancing,
- entity handoff/migration between nodes, and the fencing that makes it safe.

Broadcast is also best-effort by construction: one Router is chosen
([ServiceRouting.java:142](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java#L142)),
so delivery covers the nodes attached *to that Router* at that moment. In steady state every service
attaches to every Router and the set is complete; during warm-up, a Router restart or a partition it is
silently partial, with no acknowledgement and no way for the caller to learn who was missed.

### 2.3 The serial-Route promise is per-process, and nothing enforces it across the cluster

This is the most consequential gap. Runtime guarantees that tasks for one `(domain, key)` run serially and in
submission order — `RouteExecutor.tryExecute`
([RouteExecutor.java:5](../../game-runtime/src/main/java/cn/managame/runtime/executor/RouteExecutor.java#L5))
— **within one process**. `EntityCache` and `PendingBuffer` are likewise per-process, and the Data
specification states the boundary plainly: "No WAL, crash recovery, unbounded-backlog protection,
cross-table transactions, or multi-process consistency is promised"
([OGBS-Data-1.0.md:274](../ogbs/OGBS-Data-1.0.md)).

Put §2.1–2.3 together and the failure mode is concrete. Nothing decides which node owns player 10001; if two
nodes both serve that player — a client reconnecting to a second gateway, a failover, a Router partition, an
operator mistake — then:

1. both processes load the same row into their own `EntityCache`,
2. both serialize access locally and see no conflict,
3. both record changes into their own `PendingBuffer`,
4. both write the full row (`update` saves all mutable state, D-SINGLE-02), and the later flush wins.

The result is silent lost updates. There is no row version, no optimistic-concurrency column, no lease and no
epoch fence in the Data layer, so the collision is neither prevented nor detectable afterwards. The Router's
`nodeEpoch` fences *message delivery* to an incarnation, but nothing fences *storage*.

A framework may legitimately leave placement to the application. Leaving placement **and** giving the
application no primitive to make ownership safe is the gap worth closing first.

### 2.4 No deadline, cancellation or trace context on the wire

The request envelope ([rpc-wire.md](../rpc-wire.md)) carries `command`, `requestId`, `routeKey`,
`businessIdType`, `businessId`, `metadata`, `body`. There is no deadline, no trace ID and no idempotency key.

- A timeout is local to the calling node (`RpcNode.call(…, timeoutMillis, …)`). The Router forwards as a
  notify and the target learns nothing about the caller's deadline, so after the caller gives up the target
  still executes and replies into a dropped response (`GameRouter.Stats.responsesDropped`). There is no
  cancellation signal anywhere in the framework.
- There is no correlation identifier, and logging is `System.Logger` with no MDC equivalent, so one player
  action cannot be followed across gateway → Router → service → database.
- Core reserves **error-code** ranges carefully (1..999, 1000..1999 game-network, … 5000..5999 game-router,
  [OGBS-Core-1.0.md:59](../ogbs/OGBS-Core-1.0.md)) but reserves **no MetadataKey id range** for the framework:
  "New factories allocate no predefined business Keys" (same file, line 47). Applications are free to use key
  1, and any
  framework key added later (deadline, trace, tenant) collides with deployed applications. The asymmetry is a
  forward-compatibility hazard that costs one table to fix now and a wire break to fix later.

### 2.5 No observability surface

`RuntimeStats`, `DataStats` and `GameRouter.Stats` are in-process record snapshots pulled by the application.
There is no metrics export (no Micrometer, OpenTelemetry or Prometheus anywhere in the tree), no tracing, no
health or readiness endpoint, and no introspection of live state (per-Route queue depth, pending-change set,
peer/slot status). For a framework whose own documents repeatedly say "production capacity unverified", the
absence of the instrumentation that would verify it is self-reinforcing.

### 2.6 The untrusted plane is unhardened while the trusted plane is

The internal RPC listener — which only ever talks to other servers — has admission control and authentication:
`maxConnections` (4096), `maxInboundPeers` (1024), `maxPendingCalls` (65536), `maxFrameSize`, write
watermarks, HMAC-SHA256 handshake with a timestamp window and nonce replay protection, heartbeat and
reconnect ([RpcNodeBuilder.java](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java),
[HandshakeGuard.java](../../game-rpc/src/main/java/cn/managame/rpc/node/HandshakeGuard.java)).

The listener that faces real players has none of it.
[NetworkServerBuilder](../../game-network/src/main/java/cn/managame/network/netty/NetworkServerBuilder.java)
offers `bindAddress`, `handler`, `pipeline`, `webSocket`, event-loop groups, `channelFactory`, `option` and
`childOption` — and that is all. No connection cap, no per-IP cap, no idle or read timeout, no first-packet
deadline, no read-rate limit, no write-watermark helper. Everything is left to
`pipeline(Consumer<ChannelPipeline>)`, i.e. hand-written Netty handlers. `HttpServer` in the same module
*does* bound `maxContentLength`, `maxInitialLineLength`, `maxHeaderSize` and `readTimeoutMillis`, so the
asymmetry is not even consistent within game-network.

There are also no session, login or authentication primitives. game-demo had to write `GameSession`,
`GamePacket`, its decoder, encoder and handler, and the policy that "rejects a missing session before
queueing" — every application repeats that, including the parts that are security-relevant.

One related design limit worth stating: the handshake secret is shared by the whole cluster, so it proves
"a member of this cluster", not "the node claiming ID 7". A misconfigured or compromised member can attach
as any `serviceId` and receive that service's broadcasts. Per-node keys or signed identity would be needed
for a stronger claim; today's model is adequate for a trusted internal network and should be documented as
exactly that.

### 2.7 Cron and timers are per-process and in-memory

`@Cron(value, domain, routeKey)`
([Cron.java](../../game-runtime/src/main/java/cn/managame/runtime/timer/Cron.java)) schedules on the local
`CronScheduler`. In a cluster of N nodes the same annotation fires N times: a daily reset, a ranking
settlement or a mail sweep runs once per node. Neither the Runtime specification nor the Spring integration
addresses "run on exactly one node", and there is no leader election or distributed lock to build it on.
Timers are in-memory only, so a scheduled game effect (a building that completes in four hours) does not
survive a restart. Both may be deliberate; neither is stated as a limit where a reader would look.

## 3. Data-path risk

### 3.1 `PendingBuffer` is the last unbounded queue

[PendingBuffer](../../game-data/src/main/java/cn/managame/data/PendingBuffer.java) holds the dirty set in a
`ConcurrentHashMap` per entity type with no capacity, no admission check and no backpressure, and
`EntityCache` by contract "never evicts an entry with unsaved changes". So a database outage grows the dirty
set without limit **and** pins every dirty entity in cache: two unbounded structures feeding each other,
ending in heap exhaustion. The only signal is `DataStats.pendingChanges`; the business side is never slowed
or refused.

The inconsistency matters as much as the risk. The 2026-10-14 review bounded the log queues
(`logQueueCapacity`, default 100,000, insert returns false when full) and bounded RPC pending calls
(`maxPendingCalls`, default 65,536, immediate UNAVAILABLE). The entity dirty set — the one that holds player
progress — was left unbounded. The Data specification does disclaim backlog protection, so this is a known
gap rather than a contract violation, but it is now the only one of the three queues without a limit.

### 3.2 `EntityCache` has no capacity bound

Eviction is idle-time only (`cacheExpire`, default 30 minutes), and the specification records the decision:
"Automatic dirty tracking, immutable save snapshots and cache-capacity eviction are not added"
([OGBS-Data-1.0.md:216](../ogbs/OGBS-Data-1.0.md)). The consequence is that the working set is dictated by
access pattern rather than by a memory budget: one wide pass over many ids (a maintenance job, a ranking
build) inflates the heap and holds it for the full idle window. A configured maximum-entries bound that
refuses to evict dirty entries — the same rule the cache already enforces — would keep the current semantics
while making memory a setting rather than an outcome.

### 3.3 No per-entity durable save

The only persistence barrier is global: `GameData.flush()`
([GameData.java:17](../../game-data/src/main/java/cn/managame/data/GameData.java#L17)) runs one pass over
everything, and D-FLUSH-01 tells the caller to quiesce writers first for a stable barrier. There is no
"persist this entity now and confirm" call. With `flushInterval` defaulting to 1 second, a crash loses up to
a second of progress for every player — usually fine for gameplay, not fine for a purchase, a reward claim or
an item trade. Applications that need it today must stop all writers and flush the world, which is not
something a request handler can do.

### 3.4 Startup DDL is unconditional and racy

[MysqlSchema.initialize](../../game-data/src/main/java/cn/managame/data/mysql/MysqlSchema.java) is called from
`MysqlEntityMapper` for every registered repository, with no builder option to disable it, no migration
versioning and no verify-only mode. It queries `information_schema`, then issues `CREATE TABLE` (without
`IF NOT EXISTS`), `ALTER TABLE … ADD COLUMN` and `CREATE INDEX`.

That is check-then-act against shared state. Two nodes starting together both observe "table missing" and
both issue `CREATE TABLE`; the loser receives a non-transient `SQLException` **during startup**, so its boot
fails. The same applies to `ADD COLUMN` and `CREATE INDEX`. Multi-replica starts and rolling deploys are
exactly when this happens.

Type validation is also weaker than it appears. `compatible` strips everything from the first parenthesis
([MysqlSchema.java:55](../../game-data/src/main/java/cn/managame/data/mysql/MysqlSchema.java#L55)), so a field
declared `varchar(255)` is accepted against an existing `varchar(32)` column: startup passes and the failure
arrives later as runtime data truncation. Precision and unsigned differences are invisible for the same
reason (`information_schema.columns.data_type` carries neither).

### 3.5 The storage adapter is unverified against the databases it targets

360 tests pass and **none of them touches a real MySQL or MongoDB**. `DatabaseIntegrationTest` is gated on
`@EnabledIfEnvironmentVariable(named="OGBS_DATA_MYSQL_URL")` / `OGBS_DATA_MONGO_URI` and the CI workflow
defines no service containers, so the two tests that would exercise a real database are skipped on every run
anywhere. Maven does report them: game-data's summary reads "Tests run: 64, Failures: 0, Errors: 0,
Skipped: 2" — on a `[WARNING]` line rather than `[INFO]`, which is easy to filter out of a build log without
noticing, as happened while first measuring this.

game-data is 2,299 lines of SQL generation, DDL, partitioning and type mapping, covered by 62 tests against
H2 and unit-level mapping assertions. Unexercised as a result: real DDL and migration behaviour, partitioned
log tables, JSON column semantics, `rewriteBatchedStatements` batching — and `RetryPolicy`, which decides
whether a failed save is kept or dropped by matching **MySQL vendor codes 1205/1213** and **MongoDB exception
class-name substrings** (`name.contains("Timeout")`, `"NotPrimary"`, `"NodeIsRecovering"` …,
[RetryPolicy.java](../../game-data/src/main/java/cn/managame/data/error/RetryPolicy.java)). H2 cannot produce
any of those. The entire "an outage loses no data, one bad row does not drop the others" guarantee therefore
rests on branches no test has ever taken. This is the highest-risk module with the weakest evidence, and a
MySQL service container in CI would change that in one commit.

## 4. Gates and build

### 4.1 CI is red on `main`

`mvn -B -Pquality verify` fails:

```text
game-runtime/src/main/java/cn/managame/runtime/internal/CronSchedule.java:3:8:
  UnusedImports: Unused import - cn.managame.runtime.timer.Cron
```

The import is stale after the compiler split; `CronSchedule` mentions Quartz only in prose and references no
`Cron` type. Because Checkstyle fails fast at game-runtime, **game-data, game-spring, game-demo and
game-benchmark are never style-checked at all**. The `quality` job in
[.github/workflows/ci.yml](../../.github/workflows/ci.yml) runs exactly this command, so the pipeline has been
failing since the gate was added. The 2026-10-14 review recorded "Not yet executed: the style gate"; three
commits later it still has not passed. One-line fix, but a gate that has never been green is not a gate.

Fixed on 2026-10-17, and removing the import proved the point: Checkstyle then reached the four modules it had
never scanned, and game-benchmark failed at once — not on its three hand-written classes but on JMH's
generated harness under `target/generated-sources/annotations`, which declares imports it does not use. The
plugin walks every compile source root, and the annotation processor adds that directory to it, so the gate
was never limited to the "main sources only" its own comment claims. Pinning `sourceDirectories` to
`${project.build.sourceDirectory}` restores the documented intent. This is the same generated-source defect
as §4.2, so it was worth fixing in the gate rather than silencing the module. `mvn clean verify -Pquality`
now passes end to end: all ten reactor modules green, 0 violations in every checked module, 360 tests,
0 failures, 2 skipped.

### 4.2 The `errorprone` profile cannot build, is not in CI, and analyses generated code

First clean run of `mvn -Perrorprone clean compile`: 121 unique findings, 75 at ERROR severity, game-data
fails to compile. The substantive ones were each checked and **none is a functional bug**:

| Finding | Verdict |
| --- | --- |
| `FutureReturnValueIgnored` ×21 (`NettyConnection.write`, listeners) | Intentional — `channel.voidPromise()` routes outbound failures to `exceptionCaught`, as the code comments say |
| `NonAtomicVolatileUpdate` ×2 (`PeerTable.inbound`) | Safe — writes hold the lifecycle lock, `volatile` exists for lock-free reads |
| `ReturnValueIgnored` ×4 (`Duration.toNanos()`) | Intentional overflow probe in `GameDataBuilder` validation |
| `UnusedVariable`: `WriteBehindManager.deferred`, `MysqlLogWriter.zone` | Dead fields only. `flush()` returns the leftover count and `flushNow()` uses it, so D-FLUSH-01 still holds; `zone` is consumed by the constructor via `withZone` |
| `Finally` ×2 (`JdbcMysqlAccess`) | Narrow but real: when restoring `autoCommit` fails after a **successful** commit, the `SQLException` is thrown and a committed transaction is reported as failed. No test covers it |
| `MissingOverride` ×80 | Annotation hygiene |

36 of the 37 `ThreadPriorityCheck` hits are in **generated JMH sources** (`jmhTest.java`), so the profile
needs a generated-source exclusion before its output means anything. And the command the POM documents
(`mvn -Perrorprone compile`) is a no-op on an already-built tree — measured 0.04–0.08 s per module, i.e. it
silently analyses nothing without `clean`. Three fixable problems: it does not pass, it does not run in CI,
and its documented invocation does not work.

### 4.3 No release or consumption path

Every module is `1.0.0-SNAPSHOT`. There is no `distributionManagement`, no source or javadoc jar, no BOM
module and no `module-info.java` anywhere. AGENTS.md gives "independent releases" as a reason to create Maven
modules, but nothing in this tree can be released or consumed by version today.

Third-party versions are declared per module instead of managed centrally — only Netty (BOM) and JUnit are in
`dependencyManagement`. The drift is already visible:

| Dependency | Where | Version |
| --- | --- | --- |
| jackson-databind | game-runtime, game-data | `2.21.3` twice, independently |
| mysql-connector-j | game-data (test) / game-demo | `9.4.0` / **`9.7.0`** |
| mongodb-driver-sync, spring-context, HikariCP, H2 | inline in their modules | single-sourced nowhere |

### 4.4 Benchmarks cover only the cheap parts

game-benchmark is three JMH classes: `MetadataBenchmark`, `RpcWireBenchmark`, `RouteExecutorBenchmark` — pure
CPU paths. There is no end-to-end measurement of RPC round-trip latency, dispatch throughput under load,
write-behind rows per second or Router fan-out. "Production capacity unverified" appears in six documents and
no number appears anywhere, so there is no baseline to detect a regression against either.

## 5. Specification integrity

### 5.1 The RPC specification contradicts itself on finite admission, in both languages

`R-SEND-04` fully specifies the per-Peer pending-call limit
([OGBS-RPC-1.0.md:101](../ogbs/OGBS-RPC-1.0.md), zh-CN:89), the Java specification binds it
(`maxPendingCalls`, default 65536, [line 62](../ogbs/OGBS-RPC-Java-25-Specification-1.0.md) and line 160), and
`RpcNodeBuilder` implements it. Yet the same standard still declares the capability missing in three places:

| Location | Stale text |
| --- | --- |
| `OGBS-RPC-1.0.md:5` | "Finite admission remains unimplemented/deferred" |
| `OGBS-RPC-1.0.md:10` | "Finite admission remains a declared gap" |
| `OGBS-RPC-1.0.md:186` | "Finite admission remains deferred" |
| `OGBS-RPC-1.0.zh-CN.md:5,10,164` | 有限调用接纳仍未实现并暂缓 / 有限准入仍为已声明缺口 / 有限接纳仍暂缓 |

This is the case AGENTS.md calls out specifically: obsolete wording left in place, so the normative
cross-language document now disagrees with its own clause, with the Java layer and with the code. A reader
starting from the header concludes the limit does not exist. Both texts need the same correction.

### 5.2 One concept, three Chinese terms

The Chinese standard renders the same concept as 有限调用接纳, 有限准入 and 有限接纳 in the three passages
above. The bilingual rule asks for one contract in two languages; the term should be fixed once and used
consistently.

### 5.3 A review record names an API that was never implemented

[2026-10-14-framework-review.md](2026-10-14-framework-review.md) item 10 reports "new getAsync/getGroupAsync".
`git log -S getAsync --all` matches only the commit that added that sentence: the methods exist in neither
`game-data` nor any specification. The record is non-normative, but it is what a maintainer reads to learn the
current design, and it currently promises an asynchronous load API that does not exist. `SingleRepository`
offers `get`, `getOrCreate`, `insert`, `update`, `delete` — all synchronous, loading inline on a miss.
Synchronous loading is the contract ("Cache misses load synchronously", D-MODEL-01) and an asynchronous
accessor is deliberately not part of the API, so the correction belongs in the record, not in game-data.

### 5.4 A consequence worth documenting: who may call a blocking load

`SingleRepository.get` loads synchronously on a cache miss, and nothing guards which thread calls it — there
is no `inEventLoop` check or thread assertion anywhere in game-data or game-runtime. RPC handlers, the Router
handler and `ConnectionHandler` callbacks all run on Netty event-loop threads, so business code that calls a
repository from one of them blocks an event loop serving many connections. On a `StripedRouteExecutor` it
stalls a whole stripe; on virtual threads it is correct but unbounded against a fixed JDBC pool, so a slow
database converts into in-flight Routes until admission starts returning OVERLOADED. None of this is a
defect — it follows from documented choices — but "a Repository call may block, so call it only from a Route"
is the kind of boundary the Data specification should state outright.

### 5.5 The 2026-10-07 record still describes R-SEND-04 as deferred

Found while fixing §5.1. [2026-10-07-router-rpc-review.md](2026-10-07-router-rpc-review.md) measures that
10,000 calls to a non-replying service all stayed pending and concludes "Node-level Call admission belongs to
RPC's deferred R-SEND-04". That was accurate on that date and the file is a dated record, so it was left
unchanged rather than rewritten. But it is also the probe a maintainer would read when deciding whether call
admission exists, and it now points at a capability that has shipped. The precedent for this is already in
the repository: [2026-10-13](2026-10-13-router-forwarding-review.md) carries a later note at the top saying
which of its sections were superseded. A one-line dated note on the 2026-10-07 record would do the same.

## 6. Proposed order

Grouped by what each buys. Items in §2 are design decisions for the owner, not defects to be fixed silently.

**First — make the gates real (small, mechanical, unblocks everything else):**

1. Remove the stale `Cron` import so `-Pquality` passes, then confirm the four modules Checkstyle has never
   reached (§4.1).
2. Correct the three English and three Chinese "finite admission" passages, and fix the term (§5.1, §5.2).
3. Correct the 2026-10-14 record's `getAsync` claim (§5.3).
4. Triage Error Prone once: exclude generated sources, add the missing `@Override`s, suppress the intentional
   patterns with a reason, then add the profile to CI (§4.2).
5. Add a MySQL (and MongoDB) service container to CI and run `DatabaseIntegrationTest` there — the single
   change with the best risk-reduction per line in this list (§3.5).

**Second — close the data-path risks (bounded work, clear contracts):**

6. Bound the dirty set: a `pendingChangeCapacity` with a defined refusal, matching what log queues and RPC
   calls already do (§3.1).
7. Add a maximum-entries bound to `EntityCache`, keeping the never-evict-dirty rule (§3.2).
8. Make startup DDL optional and idempotent: a `schema(CREATE | VALIDATE | NONE)` setting, `IF NOT EXISTS`,
   tolerance of the concurrent-creation error, and length/precision in the compatibility check (§3.4).
9. Add a per-entity durable save with a reported outcome, for purchases and claims (§3.3).
10. State the threading boundary for blocking repository calls in the Data specification (§5.4).

**Third — decide the architecture (owner's call, each needs a specification before code):**

11. Reserve a framework MetadataKey id range in Core now, before applications occupy the low ids (§2.4).
12. Decide the ownership model: a lease/epoch primitive that makes one node the single writer for an entity,
    or an explicit, prominent statement that single-writer placement is the application's responsibility and
    that concurrent writers silently lose updates (§2.3).
13. Decide deadline propagation and cancellation as a wire concern — it is a protocol-version change, so it
    is cheaper to decide before more applications ship (§2.4).
14. Decide whether discovery/membership belongs in the framework or in a documented external control plane,
    and if the latter, specify the interface it must satisfy (§2.1, §2.2).
15. Bound the client-facing listener: connection cap, per-IP cap, idle and first-packet timeouts (§2.6).
16. Decide cluster-wide Cron: either a coordination primitive or a stated per-process limit (§2.7).
17. Add a metrics/health surface, even a minimal pull endpoint, so capacity claims can be measured (§2.5).

## 7. Verification status of this record

Every measured claim in §1, §4.1, §4.2 and §3.5 comes from a command run on this tree and is reproducible
with the commands given. Code claims cite file and line. §2 and §3 state capability gaps and risks, not
observed production failures: no multi-node deployment, no real database and no load test was available, so
nothing here should be read as a measured outage. The Error Prone findings in §4.2 were each read and
classified; none was confirmed as a functional bug, and that table says so explicitly rather than counting
them as defects.

One figure in the first version of this record was wrong and is corrected above: it reported 358 tests and
0 skipped, and claimed the env-gated database tests were no longer reported as skipped at all. They are —
game-data's summary line carries "Skipped: 2" — and the total is 360. The measurement had filtered the build
log to `[INFO]` lines, and that summary is a `[WARNING]`. The coverage gap §3.5 describes is unchanged: still
no test against a real MySQL or MongoDB, and still no service container in CI.
