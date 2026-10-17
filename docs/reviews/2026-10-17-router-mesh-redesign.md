# Router two-tier mesh redesign (2026-10-17)

**[English](2026-10-17-router-mesh-redesign.md)** | [简体中文](2026-10-17-router-mesh-redesign.zh-CN.md)

> Non-normative record. The current contracts are the [Router Specification](../ogbs/OGBS-Router-1.0.md), the
> [Router Java specification](../ogbs/OGBS-Router-Java-25-Specification-1.0.md) and
> [Router Profile v5](../rpc-wire.md#router-profile-v5). This page records why the design changed, what it is
> not, the decisions the user made, one race found while implementing it, and what was and was not verified.

## 1. What changed

A service Node no longer connects to every Router. It attaches to **one** Router; the Routers form a mesh and
tell each other which Nodes are attached to them. A message is delivered when its target is attached to the
sender's Router and otherwise passed **once** to the Router that reported the target.

| | Before (2026-10-13) | After |
| --- | --- | --- |
| Service → Router connections | every service to every Router, `S × R` | one each, `S + R(R−1)/2` with the mesh |
| Router ↔ Router | none | full mesh, the larger ID opens each pair |
| Router state | announcements of live connections | announcements **plus** Node locations reported by other Routers |
| Hops per message | always one | one when the target shares the sender's Router, two otherwise, never three |
| Router choice at the sender | hash of target and routeKey, with a one-second warm-up | none: there is one connection |
| Undelivered Call | resent through another Router | reported; there is no second path |
| Broadcast coverage | nodes attached to the one chosen Router | every node of the service whose Router is reachable |
| Failover | sender picked another Router automatically | the application moves the service's Peer |

## 2. What this is not

The 2026-10-13 record removed a Router-to-Router design, so the first question is whether this revives it. It
does not, and the difference is the thing that made the old one expensive:

- **Removed then, still removed:** replication of **business-key bindings**, with snapshots of up to
  1,000,000 bindings, service registration and verification, discovery removal fences, a binding table and
  route index, RoutedError, RoutingThread and the Router-side timers. A Router still never maps a key to a
  Node; the sender still resolves a player or a room itself.
- **Added now:** replication of **Node locations** only — one entry per Node, holding its epoch, service and
  reporting Router. The table is bounded by the number of Nodes, so a complete refresh is one small frame
  (16 bytes per entry) rather than a chunked snapshot of business state.

That bound is the whole argument. It is why a complete periodic refresh is affordable, why no sequence
numbers, acknowledgements or deltas-with-recovery are needed, and why the earlier design's cost does not come
back with its shape.

## 3. Decisions the user made

Two questions materially changed the work and were put to the user before implementation:

1. **Who owns the service's one connection?** → *The application.* `RT-SCOPE-01` keeps its rule that
   declaring Routers never creates or removes an RPC Peer, so neither role calls addPeer. A service declares
   the Routers it may use and attaches to the connected declared Router with the lowest ID; moving a service,
   including after its Router dies, means moving its Peer. The cost is that every application implements its
   own failover; the benefit is that game-router still touches no connection.
2. **A mesh link down with both Routers up?** → *The pair is unreachable.* A strict one-hop limit: no relay
   through a third Router, no hop count, no path selection. A location is unusable while its reporting
   Router's connection is down, so the Nodes behind a broken link report PEER_NOT_FOUND instead of
   disappearing into a hole.

## 4. The response path needs no origin field

The pre-2026-10-13 design carried the originating Router in each frame, and the first sketch of this one
assumed it would have to come back: the caller's pending call belongs to the caller's own Router, so a
response produced two hops away has to find its way there.

It does not need a field. Every Router already knows where every Node is, and a service Node is attached to
exactly one Router, so resolving the **caller's** nodeId is unique and yields exactly the Router holding the
pending call. A response is therefore an ordinary message routed by its target, like any other, and the
forwarding code has one resolution path instead of two. This is simpler than both earlier designs.

## 5. A race found while implementing it, and RT-STATE-03

Wiring the two-hop call end to end exposed a real ordering problem. Control traffic (Attach, Detach,
Locations, Unlocate) uses routeKey 1 and routed Data uses the inner message's routeKey, so the two travel the
same connection but **not the same Slot**. The sequence for a first call is:

1. node 1 attaches to Router A; A reports the location to Router B.
2. node 1 calls node 2; A passes the request to B.
3. B delivers to node 2, which replies to B.
4. B must route the response by node 1's nodeId — which needs the report from step 1.

Steps 1 and 2 can arrive at B in either order. The B→node 2→B round trip usually covers the gap, but nothing
guarantees it, and the failure mode is a dropped response and a call that times out for no visible reason.

The fix reuses a mechanism the framework already had. A service's routed message announces the service where
it is attached (RT-NODE-02); the same rule now applies one level up: **a Data frame a Router receives from
another Router also locates its own sender on that Router** (RT-STATE-03). The request that needs an answer
therefore carries the location that answer needs, and the race cannot happen. It costs one map check per
forwarded frame and removes a dependency on refresh timing.

[RouterIntegrationTest.aForwardedFrameLocatesItsOwnSenderWithoutAnyLocationReport](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java)
tests it deterministically: a raw RpcNode stands in for a mesh Router and never sends Locations at all, so
the only thing that can make its Node reachable is the forwarded frame.

## 6. What got simpler, and what got more complex

**Simpler — ServiceRouting.** With one connection there is no Router choice, so the hash, the one-second
warm-up, the per-Router `Link` state and the whole resend path are gone. The resend path had required each
in-flight call to retain its encoded frame so it could be sent again unchanged; that retention, and its
reference-count bookkeeping, is gone with it. `Call` is now a three-field record.

**More complex — GameRouter.** A second table, the report protocol, the periodic refresh thread, and
two-target resolution on the data path. It also gains the first background thread the Router role has had:
one daemon thread named `game-router-locations`.

**Better semantics, incidentally.** PEER_NOT_FOUND used to be ambiguous — it meant "not on the Router you
asked", which is why the sender had to resend to disambiguate. It now means "not anywhere the mesh this
Router reaches can see", which is an answer rather than a hint. Per-key ordering also no longer depends on a
stable Router-choice set, only on the sender's own connection and the target not moving.

## 7. Accepted costs

- **Two hops for cross-Router traffic.** The 2026-10-13 measurements noted that the stateless design halved
  this: "every routed message now crosses one Router where cross-Router traffic previously crossed two". With
  R Routers roughly `(R−1)/R` of traffic is now cross-Router, so most messages pay the second hop and a
  second Router's CPU. The connection count is what that buys.
- **Failover is the application's.** A service whose Router dies is unreachable until the application opens a
  Peer elsewhere and that Router's locations propagate.
- **A broken mesh link isolates a pair** (§3, decision 2).
- **Convergence up to one refresh period** for a report lost in flight; attach and detach are reported
  immediately, so the common cases are faster.
- **Mesh trust.** A declared Router is trusted for the locations it reports, and the RPC handshake
  authenticates cluster membership rather than a specific identity, so a cluster member configured as a
  Router could report any location. Unchanged in kind from the previous design, wider in effect.
- **65,535 entries per report**, the uint16 count. A Router with more attachments logs the overflow instead
  of reporting a truncated set.

## 8. What changed in the tree

Contracts: the Router standard gains RT-MESH-01, RT-STATE-02 and RT-STATE-03 and rewrites RT-SCOPE-02,
RT-NODE-02, RT-STATE-01, RT-ADDR-02, RT-ADDR-03, RT-DATA-01, RT-DATA-02, RT-DATA-03, RT-BCAST-01 and
RT-LIFE-01, with the tradeoff table rewritten; Router Profile v5 adds Locations (op 4) and Unlocate (op 5),
keeps the v4 Data envelope byte for byte, and lets Data flow between Routers. Both layers and both languages
were updated together, as were the Router Java specification, the module README, the architecture overview,
the OGBS index and the project README.

Code: `RouterWire` (v5, report encode/decode), `GameRouter` (location table, mesh declarations, refresh
thread, two-target resolution, broadcast fan-out, five-field `Stats`), `ServiceRouting` (one Router, no
choice, no warm-up, no resend). `RouterEchoExample` now assembles a two-Router mesh with one service on each,
so the sample itself exercises two hops.

## 9. Verification status

`mvn clean verify -Pquality` passes from the repository root: ten modules, 0 Checkstyle violations in every
checked module, 363 tests, 0 failures, 2 skipped (the environment-gated database tests). game-router is 24
tests, up from 21, and passed in three consecutive runs before the full build.

New or rewritten tests cover two-hop forwarding with every RPC field preserved, the forwarded-frame location
rule of §5, broadcast fan-out across the mesh, per-key ordering across two hops, a mesh link down reporting
PEER_NOT_FOUND, a Node leaving being forgotten across the mesh, an application moving a service to another
Router, and location reports from an undeclared Peer being rejected. The removed tests were the ones that
asserted behaviour this design does not have: Router choice spreading keys over Routers, warm-up of a
reconnected Router, and resending a call through another Router.

**Not measured.** No throughput, latency or memory number in this record is new. The two-hop cost in §7 is
reasoning from the 2026-10-13 measurements, which were taken on the previous design; the write-buffer
guidance carried into the Java specification is from the same record and describes per-connection behaviour
that has not changed. Production capacity, a real multi-Router deployment, partition behaviour and
cross-language interoperability remain unverified.
