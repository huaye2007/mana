English | [中文](README.zh-CN.md)

# managame-runtime

The single entry for a game server's business logic. Whatever starts the work (a player's request, an event, a
timer, a request from another server, an HTTP call) runs as a `GameTask` through `GameRuntime.execute`, with a
context that says where and for whom it runs. Tasks with the same domain and routing key run one at a time in
submission order; everything else runs in parallel.

```
messages ──────── Dispatcher ──┐
events ────────── EventBus ────┤
timers ────────── GameTimer ───┼──> GameRuntime.execute(context, task) ──> the domain's RouteExecutor
HTTP ──────────── HttpRoutes ──┘                                            one at a time per (domain, routingKey)
```

Depends on managame-common and managame-network (for HTTP).

## Quick start

```java
static final int PLAYER = 1, GUILD = 2, WORLD = 3;
static final byte ROLE = 1;

GameRuntime runtime = GameRuntime.builder()
        .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())   // keyed by the acting player
        .domain(GUILD, RouteExecutors.virtualThreads())                           // keyed by the message's @RouteKey(GUILD)
        .domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0))          // everything on one route
        .build();

runtime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> players.get(context.businessId()).levelUp());
```

## Contexts

Every piece of business logic runs with a `Context`:

| Field | Type | Meaning |
| --- | --- | --- |
| `domain` | int | The execution domain, such as player, guild or world; it selects the executor and the ordering rule |
| `routingKey` | long | Tasks of one domain with the same key run one at a time |
| `businessType` | byte | The kind of `businessId`, such as role or guild; 0 when there is none |
| `businessId` | long | Whom the task is for, such as the acting player |
| `metadata` | `Metadata` | Application fields carried along, such as a trace id |

- `GameContext` is the plain immutable context: `GameContext.of(domain, key)`, `GameContext.of(domain, key, type, id)`
  or `new GameContext(domain, key, type, id, metadata)`.
- Inside a task, `Context.current()` returns the running task's context; `Context.hasCurrent()` tells whether a
  task is running on this thread.
- `GameContext.copyOf(context)` is a plain copy for new work about the same subject, keeping route and metadata.
  A copy of a request cannot answer that request.
- `RequestContext` adds `reply(result)` and `fail(errorCode)` for work someone waits on. Only the first answer
  counts, and it may come from any thread, also after the task returned. If the task throws, the runtime fails
  the request with `RUNTIME_EXECUTION_ERROR`, so an accepted request is always answered.

## Domains, executors and routing keys

Each domain is registered once, with the executor that runs its tasks and the `RouteKeyResolver` that finds a
message's routing key.

**Executors** (`RouteExecutors`; a new instance per domain):

- `virtualThreads()`: one queue per routing key, drained by a virtual thread, so a blocking task holds up only its
  own key. Drained queues are cached so that a busy key does not create and drop a queue for every task:
  `virtualThreads(maxPendingPerKey, idleTimeout, maxIdleQueues)`, by default 4096 waiting tasks per key, 60
  seconds idle and 100 000 cached queues. A key over its limit is refused with `ROUTE_EXECUTOR_OVERLOADED`.
- `striped(threads)`: fixed platform threads, a key always on thread `hash(key) % threads`. No state per key, but
  a blocking task holds up every key on its thread. `striped(threads, maxPendingPerThread)`, 65 536 by default.
- Or your own `RouteExecutor`.

**Routing keys.** The resolver receives the source context and the message, and the application decides per
domain where the key comes from:

- `RouteKeys.businessId()`: the acting identity, for a player domain.
- `RouteKeys.constant(key)`: one route for everything.
- `RouteKeys.annotated(domain)`, the default of `domain(id, executor)`: the message's integral field or
  no-argument method marked `@RouteKey(domain)`, record components included. One member may route several
  domains.
- Any lambda `(source, message) -> key`. Throwing `IllegalArgumentException` means the message cannot be routed:
  the Dispatcher answers `INVALID_ROUTE_KEY`, HttpRoutes answers 400.

```java
record Donate(@RouteKey(GUILD) long guildId, int gold) {}
```

The business identity of a routed task is always the source's: a guild handler running for a player's message
has that player as `businessType` / `businessId`. `runtime.route(domain, source, message)` returns the `Route` an
entry would use.

## Handlers: annotated, registered in batches

Handlers are methods marked with annotations and registered at startup by passing the objects that declare them;
registrations do not change afterwards.

| Annotation | Called for | Entry |
| --- | --- | --- |
| `@Domain(id)` | On a class (or one method): where its handler methods run | |
| `@HandleMethod` | Messages | `Dispatcher` |
| `@EventMethod` | Events | `EventBus` |
| `@HttpMethod(path)` | HTTP requests, POST unless `method` says otherwise | `HttpRoutes` |

A handler takes one parameter, the message, matched by its exact class, and may take its context before it.
Methods are compiled to method handles at registration, so calls use no reflection. Registration throws
`IllegalArgumentException` for a missing `@Domain`, wrong parameters, two handlers for one message, or a context
type the entry cannot give.

## Dispatcher: player requests and other messages

The Dispatcher does not know any packet format. The application decodes its own packets and hands each message
over with the context it comes from and callbacks that answer in its own format.

```java
@Domain(PLAYER)
class Shop {
    @HandleMethod String buy(Buy buy) {                              // a non-null result is the answer
        return "bought " + buy.item() + " for " + Context.current().businessId();
    }
    @HandleMethod void rank(HandleContext request, AskRank ask) {    // answer later through the context
        ranks.query(request.businessId(), rank -> request.reply(rank));
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime).register(new Shop(), new Accounts()).build();
```

A gateway the application writes itself: a session per connection, its own packets, the Dispatcher only to run
them.

```java
record Session(Connection connection, long roleId) implements Context {
    public int domain() { return 0; }
    public long routingKey() { return 0; }
    public byte businessType() { return roleId == 0 ? 0 : ROLE; }
    public long businessId() { return roleId; }
    public Metadata metadata() { return Metadata.empty(); }
}

class Gateway implements ConnectionHandler {
    public void onActive(Connection connection) { connection.set(SESSION, new Session(connection, 0)); }
    public void onRead(Connection connection, Object message) {
        Packet packet = (Packet) message;
        dispatcher.dispatch(connection.get(SESSION), packet.body(),
                result -> connection.write(Packet.ok(packet.seq(), result)),
                errorCode -> connection.write(Packet.error(packet.seq(), errorCode)));
    }
    public void onInActive(Connection connection) {
        Session session = connection.get(SESSION);
        if (session.roleId() != 0) dispatcher.dispatch(session, new Logout());   // one-way; after the role's pending requests
    }
    public void onException(Connection connection, Throwable cause) { connection.close(); }
}
```

- The source is any `Context`, typically the application's session with its connection and logged-in role. A
  handler reads it as `HandleContext.source()`; a login handler can bind the role by replacing the session.
- Exactly one of `onReply` / `onFail` is called, at most once, on whichever thread answers. Give both, or neither
  for a one-way message.
- Failures: `HANDLER_NOT_FOUND`, `INVALID_ROUTE_KEY`, the runtime's refusal (`RUNTIME_CLOSED`,
  `ROUTE_EXECUTOR_OVERLOADED`), and `RUNTIME_EXECUTION_ERROR` when the handler throws.
- A complete, runnable example: `src/test/java/cn/managame/runtime/dispatch/PlayerGatewayExampleTest.java`.

### Interceptors

A `HandleInterceptor` runs before and after every handler of a Dispatcher, inside the handler's task on its
route. Each interceptor sees every handler and decides for itself, from the handler's `Method` (its annotations,
say) and the message:

```java
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
@interface Anonymous {}                                   // the application's own marker

class LoginCheck implements HandleInterceptor {
    public int before(HandleContext context, Method handler, Object message) {
        return handler.isAnnotationPresent(Anonymous.class) || context.businessId() != 0 ? SUCCESS : NOT_LOGGED_IN;
    }
}

class SavePlayer implements HandleInterceptor {
    public void after(HandleContext context, Method handler, Object message, Object result, Throwable failure) {
        if (failure == null && context.businessId() != 0) players.get(context.businessId()).saveIfDirty();
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime)
        .intercept(new LoginCheck(), new SavePlayer())
        .register(new Shop(), new Accounts())
        .build();
```

- `before` runs in the order interceptors were added. `SUCCESS` (0) goes on; any other code fails the request
  with that code (a one-way message is dropped), and then neither the handler, the later interceptors nor any
  `after` run.
- `after` runs in reverse order once the handler has returned or thrown, before its result is sent. It receives
  the result (null for void, or when the handler answers later) and the handler's exception, if any. Every
  `after` runs even when one throws.
- An exception from `before` or `after` fails the request with `RUNTIME_EXECUTION_ERROR`, as one from the handler
  does.
- Every entry that hands messages to the same Dispatcher shares its handlers and interceptors; a handler or
  interceptor tells where a message came from by `HandleContext.source()`, the context the entry passed.

## Events

```java
record LevelUp(@RouteKey(PLAYER) long playerId, @RouteKey(GUILD) long guildId, int level) {}

@Domain(PLAYER)
class Quests {
    @EventMethod void onLevelUp(LevelUp event) { ... }                        // runs on PLAYER:playerId
}
@Domain(GUILD)
class GuildLog {
    @EventMethod void onLevelUp(GameContext context, LevelUp event) { ... }   // runs on GUILD:guildId
}

EventBus events = EventBus.builder(runtime).register(new Quests(), new GuildLog()).build();
events.publish(new LevelUp(1001, 7, 10));
```

- Subscribers are registered at startup, like handlers. Each runs as a task of its own in its domain, on the key
  that domain's resolver finds, never inside the publishing task; one route receives events in publish order.
- Subscribers act for the publishing task's business identity, with its metadata; outside a task for an empty
  context.
- Events go to subscribers of their exact class, in registration order. A subscriber that cannot be routed, or
  whose task is refused, does not stop the others; the first failure is thrown afterwards.
- A context parameter is a `GameContext` (or `Context`).

## Timers

```java
GameTimer timer = new GameTimer(runtime);    // 10 ms tick; new GameTimer(runtime, tick) to change it

// In a task: on the current route, with a plain copy of its context
Scheduled expiry = timer.after(Duration.ofSeconds(30), context -> buffs.expire(context.businessId()));
// Anywhere: with a context of your own
timer.every(GameContext.of(WORLD, 0), Duration.ofMinutes(1), context -> world.tick());

expiry.cancel();
timer.close();
```

- A hashed timing wheel (Netty's `HashedWheelTimer`): adding and cancelling cost the same however many timers
  there are, which suits a timer per player. A timer fires within one tick after it is due.
- The wheel's thread only hands due tasks to the runtime; business logic runs on the route like any other task.
- Delays use the monotonic clock and are unaffected by `GameTime`.
- `every` keeps a fixed rate and fires first after one period. A firing the runtime refuses (a full route) is
  logged and skipped; once the runtime shuts down, the timer stops.

## HTTP

```java
record SendMail(String title, String text) {}
record GuildNotice(@RouteKey(GUILD) long guildId, String text) {}

@Domain(PLAYER)
class PlayerAdmin {
    @HttpMethod("/mail") String mail(SendMail mail) {          // POST; the body is the argument
        mails.send(Context.current().businessId(), mail);
        return "ok";
    }
}

@Domain(GUILD)
class GuildAdmin {
    @HttpMethod("/notice") String notice(GuildNotice notice) { // runs on GUILD:guildId, routed by the body
        guilds.get(notice.guildId()).post(notice.text());
        return "ok";
    }
}

HttpRoutes routes = HttpRoutes.builder(runtime)
        .source(request -> {                                    // whom a request acts for, as the application decides
            String role = HttpRequests.query(request, "roleId");
            return role == null ? GameContext.of(0, 0) : GameContext.of(0, 0, ROLE, Long.parseLong(role));
        })
        .codec(new JsonCodec())
        .register(new PlayerAdmin(), new GuildAdmin())
        .build();
NetworkServer.start(8080, HttpInitializer.http().contextPath("/admin").handler(routes).build());
```

- An endpoint's one parameter is its argument, which the codec reads from the request, normally from the body.
  Paths match exactly below the listener's context path, and the method is POST unless `method` says otherwise,
  for example `@HttpMethod(value = "/status", method = "GET")`.
- The argument is the message the domain's resolver routes by, so a `@RouteKey` in the body picks the route.
- Endpoints rarely need their context. When one does, `HttpRequestContext.current()` returns it, or the endpoint
  declares it first: `(HttpRequestContext context, SendMail mail)`. It gives `request()` for headers and query
  (readable until the endpoint returns), `remoteAddress()`, and `reply` / `fail` to answer later.
- `source(request -> context)` decides whom a request acts for; it runs on the connection's EventLoop and must not
  block. By default requests act for an empty context.
- Statuses: unknown path 404; another method 405 with `Allow`; an argument the codec cannot read 400, as is an
  `IllegalArgumentException` from the source or the routing; a refused task (shut down, overloaded) 503; an
  endpoint that throws 500.
- Helpers: `HttpRequests.query`, `longQuery`, `text`.

**Codec.** An `HttpCodec` reads arguments and writes results and failures. `HttpCodec.basic()`, the default, reads
a `String` parameter as the UTF-8 body, `byte[]` as its bytes and `FullHttpRequest` as the request itself, and
writes a `FullHttpResponse` as is, text as UTF-8 `text/plain` and `byte[]` / `ByteBuf` as
`application/octet-stream`; `build()` rejects endpoints taking anything else. For JSON, implement it with your
library:

```java
class JsonCodec implements HttpCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public Object decode(FullHttpRequest request, Type type) throws IOException {
        return mapper.readValue(new ByteBufInputStream(request.content()), mapper.constructType(type));
    }

    public FullHttpResponse encode(Object result) throws IOException {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(mapper.writeValueAsBytes(result)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return response;
    }
}
```

Decoding runs on the connection's EventLoop before the endpoint is called; override `encodeFailure(errorCode)` to
shape error bodies.

## Errors and shutdown

- `execute` throws `GameRuntimeException` with `RUNTIME_CLOSED` after shutdown or `ROUTE_EXECUTOR_OVERLOADED` when
  the domain's executor is full, and the task never runs. An unregistered domain throws
  `IllegalArgumentException`.
- What a task throws goes to the error handler, which logs by default (`builder.errorHandler((context, cause) -> ...)`).
- `shutdown()` refuses new tasks while accepted ones still run; `awaitTermination(timeout)` waits for them;
  `close()` does both and must not be called from a task. Close the entries (servers, timers and the like) before the
  runtime.

## Game time

`GameTime.currentTimeMillis()` and `GameTime.now(zone)` read the process-wide business clock. `setClock(Clock)`
shifts it, for tests or time-limited events, and `resetClock()` restores the system clock. It never affects
timers, delays or timeouts.

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. Tests cover ordering and refusal in the runtime and its
executors, routing keys, the Dispatcher with replies, failures and interceptors, a player gateway over real TCP, events,
timers, and HTTP routes over real connections.
