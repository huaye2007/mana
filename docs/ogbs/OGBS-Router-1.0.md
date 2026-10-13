# OGBS Router Specification 1.0

**[English](OGBS-Router-1.0.md)** | [简体中文](OGBS-Router-1.0.zh-CN.md)

Document type: language-independent Specification. Component: game-router. Companion: [Java 25 Development Specification](OGBS-Router-Java-25-Specification-1.0.md). Dependencies: [RPC](OGBS-RPC-1.0.md), [Core](OGBS-Core-1.0.md). Bytes: [Router Profile in RPC Wire Profile](../rpc-wire.md#router-profile-v4). Status: implemented Java reference; local TCP contract tests exist. Production capacity and cross-language interoperability are unverified.

A Router is a stateless relay. Every service connects to every Router; a service sends a message for an exact Node through any connected Router, and that Router passes it to the target Node connected to it. The Router keeps no routing table, registration or replica and never talks to another Router: the only thing it holds is the identity each connected Node announced, which disappears with the connection. Resolving a business key such as a player to a Node belongs to the sender. Open limits are in the [Java known limits](OGBS-Router-Java-25-Specification-1.0.md#7-known-limits).

## 1. Responsibility and composition

**RT-SCOPE-01** Router MUST build on RPC for sending, receiving, direct-Peer connections, Slots, call IDs, pending calls, timeouts and callbacks. A process has one RPC Node; installing routing MUST NOT create another Node or independent transport. An application may still use that same Node for ordinary direct RPC. Router owns relaying only; the application owns RPC startup, shutdown and Peer configuration, and connects to a Router with the same RPC Peer operation as to any other Node. Declaring which Routers a service uses MUST NOT create or remove RPC Peers.

**RT-SCOPE-02** V1 provides exact Node addressing and service broadcast through stateless Routers. Router does not resolve business keys, keep bindings or registrations, replicate anything, implement discovery or subscriptions, store or retry messages, hold player state or provide Runtime serial execution. Routers do not connect to each other, so every routed message crosses exactly one Router. RPC success or timeout never proves exactly-once business execution.

| Owner | Responsibility |
| --- | --- |
| RPC | Connections and Slots, message transport, call IDs, pending calls, timeouts and completion callbacks |
| Router | Relaying a message to a Node connected to it, using only what that Node announced on its live connection |
| Sending service | Resolving a business key (player, room) to a Node ID; choosing one connected Router per message |
| External discovery | Which service instances and Routers exist, and their addresses |
| Application | Component assembly, startup/shutdown, connecting every service to every Router |

Rationale: with no state of its own, a Router can be restarted, added or removed without any recovery or synchronization protocol, there is no replica lag or conflicting binding to resolve, and the authority over where a player lives stays with the business code that decides it. A new layer or abstraction needs a concrete responsibility that these owners cannot express; possible future extensibility alone is insufficient.

## 2. Nodes and attachment

**RT-NODE-01** A Node identity is `(nodeId, nodeEpoch, serviceId)`. nodeId is a nonzero uint32, nodeEpoch a nonzero uint64 and serviceId a positive int32. Router IDs and service Node IDs share one namespace. Each Node serves exactly one service. nodeEpoch distinguishes process incarnations: a restarted process uses a new value, which must be distinct but need not be larger.

**RT-NODE-02** Attachment is bound to the connection. A service announces its identity on each connected Router as soon as it sees the connection and again periodically, and every routed message it sends announces it too. A Router keeps the latest announcement per Node only while that Node is connected; a new incarnation replaces the old one. An announcement stops counting when its connection closes or the Node withdraws with the same epoch; an implementation that learns of closed connections only when a send fails MAY discard it then. A service that receives a delivery from a Router it does not use withdraws from that Router again, so a withdrawal overtaken by an earlier announcement still takes effect. An announcement is not a registration: it is not acknowledged, replicated or persisted and says nothing about service presence. A restarted Router learns every Node again from their next announcements. Example: after a Router restart every service sees a new connection and announces at once; a service whose connection was replaced before it noticed announces within its period, and until then deliveries to it through that Router fail with PEER_NOT_FOUND.

**RT-STATE-01** A Router MUST NOT keep state beyond the announcements of live connections: no binding or route table, cache, registration, pending call, sequence number or replica. A Router MUST NOT exchange routing traffic with other Routers. Routers are therefore interchangeable: any Router a service is connected to can carry its message.

## 3. Addressing and Router choice

**RT-ADDR-01** A routed message targets an exact nodeId, optionally with an exact nodeEpoch that the Router checks. Dynamic addressing is resolved by the sender before sending; a Router never maps keys to Nodes. Player example: the service that owns player B records in business state that B is on Node 12; a sender looks that up and sends to Node 12, putting B's ID into the business identity or payload. If B moved, the sender's lookup decides where the message goes; the Router guarantees nothing about moved players.

**RT-ADDR-02** Every service connects to every Router it sends through or receives from. A sender chooses one usable Router per message. A connected Router SHOULD become usable only after it has been connected for one announcement period, so that after a Router restart the other Nodes have announced themselves there before traffic arrives; while none is usable, any connected Router is used. The choice MUST be a deterministic function of the target and routeKey while the set of usable Routers stays the same, so messages with one routeKey to one target keep their order; ordering across a change of that set is not guaranteed.

**RT-ADDR-03** A Router answers a Call with PEER_NOT_FOUND or UNAVAILABLE only when it did not deliver it, so the sender SHOULD resend such a Call unchanged through another connected Router it has not tried for that Call, and report the error only when none is left. This makes partial connectivity, a target connected to some Routers only, invisible to calls. A Notify gets no such answer and is not resent; a Call that failed locally, for example on connection loss, may have been delivered and is not resent either. A resent Call can overtake later messages of its key.

## 4. Forwarding and call completion

**RT-DATA-01** A Router accepts a routed message only when its source Node is the direct RPC Peer it came from, and delivers it only to the target Node attached with the requested epoch (any epoch when none was requested), addressed to that exact incarnation. Metadata, body, business identity, command, routeKey and the call ID survive forwarding; the inner RPC frame passes byte for byte apart from the call ID. A service accepts routed messages only from Routers it declared and only for its own ID and epoch.

**RT-DATA-02** The caller's RPC Node owns a routed call's pending state and timeout; a Router creates no call and keeps no correlation. The Router puts the caller's outer call ID into the forwarded request; the target replies to the Router that delivered the request, addressed to the caller's exact incarnation; that Router completes the caller's call with a native RPC response. A response for an incarnation that is no longer attached is dropped. When the Router or the caller's connection to it is lost, RPC fails the caller's pending call; a late response then has nothing to complete.

**RT-DATA-03** A Call to a Node that is not attached at the chosen Router fails at once with RPC PEER_NOT_FOUND; a refused send to an attached Node fails it with UNAVAILABLE; an exception in the target's routed handler returns HANDLER_ERROR. A Notify has no one waiting and is dropped in those cases. Routers never buffer or retry business messages: a next hop whose write buffer is full refuses the message. ACCEPTED means first-hop admission only; Router provides no end-to-end receipt. A Router SHOULD count forwarded messages and every kind of drop (target not attached, send refused, response without its caller), so silent loss is observable. Remote errors, including the Router's own, go to the response handler; local RPC failures go to the failure handler.

## 5. Broadcast

**RT-BCAST-01** Broadcast is Notify-only. The sender sends one message to one chosen Router, which delivers a copy to every Node of the service attached to it, including the sender when it serves that service, each addressed to that Node's exact incarnation. Because every service connects to every Router, one Router reaches all of them; a Node not connected to that Router misses the broadcast. There is no subscription table, aggregate result, replay or guaranteed delivery.

## 6. Lifecycle and ownership

**RT-LIFE-01** Adding a Router needs no state transfer: services connect and announce themselves. Removing a Router is the application's decision: services stop choosing it and withdraw from it; calls in flight through it fail when its connections close. A Router crash fails the calls in flight through it with RPC's connection-loss errors, and later sends choose the remaining Routers. A restarted service uses a new epoch, so traffic addressed to the old incarnation is refused or dropped.

**RT-LIFE-02** The application owns assembly and shutdown order: it closes routing, then RPC. Closing the service role stops its announcements and withdraws the Node from connected Routers; closing a Router stops relaying and forgets every announcement. Routing creates no endpoint or business executor; the service role's periodic announcement is its only background work. Accepted work has no cancellation, and a timeout leaves execution uncertain. Completion uses callbacks, not future-returning APIs.

## 7. Confirmed tradeoffs and validation

| Decision | Rationale | Reconsider when |
| --- | --- | --- |
| Stateless Router holding only per-connection announcements | Restart and scaling need no recovery protocol; no replica lag or binding conflicts | A deployment cannot connect every service to every Router |
| Sender-side key resolution | Placement is business-owned; a Router can never follow a stale binding | Many senders need one shared key directory with guarantees the business store cannot give |
| Every service connected to every Router, one hop | No Router-to-Router traffic, loops or amplification; any Router carries any message | Services × Routers connections become too many |
| Response through the delivering Router | RPC pending calls belong to one Peer, so no Router has to keep call state | RPC correlates responses across Peers |
| Periodic announcement instead of acknowledged registration | Nothing to retry, verify or restore after a Router restart | Delivery right after a quiet Router restart must be guaranteed |
| Warm-up and resend of undelivered calls at the sender | Covers Router restarts and partial connectivity without Router state; a Router's PEER_NOT_FOUND/UNAVAILABLE proves non-delivery | Notifies need the same guarantee, or retried calls must keep strict per-key order |
| Ordinary Handler on the existing Node | One identity, connection system and call-completion owner; assembly outside RPC | A concrete reusable RPC capability is missing |

Java mechanisms, defaults and packages belong to the [Java specification](OGBS-Router-Java-25-Specification-1.0.md). [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java) covers real TCP call/notify/response fields, large bodies, missing targets, broadcast, per-key ordering and spreading over two Routers, Router loss and restart, warm-up of a reconnected Router, resending calls under partial connectivity, re-withdrawal from an unused Router, incarnation checks, handler errors, malformed responses, spoofed sources, undeclared Routers and lifecycle; [RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java) covers the byte layout. The [runnable example](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) assembles one RPC Node per simulated process. Local tests are not production scale, mesh security or cross-language certification. Discovery, persistence, remote cancellation and Runtime adaptation are outside V1.
