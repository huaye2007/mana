[English](README.md) | 中文

# managame-rpc

游戏服务器集群内各进程之间的调用与通知。它是独立组件，只依赖 managame-network 和 managame-common。消息的含义、编码方式、在哪个线程上处理，都由业务决定。

- **`RpcNode`** 是当前进程。任何节点都可以主动连接别的节点，开了监听的节点也可以被别人连接。两个节点之间只由一方发起连接；不监听的节点只能主动去连。
- **`RpcPeer`** 是另一个节点，按 node id 识别，不论连接由哪一方发起。连上之后，双方在同一组连接上对等地互相调用、发通知。一个对端可以有多条连接。

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

节点 1 也可以通过节点 2 发起的那条连接反过来调用它：`world.peer(2).notify(RpcNotify.of(MAIL, playerId, body))`。

## 消息

| 记录 | 字段 |
| --- | --- |
| `RpcRequest` | `requestId`、`deadline`、`command`、`routeKey`、`businessType`、`businessId`、`metadata`、`body` |
| `RpcNotify` | `command`、`routeKey`、`businessType`、`businessId`、`metadata`、`body` |
| `RpcResponse` | `requestId`、`code`、`metadata`、`body` |

- 除 `requestId`、`deadline` 和 `code` 外，所有字段都归业务所有，原样传输：
  - `command` 表示请求的内容；
  - `businessType` 和 `businessId` 表示涉及的业务对象；
  - `metadata` 是 common 的 `Metadata`，用来携带追踪 id、调用方身份等信息。

  节点自己只读 `routeKey`，用来选连接。
- `requestId` 和 `deadline` 由发起调用的节点分配。`RpcRequest.of(...)` 把它们设为 0，调用时会重新分配，覆盖请求里原有的值。被调用方用同一个 id 回一个 `RpcResponse`。
- `deadline` 是持有该请求的进程的 `System.nanoTime()` 值，表示调用方停止等待的时间点。它在线上以"剩余时间"传输，所以两台机器的时钟不必一致。被调用方可以用 `request.isExpired()` 判断应答是否已经没人等了，比如在执行一个排过队的请求之前先检查一下。
- `RpcResponse.of(requestId, [metadata,] body)` 表示成功（code 为 0）；`RpcResponse.failure(requestId, code)` 表示失败，code 必须是正数。用 `response.writeTo(connection)` 发送（见[处理消息](#处理消息)）。
- body 是 Netty 的 `ByteBuf`。三种记录都通过 body 做引用计数，和 Netty 自己的消息一样。

## 调用

| 方法 | 应答 |
| --- | --- |
| `peer.call(request, callback)` | 在节点的超时时间内执行 `callback.onResponse(request, response)`，恰好一次 |
| `peer.call(request, timeout, callback)` | 同上，超时时间为 `timeout` |
| `peer.notify(notify)` | 没有应答；发不出去时返回 false |
| `node.broadcast(peers, notify)` | 给每个对端各发一个通知，所有通知共用同一个 body；返回成功发出的个数 |

- **顺序。** 调用和通知按 `routeKey` 选连接。只要对端的连接没有变化，同一个 key 的消息就按发送顺序到达对端。比如用玩家 id 作为 key，就能保证同一个玩家的消息有序。
- **回调**拿到它所应答的请求：带有调用时分配的 id 和 deadline，但不带 body。业务可以按请求的 command、route key 和业务字段继续处理，比如交给这个 key 对应的执行器。回调不能阻塞；它抛出的异常只记日志。
- **等待中的调用归对端所有。** 每个调用都记住自己走的连接。以下任一情况都会结束这个调用：应答到达、超时、该连接关闭、对端被移除、节点关闭。
- 网络问题不会抛异常。回调收到的是被调用方的应答（可能带有被调用方自己的失败码），或者下列失败码之一：

| 错误码 | 何时出现 |
| --- | --- |
| `RPC_UNAVAILABLE` | 对端没有可用连接或正在关闭、连接太忙接不下这个请求，或应答到达前连接已关闭 |
| `RPC_TIMEOUT` | 超时时间内没有应答（默认 5 秒） |
| `RPC_PROTOCOL_ERROR` | 请求或它的应答超过最大帧长度 |
| `RPC_PEER_REMOVED` | 对端在调用前或调用期间被移除 |
| `RPC_NODE_CLOSED` | 本节点在调用前或调用期间被关闭 |
| `RPC_HANDLER_ERROR` | 被调用方的处理器抛出了异常 |
| `HANDLER_NOT_FOUND` | 被调用方没有处理器 |

**调用失败不代表请求没有执行。** 收到 `RPC_UNAVAILABLE` 或 `RPC_TIMEOUT` 时，请求可能已经到达对端并且执行过了。所以跨服操作在重试之前，应先设计成幂等的，比如在 metadata 里带一个操作 id，由被调用方记住执行过哪些。

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

- `rpcPeer` 是发送消息的节点，可以用它回调对方。`connection` 是消息到达的那条 network `Connection`。应答请求时，用请求的 `requestId` 构造 `RpcResponse` 写到这条连接上，可以立即写，也可以之后在任意线程写。只有第一个应答有效，调用方会丢弃后面的应答。
- **`response.writeTo(connection)`** 在连接繁忙时也会写出应答：应答对应的工作已经做完，丢掉它只会让调用方无法确定结果。连接已关闭时，它负责释放这个应答。直接用 `connection.write(response)` 也可以，但连接繁忙时会被拒绝，而且被拒后需要你自己释放。
- 超过最大帧长度的应答，会改成以 `RPC_PROTOCOL_ERROR` 发出，连接不会因此断开。
- 到达时就已过期的请求，会直接丢弃，不交给处理器。
- 处理器在该连接的 EventLoop 上按消息到达顺序执行，不能阻塞。
- 处理器抛出异常时：对于请求，如果还没应答过，就以 `RPC_HANDLER_ERROR` 失败；对于通知，只记日志。没有设置处理器的节点，对请求回复 `HANDLER_NOT_FOUND`，通知直接丢弃。

## 引用计数

body 是 Netty 的 `ByteBuf`，一路都不拷贝。三种记录遵循 Netty 的规则：

1. **交给节点的消息就归节点所有。** 包括：传给 `call` 的请求、传给 `notify` 或 `broadcast` 的通知、调用 `writeTo` 的应答。节点在发出后释放它们；发不出去，或调用因参数错误抛异常时，也由节点释放。交出之后就不要再使用。
2. **自己写到连接上的消息，写入被接受后归 Netty 所有。** 写入返回 `ACCEPTED` 以外的结果时，仍由你自己释放，和 network 的规则一样。
3. **节点交给你的消息，只在你的方法执行期间有效。** 包括 `handleMessage` 里的消息和 `onResponse` 里的应答，方法返回后就会被释放。要留得更久，比如带到别的线程上使用，先 `retain()`，用完再 `release()`。
4. **回调里的请求 body 为空。** 请求发出后 body 就被释放了，其余字段都还在。
5. **转发收到的消息前，先 `retain()`。** 比如网关把请求转给下一个节点：规则 1 和规则 3 各会释放一次。

## 对端与成员管理

成员关系由业务根据自己的配置或服务发现来驱动。

- **`node.createPeer(id, address[, connections])`** 用一条或多条连接主动连接一个节点，并立即返回它的对端对象。每条连接失败或断开后都会重连，默认间隔从 100 毫秒开始翻倍，最长 10 秒（可用 `redialDelay` 设置）。
- **主动连进来的节点**按握手里的 id 加入。对端的连接全部断开后，对端对象仍然保留；该节点再次连进来时，会重新挂到同一个 `RpcPeer` 实例上。
- **`node.removePeer(id)`** 移除一个对端，不论连接由哪一方发起。它会：让等待中的调用、以及之后通过这个已移除实例发起的调用以 `RPC_PEER_REMOVED` 失败；停止重连；关闭它的连接。该节点之后再连进来，会作为新的对端加入。
- **`node.peer(id)`** 返回该 id 的对端，没有则返回 null；`node.peers()` 返回所有对端的快照。

### 启动

对端还没连上时，调用会立即以 `RPC_UNAVAILABLE` 失败，不会排队。如果一个节点必须等另一个节点连上才能开始服务（比如游戏服要先在世界服注册，才能放玩家进来），就在监听器里启动这部分工作：

```java
public void onConnected(RpcPeer peer, boolean restarted) {
    if (peer.nodeId() == WORLD) executor.execute(this::registerAndOpen);
}
```

### 重启

节点的每个运行实例都有一个**实例号**（incarnation），值为启动时间的毫秒数乘以 1000 再加一个随机数，随每次握手发给对端。本节点的实例号用 `node.incarnation()` 读，对端的用 `peer.incarnation()` 读。

- 监听器能区分重启和重连：对端的实例号与上次见到的不同时，`onConnected(peer, restarted)` 里的 `restarted` 为 true。重启之后，本节点掌握的关于该对端的状态（比如某个游戏服上的玩家）都已失效；如果只是重连，这些状态仍然有效。
- **同一个对端同一时刻只连接一个实例。** 更新的实例连进来时，会替换当前已连接的实例：旧实例的连接被关闭，其上等待中的调用以 `RPC_UNAVAILABLE` 失败。这样，两个用同一 id 启动的进程不会分摊流量。如果已经有更新的实例在线，较旧的实例再来连接会被拒绝。这依赖于各台机器的时钟大致一致。

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

两个节点不能互相连接，后建立的那条连接会被拒绝。两个节点都监听时，请约定一条所有节点都遵守的规则，比如"下层连上层"（网关连游戏服，游戏服连世界服），或者"id 小的连 id 大的"。

### 监听器

`RpcListener` 有三个方法，默认实现都为空：

| 方法 | 何时调用 |
| --- | --- |
| `onConnected(peer, restarted)` | 对端的第一条连接完成握手 |
| `onDisconnected(peer)` | 对端的最后一条连接断开，包括被移除、被更新实例替换的情况 |
| `onClosing(peer)` | 对端宣布正在关闭：此后发给它的调用和通知都会立即失败，请改用其他节点 |

同一个对端的回调不会重叠，并且按顺序到达。它们在 EventLoop 上执行，不能阻塞。

## 关闭与排空

- **`node.close(Duration drain)`** 先排空再关闭，用于滚动更新或停服维护。它会：
  1. 停止监听；
  2. 向每个对端发送 Goodbye，对端此后不再给它发新消息（`peer.isClosing()` 变为 true，并触发 `onClosing`）；
  3. 等到它应答完对端发来的所有请求、自己发出的调用也都结束，或者 `drain` 时间用完；
  4. 按下面 `close()` 的方式关闭。

  排空期间，它自己发出的调用照常进行，所以处理器可以把手头的工作做完。
- **`node.close()`** 立即关闭：所有等待中的调用、以及之后发起的调用都以 `RPC_NODE_CLOSED` 失败；停止监听和重连；关闭所有连接。之后才到达的应答不会再送达调用方。

两者都会阻塞到关闭完成，重复调用没有副作用。

## 安全

- **TLS。** `serverSsl(SslContext)` 保护其他节点连进来的连接，`clientSsl(SslContext)` 保护本节点主动发起的连接。主动连接会按所连的主机名校验对方证书（具体按 client context 的配置）。在 server context 上开启客户端认证，就是双向认证。
- **准入。** `RpcAuthenticator` 根据每个 Hello 决定对端节点能否加入，两个方向的每条连接都会检查；返回 false 就关闭连接。连进来时被拒绝的节点不会留下对端对象。如果不走 TLS，握手 metadata 里的密钥只能防止误连，防不了攻击。

```java
RpcNode.builder()
        .serverSsl(serverContext).clientSsl(clientContext)
        .authenticator((connection, nodeId, metadata) -> cluster.allows(nodeId, metadata.get(TOKEN)))
```

## 背压与帧长度

- 一条连接排队的数据超过高水位后，会被视为繁忙。水位默认取 `DEFAULT_WRITE_BUFFER_WATER_MARK`（低 1 MiB，高 4 MiB），除非 bootstrap 设置了 Netty 的 `WRITE_BUFFER_WATER_MARK`。繁忙的连接会立即拒绝新调用（返回 `RPC_UNAVAILABLE`）和通知（返回 false），这样慢对端不会撑爆本节点的内存；但通过 `writeTo` 写出的应答照样接收。
- 超过 `maxFrameLength` 的消息在发出前就会单独失败，不影响其他消息：调用得到 `RPC_PROTOCOL_ERROR`，通知返回 false，应答改为 `RPC_PROTOCOL_ERROR`。集群内所有节点必须使用相同的 `maxFrameLength`。

## 握手与保活

- **握手。** 双方各发一个 Hello，内容是自己支持的协议版本范围、node id、实例号和 metadata，主动连接的一方先发。双方使用共同支持的最高版本。握手完成前只接受 Hello，收到其他帧就关闭连接。以下情况会拒绝并关闭连接，同时记一条警告日志：
  - 双方没有共同支持的协议版本；
  - 应答的节点不是 `createPeer` 指定的那个（之后会重连）；
  - 连进来的节点自称是本节点的 id，或者本节点也在主动连它（对一个已经连进来的节点调用 `createPeer` 会抛 `IllegalStateException`）；
  - 本节点正在关闭；
  - 准入检查拒绝；
  - 连进来的是某个对端的旧实例，而它的更新实例正在线。
- **保活。** 连接的任一方如果在空闲超时的三分之一时间内没写过任何数据，就发一个 Ping。连接在整个空闲超时内（默认 15 秒）没收到任何数据，就被视为已失效并关闭，比如对端机器宕机但没有关闭连接的情况。这时连接上的调用会立即以 `RPC_UNAVAILABLE` 失败，不必等到各自超时；本节点主动建立的连接会重连。始终没有完成握手的连接，也按同样的规则关闭。繁忙的连接不发 Ping。

## 可观测性

- `peer.pendingCount()`：发往该对端、还在等待应答的调用数。
- `node.unansweredCount()`：对端发来的请求中，本节点既没应答、也没过期的数量。
- `peer.connectionCount()`、`peer.isConnected()`、`peer.isClosing()`、`peer.incarnation()`，以及通过监听器获知的连接、重启和关闭事件。
- `RpcObserver` 能看到本节点发出的每个调用如何结束，无论成功还是失败。它在回调之前执行，拿到请求、应答和耗时，足以按对端和 command 统计延迟、超时和失败，对接业务自己使用的任何监控系统。

## 线程

| 内容 | 执行线程 |
| --- | --- |
| `RpcHandler.handleMessage`、`RpcAuthenticator` | 该连接的 EventLoop，按到达顺序 |
| `RpcListener` | 建立、断开或发出 Goodbye 的那条连接的 EventLoop |
| `RpcCallback`、`RpcObserver` | 应答到达或连接关闭时：该连接的 EventLoop；超时：节点的定时器线程；移除对端或关闭节点时：执行该操作的线程；调用还没发出就失败时：调用方线程 |
| `call`、`notify`、`broadcast`、`writeTo` | 任意线程 |
| `removePeer`、`close` | 节点的 EventLoop 和定时器线程以外的任意线程（它们会阻塞到连接关闭；在那些线程上调用会抛 `IllegalStateException`） |

以上回调都不能阻塞，业务逻辑应放在业务自己的线程上，比如它的 `GameRuntime`。

## 配置

| Builder 方法 | 默认值 | 含义 |
| --- | --- | --- |
| `nodeId(int)` | 必填 | 本节点 id，集群内唯一 |
| `metadata(Metadata)` | 空 | 握手时告诉每个对端的信息 |
| `listen(port)` / `listen(address)` | 不监听 | 允许其他节点连接本节点；地址可以是 server bootstrap 的 channel 能绑定的任意地址 |
| `handler(RpcHandler)` | 请求以 `HANDLER_NOT_FOUND` 失败 | 处理对端发来的消息 |
| `listener(RpcListener)` | 无 | 接收对端连接、断开、重启和关闭的通知 |
| `authenticator(RpcAuthenticator)` | 全部放行 | 决定谁能加入 |
| `observer(RpcObserver)` | 无 | 观察每个调用的结束 |
| `serverSsl(SslContext)` / `clientSsl(SslContext)` | 明文 TCP | 被连接和主动连接的 TLS |
| `bootstrap(Bootstrap)` / `serverBootstrap(ServerBootstrap)` | 节点自己的 EventLoop | 用于主动连接和监听的 Netty bootstrap，可设置 EventLoop、channel 类型和 socket 选项。handler 由节点设置；没设置的项，节点会在副本上补全 |
| `redialDelay(min, max)` | 100 毫秒、10 秒 | 主动建立的连接断开后，等多久再重连 |
| `timeout(Duration)` | 5 秒 | 调用没有单独指定超时时，等待应答的时长 |
| `idleTimeout(Duration)` | 15 秒 | 连接多久收不到数据就关闭；超过它的三分之一没写数据就发 Ping。所有节点必须相同 |
| `maxFrameLength(int)` | 8 MiB | 最大帧长度，包括长度字段。所有节点必须相同 |

`start()` 按配置开始监听，并返回运行中的节点。绑定端口失败时，会先释放已启动的资源，再抛出 `NetworkException`。

### Netty 自己的配置

bootstrap 就是 Netty 的 bootstrap，Netty 提供的配置都可以在这里设置：和业务其他服务共用 EventLoop、使用 epoll 或 io_uring 的 channel 类型、设置 socket 缓冲区、设置写水位。业务自己提供的 EventLoop 仍归业务所有，节点关闭时不会关掉它们，但会关闭自己在上面的连接。借助 Netty 的本地传输，多个节点可以在同一个进程内运行、不开端口，比如用于测试：

```java
EventLoopGroup group = new MultiThreadIoEventLoopGroup(LocalIoHandler.newFactory());
RpcNode world = RpcNode.builder().nodeId(1)
        .serverBootstrap(new ServerBootstrap().group(group).channel(LocalServerChannel.class))
        .listen(new LocalAddress("world"))
        .start();
RpcNode game = RpcNode.builder().nodeId(2)
        .bootstrap(new Bootstrap().group(group).channel(LocalChannel.class))
        .start();
game.createPeer(1, new LocalAddress("world"));
```

## 线协议

每帧以长度为前缀（4 字节，大端，不含长度字段自身）。帧内全部为大端，先是一个类型字节，然后是：

| 帧 | 内容 |
| --- | --- |
| Hello (0) | 最低版本 byte、最高版本 byte、nodeId int、incarnation long、metadata |
| Request (1) | requestId int、剩余毫秒 int（0 表示没有）、command int、routeKey long、businessType byte、businessId long、metadata、body |
| Notify (2) | command int、routeKey long、businessType byte、businessId long、metadata、body |
| Response (3) | requestId int、code int、metadata、body |
| Ping (4) | 无 |
| Goodbye (5) | 无 |

metadata 的格式是：先用 unsigned short 写长度，再写字节。Hello、Ping 和 Goodbye 是节点内部的帧，不会交给处理器。`command` 完全归业务所有，整个 `int` 范围都能用。以后的版本可以在 Hello 末尾追加字段，当前版本会忽略这些字段。收到未知类型的帧会关闭连接。本节点使用协议版本 1。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

请先安装 managame-common 和 managame-network。测试通过真实的回环连接运行多个节点，并把 Netty 的泄漏检测开到最严格。覆盖以下内容：
- 立即应答、之后在别的线程上应答、retain 后再应答；
- 所有字段都送达被调用方，回调拿回自己的请求，以及 deadline；
- 各种失败码、节点级和调用级超时，以及只有第一个应答有效；
- 通知、广播，以及在主动方发起的连接上双向调用和通知；
- 多条连接下按 route key 保序；
- 业务按握手 metadata 给对端分组；
- 通过一个能切断连接的代理，区分重启和重连；更新的实例替换旧实例；
- 握手相关的拒绝和校验：两个节点互相连接、以错误 id 应答、协议版本协商、握手前收到其他帧、始终不握手的连接、两个方向的准入检查，以及 TLS；
- Ping 保持空闲连接，沉默的对端被断开且其调用立即失败；
- 对端启动前不可达，启动后自动连上；
- 超过帧长度的消息单独失败；
- 观察者和各项计数；
- 先排空再关闭、两个方向的移除对端、对端离开、关闭节点；
- 使用业务自己的 bootstrap 和 Netty 本地传输；
- 各种路径下 body 都被释放，回调、监听器和观察者抛异常，以及各种误用。
