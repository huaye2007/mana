[English](README.md) | 中文

# managame-rpc

游戏服务器集群内各进程之间的调用。它是独立组件，只依赖 managame-network 和 managame-common：请求怎么处理、应答在哪个线程上使用，都由业务决定。

- **`RpcNode`** 是当前进程：负责监听、连接配置的对端，并把对端的请求交给业务的 `RpcHandler`。
- **`RpcPeer`** 是另一个进程，按 node id 识别，不论是谁发起的连接。一个对端可以有多条连接：本节点连向它的，以及它连向本节点的。每条连接都可以双向发起调用。

## 快速开始

节点 1 是排行榜服务器；节点 2 是游戏服务器，用两条连接连向节点 1。

```java
// 节点 1
RpcNode rankNode = RpcNode.builder()
        .nodeId(1).codec(codec).listen(7001)
        .handler(request -> {
            if (request.message() instanceof RankOf query)
                executor.execute(() -> request.reply(ranks.of(query.playerId())));
            else
                request.fail(HANDLER_NOT_FOUND);
        })
        .start();

// 节点 2
RpcNode gameNode = RpcNode.builder()
        .nodeId(2).codec(codec)
        .peer(1, new InetSocketAddress("10.0.0.1", 7001), 2)
        .start();

gameNode.peer(1).call(new RankOf(playerId), RpcCallback.of(
        rank -> executor.execute(() -> show(rank)),
        errorCode -> log.warn("rank failed: " + errorCode)));
```

节点 2 不监听端口，节点 1 也可以通过节点 2 建立的连接反过来调用它：`rankNode.peer(2).notice(new Deliver(mail))`。

## 编解码

消息和结果怎么编码由业务通过 `RpcCodec` 决定：

```java
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;
    Object decode(ByteBuf body) throws Exception;   // body 之后会被释放，不要保留它
}
```

- 编码必须自带类型：接收方靠解码出的内容区分消息，所以字节里要带上类型（类型 id 加内容、protobuf `Any` 等）。
- 集群里所有节点使用同一种格式。编解码器必须线程安全：请求在调用方线程编码，结果在应答的线程编码，解码在连接的 EventLoop 上进行。

## 发起调用

| 方法 | 应答 |
| --- | --- |
| `peer.call(request, callback)` | `callback.onResult(result)` 或 `callback.onFailure(errorCode)` |
| `peer.call(request, metadata, key, callback)` | 同上，附带 `metadata`，并走 `key` 选中的连接 |
| `peer.notice(message)` / `peer.notice(message, metadata, key)` | 无；没发出去返回 false |

- **`RpcCallback`** 有 `onResult` 和 `onFailure` 两个方法，只会执行其中一个、一次。`RpcCallback.of(onResult, onFailure)` 可以用两个 lambda 构造。回调在网络线程上执行（连接的 EventLoop、超时线程，或者调用立即失败时的调用方线程），不能阻塞：耗时的工作交给业务自己的线程。回调抛出的异常会记录日志。
- **`metadata`** 是 common 的 `Metadata`，随请求送到对端：trace id、调用方身份等。
- **`key`** 决定走哪条连接：同一个 key 的消息按发送顺序到达对端（只要对端的连接没有变化）。例如用玩家 id 作为 key，保证同一个玩家的消息有序。
- 调用不会因为网络问题抛异常；编解码器无法编码的请求会抛出编解码器的异常。

调用可能收到的失败码：

| 错误码 | 何时出现 |
| --- | --- |
| `RPC_UNAVAILABLE` | 对端没有可用连接、连接无法接收请求，或者等待应答期间连接断开 |
| `RPC_TIMEOUT` | 在节点的超时时间内（默认 5 秒）没有应答 |
| `RPC_NODE_CLOSED` | 本节点已关闭 |
| `RPC_PROTOCOL_ERROR` | 请求或结果无法解码 |
| `RPC_HANDLER_ERROR` | 对端的处理器抛了异常，或者结果无法编码 |
| `HANDLER_NOT_FOUND` | 对端没有处理器，或者它的处理器这样应答 |
| 其它 | 对端处理器给出的失败码 |

## 处理请求

builder 上设置的 `RpcHandler` 以 `RpcRequest` 的形式收到每个请求和通知：

| 方法 | |
| --- | --- |
| `peer()` | 发送它的节点，可用来回调 |
| `message()` | 解码后的消息 |
| `metadata()` | 发送方附带的内容 |
| `expectsAnswer()` | 通知为 false |
| `reply(result)` / `fail(errorCode)` | 应答一次，可在任意线程、在处理器返回之后调用；通知或重复应答返回 false |

- 处理器在连接的 EventLoop 上执行，不能阻塞：把请求交给业务执行逻辑的地方，在那里应答。
- 处理器抛异常时以 `RPC_HANDLER_ERROR` 应答失败；结果无法编码时同样如此。
- 没有设置处理器的节点，以 `HANDLER_NOT_FOUND` 应答请求，丢弃通知。

## 对端与连接

- **握手**：双方各发送一个带 node id 的 `Hello`，发起连接的一方先发。收到对方的 Hello 之后，这条连接才开始承载调用。发起方会核对连上的节点是否是配置的那个，不一致就关闭连接；Hello 中是本节点自己的 id，或者 Hello 之前收到其它帧，也会关闭连接。
- **配置的对端**：`.peer(id, address)` 建立一条连接，`.peer(id, address, connections)` 建立多条。每条连接断开后都会重连，间隔从 100 ms 开始翻倍，最长 10 秒。
- **连进来的对端**按 Hello 中的 id 加入，可以和配置的对端一样被调用。
- **`node.peer(id)`** 对本节点以外的任意 id 返回对应的对端，同一个 id 总是同一个实例。在它有连接之前，对它的调用以 `RPC_UNAVAILABLE` 失败。连接都断开后对端依然保留。`isConnected()` 和 `connectionCount()` 反映它当前的连接。
- **连接断开**：只让在这条连接上等待应答的调用以 `RPC_UNAVAILABLE` 失败。
- **存活检测**：连接的任一端在空闲超时的三分之一时间内没有写出任何帧，就发送一个空的 Ping，所以存活的连接不会长时间没有数据。整个空闲超时（默认 15 秒）内什么都没收到的连接被认为已经失效并关闭，例如对端机器宕机却没有断开连接：在这条连接上等待的调用立即以 `RPC_UNAVAILABLE` 失败，不必等到调用超时；本节点发起的连接随后重连。一直不发送 Hello 的连接也按同样规则关闭。有业务流量的连接不会发送 Ping。

## 配置

| Builder 方法 | 默认值 | 含义 |
| --- | --- | --- |
| `nodeId(int)` | 必填 | 本节点的 id，对端以此识别它 |
| `codec(RpcCodec)` | 必填 | 消息编解码，所有节点一致 |
| `handler(RpcHandler)` | 请求以 `HANDLER_NOT_FOUND` 失败 | 处理对端的请求和通知 |
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
| Request (1) | requestId int（通知为 0），metadata 长度 unsigned short，metadata 字节，body |
| Response (2) | requestId int，body |
| Failure (3) | requestId int，errorCode int |
| Ping (4) | 无内容；任何时候都可以收到，包括 Hello 之前 |

body 就是编解码器写出的内容，传输过程中不会复制。

## 关闭

`close()` 让所有等待中的调用以 `RPC_NODE_CLOSED` 失败，停止监听并关闭所有连接。已交给处理器的请求仍可能被应答，但应答可能无法再送达调用方。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-network。测试在真实回环连接上运行两个节点，覆盖：立即应答与稍后应答、metadata、失败码（拒绝、未知消息、处理器抛异常、结果无法编码、没有处理器的节点）、超时、通知、通过对方建立的连接双向调用、多连接下按 key 保持顺序、回调抛异常、Ping 让空闲连接保持可用、对端静默时连接被关闭且调用立即失败、不发送 Hello 的连接被关闭、对端启动前不可用、对端返回错误的 node id、连接断开、关闭节点，以及错误用法。
