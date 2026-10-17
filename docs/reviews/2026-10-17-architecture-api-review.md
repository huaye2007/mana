# Architecture and API design review (2026-10-17)

**[English](2026-10-17-architecture-api-review.md)** | [简体中文](2026-10-17-architecture-api-review.zh-CN.md)

> Non-normative record. The component specifications hold the current contracts; this page asks two questions
> about what is already built — is the module architecture right, and is the public API design sound — and
> records the evidence for each answer. It changes no contract. Companion:
> [2026-10-17 gap analysis](2026-10-17-framework-gap-analysis.md), which covers what is missing rather than
> what is shaped wrong.

## 1. Verdict

The architecture is sound where it is hardest to get right and loose where it is cheapest to fix. The
dependency graph, the buffer-ownership convention and the backpressure vocabulary are genuinely well built
(§2). Two placement decisions are questionable: HTTP/JSON living inside the business-execution module (§3.1,
deferred at the owner's direction — HTTP is not in scope for now) and the RPC↔Runtime seam being half in
Runtime's API and half in the Spring module (§3.2).

The public API is weaker than the architecture. One type actively lies about its contract (§4.1), the
dispatch facade has nine entry points distinguished by positional primitives (§4.3), and the same concepts
are spelled three different ways across modules (§4.2, §4.5, §4.7). None of this is a correctness bug; all of
it is friction that every application pays, repeatedly, and most of it is cheap to fix now and expensive
after applications ship against it.

## 2. What is right

These are not faint praise. Each one is a decision that frameworks commonly get wrong.

**2.1 The dependency graph is clean, and reality matches the diagram.** Collecting every
`import cn.managame.*` per module gives exactly the declared graph, with no cycles:

| Module | Depends on |
| --- | --- |
| game-core | *nothing* (no third-party dependency either) |
| game-network | *nothing* |
| game-rpc | core, network |
| game-router | rpc |
| game-runtime | core, network |
| game-data | core |
| game-spring | core, network, rpc, runtime, data |

game-rpc does not know about Runtime; game-runtime does not know about RPC; game-data knows only Core. The
integration module is the only one that knows everything, which is where that knowledge belongs.

**2.2 The Router composes instead of adding infrastructure.** `GameRouter` is an `RpcHandler` installed on
the application's own `RpcNode`: no second listener, no second thread pool, and its own Javadoc is explicit
that it runs "on the Netty thread that received it; there is no background thread". *(Superseded the same
day: the two-tier mesh redesign gives the Router one daemon thread for its periodic location reports, and
forwarding still runs on the receiving Netty thread — see
[2026-10-17 Router mesh redesign](2026-10-17-router-mesh-redesign.md).)* A relay that needed its
own transport and executor would have doubled the operational surface. It does not.

**2.3 Buffer ownership is one convention, stated the same way everywhere.** `RpcHandler.onRequest`: "Body is
borrowed. Retain/copy before saving it or transferring it to reply()." `ConnectionHandler.onMessage`:
"Reference-counted messages are borrowed and released after this callback." `RouterHandler`: "Requests and
responses are borrowed during the callback." Reference-counted buffers across three modules with one rule and
no exceptions is the single most error-prone thing in a Netty-based framework, and it is consistent.

**2.4 Backpressure has a shared vocabulary.** Three admission enums, three modules, one shape:

```java
WriteStatus       { ACCEPTED, INACTIVE, NOT_WRITABLE }        // game-network
RpcSendStatus     { ACCEPTED, PEER_NOT_FOUND, UNAVAILABLE }   // game-rpc
RouteExecuteStatus{ ACCEPTED, OVERLOADED, CLOSED }            // game-runtime
```

Every one leads with `ACCEPTED`, every one means "admitted locally, not delivered or executed", and
`RpcSendStatus` says so in its Javadoc. A reader who learns one has learned all three.

**2.5 Builders are one pattern.** Seven builders, all `fluent setters → build()` returning the product, all
validating in `build()`. Deviations are listed in §4.7 and they are small.

**2.6 Error-code allocation has a single source.** Core owns the numbers and the ranges; `RpcErrorCodes`
derives its constants from `FrameworkErrorCodes` rather than restating them, so the two cannot drift. (The
remaining problem is that only RPC has such an alias — §4.5.)

## 3. Architecture: the two placement decisions worth revisiting

### 3.1 The business-execution module owns HTTP and JSON, and loads Jackson whether you use it or not

> **Deferred 2026-10-17 at the owner's direction: HTTP is not being looked at for now.** The finding is kept
> for the record and is not to be re-raised as an action until HTTP comes back into scope. Nothing below is
> known to be wrong; it is simply not being acted on.

game-runtime is "Route execution, Context, Handler, Event, GameTime, Timer/Cron" — and also HTTP. The HTTP
surface is 15 files and about 530 of the module's 2,598 lines (roughly a fifth), and jackson-databind is
game-runtime's only third-party dependency, used by exactly three files, all of them HTTP.

That alone would be a judgement call. What makes it a defect is that the dependency is not even deferred:

```java
// GameRuntimeBuilder
private HttpResultCodec  httpResults  = HttpResultCodec.json();
private HttpRequestCodec httpRequests = HttpRequestCodec.json();
```

`HttpResultCodec.json()`'s body runs `JsonSupport.MAPPER.writer()`, and `JsonSupport.MAPPER` is a static
`new ObjectMapper()`. `HttpRequestCodec.json()` returns `JsonHttpRequestCodec.INSTANCE`, whose instance field
is the same mapper. So **constructing any `GameRuntimeBuilder` loads jackson-databind and builds an
ObjectMapper**, including on a pure RPC service node or a TCP-only game node that will never serve an HTTP
request.

Consequences, in order of cost:

1. jackson-databind cannot be marked `<optional>true</optional>`, because the eager field initializer would
   fail with `NoClassDefFoundError` at builder construction rather than at first HTTP use.
2. Every Runtime consumer inherits a JSON library in its dependency tree and an ObjectMapper in its startup
   path, for a feature it may not use.
3. This is the mechanical reason the 2026-10-14 review deferred splitting out a `game-runtime-http` module
   ("without Caffeine, the remaining dependencies are Jackson and network"). The split looks blocked by the
   dependency, but the dependency is only unavoidable because the default is eager.

The cheap fix is not the module split. Hold the two defaults as suppliers, or resolve them in `RuntimeHttp`
when HTTP is actually configured; then Jackson becomes genuinely optional and the module split becomes a
later, free choice rather than a prerequisite.

### 3.2 The RPC↔Runtime seam is split across three places, and only the Spring path is shipped

game-runtime declares no dependency on game-rpc — correctly, since that keeps the two independent. But
Runtime's public API already contains RPC:

- `GameRuntime.dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message)`
- `RpcHandlerContext` with `sourceNodeId()`, `sourceSlotId()`, `command()`, `requestId()`
- `DefaultRpcHandlerContext`, public, validating those same fields

So Runtime models RPC ingress without being able to name a single RPC type: the envelope arrives as seven
loose primitives. Meanwhile the actual adapter — turning an `RpcHandler` callback into a Runtime dispatch and
a reply — is 208 lines in `game-spring` (`GameRpc`, `GameRpcCodec`).

Two things follow. First, the knowledge is in three places (RPC's shape in Runtime's signatures, the wiring
in Spring, the contract in two specifications) when it could be in one adapter. Second, and more practically:
**an application that does not use Spring has no shipped way to connect RPC to the Runtime.** It must write
that adapter itself, against a nine-primitive method. game-spring is documented as optional; this particular
piece is not optional in practice.

The seam itself is right — RPC and Runtime should not depend on each other. The question is whether
`dispatchRpc` and `RpcHandlerContext` belong in Runtime's API at all, or in a small transport-neutral
adapter module that depends on both and that a non-Spring application can also use. Note that
`dispatchRpc` is not redundant with `dispatch(HandlerContext)` even though `DefaultRpcHandlerContext` is
public: the context entry requires the caller to know the Domain, while `dispatchRpc` resolves it from the
registered Handler. That asymmetry is itself part of §4.3.

### 3.3 Three different two-phase initialisation idioms

Objects that cannot be fully constructed in one step appear in three modules, each solving it differently:

| Type | Idiom | Invalid state reachable as |
| --- | --- | --- |
| `RpcNode` | `build()` then `start()` | `IllegalStateException("RPC node is not running")` |
| `GameRouter`, `ServiceRouting` | constructor takes the handler, `start(RpcNode)` takes the node | `volatile RpcNode rpc` null + `IllegalStateException("Router not running")` on every data path |
| `SingleRepository` etc. | framework calls package-private `initialize(...)` after reflective construction | `DataOperationException("Repository is not initialized")` |

The Router case is the costly one, because the cycle is real: the Node needs the Router as its handler, and
the Router needs the Node to send. The current resolution leaves a nullable volatile field read on every
relay and a state check in every public method. A handler-factory on `RpcNodeBuilder` (`handler(node -> …)`)
would close the cycle at build time and remove the whole "not running" class of failures. This is a design
observation, not a defect: the current code is careful and its races are handled.

### 3.4 Data's repositories are instantiated by the framework, which forecloses constructor injection

`SingleRepository<K, E>` is an abstract class the application subclasses with no members; `GameData`
constructs it reflectively (no-arg constructor, direct parameterized base) and injects `meta`, `mapper`,
`writer` and `cache` through a package-private `initialize`. Every public method is `final`, which is right.

The cost is already documented in game-demo's README: "Constructor dependency injection is not supported
because GameData constructs these instances." A second cost is not documented: a repository cannot be
constructed in a unit test without a `GameData`, so repository-level business logic is only testable through
the full Data stack. Spring Data solves the same problem with interfaces and generated implementations, which
keeps construction with the container. Whether that trade is worth revisiting is the owner's call; it should
at least be a recorded trade rather than an implementation consequence.

## 4. API design: the concrete defects

### 4.1 `RpcCallback<T>` advertises a callback and is used as an opaque token

```java
@FunctionalInterface
public interface RpcCallback<T> { void onResponse(T response); }
```

The RPC core never calls `onResponse`. `RpcNode` only hands the object back:

```java
invoke(() -> handler.onResponse(peer.nodeId, pending.command, response, pending.callback));
```

and `RpcHandler` receives it as `RpcCallback<?>`, so the type parameter cannot be recovered without a cast.
Every implementation in the tree confirms the shape is wrong:

| Implementation | `onResponse` body |
| --- | --- |
| `ServiceRouting.Call` | `public void onResponse(Object ignored) {}` |
| `RpcNodeTest`, `RpcResilienceTest` | `value -> {}` |
| `RouterIntegrationTest` | `@SuppressWarnings("unchecked") RpcCallback<Integer> completion = (RpcCallback<Integer>) callback;` |
| `GameRpc.Completion<T>` | the only one that is invoked — by game-spring, on its own object |

So in the RPC core this is an attachment: a token the caller supplies and the handler recognises. Nothing is
wrong with that design — the 2026-10-14 review considered typed attachments and rejected them for good
reasons. What is wrong is that the type is shaped like a callback. It declares a method the framework never
invokes, carries a type parameter the framework erases, forces `@SuppressWarnings("unchecked")` at the one
place that wants the type, and teaches every implementer to write an empty method body. An attachment should
look like one: a marker interface (or `Object`) named for what it is, with `onResponse` living on the type
game-spring actually completes.

### 4.2 Failure callbacks have three names and success has two

| Interface | Success | Failure |
| --- | --- | --- |
| `RouteCallback<T>` | `onSuccess(T)` | `onFail(int)`, `onFail(int, Throwable)` |
| `ConnectCallback` | `onSuccess(Connection)` | `onFailure(Throwable)` |
| `RpcHandler` | `onResponse(...)` | `onFail(...)` |
| `RouterHandler` | `onResponse(...)` | `onFail(...)` |
| `ConnectionHandler` | `onMessage(...)` | `onException(...)` |
| `RpcCallback<T>` | `onResponse(T)` | — (see §4.1) |

`onFail` / `onFailure` / `onException` for the same role, in one framework, is the kind of inconsistency a
user hits on day one and remembers. `onException` is arguably distinct (a transport error, not a result), but
`onFail` versus `onFailure` has no defence. Pick one and rename the others while the API is still
pre-release.

### 4.3 `GameRuntime` has nine dispatch entry points separated by positional primitives

The interface's own Javadoc has to disambiguate them: "Dispatch has three primary client entries (explicit
Key, configured context factory or message extraction, and extraction with explicit identity) plus the
explicit-context and RPC entries; the remaining overloads are shorthands". When an overload set needs a
paragraph, the set is the problem.

Two specific hazards, not just verbosity:

**Call sites are indistinguishable.** These are different operations:

```java
runtime.dispatch(connection, 99L, message);              // 99 is the routeKey
runtime.dispatch(connection, 1, 10001L, message);        // 1 is businessIdType, 10001 the businessId
```

Both read as "connection, number(s), message". game-demo's README has to spell out which is which. A
`RouteKey` and `BusinessId` wrapper, or a small ingress record, would make the call site self-describing —
and note that the framework already decided the opposite way for a related case, which is defensible, but it
leaves the cost here.

**Behaviour depends on builder configuration, invisibly.** `dispatch(Connection, Metadata, Object)` uses the
configured `HandlerContextFactory` *or* message-key extraction depending on whether a factory was configured,
while `dispatch(Connection, int, long, Object)` "bypasses the factory". The same source line changes meaning
when someone edits the builder in another file.

`dispatchRpc` is the extreme case: nine parameters, seven of them primitives in a row
(`int, int, int, int, long, int, long`). Transposing `sourceSlotId` and `command`, or `businessIdType` and
`businessId`, compiles and misroutes. The framework's own test shows how that reads at a call site:

```java
// TransportContextTest
runtime.dispatchRpc(10, 2, 2, 33, 100, 1, 100, metadata, new RpcMessage(…));
```

Seven consecutive numbers, two of them the same value with different meanings, and nothing at the call site
says which is the slot, the command, the request id, the route key or the business id. There are only two
callers of this method in the whole repository — this test and `GameRpc` — and it is already hard to read in
both.

### 4.4 The three repositories disagree with each other, and the disagreement is load-bearing

| | `SingleRepository` | `GroupRepository` | `LogRepository` |
| --- | --- | --- | --- |
| read | `get(K)` | `getGroup(GroupKey)`, `get(GroupKey, K)` | — |
| insert | `void insert(E)` | `void insert(E)` | **`boolean insert(E)`** |
| update | `void update(E)` | `void update(E)` | — |
| delete | **`void delete(K key)`** | **`void delete(E entity)`** | — |

Two problems. `delete` takes a key in one sibling and an entity in the other, which is a silent misuse
hazard in exactly the place where getting it wrong deletes the wrong thing. And `insert` returns `boolean` in
one of three siblings.

The `boolean` is not an oversight — it is the only one of the three that can refuse, because `LogRepository`
is the only one with a capacity (`logQueueCapacity`, default 100,000). The other two cannot refuse because
the dirty set behind them is unbounded. That connects directly to
[§3.1 of the gap analysis](2026-10-17-framework-gap-analysis.md): **bounding the dirty set will force
`insert`/`update` to grow a refusal signal, which is a breaking change to the most-used methods in the Data
API.** Deciding the return convention now — before applications are written against `void` — costs nothing;
deciding it later costs every caller.

### 4.5 Only RPC gets namespaced error-code aliases

`RpcErrorCodes.TIMEOUT` is an alias for `FrameworkErrorCodes.RPC_TIMEOUT`, derived rather than restated, so
the values cannot drift. But Runtime and Data have no equivalent: `DefaultGameRuntime`, `RuntimeTimers` and
`WriteBehindManager` all import `FrameworkErrorCodes` directly. So RPC codes have two public spellings and
every other component's have one, and a log line printed by `FrameworkErrorCodes.describe(2003)` reads
`RPC_TIMEOUT(2003)` while the constant the reader compares against is spelled `RpcErrorCodes.TIMEOUT`. Either
give every component the alias or drop the one that exists.

### 4.6 `HttpResultCodec.encode` throws raw `Exception`

> **Deferred 2026-10-17 at the owner's direction, with §3.1: HTTP is not being looked at for now.**

```java
@FunctionalInterface
public interface HttpResultCodec { byte[] encode(Object result) throws Exception; }
```

A public functional interface declaring `throws Exception` forces every caller into `catch (Exception)`,
which swallows `InterruptedException` and any unrelated checked exception along with the encoding failure. A
dedicated unchecked codec exception, or `IOException`, would keep the failure legible.

### 4.7 Two builders put `builder()` on the builder instead of the product

`RpcNode.builder()`, `NetworkServer.builder()`, `NetworkClient.builder()`, `HttpServer.builder()` and
`Metadata.builder()` are on the product. `GameDataBuilder.builder()` and `GameRuntimeBuilder.builder()` are on
the builder class, so the reader has to know the builder's name to start. `GameRuntime` is an interface, but
Java has allowed static interface methods since 8, so nothing forces the exception.

### 4.8 A nullable accessor documented only in prose, next to a sibling that validates everything

`ClientHandlerContext.connection()` is documented as "the connection is borrowed and **may be absent**", and
`DefaultClientHandlerContext` stores it with no null check. Its sibling `DefaultRpcHandlerContext` validates
`sourceNodeId != 0`, `sourceSlotId` in 0..254 and `command != 0` in the constructor. So in the same package,
one context rejects every invalid field and the other accepts a null in the field business code reaches for
most. Business code must null-check on the strength of a Javadoc sentence.

### 4.9 Minor: the Context family is 15 types for six shapes

Eight interfaces (`Context`, `InvocationContext`, `HandlerContext`, `ClientHandlerContext`,
`RpcHandlerContext`, `EventContext`, `RouteCallContext`, `TimerContext`) and seven `Default*` classes. Most of
the layering earns its place, and `TimerContext extends Context` rather than `InvocationContext` is a
*meaningful* narrowing — a timer genuinely has no business identity or metadata, and the type says so.
`RouteCallContext extends InvocationContext {}` adds nothing and is a pure marker; nothing in the framework
dispatches on it, though application code can use it with `instanceof` to tell a cross-route call frame from
a handler frame. This is noted for completeness, not as something to change.

## 5. Suggested order

Grouped by whether the cost rises with time.

**Decide before applications ship against it** — each of these is a rename or signature change that is free
now and breaking later:

1. Reshape `RpcCallback` into what it is: an attachment type, with the completion method on the type that
   actually completes (§4.1).
2. Settle the failure-callback name, one spelling across all interfaces (§4.2).
3. Settle the Data write-result convention, knowing that bounding the dirty set will need a refusal signal;
   and align `delete` on key-or-entity across `SingleRepository` and `GroupRepository` (§4.4).
4. Decide whether `dispatchRpc` and `RpcHandlerContext` stay in Runtime's API or move to an adapter that a
   non-Spring application can use too (§3.2).
5. Narrow or wrap the dispatch overload set, at minimum `dispatchRpc` (§4.3).

**Cheap and non-breaking, do whenever:**

6. Move `builder()` onto `GameData` and `GameRuntime` (§4.7).
7. Validate or `Optional`-wrap `ClientHandlerContext.connection()`, or state the guard in the Runtime Java
   specification (§4.8).
8. Give every component an error-code alias, or remove `RpcErrorCodes` (§4.5).

**Design decisions, no action implied:**

9. Whether `RpcNodeBuilder` should take a handler factory, closing the Router's construction cycle (§3.3).
10. Whether Data repositories should be container-constructed rather than framework-constructed (§3.4).

**Deferred 2026-10-17 at the owner's direction — HTTP is not in scope for now:**

- Making the Runtime's HTTP codec defaults lazy and marking jackson-databind optional (§3.1), together with
  the `game-runtime-http` split that it would unblock.
- Replacing `throws Exception` on `HttpResultCodec.encode` (§4.6).

Both stay recorded with their evidence. Revisit when HTTP comes back into scope, or sooner if a consumer
needs game-runtime without a JSON library.

## 6. Verification status of this record

The dependency table in §2.1, the file and line counts in §3.1, the implementation table in §4.1, the
callback-name table in §4.2, the repository table in §4.4 and the builder survey in §4.7 were each produced
by reading the sources in this tree, and every quoted signature and Javadoc sentence is copied from them.
Nothing here was measured at runtime: §3.1's claim that building a `GameRuntimeBuilder` initialises an
ObjectMapper is read from the field initialisers and the static field they reach, not from a profiler or a
classloading trace. No API change is proposed as urgent, and none of the findings is a correctness bug — §5
sorts them by whether delay makes them more expensive, not by severity.
