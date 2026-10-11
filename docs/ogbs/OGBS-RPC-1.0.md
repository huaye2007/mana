# OGBS RPC Specification 1.0

**[English](OGBS-RPC-1.0.md)** | [简体中文](OGBS-RPC-1.0.zh-CN.md)

Document type: **Language-independent specification**. Component: game-rpc. Java implementation is available; correlation, timeout isolation and recovery handoff repairs are locally verified. Finite admission remains unimplemented/deferred. See Java section 9.1. Production capacity and cross-language interoperability are unverified.

Companions: [Java 25 specification](OGBS-RPC-Java-25-Specification-1.0.md), [RPC Wire Profile](../rpc-wire.md).
Normative dependencies: [Network](OGBS-Network-1.0.md), [Core](OGBS-Core-1.0.md).

The Router integration review led to ordinary Handler assembly and component-owned protocol recovery; RPC has no upper-layer readiness/cleanup hooks. Finite admission remains a declared gap. See [Java integration review](OGBS-RPC-Java-25-Specification-1.0.md#rpc-router-review-2026-10-07).

<a id="1-范围"></a>

## 1. Scope

RPC provides direct communication between internal server nodes: call, notify, reply, fixed multi-connection Slots, handshakes, heartbeats, reconnection, and local call completion.

The deployment baseline is a long-lived game-server service. Its RPC endpoint starts with the service, remains running during normal operation, and closes for maintenance or service exit. Routine transport failures and remote restarts are handled by connection recovery while the local endpoint remains running. Repeated local endpoint replacement, hot lifecycle switching, and a separate RPC drain protocol are outside the current baseline.

It does not interpret business bodies or provide protocol registration, Runtime scheduling, discovery, routing services, durable delivery, automatic business retries, or remote cancellation. Receiving a response does not imply exactly-once execution; timeout does not undo remote execution.

<a id="2-对象与标识"></a>

## 2. Objects and identifiers

| Object / field | Definition |
| --- | --- |
| RpcNode | Local RPC endpoint with explicit startup and final closure |
| nodeId | Nonzero uint32 node identifier |
| RpcPeer | One directly communicating remote relationship |
| ConnectionSlot | Fixed logical connection position within a Peer |
| requestId | uint32 call identifier matched within a Peer; 0 means Notify |
| routeKey | uint64 affinity value; 0 means no affinity; not a Runtime Route |
| businessIdType / businessId | uint8 / uint64 business identity; type=0 means unspecified |
| PendingCall | Correlation ID, completion notification, and timeout for one local call |

**R-PEER-01** At most one current Peer exists per remoteNodeId; IDs cannot be 0 or self. addPeer is idempotent with identical address/slotCount and rejects differences until removal. Registration does not guarantee connectivity.

**R-PEER-02** slotCount is fixed at 1..255 for a Peer lifetime. Each Slot is empty or holds one READY connection. Changing count requires a new Peer lifetime.

**R-PEER-03** addPeer creates an active Peer with a target address and empty-Slot maintenance; valid inbound handshakes create passive Peers. Only one side of a pair may call addPeer: addPeer for a node that already has a passive Peer must fail synchronously, and an inbound handshake from a node with a local active Peer must be rejected. Each Slot therefore has one dialing direction, and the two sides can never connect simultaneously and evict each other.

**R-PEER-04** Active Peers are never automatically removed. Passive Peers are removed when they have neither connections nor PendingCalls. Cleanup and new same-ID handshake binding must serialize so cleanup cannot remove a newly established session. Removal does not ban future admission; later inbound handshakes may create a new Peer. Old asynchronous results must not revive or affect it.

Example: retain a disconnected passive Peer while PendingCalls await responses, timeout, or removal. Reconnection can then return a response over another Slot. Completely idle Peers may be reclaimed.

<a id="3-消息"></a>

## 3. Messages

Request contains command, requestId, routeKey, businessIdType, businessId, Metadata, and opaque body. command is nonzero, not necessarily positive in signed representation. requestId=0 means Notify; otherwise Call. Notify creates no PendingCall and permits no Response.

Response contains nonzero requestId, errorCode, Metadata, and body, without command or routeKey. Retrieve command from local PendingCall. errorCode uses [Core's error space](OGBS-Core-1.0.md#4-frameworkerrorcode), with no high-bit marker.

Handshake exchanges magic/version/nodeId/slotId/slotCount plus optional authentication fields (timestamp, nonce, peerNonce, MAC); Heartbeat has no payload. [Wire Profile](../rpc-wire.md) is the sole layout source.

**R-MSG-01** After argument and lifecycle validation, the sender owns the releasable body, including unsent, encoding-failure, and missing-Peer paths. Validation failure leaves ownership with the caller. Inbound bodies are borrowed during receive callbacks; asynchronous use must extend lifetime through binding-defined ownership mechanisms. Share Metadata under Core's read-only contract.

<a id="4-建立握手与-ready"></a>

## 4. Establishment, handshake, and READY

**R-HS-01** Network establishment means only transport availability. The active side sends the selected Slot's Handshake first. The passive side creates/reuses a Peer and replies with local ID and identical Slot information.

**R-HS-02** Validate magic/version, nonzero non-self remote ID, slotCount, and slotId range. Active endpoints also validate expected Peer/Slot. A count mismatch with an existing Peer rejects the new connection without changing that Peer.

**R-HS-03** READY requires local handshake acceptance by Network, validated remote handshake, and successful Slot binding. The passive side must get its reply accepted before publishing the Slot, so business frames cannot precede the handshake. No extra ACK.

**R-HS-04** Duplicate handshakes, Request/Response/Heartbeat before READY, Slot conflicts, and handshake timeout terminate the candidate. Timeout and completion compete for the same completion right.

**R-HS-05** Node-ID exchange alone is not authentication. With a shared secret, handshakes must carry an HMAC proof: the passive side verifies the MAC, clock skew and nonce uniqueness, and the initiator verifies that the reply echoes its nonce; any failure terminates the candidate. A node without a secret accepts only proof-less handshakes, so mismatched configurations cannot connect. Handshake authentication does not provide confidentiality or integrity of later frames; use transport TLS when required.

<a id="5-slot-并发"></a>

## 5. Slot concurrency

**R-SLOT-01** Bind by atomic empty → candidate competition. First success wins; duplicates must not evict an existing connection.

**R-SLOT-02** Unbind checks exact connection identity so a late old disconnect cannot clear a replacement.

**R-SLOT-03** Binding checks that Node still runs, Peer is still current, and candidate remains valid. Closure/removal invalidation must leave no new binding or Peer.

Slots are transport details. Source Slot is only a reply hint, never part of business body, Metadata, or Runtime Route.

<a id="6-发送选择"></a>

## 6. Send selection

**R-EXT-01** Upper-layer composition MUST reuse the existing Node and transport, dispatching its owned messages and delegating ordinary RPC. RPC MUST NOT depend on routing semantics or own upper-layer protocol state. Availability inspection describes transport only, not process incarnation or service presence; external discovery owns instance existence. Transport loss alone does not cancel pending calls. The application owns composition and upper-layer closure; RPC owns its own admission, pending completion and resource barrier. No upper-layer readiness/cleanup notification is required. Language-specific assembly APIs belong to the Java specification.

For example, an upper layer can recheck its protocol on reconnect without rebuilding the RPC Peer or failing an ordinary call waiting on that Peer. Loss itself has no lifecycle callback in the message handler; calls retain the existing response/timeout/removal/closure contract. Routing envelopes use ordinary notify and reply, rather than introducing a second send primitive.

**R-SEND-01** For call/notify, nonzero routeKey starts at unsigned64(routeKey) mod slotCount; zero uses Peer-local round-robin. reply first tries the Request's actual source Slot, then starts from the caller-supplied routeKey or round-robin and scans circularly, skipping the already tried source Slot. requestId is never a routing input.

**R-SEND-02** Scan at most one cycle, skipping empty, non-READY, inactive, or unwritable connections. An unaccepted frame may move to another candidate; stop immediately on first ACCEPTED. Each connection has a bounded outbound buffer; above it the connection counts as unwritable. The bound is binding configuration and prevents memory exhaustion.

**R-SEND-03** ACCEPTED means local network acceptance, not delivery/execution. Never resend an accepted business frame after asynchronous write failure, disconnection, or timeout. Reconnection restores channels only.

**R-SEND-04** notify/reply return only ACCEPTED, PEER_NOT_FOUND, or UNAVAILABLE. Backpressure and no READY connection both mean UNAVAILABLE; no waiting queue. call additionally has a finite, binding-configured Node-wide admission limit covering encoding, pending calls, and their unfinished completion notifications. Exhaustion releases the owned body and reports local UNAVAILABLE immediately without encoding, sending, waiting, or retry. This limit does not reject notify/reply.

Example: routeKey chooses slot2 but fallback sends through slot3. reply prefers slot3; if unavailable, fallback starts at slot2. A new connection in the source Slot can carry the reply; no original physical connection is retained.

Affinity does not guarantee global business ordering across connections. Applications supply ordering and idempotency.

<a id="7-pendingcall-与完成"></a>

## 7. PendingCall and completion

**R-CALL-01** PendingCalls belong to a Peer. Responses through any of its Slots may complete them; never match across Peers.

**R-CALL-02** Register PendingCall after successful encoding but before the first network send to avoid losing fast responses. Recheck Peer/Node validity after registration. If every send fails, remove it and report failure.

**R-CALL-03** Response, timeout, removal, closure, and send failure compete for one completion right; notify at most once. Remove PendingCall and cancel timeout before invoking the unified handler. Handler failure cannot restore or recomplete it. Retain the admission reservation until its completion handler returns, including exceptional return; synchronous encoding/collision/write exceptions also release their reservation. Timeout business notifications must execute outside the shared maintenance timer so one slow notification cannot block other call/handshake deadlines or reconnection tasks. Notifications for different calls have no total-order guarantee.

**R-CALL-04** requestId increments with uint32 wrap, skipping 0. Gaps are allowed; no Slot/Node/time encoding. Within one local Node lifetime, explicit removal or passive Peer reclamation must not reset allocation and immediately reuse old call IDs upon same-remote Peer recreation. Matching remains Peer-scoped; a binding may allocate IDs Node-wide. An occupied ID fails synchronously without replacing the old call or scanning for another ID. Deployments must keep unresolved remote response lifetime far below a full allocation wrap period; extreme responses delayed beyond a full wrap cannot be distinguished. Wire has no Node-incarnation field and does not guarantee rejection of saved old business replies after replacing/restarting the local Node instance; such protection needs application epoch validation or a separately versioned protocol.

**R-CALL-05** Drop the remaining unmatched Response frame without notification or creating a call; a nonzero requestId must be readable. After a match, malformed remaining data must fail the claimed call with PROTOCOL_ERROR and close the connection; removal must not lose notification.

**R-CALL-06** No receiver business deduplication or remote cancellation protocol. Unified application handlers decode Request bodies, interpret response errors, and invoke business callbacks.

<a id="8-时间生命周期与关闭"></a>

## 8. Time, lifecycle, and closure

Maintenance orchestration belongs to the application: stop new business admission, finish or terminate application work under its own policy, then close RPC. An abnormal process exit may prevent close from running; synchronous close guarantees apply when that operation executes and returns. RPC cannot promise completion callbacks or resource-cleanup execution after abrupt process termination.

Design rationale: startup and final closure are infrequent management paths. Keep their resource ownership and existing closure guarantees clear, while prioritizing call correlation, timeouts and transport recovery during long-running service. A slow one-time global cleanup scan is a lower-priority optimization without measured maintenance impact. This deployment clarification does not change R-TIME-04 or permit wrong responses, lost recovery, or leaking resources during ordinary operation.

**R-TIME-01** Call timeout starts after network ACCEPTED using monotonic timing. A fast response can precede timeout registration; the registrar must detect completion and cancel the new timeout. Scheduling/load may delay delivery; no hard real-time guarantee.

**R-TIME-02** Disconnection alone does not fail ACCEPTED PendingCalls immediately; await another Slot's response or timeout.

**R-TIME-03** removePeer first removes current topology, then terminates sessions, reconnection, and unfinished calls. Missing Peer is an idempotent no-op. Recreation starts an independent lifetime without migrating old calls.

Example: A calls B with command 101; after removal/recreation, A calls command 202 with a later ID. B can still reply to its saved command 101 over the replacement connection, but A drops that unmatched old ID and completes command 202 only from its own response. See the [Java current-source review](OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险).

**R-TIME-04** Node explicitly moves NEW → RUNNING via start, then finally CLOSED; startup failure also terminates the instance. close accepts NEW, is idempotent, and forms a synchronous barrier: reject new operations immediately and finish accepted RPC operations, outstanding calls, connections, listening, owned maintenance resources, and owned completion notifications before return. Concurrent close waits for the same cleanup. Separately submitted application tasks are excluded. Synchronous close is prohibited in contexts that would wait for themselves; see Java constraints.

**R-TIME-05** add/remove/call/notify/reply require RUNNING. Already-closed entry is a lifecycle error. An admitted call racing closure/removal may receive NODE_CLOSED/PEER_REMOVED or the winning response/timeout.

<a id="9-心跳与重连"></a>

## 9. Heartbeats and reconnection

**R-LIVE-01** After READY, send empty Heartbeat only at Write Idle threshold. Valid complete inbound frames refresh Read Idle. Business traffic supplies liveness; no Ping/Pong or sequence is needed.

**R-LIVE-02** Read Idle threshold closes the connection. Failure to accept Heartbeat, including backpressure, also closes it. Ignore idle events during handshake; independent handshake timeout applies.

**R-LIVE-03** addPeer immediately connects empty Slots. After connection/handshake failure or established disconnection, retry after a positive base delay plus a fresh uniformly sampled additive delay in [0, configured jitter]. Bindings define defaults and time granularity; zero jitter preserves fixed-delay behavior. One recovery chain per Slot covers delay, connect, and handshake; Slots are independent. Old tasks become invalid after removal/closure. No exponential backoff, retry maximum, or business resend. Jitter spreads attempts but does not impose a global connection-attempt rate limit.

Java implementation handles recovery stop/unbind races by clearing the recovery marker, rechecking and reacquiring ownership with CAS. Local concurrency regression passes; maintenance timing is not a hard real-time guarantee and production recovery convergence is unverified. See [Java validation boundaries](OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险).

<a id="10-错误边界"></a>

## 10. Error boundaries

| Event | Behavior |
| --- | --- |
| Argument/lifecycle error | Synchronous rejection; caller keeps body |
| Encoding/ID collision | Synchronous exception; reclaim owned body/frame |
| call missing Peer/unavailable/timeout/removal/closure | Local onFail |
| Any valid remote errorCode | Unified onResponse; application interprets |
| Ordinary runtime exception in onRequest | Diagnose; attempt empty HANDLER_ERROR for Call, no response for Notify; keep connection |
| Ordinary runtime exception in onResponse/onFail | Diagnose and end; no renotification or closure |
| Malformed Wire/invalid handshake | Close current connection; first fail a claimed malformed response |
| Unknown nonzero command/undecodable business body | Application handler decides |

Automatic HANDLER_ERROR includes no exception text, stack trace, or Metadata. Core alone defines framework error numbers.

<a id="11-已确认取舍与验证"></a>

## 11. Confirmed tradeoffs and validation

| Choice | Rationale | Revisit when |
| --- | --- | --- |
| Fixed header and simple Slot affinity | Cross-language interoperability and short failure paths | Measurements show significant header cost |
| Peer-level completion, no business retries | Cross-Slot replies without duplicate execution | A new explicit idempotent/reliable delivery protocol |
| Passive creation/cleanup | Bidirectional communication with one-sided configuration | Separate node authorization/topology needs |
| Unified application handler | RPC does not own business codecs/Runtime | Dedicated external integration module needed |
| Base reconnect delay with bounded jitter | Spread simultaneous recovery while retaining a configurable minimum; zero restores fixed delay | Measurements require global attempt admission or exponential backoff |

Java tests: [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java), [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java), [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java). Implementation, defaults, and examples are in the Java specification.

Local real TCP/concurrency regressions and root clean verify pass. Finite admission remains deferred; cross-language, production capacity, long-lived ID wrap and public-network deployment are unverified. RPC core has no automatic Runtime dependency; optional Spring adaptation follows [container integration semantics](OGBS-Spring-1.0.md). TLS/WS RPC Builder and discovery remain unimplemented.
