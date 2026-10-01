# OGBS Network Specification 1.0

**[English](OGBS-Network-1.0.md)** | [简体中文](OGBS-Network-1.0.zh-CN.md)

Document type: **Language-independent specification**. Companion: [Network Java Development Specification](OGBS-Network-Java-25-Specification-1.0.md).

Status: V1 Draft. Java implementation and validation: [Java 25 specification](OGBS-Network-Java-25-Specification-1.0.md).

<a id="1-职责与范围"></a>

## 1. Responsibilities and scope

OGBS Network defines transport establishment, message acceptance, ownership, backpressure, and lifecycle for reliable, ordered, bidirectional connections. V1 provides TCP, TLS TCP, Binary WebSocket, WSS, and an independent HTTP/1.1 server profile. UDP, KCP, and QUIC are excluded.

Internal HTTP serving follows §9, independently of the Connection/Server/Client model in §§2–7. It covers the server only; HTTP/2, a framework HTTP client, streaming responses and protocol Upgrade are outside this profile. Applications own routing, health checks, management operations, authentication and serialization. HTTP used for WebSocket Upgrade remains an implementation detail of the separate WebSocket transport.

Network does not interpret RPC handshakes, players, login, authentication, sessions, business connection mappings, pools, reconnection, heartbeats, or Runtime scheduling. Higher protocols perform their handshakes after a Network Connection is established. Network active does not mean RPC ready.

Other language implementations need not use Netty, Java generics, EventLoop, or a specific buffer type. Java types, defaults, and resource mechanisms belong in the companion Java specification.

<a id="2-连接建立"></a>

## 2. Connection establishment

**N-EST-01** Create a Connection only after the current transport is fully established and the application pipeline initializes successfully. An earlier socket/channel is not a Connection. Do not expose public CREATED, HANDSHAKING, or READY states.

| Transport | Required before creating Connection |
| --- | --- |
| TCP | Accept/connect succeeds |
| TLS TCP | TCP and TLS handshake |
| WebSocket | TCP and WebSocket Upgrade |
| WSS | TCP, TLS, and WebSocket Upgrade |

TLS, when configured, protects the byte stream below application protocols: inbound decryption precedes HTTP/WebSocket or business decoding, and outbound encryption follows their encoding. Connection delivery waits for the configured TLS handshake and peer verification. A secure endpoint must not silently fall back to plaintext when TLS configuration is missing; bindings define configuration and validation mechanisms.

**N-EST-02** Each valid attempt has exactly one terminal result: success or failure. Failure, cancellation, timeout, and success races have one winner. Failure must not produce that connection's business lifecycle callbacks. Closure after success must not reclassify establishment as failed.

**N-EST-03** Successful delivery order: create Connection → onConnected → successful connection result. An exception from onConnected goes to onException without reversing transport success. Closing inside onConnected may deliver an already closed or closing Connection as success.

Business messages arriving immediately after protocol establishment must also be delivered after onConnected; internal completion-notification scheduling must not lose these valid messages. For example, a server may send its first message inside onConnected, while its peer still receives its own onConnected before onMessage.

**N-EST-04** Establishment must terminate through failure or timeout; bindings specify timeout configuration and stage boundaries. Interrupting a synchronous wait must cancel unfinished work and release its underlying connection, leaving no unclaimed successful connection.

Implementations specify handshake deadline starting points, whether TLS time is included, and the relationship to TCP connect timeouts; no additional framework timeout-configuration API is required. Expiry closes the undelivered underlying connection without onConnected. Successful handshakes deliver immediately without waiting for the deadline.

**N-EST-05** Each connect is independent. Concurrent attempts and multiple connections to the same target are allowed. Server/Client maintain neither connection collections nor unfinished-attempt registries. Per-connection processing delivers messages and lifecycle events to the application handler.

<a id="21-四种建立流程"></a>

### 2.1 Four establishment flows

```text
TCP: TCP accept/connect completes
TLS: TCP → TLS handshake completes
WS:  TCP → HTTP Upgrade/WS handshake completes
WSS: TCP → TLS handshake → HTTP Upgrade/WS handshake completes
Common delivery prerequisites: transport complete and application pipeline initialized
Once prerequisites hold: create Connection
Then: onConnected → Client success result (no Client callback on the server)
```

Failure before establishment ends the attempt and releases the undelivered underlying connection. There is no business Connection yet: do not fabricate onConnected followed by onDisconnected to represent an attempt that never succeeded.

Bindings specify pipeline assembly versus transport establishment order. Both must succeed before Connection creation; the pipeline need not wait for the handshake to finish before assembly. Higher layers may start RPC or login exchanges in onConnected. Their completion does not redefine Network Connection or add a Network READY state.

<a id="22-成功取消与异常的竞争"></a>

### 2.2 Success, cancellation, and exception races

| Winner or event | Connection result | Lifecycle |
| --- | --- | --- |
| Cancellation/timeout/closure ends the attempt first | Failure | No Connection or business callbacks |
| Transport and application initialization complete and claim success first | Success | onConnected precedes the success result |
| onConnected throws | Still success | Report through onException |
| onConnected calls close | Still success | Delivered connection may already be unavailable |
| I/O failure or disconnection after success | Original success unchanged | Handle as an established Connection |

Establishment result and connection lifetime are separate. Success guarantees neither availability when the caller receives it nor delivery of the first business message. A synchronous binding must also reclaim connections that might otherwise go unclaimed in an interruption/success race; see the Java specification.

<a id="3-读写接纳与背压"></a>

## 3. Reads, writes, acceptance, and backpressure

**N-IO-01** Established client and server connections share one bidirectional contract. TCP is a byte stream; one inbound buffer is not necessarily one business message. Applications supply framing/codecs.

**N-IO-02** Accepted writes with a definite call order on one Connection preserve outbound order. Truly concurrent unordered calls have no defined global order. Transport order does not imply remote business execution order.

**N-WRITE-01** write has exactly three normal results:

| Result | Meaning | Ownership |
| --- | --- | --- |
| ACCEPTED | Accepted and submitted to the underlying outbound path | Network implementation |
| INACTIVE | Connection unavailable; message not submitted | Caller |
| NOT_WRITABLE | Backpressure observed; message not submitted | Caller |

**N-WRITE-02** ACCEPTED guarantees neither encoding, socket output, TCP ACK, remote receipt, nor business processing. Later failures do not change that result or authorize duplicate release/resubmission of the same ownership share.

**N-WRITE-03** Rejection must not consume, release, or modify the message. After acceptance, the caller must not modify, release, or resubmit the same ownership share. Bindings define programming-error exceptions and the ownership transfer point.

**N-WRITE-04** isWritable is an observation, not a capacity reservation. write rechecks it; subsequent backpressure cannot turn acceptance into rejection. Network does not wait for writability, add a sending queue, or retry automatically.

**N-WRITE-05** Established encoding/I/O errors go to onException. Ordinary errors do not cause Network to close automatically; business code chooses a policy. Transport protocol rules for rejecting invalid messages and closing remain applicable.

<a id="31-发送的可观察结果"></a>

### 3.1 Observable sending results

Sending has several stages:

```text
write → check active/writable → accept and transfer ownership
→ encode → underlying queue/send → remote transport receipt → remote decode → business processing
```

write answers only the acceptance question in the first three stages. Higher protocols need responses/acknowledgments to confirm remote processing. If encoding fails after ACCEPTED, Network still owns accepted resources; receipt of onException does not authorize releasing the same object.

| Scenario | Caller action |
| --- | --- |
| INACTIVE | Release, discard, or apply higher-level policy to the still-owned message |
| NOT_WRITABLE | Same; do not assume Network buffered it |
| ACCEPTED | Stop using that ownership share |
| isWritable=true, then write returns NOT_WRITABLE | Treat as rejection; no capacity was reserved |
| ACCEPTED, then connection becomes unwritable | Acceptance stands; later writes check separately |
| Programming-error exception | Follow the binding's transfer point; exceptions are not universally rejection |

<a id="32-背压的范围"></a>

### 3.2 Scope of backpressure

Network neither waits for writability nor maintains business retry queues or guarantees a strict byte-memory limit. Underlying watermarks observe backlog; several concurrent senders may be accepted before the observation changes.

Higher layers may drop disposable messages, stop production, disconnect slow peers, or implement bounded retries, considering idempotency and expiry. Network does not choose for them. Blindly retrying an ACCEPTED object violates ownership and may duplicate delivery.

<a id="4-生命周期与错误"></a>

## 4. Lifecycle and errors

**N-LIFE-01** Application callbacks for one Connection execute serially:

```text
onConnected
→ onMessage / onEvent / onException (zero or more)
→ onDisconnected (once)
→ end
```

Different connections may concurrently use the same handler. Data bound in onConnected must be visible to later callbacks.

**N-LIFE-02** close is nonblocking and idempotent, initiating underlying closure without guaranteeing accepted-message drain. isActive reflects actual underlying availability and need not become false before close returns. onDisconnected follows actual inactivity.

Before onDisconnected, deliver complete inbound messages produced while finalizing input already received, according to the binding's decoding and ownership rules. For example, an end-of-input decoder may emit its final message after the transport becomes inactive; onMessage precedes onDisconnected even though isActive is already false. This does not guarantee delivery of incomplete/invalid messages or draining of accepted outbound writes.

**N-LIFE-03** Exceptions from onConnected, onMessage, onEvent, and onDisconnected go to onException. Failure of onException receives final diagnostics only: no recursion or automatic Network closure.

**N-LIFE-04** If onDisconnected cleanup throws, one onException may immediately follow. No application callbacks occur after cleanup ends. Closure carries no framework CloseCause/ReasonCode; applications keep their own reason.

**N-LIFE-05** Transport consumes TLS/WS establishment events; they are not ordinary onEvent notifications. Pre-establishment failures go to the Client result or Server diagnostics, never onException(Connection, cause).

Diagnostics distinguish recognizable peer disconnection and protocol rejection from unknown or programming errors, avoiding repeated error stacks for common establishment failures. Bindings define classification and log levels; this does not change failure results, resource cleanup or established-connection exception callbacks.

<a id="41-回调失败的具体走向"></a>

### 4.1 Callback failure paths

```text
onMessage throws
→ onException
→ release this borrowed inbound reference
→ business policy determines connection state

onException throws
→ final diagnostics
→ no recursive onException

Underlying connection becomes inactive
→ finalize received input and deliver any complete final messages
→ onDisconnected
→ report one onException if cleanup throws
→ lifecycle ends; late errors receive diagnostics only
```

No automatic closure applies to ordinary established business/encoding/I/O errors. Invalid WS frames, Text input, and handshake failure are transport protocol cases; an invalid protocol connection need not remain open. Higher layers must distinguish stage and cause instead of treating every Throwable as the same business failure.

<a id="5-入站所有权"></a>

## 5. Inbound ownership

**N-OWN-01** onMessage borrows the message. Network releases its inbound reference after return or exception. Applications retaining it beyond the callback must acquire and eventually release independent ownership according to the binding.

**N-OWN-02** Ordinary onEvent retains the event producer's ownership rules, without automatic inbound-message release semantics.

Bindings define attribute APIs. This version requires no custom attribute registry, freeze on closure, or framework attribute clearing.

<a id="51-借用消息的三种用法"></a>

### 5.1 Three uses of a borrowed message

| Use | Responsibility |
| --- | --- |
| Read entirely inside onMessage | Do not keep the borrowed reference or access it after return |
| Pass to asynchronous business execution | Acquire independent ownership before return; release on submission failure or completion |
| Echo the same message | Acquire a separate send share; release that share if sending is rejected |

Inbound borrowing and outbound transfer are independent contracts. Writing a message does not cancel Network's inbound release. Ordinary events follow their producer's rules, not these automatic release rules.

<a id="6-server--client-生命周期"></a>

## 6. Server / Client lifecycle

**N-SERVER-01** Server start is synchronous; successful return means listening. Failure throws a binding-specific operation exception and releases owned resources. A Server starts only once; failure or closure requires a new instance to start again.

**N-SERVER-02** localAddress returns the actual listening address, supporting dynamic ports. Idempotent close stops accepts and releases owned infrastructure. It does not enumerate accepted channels. An establishment callback that observes the closed endpoint rejects delivery and closes its own channel; a delivery that already passed the open-endpoint check may complete concurrently with close.

**N-CLIENT-01** Client close rejects new attempts and releases owned infrastructure without enumerating unfinished attempts. Existing attempts finish independently through their transport result, timeout, channel closure or synchronous-wait interruption. A readiness callback observing the closed Client reports failure rather than delivering a Connection. An attempt that passed the open-endpoint check may still claim success against its own failure/cancellation; claimed success remains success.

**N-RESOURCE-01** Bindings distinguish owned and borrowed resources. Never close external resources. Server/Client keep no cross-connection collection, including during establishment. Higher layers own delivered connections and any indexing or batch closure; owned execution-resource shutdown may also close associated connections.

<a id="61-资源所有权矩阵"></a>

### 6.1 Resource ownership matrix

| Resource | Creator/provider | Closure responsibility |
| --- | --- | --- |
| Server listening endpoint | Server | Server.close |
| Undelivered establishment attempt | Its own connection operation | Reclaim on transport failure, timeout, channel closure, wait cancellation or rejection when it observes endpoint closure |
| Framework-created execution infrastructure | Server / Client | Reclaim when its owner closes |
| Injected execution infrastructure | Application | Application, never Network |
| Delivered Connection | Held by higher layer after delivery | Higher layer chooses lifetime; owned infrastructure shutdown also invalidates it |

Client may connect sequentially/concurrently to several targets. Each operation keeps only its own completion state. Player/node/session lookup belongs to higher-level mappings with disconnection cleanup.

With borrowed resources, Server closure stops listening and Client closure stops new attempts; neither actively closes established channels nor immediately cancels handshakes in progress. For example, a silent TLS peer remains subject to its handshake deadline after endpoint close; a later successful handshake is rejected if delivery observes the closed endpoint. Callers needing immediate resource-wide shutdown close resources they own. With owned resources, shutdown closes associated channels through the underlying runtime.

<a id="62-关闭方法不能互相替代"></a>

### 6.2 Closure methods are not interchangeable

Connection.close requests asynchronous closure of one connection. Server/Client.close closes entry points and owned infrastructure, with binding-defined waiting. Shutdown should stop accepts/new connections, handle in-flight messages according to the business protocol, close held connections, then reclaim shared resources.

Network does not await business responses, drain all sends, or gracefully stop RPC/Runtime. A send-then-close requirement needs an explicit completion criterion in a higher protocol or more specific transport integration.

## 7. Binary WebSocket Profile

**N-WS-01** Aggregate inbound Binary and Continuation fragments into complete binary messages before the application pipeline. Convert outbound binary buffers to Binary WebSocket Messages.

**N-WS-02** Transport handles Ping/Pong/Close without onMessage. Text is outside this profile and closes the connection. Oversized/protocol-invalid messages also close without delivering rejected business messages.

**N-WS-03** Maximum message size constrains both individual frame payloads and aggregate messages. Bindings specify a default. HTTP Upgrade content and business message limits are separate.

**N-WS-04** Match the full Server endpoint path exactly, excluding query. /game differs from /game/ and /game/child. No wildcards, route parameters, or automatic normalization.

**N-WS-05** V1 servers select no WebSocket subprotocol and add no separate business handshake. A client requiring a subprotocol fails establishment without onConnected if the server returns no matching selection. Results are local: the server may already have sent the Upgrade and completed its own establishment before receiving client closure. A subprotocol is not login, authentication or RPC readiness.

<a id="71-消息边界与端点示例"></a>

### 7.1 Message boundaries and endpoint examples

A complete WS binary message has different boundaries from a TCP byte fragment. Applications may decode further protocols inside binary messages, but cannot require one TCP callback to have the same business boundary.

| Input or endpoint | Result |
| --- | --- |
| Binary start + Continuation frames, finally complete | Deliver one aggregated binary message |
| Valid Ping / Pong / Close | Internal transport handling |
| Text | Unsupported; close |
| Each frame within limit, aggregate exceeds it | Reject and close |
| endpoint=/game, request /game?token=x | Path matches; higher layers interpret query |
| endpoint=/game, request /game/ or /game/child | No match |
| Differently encoded/spelled paths | No automatic normalization equivalence |

Maximum message size is neither a connection traffic quota nor a limit on decoded object size. HTTP Upgrade content limits are separately configured or fixed by the binding and cannot replace binary business-message limits.

<a id="72-已确认设计取舍"></a>

### 7.2 Confirmed design tradeoffs

| Choice | Rationale and effect | Revisit when |
| --- | --- | --- |
| Create Connection after transport completes | Uniform business read/write entry without public intermediate states | Introducing pre-transport business intervention |
| Three send acceptance results | Simple hot path; higher-layer delivery confirmation | Per-message send completion is required |
| Business chooses closure on ordinary errors | Protocol/business-specific handling | Default failure policy changes |
| No ConnectionManager | Player/node models define ownership | Adding a dedicated management component |
| Native transport extension points | Avoid rewrapping codecs, heartbeats, and low-level parameters | Cross-implementation extensions are needed |
| Binary WS profile | Clear payload/control-frame boundaries | Supporting text or other transports |

Old Connector/Acceptor, intermediate lifecycle states, tryWrite, custom attribute keys, and CloseCause are not unfinished V1 features. Introducing these capabilities requires an explicit new requirement, rationale, and migration-impact analysis.

<a id="8-合规验证"></a>

## 8. Conformance validation

See [Java validation](OGBS-Network-Java-25-Specification-1.0.md#9-validation-and-limits) for current tests. Coverage is implementation evidence, not certification of production capacity, every race, or cross-language interoperability.

This revision replaces Connector/Acceptor, onCreated/onRead/onClosed, tryWrite, custom ConnectionKey, CloseInfo, and automatic exception closure. No old-API compatibility layer is provided; higher integrations must migrate.

<a id="http-server-profile"></a>

## 9. Internal HTTP/1.1 server profile

**N-HTTP-01 — Independent entry.** HTTP serving has its own listener and request/response processing. It does not expose long-connection business callbacks or require a Connection abstraction. It accepts HTTP/1.1 requests; unsupported versions terminate with 505. CONNECT tunneling terminates with 405 and protocol Upgrade with 400. Optional TLS protects the HTTP byte stream and does not enable HTTP/2 negotiation. No implicit TLS context or plaintext fallback is provided.

**N-HTTP-02 — Complete requests and limits.** Deliver a request only after its headers and body have been decoded and aggregated, including chunked request content. Bindings specify configurable positive limits for the initial line, headers and aggregate body. Invalid framing, an invalid/over-limit line or headers, and missing, empty or duplicate Host fields terminate with 400; over-limit body terminates with 413. These requests do not reach application processing. Valid Expect: 100-continue receives 100 before body transmission; an over-limit declared body receives 413 immediately, and unsupported expectations receive 417. Rejections carry no body and close after the response attempt. Limits are per request, not connection quotas or global memory bounds.

**N-HTTP-03 — One final response and ordering.** Application processing returns one complete final response for each accepted request. Serialize request processing and response attempts in arrival order on each connection, including automatic 100/413/417 responses; separate connections may execute concurrently. Honor persistent connections unless the request or response asks to close. After selecting closure, do not execute later pipelined requests. Normalize complete fallback responses before outbound extension transformations; native response framing follows N-HTTP-08; HEAD transmits no body, 204 has no body or Content-Length, 205 has no body and length zero, and 304 has no body but may describe representation length. No arbitrary asynchronous completion, response streaming, Upgrade or informational application responses are included.

**N-HTTP-04 — Ownership and failure.** The request and its content are borrowed until fallback-function processing returns; extension ownership follows N-HTTP-08; the binding releases its reference afterward. Returning a response transfers ownership to the server even when a later write fails or the peer has disconnected. A response sharing borrowed request content needs an independent retained reference or copy. A thrown exception, null response or invalid application response attempts an empty 500 and closes; do not expose exception details to the peer. I/O failure closes the affected connection. A response return or write attempt does not confirm peer receipt, business persistence or exactly-once execution. No automatic retry occurs.

**N-HTTP-05 — Execution and time.** Bindings define the execution context and may accept caller-owned ordered executors. Keep transport-thread callbacks short; blocking work requires application-selected execution resources. A configurable inbound inactivity timeout closes idle or incomplete connections without a response. This is not a total request deadline, handler deadline or slow-client bandwidth guarantee: periodic bytes can keep a partial request alive. Disconnect, inactivity timeout and listener closure do not interrupt a running business operation. The application owns business deadlines and cancellation, and must tolerate a response becoming undeliverable after side effects have occurred.

**N-HTTP-06 — Lifecycle and resources.** Build captures configuration without listening; start binds synchronously once. Failed start is terminal. Close is idempotent, stops the listener and releases owned execution resources; never stop externally supplied groups. Owned transport-resource shutdown closes associated connections. With borrowed transport groups, existing accepted connections remain caller-owned and may continue serving until their own closure or timeout. Keep no connection registry or maintenance drain queue. Close does not confirm that external business work has stopped. Bindings prohibit blocking lifecycle calls from related execution threads and define argument/state/transport error representations.

**N-HTTP-07 — Scope and validation.** HTTP/1.1 is the confirmed initial protocol for ordinary internal endpoints. Reconsider HTTP/2 for a concrete integration requirement or a measured connection/response-order bottleneck. The profile adds no router, JSON framework, automatic compression, multipart facade, CORS policy, global admission limit, business queue, hard response-memory cap or production throughput guarantee. Execution queues and response sizes require application capacity planning. For example, a slow first request delays a second request's 100 Continue on the same connection even when processing uses an external executor; it must not be sent ahead of the first final response. A peer can disconnect after a business operation commits and receive no response. Source and validation: [HTTP Java binding](OGBS-Network-Java-25-Specification-1.0.md#native-http-server-api), HttpServerTest and HttpServerExample. Production capacity and cross-language interoperability remain unverified.

**N-HTTP-08 — Application extensions.** Provide a per-connection extension point between complete-request aggregation and optional fallback processing. Extensions may inspect/modify requests, forward them, consume them and produce complete responses, or transform outbound responses. A consumed request does not also reach the fallback; an unhandled request with no configured fallback receives an empty 404. Forwarding transfers the pipeline reference to the next processor; consuming extensions own its release and independently retain/copy content used in a response. Extension-produced responses must have valid framing and preserve request/response ordering. Persistent-connection and closure policy applies to them too. A custom response's length and compression encoding are the extension's responsibility; automatic full-response normalization belongs to the fallback-function path before outbound transformations. Extensions share the connection's ordered processing context and must not independently reorder responses or disable core protocol validation/limits. Bindings use their native pipeline mechanism instead of a separate router/plugin framework. Authentication, CORS and compression are application-selected policies; none is enabled implicitly. Arbitrary asynchronous response correlation is outside the current profile.
