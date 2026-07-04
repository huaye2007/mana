[English](README.en.md) | 中文

# game-rpc

对内（服务间）RPC 框架，基于 Netty + game-serialization。直接编解码 API 对象（无中间帧对象），
请求/响应按 requestId 关联，支持 oneway、future、callback 三种调用风格，内置握手协商、心跳保活、
断线重连、背压与在途上限保护、按服务广播。

## 设计要点

- **对等**：客户端、服务端共用 `RpcContainer` 基类。建连方向无关，握手后双方都能发起 `invoke`/`oneway`
  （服务端可反向调用已连上的客户端）。
- **peer 模型**：一个对端实例 = 一个 `RpcPeer`（`serviceName` + `serviceId`），下挂一组连接
  （`ConnectionGroup`，按 `routeKey` 环形取模选连接）+ 在途调用管理（`RpcInvokeManager`，requestId 在 peer 内唯一）。
- **零依赖边界**：本模块只依赖 game-network-netty / game-serialization，不接注册中心、不绑线程模型。
  回调在 IO 线程触发，**投递到业务线程组由宿主负责**。

## 快速开始

### 服务端

序列化器无需手动传入：`RpcServer` / `RpcClient` 默认取 `SerializerManager.getInstance()`，
该单例已预注册 JSON / Protobuf / Fory 三种序列化方式。如需追加或覆盖某个 serialType，
在启动时调用 `SerializerManager.getInstance().register(...)` 即可。

```java
RpcServer server = new RpcServer(
        new RpcServerConfig().port(9100),
        new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection conn, RpcRequest msg) {
                // IO 线程；msg.getBody() 是 byte[]，按 msg.getSerialType() 自行还原
                if (msg.getCommand() == 1) {
                    byte[] respBody = handleLogin(msg.getBody());
                    if (!msg.isOneway()) {
                        conn.writeMsg(RpcResponse.of(msg.getRequestId(), msg.getSerialType(), respBody));
                    }
                }
            }
        });
server.start();   // 绑定端口；失败会清理线程组后抛 GameRpcException
// ...
server.close();
```

业务 handler 抛异常时，框架对**请求类**消息自动回 `RpcResponse.error(requestId, 500, ...)`，
让调用方 fast-fail，不必干等超时（oneway 无回包）。

### 客户端

```java
RpcClient client = new RpcClient(
        new RpcClientConfig().serviceName("gateway").serviceId("1"),
        new RpcMessageHandler() {   // 接收服务端推送（oneway / 反向 invoke）
            @Override protected void handleUserMsg(RpcConnection conn, RpcRequest msg) { }
        });

ConnectionTargetConfig target = new ConnectionTargetConfig();
target.setServiceName("logic");
target.setServiceId("1");
target.setIp("127.0.0.1");
target.setPort(9100);
target.setConnectionSize(2);
client.connect(target);          // 异步建连 + 握手协商；幂等，重复调用不翻倍连接
```

三种调用风格：

```java
byte serial = SerializationType.JSON.typeId();

// 1) callback：按泛型 V 自动反序列化响应 body，回调在 IO 线程
client.invoke("logic", "1",
        RpcRequest.of(1).routeKey(playerId).serialType(serial).body(loginReq),
        new RpcCallback<LoginResp>() {
            @Override public void onSuccess(LoginResp resp) { /* ... */ }
            @Override public void onException(Throwable e) { /* ... */ }
        });

// 2) future：拿原始 RpcResponse（含 metadata）。await 只能在非 IO 线程调用！
RpcFuture future = client.invoke("logic", "1",
        RpcRequest.of(1).routeKey(playerId).serialType(serial).body(loginReq));
RpcResponse resp = future.await(3000);   // 阻塞，仅限业务线程

// 3) oneway：fire-and-forget，无回包、无重试
client.oneway("logic", "1",
        RpcRequest.oneway(2).routeKey(playerId).serialType(serial).body(event));

// 广播：给 "logic" 的每个实例各发一条 oneway，body 只序列化一次
client.broadcast("logic", RpcRequest.oneway(3).serialType(serial).body(notice));

// 动态成员：实例下线时主动移除（停重连 + 失败在途 + 关连接 + 回收 peer）
client.disconnect("logic", "1");
```

## 行为说明

| 能力 | 说明 |
| --- | --- |
| 握手协商 | 建连后客户端发 `HANDSHAKE`（带身份/token），服务端校验后回 `HANDSHAKE_ACK`；**客户端收到 ACK 才把连接挂入 peer 参与路由**。token 不符服务端直接断连、不回执。 |
| 心跳保活 | 客户端写空闲发 `PING`，服务端回 `PONG`；任一端读空闲（`idleTimeoutSeconds`）判死关连接。由 `IdleStateHandler` 驱动，配置为 0 则关闭。 |
| 断线重连 | 连接失败或断开后按指数退避（带抖动）自动重连；`reconnectEnabled` 控制。`disconnect`/`close` 会停止重连。 |
| 背压 | 出站缓冲到高水位（`!isWritable`）时：invoke 立即失败、oneway/broadcast 直接丢弃，计入 metrics，不撑爆堆外内存。 |
| 在途上限 | 单 peer 在途请求超过 `maxPendingPerPeer`（默认 100000，0=不限）则拒绝新 invoke，防对端假死时 OOM。 |
| 超时 | 每个 invoke 挂哈希时间轮超时（`RpcRequest.timeoutMillis` 或 `defaultTimeoutMillis`）；到点 future/callback 收到 `GameRpcException`。 |
| 广播 | `broadcast(serviceName, req)` 对该服务每个实例各选一条连接，body **只编码一次**再对各连接写帧的 `retainedDuplicate`。 |

## 配置

`RpcClientConfig` 关键项：`serviceName/serviceId`（自身身份，用于握手）、`authToken`、`connectionSize`、
`connectTimeoutMillis`、`defaultTimeoutMillis`、`maxPendingPerPeer`、`heartbeatIntervalSeconds`、
`idleTimeoutSeconds`、`reconnectEnabled`、`reconnect{Initial,Max}BackoffMillis`、`maxFrameLength`、
`writeBufferWaterMark(low, high)`。`validate()` 会在构造时强校验（如 `idleTimeoutSeconds > heartbeatIntervalSeconds`）。

`RpcServerConfig` 关键项：`port`、`bossThreads`、`workerThreads`、`backlog`、`idleTimeoutSeconds`、
`authToken`、`defaultTimeoutMillis`、`maxPendingPerPeer`、`maxFrameLength`。

事件循环线程组与 `HashedWheelTimer` 均由 `RpcServer` / `RpcClient` 构造方法内部创建，并在 `close()` 释放（不对外共享）。

## 线程模型（重要）

- `RpcMessageHandler.handleUserMsg`、`RpcCallback`、`RpcFuture` 的回调都在 **Netty IO 线程**（超时回调在**时间轮线程**）执行。
  **实现内禁止阻塞**；要切到业务线程组由宿主自行投递。
- `RpcFuture.await()` 会**阻塞调用线程**，**绝不能在 IO 线程上调用**（自锁死）。仅供业务/测试线程使用，
  游戏内优先用 callback 风格。

## 注意事项 / 已知取舍

- **oneway 是 fire-and-forget**：不重试、不报告写失败；连接不可写或无可用连接时直接丢弃（只计 metrics）。
- **`RpcRequest` 可变**：requestId 由框架在 invoke 时写入，**不要把同一个实例并发复用于多次 invoke**。
- **路由非一致性哈希**：`floorMod(routeKey, 连接数)`，连接数变化（扩缩容/断线）时同一 routeKey 可能漂到别的连接。
- **命令段**：业务 command 必须为正数；`[-100, -1]` 保留给框架内部消息。metadata key `[1,99]` 保留，业务从 `100`（`Metadata.KEY_BUSINESS_MIN`）起。
- **鉴权**：握手 token 为明文 `equals` 比较，且**无 TLS**，按"对内可信网络"使用。
- **入站背压**：autoread 常开，框架不限制入站速率——宿主须把 `handleUserMsg` 的活儿移出 IO 线程并自行施加背压。

## 不在本模块职责内（交给宿主）

- **服务发现**：`connect`/`disconnect` 是成员管理入口，由宿主桥接注册中心（如 game-registry）的上下线事件来驱动。
- **优雅停机 draining**：`close()` 先停监听再关线程组，但不等在途请求回完（符合"不做零丢失"取舍）。
