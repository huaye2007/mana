[English](README.md) | 中文

# managame-rpc

游戏服务器集群内各进程之间基于 TCP 的调用与通知。它是独立组件，只依赖 managame-network 和 managame-common。消息的含义、编码方式、在哪个线程上处理，都由业务决定。

- **`RpcNode`** 是当前进程；不和其他进程通信的进程不需要它。任何节点都可以主动连接别的节点，开了监听的节点也可以被别人连接。两个节点之间只由一方发起连接；不监听的节点只能主动去连。
- **`RpcPeer`** 是另一个节点，和运行它的进程一一对应，按 node id 识别，不论连接由哪一方发起。连上之后，双方在同一组连接上对等地互相调用、发通知。一个对端可以有多条连接。

## 快速开始

节点 1 是世界服，开启监听；节点 2 是游戏服，不监听，主动连向节点 1。

```java
// 节点 1
RpcNode world = RpcNode.builder()
        .nodeId(1).listen(7001)
        .handler((rpcPeer, connection, msg) -> {
            if (msg instanceof RpcRequest request && request.command() == RANK_OF)
                RpcResponse.of(request.requestId(), rankOf(request.body())).writeTo(connection);
        })
        .start();

// 节点 2
RpcNode game = RpcNode.builder().nodeId(2).start();
RpcPeer worldPeer = game.createPeer(1, new InetSocketAddress("10.0.0.1", 7001));
worldPeer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
    if (response.isSuccess()) show(request.routeKey(), response.body());
    else log.warn("rank failed: " + response.code());
});
```

节点 1 也可以通过节点 2 发起的连接反过来调用它：`world.peer(2).notify(RpcNotify.of(MAIL, playerId, body))`。

## 消息

| 记录 | 字段 |
| --- | --- |
| `RpcRequest` | `requestId`、`deadline`、`command`、`routeKey`、`businessType`、`businessId`、`metadata`、`body` |
| `RpcNotify` | `command`、`routeKey`、`businessType`、`businessId`、`metadata`、`body` |
| `RpcResponse` | `requestId`、`code`、`metadata`、`body` |

- 除 `requestId`、`deadline` 和 `code` 外，所有字段都归业务所有，原样传输：`command` 表示请求的内容，`businessType` 和 `businessId` 表示涉及的业务对象，`metadata`（common 的 `Metadata`）携带其他信息，比如追踪 id、调用方身份。节点自己只读 `routeKey`，用来选连接。
- `requestId` 和 `deadline` 由发起调用的节点分配。`RpcRequest.of(...)` 把它们设为 0，调用时再分配。被调用方用同一个 id 回一个 `RpcResponse`。
- `deadline` 是持有该请求的进程的 `System.nanoTime()` 值，表示调用方停止等待的时间点。它在线上以"剩余时间"传输，所以两台机器的时钟不必一致。到达时已过期的请求直接丢弃；被调用方在执行排过队的请求前，可以用 `request.isExpired()` 检查一下。
- `RpcResponse.of(requestId, [metadata,] body)` 表示成功（code 为 0）；`RpcResponse.failure(requestId, code)` 表示失败，code 必须是正数。
- body 是 Netty 的 `ByteBuf`。三种记录都通过 body 做引用计数，和 Netty 自己的消息一样。

## 调用

| 方法 | 结果 |
| --- | --- |
| `peer.call(request, callback)` | 在节点的超时时间内执行 `callback.onResponse(request, response)`，恰好一次 |
| `peer.call(request, timeout, callback)` | 同上，超时时间为 `timeout` |
| `peer.notify(notify)` | 没有应答；返回 network 的 `WriteStatus`：`ACCEPTED`；连接繁忙时为 `NOT_WRITABLE`；没有可用连接、对端已被移除或本节点已关闭时为 `INACTIVE` |
| `node.broadcast(peers, notify)` | 给每个对端各发一个通知，共用同一个 body；返回被接收的个数 |

### 选哪条连接

对端的连接数由发起连接的一方决定，每条连接占一个槽；双方都从握手里得知每条连接的槽号。

1. 调用和通知走 `floorMod(routeKey, 连接数)` 那个槽的连接。只要这条连接在，同一个 key 的消息就一直走它，按发送顺序到达对端。比如用玩家 id 作为 key，同一个玩家的消息就有序。
2. 这条连接断开期间，它的 key 改走后面槽位中第一条可用的连接。只有断开那个槽上的 key 会换连接。这个槽恢复后，它的 key 切回来；切回的那一刻，备用连接上还没到的消息可能被本槽连接上新发的消息超过。
3. 一条可用的连接都没有时，调用以 `RPC_UNAVAILABLE` 失败，通知返回 `INACTIVE`。

连接繁忙时不换连接：繁忙会频繁出现又消失，一繁忙就换会让同一个 key 的消息乱序。这时调用以 `RPC_UNAVAILABLE` 失败，通知返回 `NOT_WRITABLE`。

### 回调

- 回调会拿回它所应答的请求：带有调用时分配的 id 和 deadline，但不带 body。请求的 command 决定怎么解码应答，route key、业务字段和 metadata 就是业务构造上下文所需的字段，lambda 不用捕获任何变量。在回调里解码，再交给业务选定的线程：

  ```java
  peer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
      Object rank = response.isSuccess() ? protocol.decode(request.command(), response.body()) : null;
      executorOf(request.routeKey()).execute(() -> showRank(request.routeKey(), rank, response.code()));
  });
  ```

  业务通常把这样的适配器写一次，每次调用都传它；它在调用开始时创建，也正好是统计调用耗时的地方。
- 回调永远不会在 `call` 内部、在调用线程上执行，调用在发出前就失败时也一样：这种情况下，回调在节点主动连接所用的某个 EventLoop 上执行，按 route key 选。回调不能阻塞，抛出的异常只记日志。
- **等待中的调用归对端所有**，用对端自己的请求 id，不属于任何一条连接。应答从这个实例的哪条连接回来，都能结束对应的调用，所以调用比它发出时用的那条连接活得久：那条连接断开而其他连接还在时，被调用方的应答会改走其中一条。**对端的最后一条连接断开时**，比如它的进程崩溃或停止了，等待中的调用立即以 `RPC_UNAVAILABLE` 失败，不再等到超时：没有连接期间写出的应答反正都会丢失。以下任一情况会结束一个调用：应答到达、超时、对端的最后一条连接断开、对端的新实例替换了被调用的实例、对端被移除、本节点关闭。
- 超时由节点的时间轮触发，时间轮每 10 毫秒走一格，所以调用最多晚一格超时。
- 网络问题不会抛异常。回调收到的是被调用方的应答（可能带有被调用方自己的失败码），或者下列失败码之一：

| 错误码 | 何时出现 |
| --- | --- |
| `RPC_UNAVAILABLE` | 没有可用连接、连接繁忙、已有 `maxPendingCalls` 个调用在等待、调用等待期间对端的最后一条连接断开了、对端的新实例替换了被调用的实例，或对端被移除、本节点已关闭 |
| `RPC_TIMEOUT` | 超时时间内没有应答（默认 5 秒）；请求或应答随一条连接丢失、而其他连接还在时也是这样 |
| `RPC_PROTOCOL_ERROR` | 应答超过了被调用方的最大帧长度 |
| `RPC_HANDLER_ERROR` | 被调用方的处理器抛出了异常 |
| `HANDLER_NOT_FOUND` | 被调用方没有处理器 |

**调用失败不代表请求没有执行。** 收到 `RPC_TIMEOUT` 时，请求很可能已经到达对端并且执行过了。所以跨服操作在重试之前，应先设计成幂等的，比如在 metadata 里带一个操作 id，由被调用方记住执行过哪些。

## 处理消息

对端发来的所有请求和通知，都交给业务唯一的 `RpcHandler`：

```java
RpcHandler handler = (rpcPeer, connection, msg) -> {
    switch (msg) {
        case RpcRequest request -> {
            Object query = protocol.decode(request.command(), request.body());   // 在这里解码
            executor.execute(() -> {                                              // 之后在别的线程上应答
                if (request.isExpired()) return;                                  // 调用方已经放弃了
                RpcResponse.of(request.requestId(), protocol.encode(handle(query))).writeTo(connection);
            });
        }
        case RpcNotify notify -> handle(rpcPeer, protocol.decode(notify.command(), notify.body()));
        default -> {}
    }
};
```

- `rpcPeer` 是发送消息的节点，可以用它回调对方；`connection` 是消息到达的那条 network `Connection`。应答请求时，用请求的 `requestId` 构造 `RpcResponse`，写到这条连接上，可以立即写，也可以之后在任意线程写。只有第一个应答有效，调用方会丢弃后面的应答。
- **`response.writeTo(connection)`** 在连接繁忙时也会写出应答：应答对应的工作已经做完，丢掉它只会让调用方无法确定结果。如果那条连接到这时已经断开，它会改用这个对端同一个运行实例的另一条可用连接写出。它返回 `ACCEPTED`；这个实例没有可用连接时（比如唯一的连接正在重连），返回 `INACTIVE` 并释放这个应答，调用方的那个调用在最后一条连接断开时就已经以 `RPC_UNAVAILABLE` 失败了。应答超过本节点的最大帧长度时，改为发出 `RPC_PROTOCOL_ERROR`。
- 握手、心跳和本节点自己调用的应答都不会交给处理器：节点自己处理它们，每个应答交给对应调用的回调。
- 处理器在该连接的 EventLoop 上按消息到达顺序执行，不能阻塞。
- 处理器抛出异常时：对于请求，如果还没应答过，就以 `RPC_HANDLER_ERROR` 失败；对于通知，只记日志。没有设置处理器的节点，对请求回复 `HANDLER_NOT_FOUND`，通知直接丢弃。

## 引用计数

body 是 Netty 的 `ByteBuf`，一路都不拷贝。三种记录遵循 Netty 的规则：

1. **交给节点的消息就归节点所有**：传给 `call` 的请求、传给 `notify` 或 `broadcast` 的通知、调用 `writeTo` 的应答。节点在发出后释放它们；发不出去，或因参数错误抛异常时，也由节点释放。交出之后就不要再使用。
2. **节点交给你的消息，只在你的方法执行期间有效**：`handleMessage` 里的消息和 `onResponse` 里的应答，方法返回后就会被释放。要留得更久，比如带到别的线程上用，先 `retain()`，用完再 `release()`。
3. **回调里的请求 body 为空**：请求发出后 body 就被释放了，其余字段都还在。
4. **转发收到的消息前，先 `retain()`**。比如网关把请求转给下一个节点：规则 1 和规则 2 各会释放一次。

## 对端与成员管理

成员关系由业务根据自己的配置或服务发现来驱动。

- **`node.createPeer(id, address[, connections])`** 用一条或多条连接主动连接一个节点，并立即返回它的对端对象。每条连接失败或断开后都会重连，默认间隔从 100 毫秒开始翻倍，最长 10 秒（可用 `redialDelay` 设置）。
- **主动连进来的节点**按握手里的 id 加入。它的连接全部断开后，对端对象仍然保留；该节点再次连进来时，会重新挂到同一个 `RpcPeer` 实例上。
- **`node.removePeer(id)`** 移除一个对端，不论连接由哪一方发起：等待中的调用以 `RPC_UNAVAILABLE` 失败，停止重连，关闭它的连接。该节点之后再连进来，会作为新的对端加入。连进来的对端在被移除前一直保留，所以节点会以新 id 上下线的集群，要在服务发现看到节点离开时移除它。
- **`node.peer(id)`** 返回该 id 的对端，没有则返回 null；**`node.peers()`** 返回所有对端的快照。

### 监听器

`RpcListener` 有两个方法，默认实现都为空：

| 方法 | 何时调用 |
| --- | --- |
| `onConnected(peer, restarted)` | 对端一个运行实例的全部连接都完成了握手 |
| `onDisconnected(peer)` | 对端的最后一条连接断开，包括被移除、被新实例替换的情况 |

只断了部分连接的对端仍算连着，不发事件；它的 key 改走剩下的连接。只要还有一条连接，`peer.isConnected()` 就为 true。同一个对端的回调不会重叠，并且按顺序到达；它们在发生变化的那条连接的 EventLoop 上执行，不能阻塞。监听器里可以调用、通知、应答任何对端：调用、通知和应答都不加锁，监听器不会去等另一个对端的锁。

### 启动

对端还没连上时，调用会立即以 `RPC_UNAVAILABLE` 失败（回调不在调用线程上执行），不会排队。如果一个节点必须等另一个节点连上才能开始服务（比如游戏服要先在世界服注册，才能放玩家进来），就在监听器里启动这部分工作：

```java
public void onConnected(RpcPeer peer, boolean restarted) {
    if (peer.nodeId() == WORLD) executor.execute(this::registerAndOpen);
}
```

### 重启

节点的每个运行实例都有一个**实例号**（incarnation），值为启动时间的毫秒数乘以 1000 再加一个随机数，随每次握手发给对端。本节点的实例号用 `node.incarnation()` 读，对端的用 `peer.incarnation()` 读。

- 监听器能区分重启和重连：实例号与上次见到的不同时，`onConnected(peer, restarted)` 里的 `restarted` 为 true。重启之后，本节点掌握的关于该对端的状态（比如某个游戏服上的玩家）都已失效；如果只是重连，这些状态仍然有效。
- **同一个对端同一时刻只连接一个实例。** 更新的实例会替换当前已连接的实例：旧实例的连接被关闭，其上等待中的调用立即以 `RPC_UNAVAILABLE` 失败，因为它已经不可能再应答了。这样，两个用同一 id 启动的进程不会分摊流量。如果已经有更新的实例在线，较旧的实例再来连接会被拒绝。这依赖于各台机器的时钟大致一致。

### 节点的用途

节点不知道服务类型，那是业务的事。每个节点把希望对端知道的信息放进握手 metadata，业务自己给对端分组：

```java
static final MetadataKey<Integer> SERVICE = MetadataKey.intKey(1);

RpcNode game = RpcNode.builder().nodeId(101)
        .metadata(Metadata.builder().put(SERVICE, GAME).build())
        .start();

// 在世界服上：按对端自报的用途分组
Map<Integer, Set<RpcPeer>> byService = new ConcurrentHashMap<>();
RpcListener grouping = new RpcListener() {
    public void onConnected(RpcPeer peer, boolean restarted) {
        byService.computeIfAbsent(peer.metadata().get(SERVICE), s -> ConcurrentHashMap.newKeySet()).add(peer);
    }
    public void onDisconnected(RpcPeer peer) { byService.get(peer.metadata().get(SERVICE)).remove(peer); }
};
```

一组节点里由哪一个处理某个 key，也由业务决定：取模、哈希，或者查表（比如"这个玩家所在的游戏服"）。

### 谁连谁

两个节点不能互相连接：本节点已经在连对方时，对方连进来会被拒绝；对一个已经连进来的节点调用 `createPeer` 会抛 `IllegalStateException`。两个节点都监听时，请约定一条所有节点都遵守的规则，比如"下层连上层"（网关连游戏服，游戏服连世界服），或者"id 小的连 id 大的"。

### 下线

协议里没有告别。计划内下线的节点自己通知对端：用业务自己的通知或服务发现告诉对端，让它们不再发来新的工作；然后把手头的工作做完、该应答的应答完，再调用 `node.close()`。

- **`node.close()`** 停止监听和重连，让所有等待中的调用以 `RPC_UNAVAILABLE` 失败，关闭所有连接，对端随即看到它离开。之后发起的调用同样失败，之后才到的应答不会再送达调用方。之前已经写出的数据（比如最后几个应答）会在每条连接关闭时发出去；只有繁忙的连接在那一刻 socket 接收不下的部分会丢失。它会阻塞到关闭完成，重复调用没有副作用。

## 准入

`RpcAuthenticator` 根据每个握手决定对端节点能否加入，两个方向的每条连接都会检查；返回 false 就关闭连接，连进来时被拒绝的节点不会留下对端对象。握手是明文数据，所以 metadata 里的密钥只能防止误连（比如测试服连到了正式集群），防不了攻击。

```java
RpcNode.builder()
        .metadata(Metadata.builder().put(CLUSTER, "live").build())
        .authenticator((connection, nodeId, metadata) -> "live".equals(metadata.get(CLUSTER)))
```

## 背压与上限

- 一条连接排队的数据超过高水位后，会被视为繁忙。水位默认取 `DEFAULT_WRITE_BUFFER_WATER_MARK`（低 1 MiB，高 4 MiB），除非 bootstrap 设置了 Netty 的 `WRITE_BUFFER_WATER_MARK`。繁忙的连接会立即拒绝新调用（`RPC_UNAVAILABLE`）和通知（`NOT_WRITABLE`），这样慢对端不会撑爆本节点的内存；但通过 `writeTo` 写出的应答照样接收。从其他线程发出的消息在等待连接的 EventLoop 时，按实际大小计入水位：除非 bootstrap 自己设置了 Netty 的 `MESSAGE_SIZE_ESTIMATOR`，节点会设置一个认识调用、通知和应答的估算器。
- **被调用方靠暂停读取来反压。** 处理器总是立即返回，socket 一直被读空，所以对端发得比本节点业务处理得快时，光靠 TCP 不会让它慢下来。业务队列快满时，在消息到达的那条连接上关掉 Netty 自带的开关，处理完积压后再打开：

  ```java
  ((NettyConnection) connection).channel().config().setAutoRead(false); // 设回 true 即恢复
  ```

  对端的这条连接随之写满、变为繁忙：它的通知返回 `NOT_WRITABLE`，调用以 `RPC_UNAVAILABLE` 失败。只能短暂停顿：任何一方连续三个心跳间隔读不到数据，就会关闭连接。
- 每个对端同时等待应答的调用最多 `maxPendingCalls` 个（默认 4096），超出的调用以 `RPC_UNAVAILABLE` 失败，直到有调用结束。每个对端有一张等待表，每个允许的调用占一个引用，默认约 16 KiB。
- 每个节点只发送和接收不超过自己 `maxFrameLength` 的帧，集群里各节点用同一个值：收到更长的帧就关闭连接。调用或通知超过上限时，先释放消息，再抛 `IllegalArgumentException`；应答超过上限时，改为发出 `RPC_PROTOCOL_ERROR`。

## 握手与心跳

- **握手。** 双方各发一个握手帧，内容是自己的协议版本、node id、实例号和 metadata；主动连接的一方先发，并且还带上这条连接的槽号和它总共开几条连接。握手完成前只接受握手帧，收到其他帧就关闭连接。以下情况会拒绝并关闭连接，同时记一条警告日志：
  - 双方的协议版本不同；
  - 应答的节点不是 `createPeer` 指定的那个（之后会重连）；
  - 连进来的节点自称是本节点的 id、给出的槽号超出它的连接数，或者本节点也在主动连它；
  - 本节点已关闭；
  - 准入检查拒绝；
  - 连进来的是某个对端的旧实例，而它的更新实例正在线。
- **心跳。** 心跳由主动连接的一方负责：每隔一个心跳间隔（默认 5 秒）发一个心跳，被连接方收到后立即回一个。双方各自每隔一个自己的间隔检查一次这段时间有没有收到任何数据；连续三次都没收到，就关闭连接，比如对端机器宕机但没有关闭连接的情况。所以集群里各节点用同一个心跳间隔。本节点主动建立的连接随后会重连。始终没有完成握手的连接，也按同样的规则关闭。读数据时只打一个标记，不为每条消息取时间戳。

每个节点只有一个时间轮，每 10 毫秒走一格，节点的所有定时都靠它：每个调用的超时，以及每条连接的心跳。心跳本身在该连接的 EventLoop 上执行。

## 可观测性

- `peer.pendingCount()`：发往该对端、还在等待应答的调用数。
- `peer.connectionCount()`、`peer.isConnected()`、`peer.incarnation()`，以及通过监听器获知的连接和重启事件。
- 每个调用的结果都会和它的请求一起交给回调；业务在自己的回调适配器里按对端和 command 统计延迟、超时和失败，对接自己使用的任何监控系统。

## 线程

| 内容 | 执行线程 |
| --- | --- |
| `RpcHandler.handleMessage`、`RpcAuthenticator` | 该连接的 EventLoop，按到达顺序 |
| `RpcListener` | 发生变化的那条连接的 EventLoop |
| `RpcCallback` | 应答到达的那条连接的 EventLoop；超时时：节点的时间轮线程；调用发出前就失败时：节点主动连接所用的某个 EventLoop，按 route key 选；对端的最后一条连接断开时：那条连接的 EventLoop；对端的新实例替换旧实例时：带来新实例的那条连接的 EventLoop；移除对端或关闭节点时：执行该操作的线程。永远不会在 `call` 内部、在调用线程上执行 |
| `call`、`notify`、`broadcast`、`writeTo`、`createPeer` | 任意线程 |
| `removePeer`、`close` | 节点的 EventLoop 以外的任意线程，`close` 还不能在时间轮线程上调用（它们会阻塞到连接关闭；在那些线程上调用会抛 `IllegalStateException`） |

以上回调都不能阻塞，业务逻辑应放在业务自己的线程上，比如它的 `GameRuntime`。

## 配置

| Builder 方法 | 默认值 | 含义 |
| --- | --- | --- |
| `nodeId(int)` | 必填 | 本节点 id，集群内唯一 |
| `metadata(Metadata)` | 空 | 握手时告诉每个对端的信息 |
| `listen(port)` / `listen(InetSocketAddress)` | 不监听 | 允许其他节点连接本节点；端口 0 会绑定一个临时端口（用 `node.port()` 读） |
| `handler(RpcHandler)` | 请求以 `HANDLER_NOT_FOUND` 失败 | 处理对端发来的消息 |
| `listener(RpcListener)` | 无 | 接收对端连接、断开和重启的通知 |
| `authenticator(RpcAuthenticator)` | 全部放行 | 决定谁能加入 |
| `bootstrap(Bootstrap)` / `serverBootstrap(ServerBootstrap)` | 节点自己的 EventLoop | 用于主动连接和监听的 Netty bootstrap，可设置 EventLoop、channel 类型和 socket 选项。handler 由节点设置；没设置的项，节点会在副本上补全 |
| `redialDelay(min, max)` | 100 毫秒、10 秒 | 主动建立的连接断开后，等多久再重连 |
| `timeout(Duration)` | 5 秒 | 调用没有单独指定超时时，等待应答的时长，最多再晚 10 毫秒的一格 |
| `heartbeatInterval(Duration)` | 5 秒 | 主动建立的连接多久发一次心跳；连续三个间隔收不到数据就关闭连接。集群里各节点用同一个值 |
| `maxFrameLength(int)` | 8 MiB | 本节点发送和接收的最大帧长度，包括长度字段。集群里各节点用同一个值 |
| `maxPendingCalls(int)` | 4096 | 每个对端同时等待应答的调用最多有几个 |

`start()` 按配置开始监听，并返回运行中的节点。绑定端口失败时，会先释放已启动的资源，再抛出 `NetworkException`。

bootstrap 就是 Netty 的 bootstrap，Netty 提供的配置都可以在这里设置：和业务其他服务共用 EventLoop、使用 epoll 或 io_uring 的 channel 类型、设置 socket 缓冲区、设置写水位。业务自己提供的 EventLoop 仍归业务所有，节点关闭时不会关掉它们，但会关闭自己在上面的连接。

## 线协议

每帧以长度为前缀（4 字节，大端，不含长度字段自身）。帧内全部为大端，先是一个类型字节，然后是：

| 帧 | 内容 |
| --- | --- |
| 握手 (0) | 版本 byte、nodeId int、incarnation long、槽号 unsigned short、槽数 unsigned short、metadata |
| 心跳 (1) | 无 |
| 请求 (2) | requestId int、剩余毫秒 int（0 表示没有）、command int、routeKey long、businessType byte、businessId long、metadata、body |
| 应答 (3) | requestId int、code int、metadata、body |
| 通知 (4) | command int、routeKey long、businessType byte、businessId long、metadata、body |

metadata 的格式是：先用 unsigned short 写长度，再写字节。握手和心跳是节点内部的帧，不会交给处理器。`command` 完全归业务所有，整个 `int` 范围都能用。被连接方原样回送主动方的槽号和槽数。收到未知类型的帧、超过本节点能接收长度的帧，或者长度不够放下自身字段的帧，都会关闭连接。本节点使用协议版本 1。

每条连接有一个编解码器，位于 flush 合并之后，负责双向分帧：发出的消息是一个包含长度的头部缓冲区，后面紧跟原样的 body；收到的 body 是读到的数据的一个切片。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

请先安装 managame-common 和 managame-network。测试通过真实的回环连接运行多个节点，并把 Netty 的泄漏检测开到最严格。覆盖以下内容：
- 立即应答、之后在别的线程上应答、retain 后再应答；
- 所有字段都送达被调用方，回调拿回自己的请求，以及 deadline；
- 各种失败码、节点级和调用级超时、只有第一个应答有效，以及回调不在调用线程上执行；
- 通知、广播，以及在主动方发起的连接上双向调用和通知；
- 多条连接下按 route key 保序，key 的连接断开时改走其他连接，请求所在连接断开后应答从另一条连接回来；
- 对端的最后一条连接断开时等待中的调用立即失败，这期间写出的应答丢失；
- 监听器：全部连接连上才算连上，全部断开才算断开；业务按握手 metadata 给对端分组；
- 通过一个能切断连接的代理，区分重启和重连，旧实例的调用立即失败；更新的实例替换旧实例；
- 握手：两个节点互相连接、以错误 id 应答、协议版本不同、握手前收到其他帧、超过最大长度的帧、槽号超出连接数、始终不握手的连接，以及两个方向的准入检查；
- 心跳保持空闲连接、主动方断开不再回应心跳的对端、被连接方关闭不再发心跳的主动方，以及回应心跳；
- 最大帧长度；繁忙的连接；等待调用的上限；
- 两个对端同时加入、各自的监听器互相调用对方时不会死锁，节点关闭前刚写出的应答仍能送达调用方；
- 移除对端、关闭节点、在节点的 EventLoop 和时间轮线程上调用阻塞方法被拒绝、回调和监听器抛异常，以及各种误用；
- 单独测试等待调用表：id、上限、全部失败，以及并发下每个调用都恰好结束一次。
