English | [中文](README.zh-CN.md)

# managame-runtime

The single entry for a game server's business logic. Whatever starts the work (a player's request, an event, a
timer, a request from another server, an HTTP call, a callback bringing a result back) runs as a `GameTask` through
`GameRuntime.execute`, with a context that says where and for whom it runs. Tasks with the same domain and route key
run one at a time in submission order; everything else runs in parallel.

```
messages ──────── Dispatcher ──┐
events ────────── EventBus ────┤
timers ────────── GameTimer ───┼──> GameRuntime.execute(context, task) ──> the domain's RouteExecutor
HTTP ──────────── HttpRoutes ──┤                                            one at a time per (domain, routeKey)
callbacks ─────────────────────┘
```

Depends on managame-common and managame-network (for HTTP).

## Quick start

```java
static final int PLAYER = 1, GUILD = 2, WORLD = 3;
static final byte ROLE = 1;

GameRuntime runtime = GameRuntime.builder()
        .domain(PLAYER, RouteExecutors.virtualThreads())                              // keyed by the acting player
        .domain(GUILD, RouteExecutors.virtualThreads(), RouteKeys.field("guildId"))   // keyed by the message's guildId
        .domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0))              // everything on one route
        .build();

runtime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> players.get(context.businessId()).levelUp());
```

## Contexts

Every piece of business logic runs with a `Context`:

| Field | Type | Meaning |
| --- | --- | --- |
| `domain` | int | The execution domain, such as player, guild or world; it selects the executor and the ordering rule |
| `routeKey` | long | Tasks of one domain with the same key run one at a time |
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
  counts, and it may come from any thread, also after the task returned. A request is always answered: if its task
  throws, the runtime fails it with `RUNTIME_EXECUTION_ERROR`, and if `execute` refuses its task, with the refusal's
  code.

### Continuing on a route

A callback runs on whatever thread delivers it, not on the route of the task that started the work, so touching a
player's state there would break the one-at-a-time rule. One rule covers every case: **when a result comes back from
another thread or another route, hand the rest to `runtime.execute(context, task)` with the context to continue on**,
the task's own or its request.

```java
// A call to another server: the answer arrives on the RPC thread, the rest runs on the player's route
peer.call(rpcRequest, (call, response) -> runtime.execute(context, _ -> rank.update(response)));

// A trade: take from the other player on their route, then give to this player back on its own
@HandleMethod void trade(HandleContext request, Trade trade) {
    runtime.execute(GameContext.of(PLAYER, trade.with()), _ -> {     // on the other player's route
        boolean taken = bags.get(trade.with()).take(trade.item());
        runtime.execute(request, _ -> {                               // back on this player's route
            if (!taken) { request.fail(NOT_ENOUGH); return; }
            bags.get(request.businessId()).add(trade.item());
            request.reply("traded");
        });
    });
}

// Only reading another player: an answer may come from any thread, so there is no way back to take
runtime.execute(GameContext.of(PLAYER, target), _ -> request.reply(profiles.get(target)));
```

The framework's part is `runtime.execute`, `GameContext.of` and the request's `reply` and `fail`; `Trade`, `bags`,
`profiles` and `NOT_ENOUGH` are the game's own, and a task that does not use its context names it `_`. The continued
task sees `Context.current()` as that context, answers its request once, and, if it throws or is refused, fails the
request with a code: nothing more to remember. Steps on two routes are not one transaction: if the way back is refused,
the request fails with the refusal's code and putting back what the first step took is the game's. On a virtual-thread
route, a blocking call such as a
database query needs no callback at all, as it holds up only its own key; a callback is for waiting on another route,
where waiting in place could leave two routes waiting on each other, or on an API that is asynchronous anyway.

## Domains, executors and route keys

Each domain is registered once, with the executor that runs its tasks and the `RouteKeyResolver` that finds a
message's route key.

**Executors** (`RouteExecutors`; a new instance per domain):

- `virtualThreads()`: one queue per route key, drained by a virtual thread, so a blocking task holds up only its
  own key. A task joins its key's queue without a lock. A drained queue stays for its key, so a busy key does not
  create and drop a queue for every task, and a sweeper drops queues idle longer than the idle timeout, so memory
  follows the keys used lately: `virtualThreads(maxPendingPerKey, idleTimeout)`, by default 4096 tasks waiting per
  key besides the running one and 60 seconds idle. A key over its limit is refused with `ROUTE_EXECUTOR_OVERLOADED`.
- `striped(threads)`: fixed platform threads, a key always on thread `hash(key) % threads`. No state per key, but
  a blocking task holds up every key on its thread. `striped(threads, maxPendingPerThread)`, 65 536 by default.
- Or your own `RouteExecutor`: `execute`, plus `shutdown` and `awaitTermination`, which finish its work from its own
  state.

**Route keys.** A domain's resolver decides, once for the whole domain, where the key of every message arriving for
it comes from; messages and methods carry no routing of their own:

- `RouteKeys.businessId()`, the default of `domain(id, executor)`: the acting player, the session's for a request and
  the publishing task's for an event. Most domains need nothing more.
- `RouteKeys.field("guildId")`: the message's integral member of that name, a record component, no-argument method
  or field. Registering a method whose message has no such member fails at startup.
- `RouteKeys.constant(key)`: one route for everything.
- Any lambda `(source, message) -> key`. Throwing `IllegalArgumentException` means the message cannot be routed:
  the Dispatcher answers `INVALID_ROUTE_KEY`, HttpRoutes answers 400.

```java
record Donate(long guildId, int gold) {}   // handled in GUILD, routed by RouteKeys.field("guildId")
```

An event or a request published outside any player's task has no acting player, so a domain routed by business id
puts it on key 0: publish a player's events from that player's tasks.

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
| `@Cron(domain, value)` | Calendar times, see [Cron](#cron); domain and route key in the annotation, no message | `GameTimer` |

A handler takes one parameter, the message, matched by its exact class, and may take its context before it.
A `@HandleMethod` returns void and answers through its context; an `@HttpMethod` may return its answer instead.
Methods are compiled to method handles at registration, so calls use no reflection. Registration throws
`IllegalArgumentException` for a missing `@Domain`, wrong parameters, a `@HandleMethod` that returns a value, two
handlers for one message, or a context type the entry cannot give.

## Dispatcher: player requests and other messages

The Dispatcher does not know any packet format. The application decodes its own packets and hands each message
over with the context it comes from and callbacks that answer in its own format.

```java
@Domain(PLAYER)
class Shop {
    @HandleMethod void buy(HandleContext request, Buy buy) {         // answer now through the context
        if (!wallets.pay(request.businessId(), buy.price())) request.fail(NOT_ENOUGH_GOLD);
        else request.reply(bags.add(request.businessId(), buy.item()));
    }
    @HandleMethod void rank(HandleContext request, AskRank ask) {    // or later, from any thread
        ranks.query(request.businessId(), rank -> request.reply(rank));
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(new Shop(), new Accounts())).build();
```

A gateway the application writes itself: a session per connection, its own packets, the Dispatcher only to run
them.

```java
record Session(Connection connection, long roleId) implements Context {
    public int domain() { return 0; }
    public long routeKey() { return 0; }
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
    public void after(HandleContext context, Method handler, Object message, Throwable failure) {
        if (failure == null && context.businessId() != 0) players.get(context.businessId()).saveIfDirty();
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime)
        .intercept(List.of(new LoginCheck(), new SavePlayer()))
        .register(List.of(new Shop(), new Accounts()))
        .build();
```

- `before` runs in the order interceptors were added. `SUCCESS` (0) goes on; any other code fails the request
  with that code (a one-way message is dropped), and then neither the handler, the later interceptors nor any
  `after` run.
- `after` runs in reverse order once the handler has returned or thrown, and receives the handler's exception, if
  any. An answer the handler gave while it ran is already on its way. Every `after` runs even when one throws.
- An exception from `before` or `after` fails the request with `RUNTIME_EXECUTION_ERROR`, as one from the handler
  does, unless the handler has answered it already.
- Every entry that hands messages to the same Dispatcher shares its handlers and interceptors; a handler or
  interceptor tells where a message came from by `HandleContext.source()`, the context the entry passed.

## Events

```java
record LevelUp(long playerId, long guildId, int level) {}   // only data: it knows no domain

@Domain(PLAYER)
class Quests {
    @EventMethod void onLevelUp(LevelUp event) { ... }                        // runs on PLAYER:the publishing player
}
@Domain(GUILD)
class GuildLog {
    @EventMethod void onLevelUp(GameContext context, LevelUp event) { ... }   // runs on GUILD:guildId, RouteKeys.field
}

EventBus events = EventBus.builder(runtime).register(List.of(new Quests(), new GuildLog())).build();
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

### Cron

```java
class DailyJobs {
    @Cron(domain = WORLD, value = "0 0 5 * * *")                                  // 05:00:00 every day, route key 0
    void resetDaily() { world.resetDaily(); }

    @Cron(domain = GUILD, routeKey = 0, value = "0 0 20 * * SAT")
    void openWar(GameContext context) { war.open(); }
}

List<Scheduled> jobs = timer.register(List.of(new DailyJobs()));                 // schedules every @Cron method
timer.cron(GameContext.of(WORLD, 0), activity.cron(), context -> activity.open()); // the same from code, e.g. from config
```

- Six fields: second, minute, hour, day of month, month, day of week. Each is `*`, a value, a range `a-b`, a list
  `a,b` or a step (`*/15`, `5/20`, `9-17/2`); months may be `JAN`–`DEC` and days of week `SUN`–`SAT` or `0`–`7`
  (0 and 7 are both Sunday); `?` is the same as `*`. When both day fields name days, a day matching either fires, as
  in Unix cron. `L`, `W` and `#` are not supported.
- The method takes nothing or its `GameContext`, `GameContext.of(domain, routeKey)`, and what it returns is ignored.
  A wrong method, a bad expression or an unregistered domain makes `register` throw
  `IllegalArgumentException`, and nothing is scheduled.
- Times are read on `GameTime`'s clock and zone, see [Game time](#game-time). A cron timer looks at `GameTime` at
  least once a second: it fires when its time has come and finds the next one after the later of now and the time
  just due; when the clock or zone has changed, it finds its next time again on the new one, so `setClock`, `setZone`
  and `resetClock` reschedule it within a second, with nothing to cancel or register again. A local time daylight
  saving skips does not fire and one it repeats fires once. A late firing runs once, times while the process was down
  are not made up, and a firing the runtime refuses is logged and skipped.

## HTTP

```java
record SendMail(String title, String text) {}
record GuildNotice(long guildId, String text) {}

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
        .register(List.of(new PlayerAdmin(), new GuildAdmin()))
        .build();
NetworkServer.start(8080, HttpInitializer.http().contextPath("/admin").handler(routes).build());
```

- An endpoint's one parameter is its argument, which the codec reads from the request, normally from the body.
  Paths match exactly below the listener's context path, and the method is POST unless `method` says otherwise,
  for example `@HttpMethod(value = "/status", method = "GET")`.
- The argument is the message the domain's resolver routes by, so in a domain routed by `RouteKeys.field`, that
  member of the body picks the route.
- Endpoints rarely need their context. When one does, `HttpRequestContext.current()` returns it, or the endpoint
  declares it first: `(HttpRequestContext context, SendMail mail)`. It gives `request()` for headers and query
  (readable until the endpoint returns), `remoteAddress()`, and `reply` / `fail` to answer later.
- `source(request -> context)` decides whom a request acts for; it runs on the connection's EventLoop and must not
  block. By default requests act for an empty context.
- Statuses: unknown path 404; another method 405 with `Allow`; an argument the codec cannot read 400, as is an
  `IllegalArgumentException` from the source or the routing; a refused task (shut down, overloaded) 503; an
  endpoint that throws 500.
- Helpers: `HttpRequests.query`, `longQuery`, `text`.

**Codec.** An `HttpCodec` reads arguments and writes results and failures. An endpoint taking a `FullHttpRequest`
gets the request itself and a `FullHttpResponse` result is sent as is: neither goes through the codec, whichever it
is. `HttpCodec.basic()`, the default, reads a `String` parameter as the UTF-8 body and `byte[]` as its bytes, and
writes text as UTF-8 `text/plain` and `byte[]` / `ByteBuf` as `application/octet-stream`; `build()` rejects
endpoints taking anything else. For JSON, implement it with your library:

```java
class JsonCodec implements HttpCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public Object decode(FullHttpRequest request, Type type) throws IOException {
        return mapper.readValue(new ByteBufInputStream(request.content()), mapper.constructType(type));
    }

    public FullHttpResponse encode(Object result) throws IOException {
        // Strings and numbers are JSON too ("ok" as the quoted "ok"), so clients always parse JSON; for plain text:
        // if (result instanceof CharSequence || result instanceof byte[]) return HttpCodec.basic().encode(result);
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
  the domain's executor is full, and the task never runs; a request is first failed with that code. An unregistered
  domain throws `IllegalArgumentException`.
- What a task throws goes to the error handler, which logs by default (`builder.errorHandler((context, cause) -> ...)`).
- `shutdown()` refuses new tasks while accepted ones still run; `awaitTermination(timeout)` waits for them;
  `close()` does both and must not be called from a task. Close the entries (servers, timers and the like) before the
  runtime, so nothing hands it work while it shuts down.
- The runtime counts no task in flight. Each domain's executor knows its own work and finishes it: `striped` through
  its ThreadPoolExecutors' own shutdown, `virtualThreads` by making no new queue and retiring each as it drains. A task
  given while an executor shuts down is refused with `ROUTE_EXECUTOR_CLOSED` or runs before termination.

## Game time

`GameTime` is the process's one business clock and time zone, where business code reads the time:
`currentTimeMillis()`, `now()` (the date and time in the game's zone), `zone()` and `clock()`. The zone is set here
once, the system's by default:

```java
GameTime.setZone(ZoneId.of("Asia/Shanghai"));   // once at startup: dates and cron times are read in it
GameTime.setClock(Clock.offset(Clock.system(GameTime.zone()), Duration.ofDays(1)));   // tests or events: a day ahead
GameTime.resetClock();                           // back to the system clock, keeping the zone
```

`setClock` replaces the clock, and the clock's zone becomes the game's. Cron timers (`@Cron`, `timer.cron`)
reschedule on a new clock or zone within a second; delays, periodic timers and timeouts use the monotonic clock and
do not move.

## Logging

Through `System.Logger`, so any backend can take it; see the workspace README.

| Logger | Logs |
| --- | --- |
| `cn.managame.runtime` | INFO: the runtime started (each domain and its executor), shutting down and closed. DEBUG: each refused task, idle route queues retired. ERROR: a task that failed, from the default error handler. |
| `cn.managame.runtime.dispatch` | INFO: the Dispatcher's handlers and interceptors. DEBUG: each message's handler, each rejected message. ERROR: a handler that could not run. |
| `cn.managame.runtime.event` | INFO: the event bus's subscribers. DEBUG: each event's subscribers. |
| `cn.managame.runtime.http` | INFO: the HTTP endpoints. DEBUG: each endpoint, each bad request. ERROR: a request that could not run or be answered. |
| `cn.managame.runtime.timer` | INFO: each `@Cron` method scheduled. DEBUG: each `timer.cron`. WARNING: a firing refused. ERROR: a firing that failed. |

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. Tests cover ordering and refusal in the runtime and its
executors, route keys, the Dispatcher with replies, failures and interceptors, a player gateway over real TCP, events,
timers, and HTTP routes over real connections.
