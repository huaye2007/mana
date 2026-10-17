# Lifecycle and observability review (2026-10-18)

**[English](2026-10-18-lifecycle-and-observability-review.md)** | [简体中文](2026-10-18-lifecycle-and-observability-review.zh-CN.md)

> Non-normative record. The component specifications hold the current contracts. This page does not repeat
> the [gap analysis](2026-10-17-framework-gap-analysis.md) or the
> [architecture and API review](2026-10-17-architecture-api-review.md): §2 states what those two now say
> after a day's work, and §3 is what is new, found by reading the shutdown path and the readings that were
> just added. Every claim in §3 is from the sources, with file and line.

## 1. Why another pass

Three things changed since 2026-10-17: the Router became a two-tier mesh, observability was added across all
components, and the style gate went green. Each of those closes items on the earlier lists and two of them
open new ones. A list that is re-read without being re-derived goes stale in exactly this situation, so this
pass re-checked the earlier findings against the tree and then looked where the new work landed.

## 2. Where the earlier lists stand

Closed since 2026-10-17:

| Item | How |
| --- | --- |
| Gap §4.1 style gate red, CI failing | Stale import removed and the Checkstyle scan pinned to `src/main/java`; `mvn clean verify -Pquality` passes for all ten modules |
| Gap §5.1–5.3 specification contradictions | The three English and three Chinese "finite admission" passages corrected, the term unified, the `getAsync` claim fixed |
| Gap §2.5 no observability surface | Core metrics SPI, `NetworkStats` and `RpcStats`, Spring adapters, `/metrics` and `/readiness` |
| Gap §2.2 broadcast covered only one Router | The mesh fans a broadcast out across the Routers; coverage is now every Node of the service whose Router is reachable |
| Architecture §2.2 Router had no background thread | No longer true: one daemon thread refreshes location reports. The record carries a note |

Still open, unchanged, and not re-argued here — each was verified against the tree today:

| Item | Where |
| --- | --- |
| The dirty set is unbounded; a database outage grows it and the cache together until the heap is gone | [Gap §3.1](2026-10-17-framework-gap-analysis.md) |
| `EntityCache` has no entry bound, only idle expiry | Gap §3.2 |
| No per-entity durable save; the only barrier is a global `flush()` | Gap §3.3 |
| Startup DDL is unconditional, check-then-act, and its type check ignores length | Gap §3.4 |
| No test touches a real MySQL or MongoDB, and CI defines no service container | Gap §3.5 |
| The Error Prone profile still fails and is still not in CI | Gap §4.2 |
| No release path: all modules `1.0.0-SNAPSHOT`, no BOM, versions duplicated per module | Gap §4.3 |
| The client-facing listener has no connection cap, per-IP cap, idle or first-packet timeout | Gap §2.6 |
| No single-writer guarantee for an entity across the cluster | Gap §2.3 |
| No deadline, cancellation or trace context on the wire | Gap §2.4 |
| Core reserves error-code ranges but no MetadataKey id range, so a framework key added later collides | Gap §2.4 |
| Cron is per-process; N nodes fire the same `@Cron` N times | Gap §2.7 |
| `RpcCallback` declares a method the RPC core never calls | [Architecture §4.1](2026-10-17-architecture-api-review.md) |
| `onFail` / `onFailure` / `onException` for one role | Architecture §4.2 |
| Nine dispatch entry points separated by positional primitives | Architecture §4.3 |
| `SingleRepository.delete(K)` against `GroupRepository.delete(E)`; `insert` returns `boolean` in one sibling | Architecture §4.4 |
| HTTP/JSON inside the business-execution module | Architecture §3.1, deferred at the owner's direction |

## 3. New findings

> **§3.1 and §3.2 were fixed the same day.** Shutdown is now one declared budget — `game.shutdown.budget-millis`, default 25 000 under a Kubernetes grace period — split into a lead, a derived drain and a reserved flush slice that Data receives, with an over-committed split failing at refresh. The lead is the phase readiness was missing: it reports 503 while admission stays open. The third question is answered in §3.2: Timers and Crons stay cancelled when admission closes, because a fired Timer's submission is refused with RUNTIME_CLOSED and dropped, so running them through the drain would discard rather than execute — but they do fire during the lead. §3.3 and §3.4 are open. The findings are kept below as the record of what was found.

### 3.1 The shutdown budget is larger than any common grace period, and the phase that persists data is the one that gets killed

Two timeouts run in sequence on shutdown, and neither knows about the other:

| Phase | Default | Source |
| --- | --- | --- |
| Runtime drain | **60 s** | `game.runtime.drain-timeout-millis`, default `60_000` ([RuntimeConfiguration.java:37](../../game-spring/src/main/java/cn/managame/spring/runtime/RuntimeConfiguration.java#L37)) |
| Data final flush | **30 s** | `shutdownTimeout`, default `Duration.ofSeconds(30)` ([GameDataBuilder.java:22](../../game-data/src/main/java/cn/managame/data/GameDataBuilder.java#L22)) |

The order is fixed by Spring: `RuntimeDrainListener` is a `ContextClosedEvent` listener at
`HIGHEST_PRECEDENCE`, so the drain runs to completion before bean destruction, and `GameData.close()` — which
is where D-CLOSE-01 does the final flush — runs after it. That ordering is correct in itself: in-flight Route
work finishes its `repository.update(...)` calls before Data stops accepting them.

The budget is not. Worst case is **60 + 30 = 90 seconds** of sequential shutdown. Kubernetes'
`terminationGracePeriodSeconds` defaults to 30 seconds and `docker stop` to 10, after which the process is
SIGKILLed. With framework defaults the kill therefore lands **inside the drain**, before the Data flush has
started at all: the last up-to-`flushInterval` of every player's progress, plus everything already pending,
is lost, and nothing reports it because the reporting happens in the phase that never ran.

The ordering makes it worse rather than better. The long phase is the one that only waits, and the short
phase is the one that actually writes to the database. A deployment that shortens only the grace period
starves the write; a deployment that shortens only the drain still has to discover that the two numbers add
up. Nothing in the specifications relates them, no default is derived from the other, and no log line says
what the total could be.

What would fix it is a decision, not a patch: either the drain default drops below a typical grace period, or
the two share one declared budget with the flush reserved a guaranteed slice, or the framework states the sum
and requires the operator to set the grace period above it. All three are cheap; choosing is the work.

### 3.2 Readiness has no lead time, so it cannot do the job it was added for

`/readiness` reports from `GameRuntime.stats().accepting()`. That flag is cleared by `runtime.shutdown()`
([DefaultGameRuntime.java:338](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java#L338)),
which is the first statement of the drain listener. So admission closes and readiness flips to 503 **in the
same call**. The sequence on SIGTERM is:

1. `runtime.shutdown()` — admission closed, readiness now 503
2. `awaitTermination(60 s)` — finishing already-admitted work
3. bean destruction — the HTTP listener closes, so readiness stops answering at all
4. `GameData.close()` — the final flush

A load balancer learns about step 1 only at its next probe, typically seconds later. Everything it sends in
between is refused, because the instance stopped accepting at the same instant it started saying so. The
window that readiness exists to create — *not ready, but still serving what arrives* — does not exist.

This is a flaw in what was added yesterday, not an old one. The fix is a phase the framework does not have: a
configurable interval during which readiness reports 503 while admission stays open, entered before
`shutdown()`. Without it the endpoint is useful for "is this instance sick" and useless for "take it out of
rotation cleanly", which is the case most deployments want it for.

A second consequence of the same line: `shutdown()` also calls `crons.close()` and `timers.close()`
immediately, so a timer or Cron due during the 60-second drain never fires. For a game that is a real
behaviour — a building that completes, a reward that unlocks — silently skipped on every deploy, with the
in-memory timer gap of Gap §2.7 on top of it.

### 3.3 Runtime readings have neither a dimension nor a denominator

`RuntimeStats` is built entirely from global LongAdder sums
([DefaultGameRuntime.java:351](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java#L351)):
`queued.sum()`, `running.sum()`, `rejected.sum()`. There is no breakdown by Domain and none by Route, so
`route.rejected` rising says the instance is shedding work but not which Domain, and never which player or
room. For a framework whose central guarantee is serial execution per `(domain, key)`, "which key is hot" is
the question an operator asks first, and it is the one reading that cannot be answered.

`VirtualThreadRouteExecutor` keeps `capacity` and `routeCapacity` as private fields with no accessor and its
`activeMailboxes` map likewise, so the configured limits are not reported either. `route.queued` therefore
has no denominator: an operator can see a number rising but cannot see how close it is to the point where
`tryExecute` starts returning OVERLOADED. The two together mean the executor can be read but not judged.

Both are now cheap to close, because the plumbing exists: the readings only need a per-Domain counter array —
Domains are registered up front and few, so cardinality is bounded and C-OBS-05 is not at risk — and two
gauges for the configured capacities. Per-Route remains out of reach by design, since routeKeys are unbounded
in cardinality; the honest substitute is a reading of the largest current mailbox, or the key of it, which
the executor could expose without keeping a map of counters.

### 3.4 The Router mesh moved failover onto every application, and nothing fills the gap

Before 2026-10-17 a service connected to every Router and the sender chose one per message, so losing a
Router was invisible: the next send picked another. The mesh deliberately replaced that, and the decision was
the owner's — `RT-SCOPE-01` keeps its rule that declaring Routers never creates or removes an RPC Peer, so
`ServiceRouting` never touches a Peer (its only mention of `addPeer` is the Javadoc telling the application
to call it).

What the record of that change states as an accepted cost is worth restating as an open item, because
nothing fills it: a service whose Router dies is unreachable until **the application** notices, opens a Peer
to another declared Router and waits for that Router's locations to propagate. Every application therefore
implements the same detection and switch, and gets it subtly wrong in the same ways — no backoff, no
hysteresis, flapping between two Routers, or reconnecting to a Router that is up but partitioned from the
rest of the mesh. `ServiceRouting` already runs a thread that evaluates which declared Router is connected
and already knows when it has none; the missing piece is the policy, not the machinery.

This is the clearest case in the framework of a gap that is cheap for the framework and expensive for every
application, which is the shape worth closing first.

## 4. Suggested order

**Decide, because the defaults are wrong rather than missing:**

1. Relate the two shutdown timeouts: one declared budget, a reserved slice for the flush, or a default drain
   below a typical grace period, and say the total in the Spring specification (§3.1).
2. Add the pre-drain readiness phase, so readiness can be used for what it is for (§3.2), and decide whether
   timers and Crons should keep firing during the drain.

**Cheap, and the plumbing is already there:**

3. Per-Domain `rejected`, `queued` and `completed`, plus gauges for the executor's configured capacities
   (§3.3).
4. A Router selection policy in `ServiceRouting` — or an explicit statement that the application owns it,
   with the detection it already has exposed so the policy has something to build on (§3.4).

**Unchanged from the earlier lists, in their order there:** the data-path bounds of Gap §3.1–3.4, a database
in CI (Gap §3.5), the API decisions of Architecture §4.1–4.4, and the architecture questions of Gap §2.3–2.4.

## 5. Verification status

§2's closures were each checked against the tree today, not recalled: the quality gate was run, the
specification passages re-read, the Router and metrics code read. §2's open items were each re-verified by
inspection — the dirty set, the cache, the DDL path, the listener builder, the Core key range, the CI
workflow and the executor accessors. §3's four findings cite file and line and come from reading the
shutdown path and the new readings; none was measured at runtime. In particular no deploy was timed: §3.1's
90 seconds is the sum of two documented defaults, and the claim that a SIGKILL lands inside the drain follows
from comparing it to the orchestrators' documented grace periods, not from an observed shutdown.
