[English](README.md) | 中文

# managame-rpc

游戏服务器集群内各进程之间的调用。请求由和玩家消息相同的 `@HandleMethod` 处理，请求、结果和失败回调全部经 runtime 执行，所以按路由的执行顺序跨服务器同样成立。

- **`RpcNode`** 是当前进程：负责监听、连接配置的对端，并持有处理器。
- **`RpcPeer`** 是另一个进程，按 node id 识别，不论是谁发起的连接。一个对端可以有多条连接：本节点连向它的，以及它连向本节点的。每条连接都可以双向发起调用。

依赖 managame-runtime（并通过它依赖 managame-network 和 managame-common）。

## 快速开始

节点 1 是排行榜服务器；节点 2 是游戏服务器，用两条连接连向节点 1。请求由普通的 `@HandleMethod` 类处理：每个节点把玩家网关用的同一个 `Dispatcher` 交给 RpcNode，所以不管消息是谁发来的，处理器都只注册一次。

```java
// 节点 1
@Domain(RANK)
class RankService {
    @HandleMethod Integer rankOf(RankOf query) { return ranks.of(Context.current().businessId()); }
}
Dispatcher rankHandlers = Dispatcher.builder(rankRuntime).register(new RankService()).build();
RpcNode rankNode = RpcNode.builder(rankHandlers)
        .nodeId(1).codec(codec).listen(7001)
        .start();

// 节点 2
Dispatcher gameHandlers = Dispatcher.builder(gameRuntime).register(new Shop(), new Ranking(), new Mailbox()).build();
RpcNode gameNode = RpcNode.builder(gameHandlers)
        .nodeId(2).codec(codec)
        .peer(1, new InetSocketAddress("10.0.0.1", 7001), 2)
        .start();
```

节点 2 上的玩家请求，在调用的回调里应答：

```java
@Domain(PLAYER)
class Ranking {
    @HandleMethod void rank(HandleContext request, AskRank ask) {
        gameNode.peer(1).call(new RankOf(),
                (Context c, Integer rank) -> request.reply(rank),
                (c, errorCode) -> request.fail(errorCode));
    }
}
```

节点 2 不监听端口，节点 1 也可以通过节点 2 建立的连接反过来调用它：

```java
rankNode.peer(2).notice(GameContext.of(RANK, 0, ROLE, playerId), new Deliver(mail));
```

## 编解码

消息和结果怎么编码由业务通过 `RpcCodec` 决定：

```java
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;
    Object decode(ByteBuf body) throws Exception;   // body 之后会被释放，不要保留它
}
```

- 编码必须自带类型：接收方按解码出的消息类型选择处理器，所以字节里要带上类型（类型 id 加内容、protobuf `Any` 等）。
- 集群里所有节点使用同一种格式。编解码器必须线程安全：请求在调用方线程编码，结果在处理器所在线程编码，解码在连接的 EventLoop 上进行。

## 发起调用

| 方法 | 以谁的身份 | 应答 |
| --- | --- | --- |
| `peer.call(request, onResult, onFailure)` | 当前游戏任务（`Context.current()`） | 回调 |
| `peer.call(context, request, onResult, onFailure)` | `context`，任意位置都可调用 | 回调 |
| `peer.call(request, onResult)` | 当前游戏任务 | 失败只记日志 |
| `peer.notice(message)` | 当前任务；在任务之外则不代表任何人 | 无；没发出去返回 false |
| `peer.notice(context, message)` | `context` | 无；没发出去返回 false |

- `onResult` 和 `onFailure` 作为任务在调用方上下文的路由上执行，排在发起调用的工作之后，并与该路由上的其它任务保持顺序。调用方上下文是一个请求时，可以在回调里应答它。
- 调用不会因为网络问题抛异常。编解码器无法编码的请求会抛出编解码器的异常；在任务之外又没有传上下文时抛 `IllegalStateException`。
- runtime 已关闭而拒绝的回调会记录日志后丢弃。

调用可能收到的失败码：

| 错误码 | 何时出现 |
| --- | --- |
| `RPC_UNAVAILABLE` | 对端没有可用连接、连接无法接收请求，或者等待应答期间连接断开 |
| `RPC_TIMEOUT` | 在节点的超时时间内（默认 5 秒）没有应答 |
| `RPC_NODE_CLOSED` | 本节点已关闭 |
| `RPC_PROTOCOL_ERROR` | 请求或结果无法解码 |
| `RPC_HANDLER_ERROR` | 对端无法编码处理器返回的结果 |
| `HANDLER_NOT_FOUND`、`INVALID_ROUTE_KEY`、`RUNTIME_EXECUTION_ERROR`、`RUNTIME_CLOSED`、`ROUTE_EXECUTOR_OVERLOADED` | 对端的 Dispatcher 或 runtime 给出的应答 |

## 处理请求

- 请求和通知由传给 `RpcNode.builder(...)` 的 `Dispatcher` 中的处理器执行，通常就是业务玩家网关用的那一个，连同它的拦截器。调用结果在该 Dispatcher 的 runtime 上执行。
- 请求带着调用方的上下文：domain、路由键、业务类型与 id、metadata。在接收方，它在处理器所在的 domain 中执行，路由键由该 domain 的 resolver 决定，并以调用方的业务身份、带着调用方的 metadata 执行。因此用 `RouteKeys.businessId()` 路由的玩家 domain，会在请求所针对的那个玩家上执行。
- 返回非 null 即为应答；参数带 `HandleContext` 的处理器可以稍后通过它应答。通知（notice）分发时没有应答方式。
- `HandleContext.source()` 是一个 `RpcSource`：包含调用方的各字段、`nodeId()`，以及用于回调调用方节点的 `peer()`。
- 哪些处理器玩家可以调到由业务决定：客户端协议只解码玩家允许发送的消息，Dispatcher 上的拦截器也可以通过来源区分对端节点和玩家。`before` 返回的错误码会送到调用方的失败回调：

  ```java
  class PeersOnlyCheck implements HandleInterceptor {
      public int before(HandleContext context, Method handler, Object message) {
          return handler.isAnnotationPresent(PeersOnly.class) && !(context.source() instanceof RpcSource)
                  ? HANDLER_NOT_FOUND : SUCCESS;
      }
  }
  ```

## 对端与连接

- **握手**：双方各发送一个带 node id 的 `Hello`，发起连接的一方先发。收到对方的 Hello 之后，这条连接才开始承载调用。发起方会核对连上的节点是否是配置的那个，不一致就关闭连接；Hello 中是本节点自己的 id，或者 Hello 之前收到其它帧，也会关闭连接。
- **配置的对端**：`.peer(id, address)` 建立一条连接，`.peer(id, address, connections)` 建立多条。每条连接断开后都会重连，间隔从 100 ms 开始翻倍，最长 10 秒。
- **连进来的对端**按 Hello 中的 id 加入，可以和配置的对端一样被调用。
- **`node.peer(id)`** 对本节点以外的任意 id 返回对应的对端，同一个 id 总是同一个实例。在它有连接之前，对它的调用以 `RPC_UNAVAILABLE` 失败。连接都断开后对端依然保留。`isConnected()` 和 `connectionCount()` 反映它当前的连接。
- **连接选择**：调用使用调用方上下文的 `(domain, routingKey)` 哈希到的那条连接。因此只要对端的连接没有变化，同一路由的消息会按发送顺序到达对端，不同的路由则分散到各条连接上。
- **连接断开**：只让在这条连接上等待应答的调用以 `RPC_UNAVAILABLE` 失败。
- **存活检测**：连接的任一端在空闲超时的三分之一时间内没有写出任何帧，就发送一个空的 Ping，所以存活的连接不会长时间没有数据。整个空闲超时（默认 15 秒）内什么都没收到的连接被认为已经失效并关闭，例如对端机器宕机却没有断开连接：在这条连接上等待的调用立即以 `RPC_UNAVAILABLE` 失败，不必等到调用超时；本节点发起的连接随后重连。一直不发送 Hello 的连接也按同样规则关闭。有业务流量的连接不会发送 Ping。

## 配置

`RpcNode.builder(dispatcher)` 传入业务的 Dispatcher，然后：

| Builder 方法 | 默认值 | 含义 |
| --- | --- | --- |
| `nodeId(int)` | 必填 | 本节点的 id，对端以此识别它 |
| `codec(RpcCodec)` | 必填 | 消息编解码，所有节点一致 |
| `listen(port)` / `listen(address)` | 不监听 | 接受对端的连接；只发起调用的节点可以不监听 |
| `peer(id, address[, connections])` | 无 | 要连接的节点，一条或多条连接 |
| `timeout(Duration)` | 5 秒 | 调用等待应答的时长 |
| `idleTimeout(Duration)` | 15 秒 | 连接多久收不到任何数据就关闭；空闲达到它的三分之一时发送 Ping。所有节点必须一致 |
| `maxFrameLength(int)` | 8 MiB | 帧的最大长度 |

`start()` 开始监听和连接，返回运行中的节点。绑定失败时释放已启动的资源，然后抛出 `NetworkException`。

## 传输格式

帧带 4 字节大端长度头（不含自身）。帧内为大端，先是一个类型字节，然后：

| 帧 | 布局 |
| --- | --- |
| Hello (0) | nodeId int |
| Request (1) | requestId int（通知为 0），然后是调用方的 domain int、routingKey long、businessType byte、businessId long、metadata 长度 unsigned short、metadata 字节，最后是 body |
| Response (2) | requestId int，body |
| Failure (3) | requestId int，errorCode int |
| Ping (4) | 无内容；任何时候都可以收到，包括 Hello 之前 |

body 就是编解码器写出的内容，传输过程中不会复制。

## 关闭

`close()` 让所有等待中的调用以 `RPC_NODE_CLOSED` 失败，停止监听并关闭所有连接。已交给 runtime 的请求仍会执行，但它们的应答可能无法再送达调用方。先关闭节点，再关闭它的 runtime。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-runtime。测试在真实回环连接上运行两个节点，覆盖：以调用方身份路由并在其路由上应答、在回调中应答玩家、通过对方建立的连接双向调用、多连接下的顺序、拦截器、对端返回的失败码、超时、通知、对端启动前不可用、对端返回错误的 node id、Ping 让空闲连接保持可用、对端静默时连接被关闭且调用立即失败、不发送 Hello 的连接被关闭、连接断开、关闭节点，以及错误用法。
