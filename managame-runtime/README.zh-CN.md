[English](README.md) | 中文

# managame-runtime

游戏服务器业务逻辑的统一入口。不管工作由什么触发（玩家请求、事件、定时器、其它服务器的请求、HTTP 调用、带回结果的回调），都以
`GameTask` 的形式经 `GameRuntime.execute` 执行，并带着一个说明"在哪执行、为谁执行"的上下文。domain 和路由键都相同的任务按提交顺序逐个执行，其余的并行执行。

```
消息 ────────── Dispatcher ──┐
事件 ────────── EventBus ────┤
定时器 ──────── GameTimer ───┼──> GameRuntime.execute(context, task) ──> 该 domain 的 RouteExecutor
HTTP ────────── HttpRoutes ──┤                                           同一 (domain, routeKey) 逐个执行
回调 ────────────────────────┘
```

依赖 managame-common 和 managame-network（HTTP 用到）。

## 快速开始

```java
static final int PLAYER = 1, GUILD = 2, WORLD = 3;
static final byte ROLE = 1;

GameRuntime runtime = GameRuntime.builder()
        .domain(PLAYER, RouteExecutors.virtualThreads())                              // 按当前操作的玩家路由
        .domain(GUILD, RouteExecutors.virtualThreads())                              // 处理方法自己指定：routeKey = "guildId"
        .domain(WORLD, RouteExecutors.striped(1), RouteKeys.constant(0))              // 全部在同一条路由上
        .build();

runtime.execute(GameContext.of(PLAYER, 1001, ROLE, 1001), context -> players.get(context.businessId()).levelUp());
```

## 上下文

所有业务逻辑都带着一个 `Context` 执行：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `domain` | int | 执行域，例如玩家、公会、世界；决定使用哪个执行器和顺序规则 |
| `routeKey` | long | 同一 domain 内路由键相同的任务逐个执行 |
| `businessType` | byte | `businessId` 的类别，例如角色、公会；没有时为 0 |
| `businessId` | long | 任务为谁执行，例如当前操作的玩家 |
| `metadata` | `Metadata` | 随之传递的业务字段，例如 trace id |

- `GameContext` 是普通的不可变上下文：`GameContext.of(domain, key)`、`GameContext.of(domain, key, type, id)` 或
  `new GameContext(domain, key, type, id, metadata)`。
- 任务内部用 `Context.current()` 取得当前任务的上下文；`Context.hasCurrent()` 判断当前线程是否在执行任务。
- `GameContext.copyOf(context)` 得到一份普通副本，用于针对同一对象的新工作，保留路由和 metadata。请求的副本不能应答该请求。
- `RequestContext` 在此基础上增加 `reply(result)` 和 `fail(errorCode)`，用于有人在等待结果的工作。只有第一次应答有效，可以在任意线程、在任务返回之后应答。请求一定会得到应答：任务抛异常时 runtime 以 `RUNTIME_EXECUTION_ERROR` 应答，`execute` 拒绝任务时以拒绝的错误码应答。`fail(errorCode, args...)` 还可以带上字符串参数，例如错误提示里要显示的数值，由来源按自己的格式放置；HTTP 请求要显示的是文案，由编解码器根据它们生成。

### 回到原路由继续执行

回调在投递它的那个线程上运行，而不是在发起工作的任务所在的路由上，在回调里直接改玩家状态会破坏"同一路由串行"的保证。所有
情况都是同一条规则：**结果从别的线程或别的路由回来时，把剩下的工作交给 `runtime.execute(context, task)`，传入想继续执行的
那个 context**，也就是任务自己的或它的请求。

```java
// 交易：先在对方的路由上扣对方的物品，再回到自己的路由上加给自己
@HandleMethod void trade(HandleContext request, Trade trade) {
    runtime.call(GameContext.of(PLAYER, trade.with()),
            () -> bags.get(trade.with()).take(trade.item()),          // 在对方的路由上
            taken -> {                                                // 回到自己的路由上
                if (!taken) { request.fail(NOT_ENOUGH); return; }
                bags.get(request.businessId()).add(trade.item());
                request.reply("traded");
            });
}

// 只读别的玩家：应答可以在任意线程上发出，不用再回来
runtime.execute(GameContext.of(PLAYER, target), _ -> request.reply(profiles.get(target)));
```

`runtime.call(target, work, then)` 用于游戏任务需要从别的路由拿结果的情况：`work` 在目标路由上执行，`then` 带着结果排回当前任务的路由队列。两段互不等待，所以虚拟线程和普通线程上行为一样，两条路由互相调用也不会死锁。一次调用的两段按顺序执行，但两段之间这条路由的其他任务可能插进来，所以 `then` 要以当时的状态为准。`work` 抛异常或被拦截器拒绝时，`then` 不执行，请求以 `RUNTIME_EXECUTION_ERROR` 失败。

框架提供的只有 `runtime.execute`、`runtime.call`、`GameContext.of` 和请求的 `reply`、`fail`；`Trade`、`bags`、`profiles`、`NOT_ENOUGH`
都是游戏自己的，任务用不到的 context 写成 `_`。继续执行的任务里 `Context.current()` 就是这个 context，请求只应答一次，任务
抛异常或被拒绝时请求会以错误码失败：不需要再记别的。任务里交出去的工作不会因为过载或关闭被拒绝，所以两条路由上的两步都会执行。RPC 回调这类从任务之外回来的工作算入口，路由满时可能被拒绝：这时请求以拒绝的错误码
失败，把第一步已经做完的事还原是游戏自己的事。在虚拟线程路由上，查数据库这类阻塞调用不需要回调，它只会卡住自己的键；回调只用于等待别的路由（原地等待可能让两条路由
互相等待），或者本来就是异步的 API。

## Domain、执行器与路由键

每个 domain 注册一次，同时指定执行它的任务的执行器，以及为消息找路由键的 `RouteKeyResolver`。

**执行器**（`RouteExecutors`，每个 domain 用一个新实例）：

- `virtualThreads()`：每个路由键一个队列，由虚拟线程消费，阻塞的任务只会卡住自己的键。任务加入键的队列不加锁。处理完的队列会留给它的键，繁忙的键不会每个任务都新建、丢弃一次队列；空闲超过空闲时间的队列由清理线程回收，内存只跟最近用到的键相关：`virtualThreads(maxPendingPerKey, idleTimeout, maxKeys)`，默认每个键除正在执行的任务外最多 4096 个等待任务、空闲 60 秒、最多 1 048 576 个键。键超过上限，或者已有 `maxKeys` 个队列（包括空闲的）时来了新键，都以 `ROUTE_EXECUTOR_OVERLOADED` 拒绝。键数上限用来约束按客户端发来的值路由的 domain 能占用多少内存，比如鉴权之前按账号路由的登录。
- `striped(threads)`：固定数量的平台线程，一个键总在 `hash(key) % threads` 号线程上。不保存键的状态，但一个阻塞的任务会卡住同一线程上的所有键。`striped(threads, maxPendingPerThread)`，默认 65 536。
- 也可以自己实现 `RouteExecutor`：`execute`，加上 `shutdown` 和 `awaitTermination`，用它自己的状态把活做完；如果它会因过载拒绝任务，还要实现 `handOn`，让任务能越过限制把工作交下去；能提供的话再实现 `pending`（关闭时要等它归零）、`maxPending` 和 `routes`，用于[指标](#指标)。

**路由键**：每个 domain 的 resolver 为这个 domain 收到的所有消息统一给出路由键：

- `RouteKeys.businessId()`，即 `domain(id, executor)` 的默认值：当前操作的玩家，请求取会话里的，事件取发布任务的。大多数
  domain 用它就够了。
- `RouteKeys.constant(key)`：所有消息走同一条路由。
- 任意 lambda `(source, message) -> key`。抛 `IllegalArgumentException` 表示消息无法路由：Dispatcher 应答 `INVALID_ROUTE_KEY`，HttpRoutes 返回 400。

消息本身带着路由键的处理方法（比如捐献给哪个公会），在注解里写出这个成员，各个消息里叫什么名字都行：

```java
@Domain(GUILD)
class GuildHandle {
    @HandleMethod(routeKey = "guildId")         // 在 GUILD:捐献的公会 上执行
    HandleResult donate(Donate donate) { ... }
    @HandleMethod(routeKey = "targetGuildId")   // 另一个消息里叫别的名字
    HandleResult apply(ApplyReq apply) { ... }
}
```

- `routeKey` 是方法参数里那个消息的成员名：先找同名的无参方法，再找它的 getter（`getGuildId()`，protobuf 和 JavaBean
  的命名），再找字段，类型必须是整数或其包装类型。注册方法时就编译成 method handle，成员名写错或类型不对启动时就失败；
  每条消息读一次，只多一次 method handle 调用。
- 只有需要的方法才写；同一个 domain 或同一个类里没写的方法，照样用 domain 的路由键。
- 一个 domain 里路由键的含义要一致：公会 domain 里每个方法都必须按公会 id 路由，不管消息里叫什么名字，否则不同公会的任务会
  排到同一条路由上，同一个公会的任务也不再依次执行。框架判断不了一个成员装的是哪种 id。

不在任何玩家任务里发布的事件或请求没有操作的玩家，按 businessId 路由的 domain 会把它排到 0 号路由上：玩家的事件要在该玩家
的任务里发布。

路由后的任务，业务身份总是来源的身份：为玩家消息执行的公会处理器，`businessType` / `businessId` 是这个玩家。`runtime.route(domain, source, message)` 返回入口为没写 `routeKey` 的处理方法使用的 `Route`。

## 处理器：注解声明，批量注册

处理器就是带注解的方法，启动时把声明它们的对象传进去批量注册，之后不再变化。

| 注解 | 处理什么 | 入口 |
| --- | --- | --- |
| `@Domain(id)` | 标在类（或单个方法）上：其中的处理器在哪个 domain 执行 | |
| `@HandleMethod` | 消息 | `Dispatcher` |
| `@EventMethod` | 事件 | `EventBus` |
| `@HttpMethod(path)` | HTTP 请求，默认 POST，可用 `method` 指定 | `HttpRoutes` |
| `@Cron(domain, value)` | 日历时间，见[定时任务](#定时任务)；domain 和路由键写在注解里，方法不带消息 | `GameTimer` |

处理器只有一个参数，即消息，按精确类型匹配。大多数处理器只需要消息；业务要读上下文里的字段（比如当前操作的玩家），或者要稍后应答时，才在消息前面多加一个上下文参数。`@HandleMethod` 返回 `HandleResult`，要稍后通过上下文应答时声明为 void；`@HttpMethod` 返回 `HttpResult`、编解码器能写出的自定义类型或 `FullHttpResponse`，稍后应答时同样声明为 void。只有 void 方法才算稍后应答：有返回类型的方法返回 null 是 bug，以 `RUNTIME_EXECUTION_ERROR` 应答，并交给错误处理器。注册时方法被编译成 method handle，调用时不走反射。注册时遇到以下情况抛 `IllegalArgumentException`：缺少 `@Domain`、参数不对、`@HandleMethod` 返回其它类型、消息里没有 `routeKey` 指定的成员、同一个消息有两个处理器、上下文类型入口给不了。

## Dispatcher：玩家请求与其它消息

Dispatcher 不知道任何包格式。业务自己解码数据包，把消息连同它的来源上下文、以及一个按自己格式应答的回调一起交给 Dispatcher。

```java
@Domain(PLAYER)
class Shop {
    @HandleMethod HandleResult prices(AskPrices ask) {                  // 常规写法：传入消息，返回应答
        return HandleResult.ok(catalog.prices(ask.category()));
    }
    @HandleMethod HandleResult sell(HandleContext context, Sell sell) { // 业务逻辑要知道当前操作的玩家时才取上下文
        return market.sell(context.businessId(), sell.item());
    }
    @HandleMethod void rank(HandleContext context, AskRank ask) {       // 或者要稍后在任意线程应答时
        ranks.query(context.businessId(), rank -> context.reply(rank));
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

业务自己写的网关：每个连接一个会话，自己的包格式，只用 Dispatcher 执行消息。

```java
record Session(Connection connection, long roleId) {}                 // 业务自己的会话，不是 Context

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
        if (session.roleId() != 0) dispatcher.dispatch(context(session, null), new Logout());   // 单向消息；排在该角色未完成的请求之后
    }
    public void onException(Connection connection, Throwable cause) { connection.close(); }

    /** 消息以谁的身份执行：会话的角色、在它自己的路由上，带上包里随消息传来的数据。 */
    private static GameContext context(Session session, Packet packet) {
        long role = session.roleId();
        Metadata metadata = packet == null ? Metadata.empty() : packet.metadata();
        return role == 0 ? new GameContext(LOGIN, 0, (byte) 0, 0, metadata) : new GameContext(PLAYER, role, ROLE, role, metadata);
    }
}
```

- 来源是消息带进来的上下文，由入口为每条消息建立：谁在执行（比如连接登录的角色），以及消息的 metadata（比如 trace id）。domain 由处理器决定，路由键由处理器的 routeKey 成员或 domain 的解析器决定。会话是业务自己的东西，不进入 runtime；入口给它绑定角色，比如在登录的应答里。
- 应答经回调回去，回调里捕获了回去的路（连接、包的序号），所以上下文不需要连接，稍后在别的线程上应答也一样。在请求之外推送给玩家（来自定时器或别的玩家的路由），走业务自己维护的角色到会话的登记，由网关维护。
- 回调最多调用一次，在应答发生的线程上执行，不能阻塞。单向消息不传回调。
- 所有结果都以 `HandleResult` 交给回调，字段是 `code`、`data` 和 `args`：成功时是 `SUCCESS` 和返回结果；失败时是错误码和原样的参数，没有参数时是空数组，框架自己的失败也一样。它们放在包里的什么位置由业务决定，例如放在失败包的 body 里。
- 处理器返回 `HandleResult.fail(code, args...)` 表示失败，处理器之下的业务逻辑像返回结果一样把它逐层返回。异常表示 bug，不是业务失败：以 `RUNTIME_EXECUTION_ERROR` 应答，并交给错误处理器。
- 失败：`HANDLER_NOT_FOUND`、`INVALID_ROUTE_KEY`、runtime 拒绝（`RUNTIME_CLOSED`、`ROUTE_EXECUTOR_OVERLOADED`），处理器抛异常时为 `RUNTIME_EXECUTION_ERROR`。
- 完整可运行的示例：`src/test/java/cn/managame/runtime/dispatch/PlayerGatewayExampleTest.java`。
- 把消息交给同一个 Dispatcher 的所有入口共用它的处理器。

## 拦截器

`TaskInterceptor` 在 runtime 的每个任务执行前后运行，不管任务来自哪个入口：Dispatcher 的处理器、事件、HTTP 接口、定时器和定时任务，以及用 `runtime.execute` 回到某条路由上继续的工作（它本身也是一个任务）。拦截器在任务的路由和线程上运行，`Context.current()` 就是任务的上下文。每个拦截器都会看到所有任务，由它自己按 domain 或上下文的类型决定怎么做：

```java
class LoginCheck implements TaskInterceptor {             // 登录前只能执行登录 domain 的处理器
    public boolean before(Context context) {
        if (context instanceof HandleContext request && context.domain() != LOGIN && context.businessId() == 0) {
            request.fail(NOT_LOGGED_IN);
            return false;                                     // 任务不再执行
        }
        return true;
    }
}

class SavePlayer implements TaskInterceptor {             // 不管是请求、定时器还是事件改了玩家
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

- `before` 按拦截器添加的顺序执行，然后是任务，再按相反顺序执行 `after`。只有 `before` 返回 true 的拦截器才会执行 `after`，所以 `before` 里设置的东西（比如日志上下文里的 trace id）一定会被 `after` 清掉。某个 `after` 抛异常时，其余的 `after` 仍会执行。
- `before` 返回 false 表示拒绝任务：任务和之后的 `before` 都不再执行，也不会交给错误处理器，因为拒绝不是错误。被拒绝的请求由拦截器自己应答（如上面的 `LoginCheck`），没有应答时以 `RUNTIME_EXECUTION_ERROR` 应答，因为请求一定会得到应答。
- `after` 拿到任务或后面某个 `before` 抛出的异常，没有则为 null，后面的 `before` 拒绝任务时也是 null。任务执行中给出的应答此时已经发出。
- `before` 或 `after` 抛异常时，和任务抛异常一样处理：请求以 `RUNTIME_EXECUTION_ERROR` 应答，已经应答过的除外，异常交给错误处理器。`before` 抛异常时，任务和之后的 `before` 同样不再执行。
- 没有添加拦截器时 runtime 原样执行任务；每添加一个，每个任务多两次调用（`ExecuteThroughputBenchmark.executeIntercepted` 测量了一个什么都不做的拦截器）。
- 需要看消息本身的检查，比如按消息类型限流，放在应用的入口里、分发之前做（那里有消息和包的协议号），或者放在处理器里。

## 事件

```java
record LevelUp(long playerId, long guildId, int level) {}   // 只是数据，不知道任何 domain

@Domain(PLAYER)
class Quests {
    @EventMethod void onLevelUp(LevelUp event) { ... }                        // 在 PLAYER:发布事件的玩家 上执行
}
@Domain(GUILD)
class GuildLog {
    @EventMethod(routeKey = "guildId")                                         // 在 GUILD:guildId 上执行
    void onLevelUp(GameContext context, LevelUp event) { ... }
}

EventBus events = EventBus.builder(runtime).register(List.of(new Quests(), new GuildLog())).build();
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
- `every` 按固定频率执行，第一次在一个周期之后。上一次还没结束时，这一次会记录日志并跳过，所以任务比周期慢时只会变慢，不会占满路由：做 tick 的业务要读实际过了多久，不要数触发次数。被 runtime 拒绝（路由已满）的那一次同样记录日志并跳过；runtime 关闭后定时器停止。
- `after` 只触发一次，被拒绝了没有下一次可补，所以路由再满它也会执行：任务为之设定时器的事，比如 buff 结束、建筑完工，一定会发生。只有到期时 runtime 已经关闭，它才会丢。
- `close()` 之后仍然可以设定时器：拿到的是已取消的定时器，永远不会触发，所以应用关闭期间还在收尾的任务不会因为定时器先关了而失败。
- 在定时器自己的路由上取消后，它不会再执行，已经排在路由上的那次也不会，所以关闭副本的任务取消心跳后就不用再管了。在别的线程上取消时，`cancel` 返回的同时可能正好有一次开始执行。

### 按键管理的定时器

业务代码按"这个定时器是干什么的"来取消，玩家下线时一次全部取消，不用为每个定时器保存一个 `Scheduled`：

```java
record BuffExpiry(int buffId) {}                                              // 键：record 或字符串都可以

// 放在应用保存玩家内存状态的地方，玩家上线时创建
TimerGroup timers = timer.group(GameContext.of(PLAYER, playerId, PLAYER_ID, playerId));

// 在玩家的任务里
timers.after(new BuffExpiry(buffId), buff.duration(), context -> buffs.expire(buffId));   // 再设一次就重新计时
timers.every("regen", Duration.ofSeconds(5), context -> player.regenerate());
timers.cancel(new BuffExpiry(buffId));                                        // buff 被驱散
timers.cancelAll();                                                           // 玩家下线
```

- 一个组是一条路由上的定时器：都在这条路由上、带着组的上下文执行。`after`、`every`、`cron` 就是定时器自己的那三个，前面多一个键。
- 一个键对应一个定时器。同一个键再设一次，原来的那个会被取消，所以给 buff 续期就是重新设它的定时器。只触发一次的定时器在任务开始时离开组，所以任务里可以再设自己的键。
- 组由应用持有，和连接一样：放在这条路由的内存状态里，比如在线玩家或开着的副本的对象。`GameTimer` 不保存任何组，定时器都取消后丢掉组没有任何代价。
- 组只能在它那条路由的任务里使用，所以不加锁也不会乱；其它调用方会得到 `IllegalStateException`。在路由上取消是精确的：被取消或被替换的定时器之后不会再执行。

### 定时任务

```java
class DailyJobs {
    @Cron(domain = WORLD, value = "0 0 5 * * *")                                  // 每天 05:00:00，routeKey 默认 0
    void resetDaily() { world.resetDaily(); }

    @Cron(domain = GUILD, routeKey = 0, value = "0 0 20 * * SAT")
    void openWar(GameContext context) { war.open(); }
}

List<Scheduled> jobs = timer.register(List.of(new DailyJobs()));                 // 排上每个 @Cron 方法
timer.cron(GameContext.of(WORLD, 0), activity.cron(), context -> activity.open()); // 代码里的同等写法，比如表达式来自配置
```

- 表达式 6 段：秒、分、时、日、月、周。每段是 `*`、一个值、范围 `a-b`、列表 `a,b` 或步长（`*/15`、`5/20`、`9-17/2`）；月可写 `JAN`–`DEC`，周可写 `SUN`–`SAT` 或 `0`–`7`（0 和 7 都是周日）；`?` 等同于 `*`。日和周都写了具体值时，满足其一就触发，与 Unix cron 相同。不支持 `L`、`W`、`#`。
- 方法不带参数，或带一个 `GameContext`（即 `GameContext.of(domain, routeKey)`），返回值忽略。方法签名、表达式不对或 domain 未注册时，`register` 抛 `IllegalArgumentException`，一个也不排。
- 时间按 `GameTime` 的时钟和时区读取，见[游戏时间](#游戏时间)。定时任务至少每秒看一次 `GameTime`：到点就触发，再在"现在"和"刚到点的时刻"中较晚者之后找下一次；发现时钟或时区换了，就在新的上面重新找，所以 `setClock`、`setZone`、`resetClock` 之后一秒内自动重排，不用取消再注册。夏令时跳过的本地时刻不触发，重复的只触发一次。晚了的那次只补一次，停机期间错过的不补，上一次还没结束或被 runtime 拒绝的那次记录日志并跳过。

## HTTP

```java
record SendMail(String title, String text) {}
record GuildNotice(long guildId, String text) {}

@Domain(PLAYER)
class PlayerAdmin {
    @HttpMethod("/mail") String mail(SendMail mail) {          // POST；参数就是请求体
        mails.send(Context.current().businessId(), mail);
        return "ok";
    }
}

@Domain(GUILD)
class GuildAdmin {
    @HttpMethod(value = "/notice", routeKey = "guildId")     // 从请求体读出，在 GUILD:guildId 上执行
    String notice(GuildNotice notice) {
        guilds.get(notice.guildId()).post(notice.text());
        return "ok";
    }
}

HttpRoutes routes = HttpRoutes.builder(runtime)
        .codec(new JsonCodec())
        .register(List.of(new PlayerAdmin(), new GuildAdmin()))
        .build();
NetworkServer.start(8080, HttpInitializer.http().contextPath("/admin").handler(routes).build());
```

- 接口只有一个参数，由编解码器从请求中读出，通常就是请求体。路径在监听的 contextPath 之下精确匹配；方法默认为 POST，需要其它方法时用 `method` 指定，例如 `@HttpMethod(value = "/status", method = "GET")`。
- `routeKey` 读的就是这个参数，所以 `@HttpMethod(value = "/notice", routeKey = "guildId")` 按请求体上的那个成员路由；没写时由 domain 的 resolver 路由。
- 接口一般用不到上下文。需要时在接口内调用 `HttpRequestContext.current()`，或者把它声明为第一个参数：`(HttpRequestContext context, SendMail mail)`。通过它可以取 `request()`（读请求头和查询参数，接口返回前可读）、`remoteAddress()`，以及用 `reply` / `fail` 稍后应答。
- 请求不代表任何人：`businessType` 和 `businessId` 都是 0，业务归属由接口从参数里读。编解码器拿得到整个请求，所以既可以从请求体、也可以从查询参数填充参数对象。
- 状态码：未知路径 404；方法不对 405 并带 `Allow`；编解码器读不出参数 400，路由抛 `IllegalArgumentException` 也是 400；任务被拒绝（已关闭、过载）503；接口抛异常 500。
- 辅助方法：`HttpRequests.query`、`longQuery`、`text`。

**返回值**：接口的返回值就是它的应答，类型按需要选：

```java
@HttpMethod("/mail") HttpResult mail(SendMail mail) { return HttpResult.ok(mails.send(mail)); }    // 接口统一的格式
@HttpMethod("/pay/notify") PayAck payNotify(PayNotice notice) { return new PayAck("SUCCESS"); }    // 对方指定的格式
@HttpMethod(value = "/export", method = "GET") FullHttpResponse export(FullHttpRequest request) { ... }   // 自己定状态码和响应头
```

- `HttpResult` 的字段是 `code`、`data` 和 `msg`（给调用方看的文案），用 `HttpResult.ok(data)` 和 `HttpResult.fail(code, msg)` 创建。它和其它返回值一样经过编解码器，所以 JSON 库不用额外处理就能写成 `{code, data, msg}`。
- 失败一律交给编解码器的 `encodeFailure`，与接口的返回类型无关：任务被拒绝、抛异常、在上下文上 `fail` 都是如此。接口要用自己的类型应答失败时，直接返回该类型。任务被拒绝时接口根本没有运行，所以仍是编解码器的格式。

**编解码器**：`HttpCodec` 负责读取参数、写出结果和失败响应。参数是 `FullHttpRequest` 的接口直接拿到请求本身，返回 `FullHttpResponse` 的原样发送，这两种都不经过编解码器，不管配的是哪个。默认的 `HttpCodec.basic()` 把 `String` 参数读成 UTF-8 请求体，`byte[]` 读成请求体字节；写出时文本按 UTF-8 `text/plain`，`byte[]` / `ByteBuf` 按 `application/octet-stream`，`HttpResult` 写出它的 data，失败时按失败响应写出，msg 作为文本；接口参数是其它类型时 `build()` 直接报错。需要 JSON 时用自己的库实现：

```java
class JsonCodec implements HttpCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public Object decode(FullHttpRequest request, Type type) throws IOException {
        return mapper.readValue(new ByteBufInputStream(request.content()), mapper.constructType(type));
    }

    public FullHttpResponse encode(Object result) throws IOException {
        // 字符串、数字也按 JSON 写（"ok" 写成带引号的 "ok"），客户端始终按 JSON 解析；想让文本按纯文本返回，加上：
        // if (result instanceof CharSequence || result instanceof byte[]) return HttpCodec.basic().encode(result);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(mapper.writeValueAsBytes(result)));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        return response;
    }
}
```

解码在连接的 EventLoop 上、调用接口之前进行。重写 `encodeFailure(errorCode, args)` 可以定制失败响应，默认任务被拒绝时 503、其它情况 500，错误码作为文本。HTTP 要显示的是文案而不是参数，所以用游戏自己的文案表生成；它抛异常时以 500 空响应应答：

```java
public FullHttpResponse encodeFailure(int errorCode, String[] args) throws IOException {
    return encode(HttpResult.fail(errorCode, messages.format(errorCode, args)));
}
```

## 错误与关闭

- `execute` 在关闭后抛带 `RUNTIME_CLOSED` 的 `GameRuntimeException`，执行器已满时抛带 `ROUTE_EXECUTOR_OVERLOADED` 的，任务都不会执行；如果是请求，会先以该错误码应答。未注册的 domain 抛 `IllegalArgumentException`。
- 任务不会执行时，会调用它的 `refused(context)` 来代替 `run`：被拦截器拦下、拦截器在它之前抛了异常，或者 runtime 没有接受它。持有东西的任务（比如 `HttpRoutes` 交给 runtime 的任务持有请求缓冲区）在 `run` 结束时和 `refused` 里都要归还。lambda 不持有东西，两处都不用管。
- 任务抛出的异常交给错误处理器，默认记录日志（`builder.errorHandler((context, cause) -> ...)`）。
- 限流和关闭只针对从外部进来的工作。在游戏任务里调用 `execute` 不会因为过载或关闭被拒绝，已接受的工作能把开始的事做完。其它调用方都算入口，包括 RPC 回调，它们的任务可能被拒绝。任务不断给自己交工作时同样会越过所有限制，可以从 `routes.max_pending` 发现。
- `shutdown()` 拒绝来自外部的任务，并立即返回。已接受的任务继续执行，也可以继续交出工作，所有 domain 都没有工作后执行器才关闭；`awaitTermination(timeout)` 等到这一步，`close()` 两者都做，不能在任务中调用。先关闭各入口（服务器、RPC、定时器等），再关闭 runtime，这样 RPC 回调回来时 runtime 还能接受。
- runtime 不统计进行中的任务：它等的是各 domain 的 `pending`，即执行器本来就持有的状态。没有工作后，`striped` 用其 ThreadPoolExecutor 自带的关闭，`virtualThreads` 不再新建队列、每个队列排空后就退休。

## 游戏时间

`GameTime` 是整个进程统一的业务时钟和时区，业务代码都从这里取时间：`currentTimeMillis()`、`now()`（游戏时区下的日期时间）、`zone()`、`clock()`。时区只在这里设一次，默认系统时区：

```java
GameTime.setZone(ZoneId.of("Asia/Shanghai"));   // 启动时设一次，日期和 cron 都按它读
GameTime.setClock(Clock.offset(Clock.system(GameTime.zone()), Duration.ofDays(1)));   // 测试或活动：拨快一天
GameTime.resetClock();                           // 回到系统时钟，保留时区
```

`setClock` 换掉时钟，时钟带的时区成为游戏时区。定时任务（`@Cron`、`timer.cron`）一秒内按新的时钟或时区重排；延迟、周期定时器和超时用单调时钟，不受影响。

## 日志

经 `System.Logger` 输出，可以接任意日志实现，见工作区 README。

| Logger | 内容 |
| --- | --- |
| `cn.managame.runtime` | INFO：runtime 启动（每个 domain 及其执行器，以及任务拦截器的数量）、开始关闭、关闭完成。DEBUG：每个被拒绝的任务、回收的空闲路由队列。WARNING：设置了 `slowTask` 时的慢任务。ERROR：默认错误处理器记录的失败任务。 |
| `cn.managame.runtime.dispatch` | INFO：Dispatcher 的处理器数量。DEBUG：每种消息对应的处理器、每条被拒绝的消息。ERROR：无法执行的处理器。 |
| `cn.managame.runtime.event` | INFO：事件总线的订阅者数量。DEBUG：每种事件的订阅者。 |
| `cn.managame.runtime.http` | INFO：HTTP 接口数量。DEBUG：每个接口、每个错误请求。ERROR：无法执行或无法应答的请求。 |
| `cn.managame.runtime.timer` | INFO：每个排上的 `@Cron` 方法。DEBUG：每个 `timer.cron`。WARNING：被拒绝或跳过的触发。ERROR：执行失败的触发。 |

## 指标

`GameRuntime.builder().metrics(metrics)` 通过 common 的 `Metrics` 上报，默认 `Metrics.noop()`，此时不读时钟，任务的开销和没有指标时一样。接了后端时，每个任务读三次时钟，并累加几个计数器。业务代码不用改。按 domain 上报，带 `domain` 标签：

| 指标 | 类型 | 回答的问题 |
| --- | --- | --- |
| `managame.runtime.tasks.refused` | counter | 扛不住了吗？没有被接受的任务，带 `code` 标签：`RUNTIME_CLOSED`、`ROUTE_EXECUTOR_OVERLOADED` 或 `ROUTE_EXECUTOR_CLOSED` |
| `managame.runtime.tasks.failed` | counter | 有 bug 吗？抛异常的任务 |
| `managame.runtime.tasks.pending` | gauge | 积压多少？已接受、尚未结束的任务 |
| `managame.runtime.tasks.wait_bucket` | counter | 路由跟得上吗？在路由上排队不超过 `le` 毫秒的任务数，带 `le` 标签：`0.5`、`1`、`5`、`10`、`50`、`100`、`500` 或 `+Inf`（计入全部） |
| `managame.runtime.tasks.wait_nanos` | counter | 这些任务的总排队时间；两次读数的差除以 `le="+Inf"` 的差，就是这段时间的平均值 |
| `managame.runtime.routes.max_pending` | gauge | 有路由卡住吗？单个路由键上最多的任务数，striped 的 domain 是单个线程上的 |
| `managame.runtime.routes.active` | gauge | 离 `maxKeys` 还有多远？有队列的路由键；striped 的 domain 不保存键的状态，始终为 0 |

按执行的内容上报，带 `handler` 标签：

| 指标 | 类型 | 回答的问题 |
| --- | --- | --- |
| `managame.runtime.handler.calls` | counter | 执行得多频繁？ |
| `managame.runtime.handler.run_nanos` | counter | 时间花在哪？按它排序就是最耗时的处理器 |
| `managame.runtime.handler.failed` | counter | 抛异常的任务 |
| `managame.runtime.handler.business_failed` | counter | 以业务自己的失败应答的请求，比如 `HandleResult.fail(GOLD_SHORT)` |

`handler` 标签的取值：

- 处理器、事件订阅者、HTTP 接口或 `@Cron` 方法：类名加方法名，`com.game.ShopHandle#buy`；
- 用请求的 context 继续执行的工作，比如 RPC 回调：这个请求的处理器，所以带一个回调的请求算两个任务；
- 手写的任务，也就是交给 `execute` 或定时器的 lambda、方法引用或匿名类：写它的那个类，`com.game.TradeHandle`；
- 写成具名类的任务：这个类本身，`com.game.TradeHandle$Settle`，用于需要单独统计的任务。

一个处理器或一个类不管执行多少次都只有一条序列，所以名字不会让序列数增长，任务也不用构造名字或标签。任务是谁的只写进日志，不做标签：`GameRuntime.builder().slowTask(Duration.ofMillis(100))` 会为每个执行这么久的任务写一行日志，不接后端时也有效：

```
WARNING Slow task; handler=com.game.GuildHandle#donate, domain=3, routeKey=88, businessType=1, businessId=10001, wait=12ms, run=340ms
```

gauge 在采集时才读取执行器自己的状态，`close()` 时移除。路由卡住后只要还有任务进来，就会体现在 `routes.max_pending` 上；没有新任务进来时看不出来。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-network。测试覆盖 runtime 及其执行器的顺序与拒绝、路由键、Dispatcher 的应答与失败、包住所有入口的任务拦截器、基于真实 TCP 的玩家网关、事件、定时器，以及基于真实连接的 HTTP 路由。
