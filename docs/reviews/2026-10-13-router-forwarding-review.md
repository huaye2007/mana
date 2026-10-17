# Router forwarding and structure review record (2026-10-13)

**[English](2026-10-13-router-forwarding-review.md)** | [简体中文](2026-10-13-router-forwarding-review.zh-CN.md)

> Non-normative record. Current contracts live in the [Router Specification](../ogbs/OGBS-Router-1.0.md), the [Router Java specification](../ogbs/OGBS-Router-Java-25-Specification-1.0.md) and the [Wire Profile](../rpc-wire.md#router-profile-v4). This file keeps the questions, measurements and decisions of the review.
>
> Later the same day the Router became a stateless relay (section 4). That removed RoutingThread, RouterSync, the origin field and the Router-to-Router hop discussed in sections 1–3; the header-rewrite forwarding and the write-buffer guidance carry over unchanged.
>
> Superseded on 2026-10-17: section 4's design — every service connected to every Router, so Routers never talk to each other — was replaced by a two-tier mesh in which a service attaches to one Router and the Routers replicate Node locations. Node-location replication is bounded by Node count and is not the business-key binding replication that section 4 removed, which stays removed. Sections 1–3 remain the record of the design before section 4. See [2026-10-17 Router mesh redesign](2026-10-17-router-mesh-redesign.md).

## 1. Question: can RoutingThread be removed?

No. RoutingThread is the single mechanism that keeps each role's control state lock-free: GameRouter's local bucket, discovery fences and replication, and ServiceRouting's registration and control queue are changed only there. The alternatives were weighed and rejected:

| Alternative | Cost |
| --- | --- |
| Locks instead of one owner thread | Netty threads contend with control work; capturing or applying a large snapshot blocks them (the earlier ServiceRouting problem) |
| Run control work on a Netty EventLoop | A snapshot of up to 1,000,000 bindings stalls every connection on that loop; RpcNode exposes no loop either |
| Inline an executor into each role | The same `call/post/schedule/every/close` code twice |

The class stays at 48 lines. The 1 ms `schedule` retry also stays: RPC offers no writability callback, and retrying on the 20 ms tick would cap a snapshot drained through a 64 KiB write buffer at about 3 frames per 20 ms.

## 2. Findings and changes

1. **Every Router hop decoded and re-encoded the inner RPC frame.** JFR on a two-Router routed-notify load put RouterWire.data, RpcWire.decodeRequest and RpcEncoder.encode on the Router's hot path, and each broadcast recipient paid one re-encode. A Router now parses the 44-byte routing header plus the inner call ID and routeKey, writes a new header and passes the inner frame on: copied up to 2 KiB, referenced above. Microbenchmark of one hop including the outer RPC encode (JDK 21, 2 vCPU container, ±10 %):

   | Inner body | Decode + re-encode | Header rewrite |
   | --- | --- | --- |
   | 64 B | 470–520 ns | 280–350 ns |
   | 1 KiB | 640–720 ns | 440–540 ns |
   | 8 KiB | 1,100–1,300 ns | 740–870 ns |

   Always referencing the inner frame (no copy for small frames) measured 370–450 ns at 64 B, so small frames keep being copied. An end-to-end loopback run (one JVM, two Routers, two services) showed no difference beyond noise: there the sender, loopback TCP and shared EventLoops dominate. The saving matters when a Router is CPU-bound or fans out broadcasts.
2. **A response without an origin Router was located through the replica index**, contrary to RT-DATA-02 ("never through a newly resolved binding or a replica lookup"). The fallback and the origin-less `RoutedRequest` constructor are removed; a Router drops such a response.
3. **GameRouter (659 lines) held four responsibilities**: API and lifecycle, local authority, Router-to-Router replication and forwarding. Replication moved to package-private RouterSync (sessions, snapshots, deltas, replicas); GameRouter keeps authority, service controls, forwarding and the public API (336 lines). `GameRouter.forServiceNode` returned a ServiceRouting, so both factories were replaced by public constructors. `RouteIndex.resolve` returns the located Node directly, removing a second lookup per dynamic message.
4. **Routed Notify loss under burst is a write-buffer configuration issue, not a defect.** Routers never buffer business data, and one Router-to-Router Slot carries all traffic between two Routers. With 2,000 messages in flight through two Routers:

   | Router write buffer (low / high) | 64 B Notify | 1 KiB Notify |
   | --- | --- | --- |
   | Netty default 32 / 64 KiB | 1.7–1.8 k of 50 k lost, 10–15 k/s | — |
   | 512 KiB / 1 MiB | none lost, 120–200 k/s | 0.9–1.4 k of 50 k lost |
   | 2 / 4 MiB | none lost, 120–185 k/s | none lost, 100–135 k/s |

   The high watermark has to cover the bytes in flight. The Java specification now says so and RouterEchoExample configures 512 KiB / 1 MiB.

## 3. Not changed

- One class per Link instead of RouterSync: the shared session counter, snapshot source and pump scheduling would have to be passed into every Link.
- Router-side buffering of refused data: it would trade a visible drop for unbounded memory and hidden latency; sizing the write buffer for the bytes in flight removes the measured loss.
- Validation depth: a Router still checks the envelope and the inner frame's length, type and fixed fields; Metadata and body are checked once, by the receiving service.

Validation: game-router 47 tests passed in six consecutive runs; game-spring 17 and game-demo 31 passed. Benchmarks were temporary programs and are not part of the build.

## 4. Decision: the Router keeps no state

The user then required game-router to only forward messages and keep no other state. Three choices settled the design: business keys are resolved by the sender (bind/unbind and dynamic addressing are removed); every service connects to every Router, so Routers never talk to each other; and a Router may keep only what a connected Node announces about itself (nodeEpoch, serviceId), dropped with the connection.

What went away: Router-to-Router sessions, snapshots, deltas and replicas; the binding table and route index; service registration, verification and restoration; discovery removal fences; RoutedError; the origin field; RoutingThread and every Router-side timer; RouterErrorCodes and Core codes 5001–5004, which stay retired. game-router main code went from about 1,530 lines to about 550, and the Router role holds one ConcurrentHashMap of announcements and runs only on Netty threads.

What replaced it: a service announces itself with an unacknowledged ATTACH on each new connection and every second; every routed message also announces its sender; a call's response returns through the Router that delivered the request, because RPC pending calls belong to one Peer. Each send picks one connected Router from the target and routeKey. Router Profile v4 has three operations (Attach, Detach, Data) and a 35-byte Data envelope.

Accepted costs, also listed as Java known limits: up to a second of unreachability after an unnoticed Router restart; no retry through another Router when the target is not connected to the chosen one; order kept per key only while the connected Router set is stable; services × Routers connections.

A loopback run with two Routers and two services connected to both (one JVM, 2 vCPU, 4 MiB write buffer, routeKeys spread over both Routers) measured 105–155 k/s Notify at 64 B and 1 KiB with no loss, and 47–61 k/s windowed calls; every routed message now crosses one Router where cross-Router traffic previously crossed two.

Validation: game-router 17 tests passed in six consecutive runs; game-core 7, game-rpc 36, game-spring 17 and game-demo 31 passed.

## 5. Follow-up review of the stateless Router

A second pass over the stateless design found these problems; all were fixed the same day.

| Finding | Fix |
| --- | --- |
| A sender used a Router as soon as its own connection came back, before other Nodes had reconnected and announced themselves there. Probe: calls every 2 ms over two Routers while one restarts; 12–16 calls per restart failed with PEER_NOT_FOUND within about 0.1 s after the sender's reconnect | A Router becomes usable one announcement period (1 s) after its connection is seen; calls a Router answers with PEER_NOT_FOUND/UNAVAILABLE, which prove non-delivery, are resent once through each other connected Router. Same probe afterwards: no PEER_NOT_FOUND; only the one call in flight through the closing Router failed with a local UNAVAILABLE |
| A target connected to only some Routers failed every call routed through the others | Covered by the resend |
| Attach and Detach used routeKey 0, so RPC spread them over Slots and a Detach could overtake an Attach, leaving a stale entry that kept receiving deliveries the service rejected | Both use routeKey 1; a service answers a delivery from a Router it does not use with another Detach |
| Drops (target not attached, send refused, response without its caller) were silent | `GameRouter.stats()` counts forwarded messages and each kind of drop |
| The inner frame limit (4 MiB − 64) let a Data request exceed the 4 MiB RPC frame by up to 3 bytes | Limit is 4 MiB − 67 |
| A handler exception was hidden when its HANDLER_ERROR reply failed after close | The reply failure is added as suppressed |
| The specification said an announcement disappears with its connection; RPC reports no disconnects, so the entry is only dropped when a send to it fails | Wording states the lazy removal |

Broadcast still scans every attachment; it was left as is until a deployment with many Nodes and frequent broadcasts measures it.

Each new test (resend under partial connectivity, warm-up after a Router restart, re-withdrawal from an unused Router) failed with its fix removed and passed with it. game-router 21 tests passed in six consecutive runs; game-spring 17 and game-demo 31 passed.

