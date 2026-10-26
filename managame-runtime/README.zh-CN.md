[English](README.md) | 中文

# managame-runtime

游戏服务器业务逻辑的统一入口。不管工作由什么触发（玩家请求、事件、定时器、其它服务器的请求、HTTP 调用），都以
`GameTask` 的形式经 `GameRuntime.execute` 执行，并带着一个说明"在哪执行、为谁执行"的上下文。domain 和路由键都相同的任务按提交顺序逐个执行，其余的并行执行。

```
玩家数据包 ──── Dispatcher ──┐
事件 ────────── EventBus ────┤
定时器 ──────── GameTimer ───┼──> GameRuntime.execute(context, task) ──> 该 domain 的 RouteExecutor
HTTP ────────── HttpRoutes ──┤                                           同一 (domain, routingKey) 逐个执行
其它服务器 ──── RpcNode ─────┘    (managame-rpc)
```

依赖 managame-common 和 managame-network（HTTP 用到）。

## 快速开始

```java
static final int PLAYER = 1, GUILD = 2, WORLD = 3;
static final byte ROLE = 1;

GameRuntime runtime = GameRuntime.builder()
        .domain(PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())   // 按当前操作的玩家路由
        .domain(GUILD, RouteExecutors.virtualThreads())                           // 按消息上的 @RouteKey(GUILD) 路由
        .domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0))          // 全部在同一条路由上
        .build();

runtime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> players.get(context.businessId()).levelUp());
```

## 上下文

所有业务逻辑都带着一个 `Context` 执行：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `domain` | int | 执行域，例如玩家、公会、世界；决定使用哪个执行器和顺序规则 |
| `routingKey` | long | 同一 domain 内路由键相同的任务逐个执行 |
| `businessType` | byte | `businessId` 的类别，例如角色、公会；没有时为 0 |
| `businessId` | long | 任务为谁执行，例如当前操作的玩家 |
| `metadata` | `Metadata` | 随之传递的业务字段，例如 trace id |

- `GameContext` 是普通的不可变上下文：`GameContext.of(domain, key)`、`GameContext.of(domain, key, type, id)` 或
  `new GameContext(domain, key, type, id, metadata)`。
- 任务内部用 `Context.current()` 取得当前任务的上下文；`Context.hasCurrent()` 判断当前线程是否在执行任务。
- `GameContext.copyOf(context)` 得到一份普通副本，用于针对同一对象的新工作，保留路由和 metadata。请求的副本不能应答该请求。
- `RequestContext` 在此基础上增加 `reply(result)` 和 `fail(errorCode)`，用于有人在等待结果的工作。只有第一次应答有效，可以在任意线程、在任务返回之后应答。任务抛异常时 runtime 会以 `RUNTIME_EXECUTION_ERROR` 应答该请求，因此被接受的请求一定会得到应答。

## Domain、执行器与路由键

每个 domain 注册一次，同时指定执行它的任务的执行器，以及为消息找路由键的 `RouteKeyResolver`。

**执行器**（`RouteExecutors`，每个 domain 用一个新实例）：

- `virtualThreads()`：每个路由键一个队列，由虚拟线程消费，阻塞的任务只会卡住自己的键。处理完的队列会被缓存，繁忙的键不会每个任务都新建、丢弃一次队列：`virtualThreads(maxPendingPerKey, idleTimeout, maxIdleQueues)`，默认每个键最多 4096 个等待任务、空闲 60 秒、最多缓存 100 000 个队列。超过上限的键以 `ROUTE_EXECUTOR_OVERLOADED` 拒绝。
- `striped(threads)`：固定数量的平台线程，一个键总在 `hash(key) % threads` 号线程上。不保存键的状态，但一个阻塞的任务会卡住同一线程上的所有键。`striped(threads, maxPendingPerThread)`，默认 65 536。
- 也可以自己实现 `RouteExecutor`。

**路由键**：resolver 拿到来源上下文和消息，路由键从哪来由业务按 domain 决定：

- `RouteKeys.businessId()`：当前操作者的身份，用于玩家 domain。
- `RouteKeys.constant(key)`：所有消息走同一条路由。
- `RouteKeys.annotated(domain)`，即 `domain(id, executor)` 的默认值：消息上标了 `@RouteKey(domain)` 的整数字段或无参方法，record 组件也可以。一个成员可以同时为多个 domain 提供路由键。
- 任意 lambda `(source, message) -> key`。抛 `IllegalArgumentException` 表示消息无法路由：Dispatcher 应答 `INVALID_ROUTE_KEY`，HttpRoutes 返回 400。

```java
record Donate(@RouteKey(GUILD) long guildId, int gold) {}
```

路由后的任务，业务身份总是来源的身份：为玩家消息执行的公会处理器，`businessType` / `businessId` 是这个玩家。`runtime.route(domain, source, message)` 返回入口会使用的 `Route`。

## 处理器：注解声明，批量注册

处理器就是带注解的方法，启动时把声明它们的对象传进去批量注册，之后不再变化。

| 注解 | 处理什么 | 入口 |
| --- | --- | --- |
| `@Domain(id)` | 标在类（或单个方法）上：其中的处理器在哪个 domain 执行 | |
| `@HandleMethod` | 消息 | `Dispatcher` |
| `@EventMethod` | 事件 | `EventBus` |
| `@HttpMethod(path)` | HTTP 请求，默认 POST，可用 `method` 指定 | `HttpRoutes` |

处理器只有一个参数，即消息，按精确类型匹配；也可以在它前面多加一个上下文参数。注册时方法被编译成 method handle，调用时不走反射。注册时遇到以下情况抛 `IllegalArgumentException`：缺少 `@Domain`、参数不对、同一个消息有两个处理器、上下文类型入口给不了。

## Dispatcher：玩家请求与其它消息

Dispatcher 不知道任何包格式。业务自己解码数据包，把消息连同它的来源上下文、以及按自己格式应答的回调一起交给 Dispatcher。

```java
@Domain(PLAYER)
class Shop {
    @HandleMethod String buy(Buy buy) {                              // 返回非 null 即为应答
        return "bought " + buy.item() + " for " + Context.current().businessId();
    }
    @HandleMethod void rank(HandleContext request, AskRank ask) {    // 稍后通过上下文应答
        ranks.query(request.businessId(), rank -> request.reply(rank));
    }
}

Dispatcher dispatcher = Dispatcher.builder(runtime).register(new Shop(), new Accounts()).build();
```

业务自己写的网关：每个连接一个会话，自己的包格式，只用 Dispatcher 执行消息。

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
        if (session.roleId() != 0) dispatcher.dispatch(session, new Logout());   // 单向消息；排在该角色未完成的请求之后
    }
    public void onException(Connection connection, Throwable cause) { connection.close(); }
}
```

- 来源可以是任意 `Context`，通常是业务的会话，里面有连接和已登录的角色。处理器通过 `HandleContext.source()` 读取它；登录处理器替换会话即可绑定角色。
- `onReply` / `onFail` 只会调用其中一个、最多一次，在应答发生的线程上执行。两个都传，或者单向消息两个都不传。
- 失败：`HANDLER_NOT_FOUND`、`INVALID_ROUTE_KEY`、runtime 拒绝（`RUNTIME_CLOSED`、`ROUTE_EXECUTOR_OVERLOADED`），处理器抛异常时为 `RUNTIME_EXECUTION_ERROR`。
- 完整可运行的示例：`src/test/java/cn/managame/runtime/dispatch/PlayerGatewayExampleTest.java`。

### 拦截器

`HandleInterceptor` 在 Dispatcher 的每个处理器执行前后运行，就在处理器的任务里、它的路由上。每个拦截器都会看到所有处理器，由它自己根据处理器的 `Method`（例如它的注解）和消息决定怎么做：

```java
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
@interface Anonymous {}                                   // 业务自己的标记注解

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

- `before` 按拦截器添加的顺序执行。返回 `SUCCESS`（0）继续；返回其它错误码则以该错误码应答失败（单向消息直接丢弃），之后的拦截器、处理器以及所有 `after` 都不再执行。
- `after` 在处理器返回或抛异常之后、结果发出之前按相反顺序执行。它拿到处理器的返回值（void 或稍后应答时为 null）以及处理器抛出的异常（如果有）。某个 `after` 抛异常时，其余的 `after` 仍会执行。
- `before` 或 `after` 抛异常时，和处理器抛异常一样，以 `RUNTIME_EXECUTION_ERROR` 应答失败。
- RPC 处理器通过 `RpcNode.Builder.intercept(...)` 添加拦截器。

## 事件

```java
record LevelUp(@RouteKey(PLAYER) long playerId, @RouteKey(GUILD) long guildId, int level) {}

@Domain(PLAYER)
class Quests {
    @EventMethod void onLevelUp(LevelUp event) { ... }                        // 在 PLAYER:playerId 上执行
}
@Domain(GUILD)
class GuildLog {
    @EventMethod void onLevelUp(GameContext context, LevelUp event) { ... }   // 在 GUILD:guildId 上执行
}

EventBus events = EventBus.builder(runtime).register(new Quests(), new GuildLog()).build();
events.publish(new LevelUp(1001, 7, 10));
```

- 订阅者和处理器一样在启动时注册。每个订阅者在自己的 domain 里作为独立任务执行，路由键由该 domain 的 resolver 决定，绝不会在发布事件的任务内部执行；同一条路由按发布顺序收到事件。
- 订阅者以发布任务的业务身份、带着它的 metadata 执行；在任务之外发布时为空上下文。
- 事件只发给订阅了其精确类型的订阅者，按注册顺序。某个订阅者无法路由或被拒绝，不影响其它订阅者；全部投递后抛出第一个失败。
- 上下文参数的类型为 `GameContext`（或 `Context`）。

## 定时器

```java
GameTimer timer = new GameTimer(runtime);    // 默认 10 ms 一格；new GameTimer(runtime, tick) 可修改

// 在任务中：在当前路由上执行，使用当前上下文的普通副本
Scheduled expiry = timer.after(Duration.ofSeconds(30), context -> buffs.expire(context.businessId()));
// 任意位置：使用自己给出的上下文
timer.every(GameContext.of(WORLD, 0), Duration.ofMinutes(1), context -> world.tick());

expiry.cancel();
timer.close();
```

- 基于时间轮（Netty 的 `HashedWheelTimer`）：添加和取消的开销与定时器数量无关，适合每个玩家一个定时器。定时器在到期后一格之内触发。
- 时间轮的线程只负责把到期任务交给 runtime，业务逻辑和其它任务一样在路由上执行。
- 延迟使用单调时钟，不受 `GameTime` 影响。
- `every` 按固定频率执行，第一次在一个周期之后。被 runtime 拒绝（路由已满）的那一次会记录日志并跳过；runtime 关闭后定时器停止。

## HTTP

```java
record SendMail(String title, String text) {}
record GuildNotice(@RouteKey(GUILD) long guildId, String text) {}

@Domain(PLAYER)
class PlayerAdmin {
    @HttpMethod("/mail") String mail(SendMail mail) {          // POST；参数就是请求体
        mails.send(Context.current().businessId(), mail);
        return "ok";
    }
}

@Domain(GUILD)
class GuildAdmin {
    @HttpMethod("/notice") String notice(GuildNotice notice) { // 按请求体路由，在 GUILD:guildId 上执行
        guilds.get(notice.guildId()).post(notice.text());
        return "ok";
    }
}

HttpRoutes routes = HttpRoutes.builder(runtime)
        .source(request -> {                                    // 请求以谁的身份执行，由业务决定
            String role = HttpRequests.query(request, "roleId");
            return role == null ? GameContext.of(0, 0) : GameContext.of(0, 0, ROLE, Long.parseLong(role));
        })
        .codec(new JsonCodec())
        .register(new PlayerAdmin(), new GuildAdmin())
        .build();
NetworkServer.start(8080, HttpInitializer.http().contextPath("/admin").handler(routes).build());
```

- 接口只有一个参数，由编解码器从请求中读出，通常就是请求体。路径在监听的 contextPath 之下精确匹配；方法默认为 POST，需要其它方法时用 `method` 指定，例如 `@HttpMethod(value = "/status", method = "GET")`。
- 这个参数就是 domain 的 resolver 用来路由的消息，所以请求体上的 `@RouteKey` 决定路由。
- 接口一般用不到上下文。需要时在接口内调用 `HttpRequestContext.current()`，或者把它声明为第一个参数：`(HttpRequestContext context, SendMail mail)`。通过它可以取 `request()`（读请求头和查询参数，接口返回前可读）、`remoteAddress()`，以及用 `reply` / `fail` 稍后应答。
- `source(request -> context)` 决定请求以谁的身份执行；它在连接的 EventLoop 上运行，不能阻塞。默认以空上下文执行。
- 状态码：未知路径 404；方法不对 405 并带 `Allow`；编解码器读不出参数 400，source 或路由抛 `IllegalArgumentException` 也是 400；任务被拒绝（已关闭、过载）503；接口抛异常 500。
- 辅助方法：`HttpRequests.query`、`longQuery`、`text`。

**编解码器**：`HttpCodec` 负责读取参数、写出结果和失败响应。默认的 `HttpCodec.basic()` 把 `String` 参数读成 UTF-8 请求体，`byte[]` 读成请求体字节，`FullHttpRequest` 就是请求本身；写出时 `FullHttpResponse` 原样发送，文本按 UTF-8 `text/plain`，`byte[]` / `ByteBuf` 按 `application/octet-stream`；接口参数是其它类型时 `build()` 直接报错。需要 JSON 时用自己的库实现：

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

解码在连接的 EventLoop 上、调用接口之前进行；重写 `encodeFailure(errorCode)` 可以定制错误响应体。

## 错误与关闭

- `execute` 在关闭后抛带 `RUNTIME_CLOSED` 的 `GameRuntimeException`，执行器已满时抛带 `ROUTE_EXECUTOR_OVERLOADED` 的，任务都不会执行。未注册的 domain 抛 `IllegalArgumentException`。
- 任务抛出的异常交给错误处理器，默认记录日志（`builder.errorHandler((context, cause) -> ...)`）。
- `shutdown()` 拒绝新任务，已接受的继续执行；`awaitTermination(timeout)` 等待它们完成；`close()` 两者都做，不能在任务中调用。先关闭各入口（服务器、RPC 节点、定时器），再关闭 runtime。

## 游戏时间

`GameTime.currentTimeMillis()` 和 `GameTime.now(zone)` 读取进程级的业务时钟。`setClock(Clock)` 可以调整它，用于测试或限时活动，`resetClock()` 恢复系统时钟。它不影响定时器、延迟和超时。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-network。测试覆盖 runtime 及其执行器的顺序与拒绝、路由键、Dispatcher 的应答、失败与拦截器、基于真实 TCP 的玩家网关、事件、定时器，以及基于真实连接的 HTTP 路由。
