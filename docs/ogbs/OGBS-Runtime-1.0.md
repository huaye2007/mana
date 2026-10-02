# OGBS Runtime Specification 1.0

**[English](OGBS-Runtime-1.0.md)** | [简体中文](OGBS-Runtime-1.0.zh-CN.md)

Document type: **Language-independent specification**. Companion: [Runtime Java specification](OGBS-Runtime-Java-25-Specification-1.0.md).

Status: repository draft for mana3 `game-runtime`. This document defines execution semantics, including shared business time and explicit Cron rescheduling. Types/annotations are in the [Java 25 specification](OGBS-Runtime-Java-25-Specification-1.0.md).

<a id="1-职责与边界"></a>

## 1. Responsibilities and boundaries

Runtime provides in-process business execution and Route scheduling: Handler and HTTP method dispatch, serial Route execution, context binding, local events, timers, and cross-Route calls.

It does not own connections, RPC Peer selection, ordinary-message/request business codecs, login/authentication, persistence, or distributed transactions. HTTP business result encoding belongs to its HTTP adapter. Integration constructs contexts from trusted sources and explicitly chooses Route. HTTP dispatch hands returned or deferred responses to the transport's completion capability; the transport owns actual delivery. Ordinary message dispatch leaves replies to integration.

Shared Metadata/errors: [Core](OGBS-Core-1.0.md). Business failures use business results, not Runtime framework codes.

<a id="2-route-模型"></a>

## 2. Route model

Complete Route identity:

```text
(runtime instance, routeDomain, routeKey)
```

| Field | Meaning |
| --- | --- |
| routeDomain | Explicitly registered positive in-process business-domain integer |
| routeKey | Nonzero 64-bit key; signed languages permit negatives |
| runtime instance | Runtime owning executors and contexts |

**RT-ROUTE-01**: Each registered Domain binds exactly one RouteExecutor; one Executor may serve several Domains. Domain names describe but do not identify Routes.

**RT-ROUTE-02**: Same Domain/Key tasks within one Runtime must not overlap. Different Routes may run concurrently but need not run in parallel.

**RT-ROUTE-03**: Accepted external submissions execute serially in actual per-Route enqueue order. Concurrent submitters have no additional ordering.

**RT-ROUTE-04**: Nested operations already on the same Runtime and complete Route execute directly without requeueing. They may finish before existing queued tasks. Equal Domain/Key in another Runtime does not permit inlining.

**RT-ROUTE-05**: Route serialization does not authorize other threads to access its mutable state. Cross-Route results should be immutable values or snapshots.

Serialization is not a database transaction. After a Handler initiates a cross-Route call and returns, the source may process more tasks; callbacks observe callback-time state.

<a id="21-如何划分业务状态的归属"></a>

### 2.1 Assigning business state ownership

For player Domain 1 and guild Domain 2, player 42's inventory belongs to (1,42), guild 42's members to (2,42). Equal numeric keys do not share serialization. Player Handlers may modify their own inventory; mutable guild access enters the guild Route and returns immutable results. Runtime neither derives guild IDs nor selects Domains.

Putting several players on one Route serializes them together and makes them wait for each other. Assigning one mutable state to several Routes loses protection. Route granularity is a state-ownership decision, not an executor-thread count.

<a id="22-入队顺序与内联顺序"></a>

### 2.2 Queue and inline order

A, B, and C share one complete Route:

```text
A started; B accepted and waiting
A submits C
  → C executes directly
  → C completes; restore A's Context
A continues and completes
B starts
```

Completion may be C,A,B. Same-Route serialization does not mean every API is asynchronous FIFO. A cannot wait for B, because B requires A to exit. C in another Runtime uses that Runtime's admission even with equal Domain/Key.

Routes may share threads/shards, affecting parallelism but not ownership. Same-thread execution does not authorize access to another Route's state.

<a id="3-执行器与接纳"></a>

## 3. Executors and admission

RouteExecutor results:

| Result | Meaning |
| --- | --- |
| ACCEPTED | Task admitted |
| OVERLOADED | Insufficient capacity; not admitted |
| CLOSED | Executor closed; not admitted |

**RT-EXEC-01**: Submission must not wait for capacity. Short critical sections are not capacity waits; lock-free implementation is not required.

**RT-EXEC-02**: OVERLOADED/CLOSED tasks must never execute. ACCEPTED tasks become the executor's responsibility to execute once.

**RT-EXEC-03**: Use complete Domain/Key to isolate serial boundaries, never Key alone.

**RT-EXEC-04**: Runtime decides same-Route inlining without executor admission. Business code controls recursive depth; no stack-depth protection.

**RT-EXEC-05**: Per-Route mailbox state with queued or executing work must remain active and cannot be evicted by inactivity or cache pressure. Once all work finishes, implementations may retain the empty mailbox for bounded reuse, then automatically reclaim it after idle expiry or under cache size pressure. A later submission reuses or recreates the mailbox without losing admitted work, changing enqueue order, or introducing a second concurrent consumer. Idle time starts after the latest drain completes, not when a long-running action starts. Mailbox expiry neither cancels business operations nor expires Route-owned business data.

For example, action A on Route (1,42) may run longer than the idle timeout while its waiting queue is empty. That Route remains active, and action B submitted meanwhile must wait for A. Only after both return can the empty mailbox enter idle retention. Expiry racing with action C may discard the idle mailbox, but C must still execute exactly once on the same serialization boundary. The Java binding defines defaults and maintenance timing; these are resource-retention limits, not execution deadlines. [VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java) validates reuse, automatic expiry, active-work protection, and concurrent handoff.

The repository supplies platform-thread sharded queues and virtual-thread Mailboxes with different capacity meanings; see Java specification. Task capacity is not a complete memory bound.

<a id="4-构建与注册"></a>

## 4. Build and registration

Build through explicit registration without required classpath scanning or dependency injection.

Validate and freeze:

1. Unique Domain IDs and exactly one complete Executor binding per Domain.
2. Unique protocol type+command and unique message type.
3. Correct registered request/response associations.
4. At most one Handler per message type, targeting a valid Domain.
5. Valid Handler/Event/Cron signatures.
6. At most one RouteKey extractor per message type.
7. Valid Cron expressions and target Routes.
8. Unique HTTP method+raw path, valid signatures/Domains and RouteKey rules, and an explicit context factory for endpoints without a Key rule.

**RT-BUILD-01**: Failed builds return no partially usable Runtime. After success, mutation of original registration objects cannot change protocol tables.

**RT-BUILD-02**: Protocol and RouteKey extraction registration are independent. Protocol descriptors contain no implicit Domain, Key, or codec; dispatch never automatically invokes extractors.

**RT-BUILD-03**: Successful build is immediately usable. Closed instances never restart; build another.

<a id="41-一次请求的完整接入流程"></a>

### 4.1 Complete request integration flow

```text
Integration receives/decodes message
→ validates connection identity and request
→ selects Domain and explicitly extracts/computes Key
→ constructs HandlerContext
→ dispatch validates and attempts admission
→ bind Context on target Route
→ execute Handler
→ restore previous Context
```

Protocol registration identifies messages, Key registration extracts keys, and Handler registration chooses methods. These are independent: descriptors do not add routing, authentication, or codecs. Integration may use registered extraction or authenticated player identity.

Accepted dispatch is not business success; successful Handler execution is not response delivery. Higher layers own encoding, correlation, and sending. Resubmission must not hide integration failure; infinite overload retries amplify pressure.

## 5. Context

| Context | Contents |
| --- | --- |
| Context | Domain, Key |
| InvocationContext | Context + businessIdType, businessId, Metadata |
| HandlerContext | InvocationContext + message |
| HttpContext | Context + borrowed HTTP request + response completion capability |
| EventContext | InvocationContext + event |
| TimerContext | Context |
| RouteCallContext | InvocationContext |

businessIdType is unsigned 8-bit identity category; businessId is 64-bit business identity. These serve different purposes from Route identity. Runtime does not authenticate them.

**RT-CTX-01**: Bind current context only during actual business execution. Restore the previous context after return or exception.

**RT-CTX-02**: Nested inline work uses its own context and restores the outer one. Enqueued work must not implicitly inherit undeclared submitting-thread context.

**RT-CTX-03**: Current context carries internal owning-Runtime identity to distinguish instances.

**RT-CTX-04**: Share Metadata as immutable values. Inheritance does not establish trust; integration still owns the trust boundary.

TimerContext carries no business identity/Metadata. Explicitly capture suitable immutable data when needed.

<a id="51-上下文传播矩阵"></a>

### 5.1 Context propagation matrix

| Operation | Execution context | Identity/Metadata | Custom fields |
| --- | --- | --- | --- |
| dispatch | Supplied HandlerContext | Supplied values | Preserve original instance fields |
| HTTP dispatch | Default or factory-created HttpContext | Explicit routing rule; no implicit business identity/Metadata | Preserve original instance fields |
| Event from same-Runtime InvocationContext | New EventContext | Inherit | No automatic copy |
| Event from external thread/other Runtime | New EventContext | Defaults/empty | No copy |
| Cross-Route computation | New RouteCallContext | Inherit if source is InvocationContext | No automatic copy |
| Cross-Route callback | Original source Context | Original values | Restore original instance |
| Timer / Cron | New TimerContext | None | No copy |

Restoring source Context does not restore past business state. Referenced connections/objects gain no snapshot, lifetime, or thread-safety guarantee. Explicit immutable values are needed for stability across time.

Scope covers the current action. Self-created threads and arbitrary external callbacks gain no Route execution rights, even if language mechanisms propagate thread-local values.

<a id="6-handler-分发"></a>

## 6. Handler dispatch

Handlers receive registered REQUEST/NOTIFY messages only; RESPONSE is not an inbound dispatch target.

**RT-DISPATCH-01**: Match exact message type without superclass/interface polymorphism.

**RT-DISPATCH-02**: Before submission validate Runtime state, Domain, nonzero Key, Handler existence/Domain, and context type. Expose explicit rejection to callers.

**RT-DISPATCH-03**: Once Handler executes, report its exceptions to Runtime error handling, never automatically send them as remote business responses.

Context mismatch is an integration error: a Handler requiring a custom HandlerContext subtype must receive that subtype.

<a id="7-本地事件"></a>

## 7. Local events

Event supplies target Domain/Key. Events are in-process messages without persistence, replay, or remote delivery.

**RT-EVENT-01**: Match exact event type and invoke listeners in ascending order; ties are unordered.

**RT-EVENT-02**: Enter the event's Route; same-Route publication may inline. Inherit identity/Metadata only from same-Runtime InvocationContext; otherwise use defaults/empty.

**RT-EVENT-03**: Report a listener exception but continue later listeners in that publication.

**RT-EVENT-04**: No listeners is not a missing-Handler error. Route validation and admission still apply.

Publishers must not arbitrarily mutate events while execution may still be pending.

<a id="8-跨-route-调用"></a>

## 8. Cross-Route calls

Calls comprise target Route, computation, and callback. They support local collaboration, not RPC/network timeout/remote cancellation.

**RT-CALL-01**: Source must be an executing context of the same Runtime; absent/foreign contexts cannot supply a source Route.

**RT-CALL-02**: Compute in target RouteCallContext, inheriting source InvocationContext identity/Metadata or defaults otherwise.

**RT-CALL-03**: Return success/failure to source Route and restore the exact original context object. Same-source/target callbacks may complete inline.

**RT-CALL-04**: Target validation/admission failure or computation exception goes to the source failure callback. Computation exceptions also report ROUTE_CALL_EXECUTION_ERROR.

**RT-CALL-05**: Callback exceptions only report execution error; never invoke failure callback recursively.

**RT-CALL-06**: If source executor closes/overloads, report ROUTE_CALLBACK_DISPATCH_FAILED. Never execute the callback on the wrong Route. Callback delivery is not guaranteed then.

Source may process tasks before completion. No atomic cross-Route state update. Business failure uses result objects; framework failures use framework codes.

<a id="81-跨-route-时间线与状态再检查"></a>

### 8.1 Cross-Route timeline and state revalidation

Joining a guild:

```text
Player Route: check player state, call guild Route, return Handler
Player Route: may handle logout, cancellation, or other requests
Guild Route: compute and return immutable result
Player Route: enqueue callback; restore original Context when it runs
Player Route: recheck current player state, then apply result
```

The two player sections are serial individually with intervening tasks permitted. Use business request versions/state checks to verify relevance. Runtime neither pauses source nor provides cross-Route transactions.

<a id="82-失败发生在哪一段"></a>

### 8.2 Where failure occurs

| Stage | Result | Source-side guarantee |
| --- | --- | --- |
| No valid source context | Reject call | Target computation not executed |
| Invalid/rejected target | Attempt source failure callback | Target computation not executed |
| Computation throws | Report execution error, attempt failure callback | No automatic computation retry |
| Computation returns | Attempt success callback | Business effects may already exist |
| Callback admission fails | Report callback dispatch error | Never run it on target as fallback |
| Callback throws | Report execution error | No recursive onFail |

Target failure and return-path failure are distinct. Return-path failure may occur after target effects; it does not imply nonexecution. Guaranteed eventual delivery requires higher-level durable/compensation design, absent from V1.

<a id="9-gametimetimer-与-cron"></a>

## 9. GameTime, Timer, and Cron

<a id="91-业务时间"></a>

### 9.1 Business time

**RT-TIME-01**: GameTime provides shared business wall time: epoch milliseconds, local time with explicit zone, source replacement/restoration. Current Java binding is process-wide with system UTC Clock by default.

**RT-TIME-02**: Clock changes never automatically notify, reschedule, or replay Timer/Cron. Business code explicitly cancels dynamic timers, recomputes delay from GameTime, and registers again; static Cron uses explicit CronScheduler rescheduling.

**RT-TIME-03**: Distinguish wall time from elapsed time. One-shot timers retain monotonic delays; GameTime changes must not affect network timeouts or duration metrics.

<a id="92-一次性-timer"></a>

### 9.2 One-shot Timer

**RT-TIMER-01**: Expiry only submits to target Route; business code executes under Route semantics. Delay may be zero, never negative.

**RT-TIMER-02**: Successful cancellation means cancellation won before trigger eligibility. Once triggering wins, cancellation cannot claim to retract queued work.

**RT-TIMER-03**: Timer/Cron create fresh TimerContext without creator InvocationContext inheritance.

### 9.3 Cron

**RT-CRON-01**: Validate Domain, Key, and expression at build. Calendar zone is explicit, UTC by default in this binding.

**RT-CRON-02**: Cycle: read GameTime → calculate next calendar instant → register one-shot Timer → execute on Route → recalculate. Recalculate only after the method finishes. No real-time Tick or replay of every missed instant.

**RT-CRON-03**: Report Timer/Cron business exceptions without implicit retry. Cron schedules another cycle after Route rejection or method exception.

**RT-CRON-04**: CronScheduler provides cancel/reschedule/rescheduleAll. Java keys use declaring class and method name; reject duplicates at build.

**RT-CRON-05**: cancel stops current scheduling and future cycles but lets already claimed methods finish. Its return indicates active-to-stopped transition, not interruption.

**RT-CRON-06**: reschedule cancels old scheduling and computes from current GameTime, including reactivating canceled Cron. rescheduleAll includes canceled entries and need not switch all entries atomically.

**RT-CRON-07**: Old generations cannot overwrite new rescheduling. Skip queued old business actions not yet claimed; already running old methods cannot register another old cycle afterward.

**RT-CRON-08**: Cancellation/rescheduling supplies neither business idempotency nor calendar deduplication. Explicit rescheduling may schedule the same calendar instant again; applications handle repeated effects.

Expression support is specified in Java documentation; do not infer full syntax from other Cron products.

<a id="94-时间变更示例"></a>

### 9.4 Clock-change example

At business 10:00, register a timer one hour away. Five minutes later change GameTime to 12:00. The original timer still fires after its original elapsed hour, not immediately. To follow the new wall clock, cancel it, reread GameTime, then process immediately or recompute delay.

Cron also retains already scheduled triggers until explicit reschedule recalculates with the new wall clock/zone. Missed calendar instants are not all replayed. Backward clock changes or repeated rescheduling may revisit an instant; settlement logic needs its own idempotency.

<a id="95-取消与重排的竞争边界"></a>

### 9.5 Cancellation and rescheduling races

| Moment | One-shot Timer.cancel | Cron.cancel / reschedule |
| --- | --- | --- |
| Before trigger eligibility | May cancel trigger | Cancel old schedule; reschedule may create new |
| Triggered/submitted but business not running | Cannot retract task | Skip unclaimed old-generation actions |
| Business method started | No interruption | Let finish; old generation cannot schedule again |
| Business completed | No undo | Affect future scheduling only |

Timer trigger eligibility and Cron business-execution eligibility are different layers. Cron rescheduling does not stop the entire Route; ordinary requests keep normal admission/serialization.

<a id="10-错误与关闭"></a>

## 10. Errors and closure

Numbers: [Core](OGBS-Core-1.0.md). Runtime uses 3001–3010:

| Name | Trigger |
| --- | --- |
| RUNTIME_CLOSED | Runtime closed |
| ROUTE_DOMAIN_MISMATCH | Domain unregistered or differs from Handler Domain |
| INVALID_ROUTE_KEY | Zero Key |
| ROUTE_EXECUTOR_OVERLOADED | Insufficient target capacity |
| ROUTE_EXECUTOR_CLOSED | Target executor closed |
| HANDLER_NOT_FOUND | No message Handler |
| HANDLER_CONTEXT_MISMATCH | Context fails Handler type requirement |
| ROUTE_CALL_EXECUTION_ERROR | Cross-Route computation throws |
| RUNTIME_EXECUTION_ERROR | Business/listener/callback/executor invocation error |
| ROUTE_CALLBACK_DISPATCH_FAILED | Cannot submit completion to source Route |

**RT-CLOSE-01**: Closure is idempotent, stops Timer/Cron and closes owned executors. Close shared executor objects only once across Domains.

**RT-CLOSE-02**: Reject new ordinary submissions after closure. Official executors continue admitted tasks, but close does not await all business completion.

**RT-CLOSE-03**: Closure is not a whole-service graceful shutdown barrier. Applications manage network admission, in-flight requests, and business completion first.

Callers own executors on build failure; successful build transfers lifecycle ownership to Runtime.

<a id="101-服务停机的组合顺序"></a>

### 10.1 Composing service shutdown

Runtime.close performs Runtime's own actions only. Services needing business completion usually stop new admission, await tracked requests/cross-Route work, stop scheduling/Runtime, then close downstream resources. Application waiting mechanisms are separate; close return does not prove every Handler finished.

Closure meanings differ: Connection.close initiates asynchronous disconnection; Runtime.close does not drain all business queues synchronously; Data.close synchronously processes accepted persistence. Integration must not assume all close returns mean everything has ended.

<a id="102-已确认设计取舍"></a>

### 10.2 Confirmed design tradeoffs

This table explains existing clauses without adding APIs. Continue from these baselines rather than restoring excluded models.

| Choice | Purpose/effect | Revisit trigger |
| --- | --- | --- |
| Complete Route defines serialization | Multiple Domains/Runtimes; Key alone insufficient | State ownership/routing identity changes |
| Direct same-Route execution | Avoid redundant posting; allow sync completion/recursion | Uniform async calls or stack bounds required |
| Execution-scoped Context | Avoid leakage, support nested restoration | New propagation mechanism |
| Separate extraction and dispatch | Explicit integration routing; descriptors carry no policy | Automatic routing needed |
| Callbacks return to source | Preserve serial source-state access | Guaranteed delivery needs separate design |
| Timers omit identity; clock changes do not reschedule | Explicit time/identity sources | New scheduling/replay semantics |
| Explicit frozen registration | Startup conflict detection, fixed runtime paths | Dynamic hot registration |

V1 defines no cross-process Route migration, persistent tasks, automatic retry, guaranteed callbacks, source pausing, or real-time Tick. New capabilities need behavioral clauses and compatibility analysis; ordinary optimization does not imply them.

<a id="11-实现与验证映射"></a>

## 11. Implementation and validation mapping

| Contract | Implementation/test |
| --- | --- |
| Build, Context, dispatch, events, callbacks, Timer/Cron | [RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java) |
| Route serialization, capacity, executor closure | [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java) |
| Scheduling/lifecycle | [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java) |
| GameTime replacement, zones, restoration | [GameTimeTest](../../game-runtime/src/test/java/cn/managame/runtime/time/GameTimeTest.java) |
| Cron cancellation, generations, overload, closure | [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java) |
| Build validation | [GameRuntimeBuilder](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java) |

These are current verification entries, not exhaustive enumeration of every race. Extensions need targeted admission, context restoration, closure-race, and callback-rejection tests.

<a id="runtime-http-profile"></a>

## 12. HTTP business dispatch

HTTP dispatch is an optional Runtime entry point using the same complete Route identity and admission boundary as ordinary Handlers, Events, and calls. Transport framing, connection sequencing, and response delivery remain in the [Network HTTP profile](OGBS-Network-1.0.md#http-server-profile). Java annotations and dependencies are defined in the [Java binding](OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api).

**RT-HTTP-01**: Explicitly register and freeze endpoints identified by HTTP method and raw path. Supported endpoint methods are GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, and TRACE; an unspecified method defaults to POST. GET must be selected explicitly. Ignore the query during endpoint lookup; do not decode percent escapes, normalize paths, remove trailing slashes, match templates, or infer HEAD/OPTIONS behavior. Duplicates fail at build. Unknown paths return 404; a known path with an unregistered method returns 405 and its allowed methods. The initial profile accepts origin-form request targets, rejects malformed targets with 400, and does not implement CONNECT tunneling or registration of custom method tokens.

**RT-HTTP-02**: Registration selects a registered Domain. Before Route admission, select a nonzero Key through the explicit endpoint/handler rule (RT-HTTP-07), or the application context factory if no rule exists. With a rule and no factory, create the default HTTP context containing Route, request, and response completion capability. A supplied factory receives the selected Key and must preserve it; it may add application-specific HTTP fields such as session data. The HTTP context does not define business identity or Metadata. No implicit player field or submitting-thread identity is supplied. A factory can reject explicitly without executing the method; invalid input/zero Key returns 400. An incompatible factory result is an integration failure, reported to Runtime error handling and completed as a server failure.

**RT-HTTP-03**: Execute accepted methods under the same Route executor and context scope as other Runtime entries. Same complete Route work never overlaps and nested same-Route work may inline. The HTTP context follows the base Context model rather than the invocation identity model. Under the ordinary Context inheritance rules, Events/calls from it receive default identity and empty Metadata; call callbacks restore the exact HTTP context, including its custom fields. HTTP has no framework invocation envelope carrying business identity or Metadata, and a routing Key is not authenticated identity. Application-specific fields are not automatically copied into Event/call contexts. Key extraction and factory execution occur before the Route and must not access Route-owned mutable business state.

**RT-HTTP-04**: A method either returns a business object for automatic completion or returns no result and explicitly completes later with a business object. The result contract contains no transport-specific response envelope or protocol version. First response/failure completion wins; losing objects are not encoded. Completing a response does not prove transport delivery or business success. A method exception or encoding failure is reported and attempts server-failure completion; a previously completed result is not replaced. Null is a valid object result, not an execution failure. No automatic business retry occurs.

**RT-HTTP-05**: Runtime owns one additional request reference from submission through method return/exception, releasing it also on rejection. Request access inside the method is borrowed. Later response completion or restoration of the same context does not extend that borrow: asynchronous users must independently retain/copy and release their own resources. Business results remain ordinary caller-owned values; the adapter owns encoded transport resources and releases them on failed/late delivery. Deferred result data must be independently usable after the request borrow ends.

**RT-HTTP-06**: Runtime/executor closure or overload rejects new HTTP work with 503 and no business execution. Already admitted work remains the executor's responsibility, including release of its request reference. Runtime close does not own the HTTP server or await all requests. Disconnect does not cancel admitted business actions or undo effects. Runtime imposes no deferred-response deadline; applications arrange completion and shutdown, while Network owns transport inactivity/closure. An external callback gains no Route access rights merely by holding an HTTP context.

For example, a player HTTP method starts a guild call and returns. Runtime releases its request reference and may run more player actions. The guild callback later enters the original player Route with the original HTTP context; it may recheck player state and reply, but cannot read the borrowed request. Capture immutable body data first, or own an independent retained reference. If callback admission fails, ordinary call failure reporting applies and HTTP response completion is not guaranteed.

The confirmed tradeoff is one business execution model for the HTTP entry points common to game services, with transport lifecycle and authentication still explicit. POST is the default for body-based game-service requests; GET remains an explicit query-based entry. Changing the previous GET default does not register an alias: an endpoint declared without a method now accepts POST, while GET returns 405 unless separately registered. Existing GET entries must select GET explicitly to retain their behavior. Typed method selection in the language binding prevents spelling/case mistakes, and additional method tokens require a concrete compatibility review. Exact raw paths keep endpoint selection predictable and avoid a second routing framework. Reconsider only with concrete needs for templates, body binding, or additional HTTP profiles; automatic request DTO binding, multipart/streaming dispatch, HTTP client support, guaranteed callback delivery, and production capacity certification are outside this implementation.

Implementation and validation: [RuntimeHttp](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeHttp.java), [RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java), and [RuntimeHttpExample](../../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java). Tests cover default POST, explicit supported methods, 405/Allow boundaries, matching, admission, reference ownership, completion races, context propagation, closure, and real HTTP transport integration.

**RT-HTTP-07 — Explicit Key selection.** An endpoint may override its Handler's default Key selector as a whole; an absent endpoint selector inherits the Handler rule. A selector is either an exact field name or an explicit extraction method, not both. A field selector reads one decoded query parameter for GET and one top-level JSON body property for other methods, without falling back between sources. JSON must be one complete valid UTF-8 object, with no duplicate property names or trailing value. The selected value is a nonzero signed 64-bit integer or decimal integer string; do not truncate fractions, floating-point/exponent numbers, or overflow. Missing/duplicate/invalid values return 400 before factory/admission, with no business execution. Custom extraction runs before admission, returns a nonzero Key, and may reject invalid input; unexpected failures are diagnosed and attempt server-failure completion. Once a selector is configured, failure never falls back to a class rule or factory Key. Extraction preserves the readable request body and its ownership. JSON nesting/size limits are defined by the language binding and do not replace transport limits. The Key is routing input, not authenticated business identity.

For example, a Handler defaults to field `playerId`, while one endpoint overrides to `guildId`. GET `/guild?playerId=42&guildId=73` selects Key 73; POST `/guild` with body `{"playerId":42,"guildId":73}` also selects 73. Missing guildId rejects even when playerId exists. A malformed body rejects even if the Key appears before the malformed part. [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java) covers source selection, override/failure boundaries, full JSON validation, integer limits, custom extraction, and body ownership. Explicit field selection avoids per-endpoint boilerplate without introducing automatic DTO binding or trust inference.

**RT-HTTP-08 — Business results and encoding.** Default object completion uses status 200 and UTF-8 JSON, including JSON null for a null result. Explicit asynchronous completion may supply a final numeric status without constructing a transport response. A configurable result codec may supply another media representation. Encode the winning result synchronously in the completing thread; automatic method results encode on that Route, while arbitrary external callbacks gain no Route access rights. The object must be stable during encoding and must not borrow expired request content. After successful encoding, transport sends independently owned bytes; it does not retain the business object for later serialization. Encoding errors are Runtime execution errors and attempt server failure, without retry or rollback of business effects. A later transport profile must preserve this business result contract; the current implementation still supports only the Network HTTP/1.1 profile.

For example, a method returns a player snapshot `{id, name}` rather than building an HTTP/1.1 response. A cross-Route completion supplies the same snapshot through its result callback. Both use the configured result codec and the same first-completion rule. A response received by a peer does not imply a database commit. [HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java) covers DTO/null encoding, deferred results, completion races, encoding failures, custom media representation, and unsupported transport result rejection. Protocol-specific framing remains Network's responsibility.
