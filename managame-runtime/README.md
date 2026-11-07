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
        .domain(GUILD, RouteExecutors.virtualThreads())                              // handlers name the key: routeKey = "guildId"
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
  code. `fail(errorCode, args...)` adds strings, such as the values the code's message shows, for the source to place
  in its format; an HTTP request shows a text instead, which its codec makes from them.

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

**Route keys.** A domain's resolver gives the key of every message arriving for it, once for the whole domain:

- `RouteKeys.businessId()`, the default of `domain(id, executor)`: the acting player, the session's for a request and
  the publishing task's for an event. Most domains need nothing more.
- `RouteKeys.constant(key)`: one route for everything.
- Any lambda `(source, message) -> key`. Throwing `IllegalArgumentException` means the message cannot be routed:
  the Dispatcher answers `INVALID_ROUTE_KEY`, HttpRoutes answers 400.

A handler whose message carries its key, such as the guild a donation goes to, names that member in its annotation
instead, whatever each message calls it:

```java
@Domain(GUILD)
class GuildHandle {
    @HandleMethod(routeKey = "guildId")         // GUILD:the guild donated to
    void donate(HandleContext request, Donate donate) { ... }
    @HandleMethod(routeKey = "targetGuildId")   // another message names it otherwise
    void apply(HandleContext request, ApplyReq apply) { ... }
}
```

- `routeKey` names a member of the method's message: a no-argument method of that name, else its getter
  (`getGuildId()`, as protobuf and JavaBeans name it), else a field, of an integral type or its box. It is compiled
  into a method handle when the method is registered, so a misspelt or non-integral member fails at startup, and
  reading it costs one method-handle call per message.
- Only the methods that need it name one; the others, in the same domain or class, run on the domain's key.
- The key keeps one meaning per domain: in a guild domain every method must route by a guild's id, whatever its
  message calls it, or tasks of different guilds share a route and tasks of one guild stop running one at a time.
  The framework cannot tell which id a member holds.

An event or a request published outside any player's task has no acting player, so a domain routed by business id
puts it on key 0: publish a player's events from that player's tasks.

The business identity of a routed task is always the source's: a guild handler running for a player's message
has that player as `businessType` / `businessId`. `runtime.route(domain, source, message)` returns the `Route` an
entry would use for a handler that names no `routeKey`.

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
A `@HandleMethod` returns a `HandleResult`, or void or null when it answers later through its context. An
`@HttpMethod` returns an `HttpResult`, any type of its own that the codec writes, or a `FullHttpResponse`, or void or
null likewise.
Methods are compiled to method handles at registration, so calls use no reflection. Registration throws
`IllegalArgumentException` for a missing `@Domain`, wrong parameters, a `@HandleMethod` that returns anything else, a
`routeKey` its message lacks, two handlers for one message, or a context type the entry cannot give.

## Dispatcher: player requests and other messages

The Dispatcher does not know any packet format. The application decodes its own packets and hands each message
over with the context it comes from and a callback that answers in its own format.

```java
@Domain(PLAYER)
class Shop {
    @HandleMethod HandleResult buy(HandleContext request, Buy buy) {    // answer with what it returns
        if (!wallets.pay(request.businessId(), buy.price())) return HandleResult.fail(NOT_ENOUGH_GOLD, String.valueOf(buy.price()));
        return HandleResult.ok(bags.add(request.businessId(), buy.item()));
    }
    @HandleMethod void rank(HandleContext request, AskRank ask) {       // or later through the context, from any thread
        ranks.query(request.businessId(), rank -> request.reply(rank));
    }
    @HandleMethod HandleResult sell(HandleContext request, Sell sell) { // or with what the game's logic returns
        return market.sell(request.businessId(), sell.item());
    }
}

class Market {
    HandleResult sell(long roleId, int item) {
        if (!bags.remove(roleId, item)) return HandleResult.fail(NOT_ENOUGH_ITEMS, String.valueOf(item));
        return HandleResult.ok(new SellResult(item, wallets.add(roleId, prices.of(item))));
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime).register(List.of(new Shop(), new Accounts())).build();
```

A gateway the application writes itself: a session per connection, its own packets, the Dispatcher only to run
them.

```java
record Session(Connection connection, long roleId) {}                 // the application's own, no Context

class Gateway implements ConnectionHandler {
    public void onActive(Connection connection) { connection.set(SESSION, new Session(connection, 0)); }
    public void onRead(Connection connection, Object message) {
        Packet packet = (Packet) message;
        dispatcher.dispatch(context(connection.get(SESSION), packet), packet.body(), result -> {
            if (result.data() instanceof LoginOk ok) connection.set(SESSION, new Session(connection, ok.roleId()));
            connection.write(new Packet(packet.seq(), result.code(), result.args(), result.data()));
        });
    }
    public void onInActive(Connection connection) {
        Session session = connection.get(SESSION);
        if (session.roleId() != 0) dispatcher.dispatch(context(session, null), new Logout());   // one-way; after the role's pending requests
    }
    public void onException(Connection connection, Throwable cause) { connection.close(); }

    /** Whom a message acts for, the session's role on its own route, with what the packet carries along. */
    private static GameContext context(Session session, Packet packet) {
        long role = session.roleId();
        Metadata metadata = packet == null ? Metadata.empty() : packet.metadata();
        return role == 0 ? new GameContext(LOGIN, 0, (byte) 0, 0, metadata) : new GameContext(PLAYER, role, ROLE, role, metadata);
    }
}
```

- The source is the context a message comes in with, which the entry builds for each message: who acts, such as
  the role a connection is logged in as, and the message's metadata, such as a trace id. The handler gives the domain,
  and its routeKey member or the domain's resolver the route key. A session is the application's own and stays out
  of the runtime; the entry binds a role to it, on the answer to a login say.
- The answer goes back through the callback, which holds the way back (the connection, the packet's sequence
  number), so a context needs no connection, also for an answer given later from another thread. Pushing to a
  player outside a request, from a timer or another player's route, goes through the application's own map of roles
  to sessions, kept by its gateway.
- The callback is called at most once, on whichever thread answers, and must not block. Leave it out for a one-way
  message.
- It receives a `HandleResult` for every outcome, `code`, `data` and `args`: `SUCCESS` with the result, or a failure's
  code with its args as they are, an empty array when it gave none, as the framework's own failures do. Where they
  go in the packet is the application's, a failure's body for instance.
- A handler fails by returning `HandleResult.fail(code, args...)`, and the logic below it hands that up as it hands up
  its results. An exception is a bug, not a failure: it answers `RUNTIME_EXECUTION_ERROR` and goes to the error handler.
- Failures: `HANDLER_NOT_FOUND`, `INVALID_ROUTE_KEY`, the runtime's refusal (`RUNTIME_CLOSED`,
  `ROUTE_EXECUTOR_OVERLOADED`), and `RUNTIME_EXECUTION_ERROR` when the handler throws.
- A complete, runnable example: `src/test/java/cn/managame/runtime/dispatch/PlayerGatewayExampleTest.java`.
- Every entry that hands messages to the same Dispatcher shares its handlers.

## Interceptors

A `TaskInterceptor` runs before and after every task of the runtime, whichever entry gave it: a Dispatcher's
handler, an event, an HTTP endpoint, a timer or a Cron, or work continued on a route with `runtime.execute`, which
is a task of its own. It runs on the task's route and thread, with `Context.current()` the task's context. Each
interceptor sees every task and decides for itself, by its domain or the kind of its context:

```java
class LoginCheck implements TaskInterceptor {             // only the login domain's handlers run before login
    public boolean before(Context context) {
        if (context instanceof HandleContext request && context.domain() != LOGIN && context.businessId() == 0) {
            request.fail(NOT_LOGGED_IN);
            return false;                                     // the task does not run
        }
        return true;
    }
}

class SavePlayer implements TaskInterceptor {             // whatever changed the player: a request, a timer, an event
    public void after(Context context, Throwable failure) {
        if (context.domain() == PLAYER && failure == null) players.get(context.routeKey()).saveIfDirty();
    }
}

GameRuntime runtime = GameRuntime.builder()
        .domain(PLAYER, RouteExecutors.virtualThreads())
        .domain(LOGIN, RouteExecutors.virtualThreads())
        .intercept(List.of(new LoginCheck(), new SavePlayer()))
        .build();
```

- `before` runs in the order interceptors were added, then the task, then `after` in reverse order. Only an
  interceptor whose `before` returned true has its `after` run, so what a `before` sets up, such as a trace id in the
  logging context, an `after` always takes down. Every `after` runs even when one throws.
- A `before` that returns false refuses the task: it and the later `before`s do not run, and nothing reaches the error
  handler, since a refusal is no error. The interceptor answers a request it refuses, as `LoginCheck` does, or else it
  fails with `RUNTIME_EXECUTION_ERROR`, since a request is always answered.
- `after` receives what the task threw, or a later `before`, or null, as when a later `before` refused the task. An
  answer the task gave while it ran is already on its way.
- An exception from `before` or `after` is handled as one from the task: a request fails with
  `RUNTIME_EXECUTION_ERROR`, unless it was answered already, and the exception goes to the error handler. A `before`
  that throws also keeps the task and the later `before`s from running.
- With none added the runtime runs tasks as they are; each one added costs a task its two calls
  (`ExecuteThroughputBenchmark.executeIntercepted` measures one that does nothing).
- Checks that need the message itself, such as limits per message kind, belong in the application's entry before it
  dispatches, where the message and the packet's command are at hand, or in the handler.

## Events

```java
record LevelUp(long playerId, long guildId, int level) {}   // only data: it knows no domain

@Domain(PLAYER)
class Quests {
    @EventMethod void onLevelUp(LevelUp event) { ... }                        // runs on PLAYER:the publishing player
}
@Domain(GUILD)
class GuildLog {
    @EventMethod(routeKey = "guildId")                                         // runs on GUILD:guildId
    void onLevelUp(GameContext context, LevelUp event) { ... }
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
    @HttpMethod(value = "/notice", routeKey = "guildId")     // runs on GUILD:guildId, read from the body
    String notice(GuildNotice notice) {
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
- The argument is the message `routeKey` reads, so `@HttpMethod(value = "/notice", routeKey = "guildId")` routes by
  that member of the body; without one, the domain's resolver routes.
- Endpoints rarely need their context. When one does, `HttpRequestContext.current()` returns it, or the endpoint
  declares it first: `(HttpRequestContext context, SendMail mail)`. It gives `request()` for headers and query
  (readable until the endpoint returns), `remoteAddress()`, and `reply` / `fail` to answer later.
- `source(request -> context)` decides whom a request acts for; it runs on the connection's EventLoop and must not
  block. By default requests act for an empty context.
- Statuses: unknown path 404; another method 405 with `Allow`; an argument the codec cannot read 400, as is an
  `IllegalArgumentException` from the source or the routing; a refused task (shut down, overloaded) 503; an
  endpoint that throws 500.
- Helpers: `HttpRequests.query`, `longQuery`, `text`.

**Results.** What an endpoint returns is its answer, of whichever type suits it:

```java
@HttpMethod("/mail") HttpResult mail(SendMail mail) { return HttpResult.ok(mails.send(mail)); }    // the API's common shape
@HttpMethod("/pay/notify") PayAck payNotify(PayNotice notice) { return new PayAck("SUCCESS"); }    // the shape the other side fixes
@HttpMethod(value = "/export", method = "GET") FullHttpResponse export(FullHttpRequest request) { ... }   // its own status and headers
```

- `HttpResult` has `code`, `data` and `msg`, the text for whoever called, with `HttpResult.ok(data)` and
  `HttpResult.fail(code, msg)`. It goes through the codec like any other result, so a JSON library writes it as
  `{code, data, msg}` as it is.
- A failure goes to the codec's `encodeFailure` whatever the endpoint returns: a refused task, an exception, `fail` on
  the context. An endpoint that answers its failures in its own type returns that type. A refused task never reaches
  the endpoint, so it stays in the codec's shape.

**Codec.** An `HttpCodec` reads arguments and writes results and failures. An endpoint taking a `FullHttpRequest`
gets the request itself and a `FullHttpResponse` result is sent as is: neither goes through the codec, whichever it
is. `HttpCodec.basic()`, the default, reads a `String` parameter as the UTF-8 body and `byte[]` as its bytes, and
writes text as UTF-8 `text/plain`, `byte[]` / `ByteBuf` as `application/octet-stream`, and an `HttpResult` as its
data, or, when it failed, as a failure with its msg as text; `build()` rejects endpoints taking anything else. For
JSON, implement it with your library:

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

Decoding runs on the connection's EventLoop before the endpoint is called. Override `encodeFailure(errorCode, args)`
to shape failures, which by default answer 503 for a refused task and 500 otherwise, with the code as text. HTTP shows
a text rather than args, so make it from the game's own messages; an exception from it answers 500 with no body:

```java
public FullHttpResponse encodeFailure(int errorCode, String[] args) throws IOException {
    return encode(HttpResult.fail(errorCode, messages.format(errorCode, args)));
}
```

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
| `cn.managame.runtime` | INFO: the runtime started (each domain and its executor, and how many task interceptors), shutting down and closed. DEBUG: each refused task, idle route queues retired. ERROR: a task that failed, from the default error handler. |
| `cn.managame.runtime.dispatch` | INFO: the Dispatcher's handlers. DEBUG: each message's handler, each rejected message. ERROR: a handler that could not run. |
| `cn.managame.runtime.event` | INFO: the event bus's subscribers. DEBUG: each event's subscribers. |
| `cn.managame.runtime.http` | INFO: the HTTP endpoints. DEBUG: each endpoint, each bad request. ERROR: a request that could not run or be answered. |
| `cn.managame.runtime.timer` | INFO: each `@Cron` method scheduled. DEBUG: each `timer.cron`. WARNING: a firing refused. ERROR: a firing that failed. |

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. Tests cover ordering and refusal in the runtime and its
executors, route keys, the Dispatcher with replies and failures, task interceptors around every entry, a player
gateway over real TCP, events, timers, and HTTP routes over real connections.
