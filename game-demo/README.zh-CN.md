# game-demo

[English](README.md) | 简体中文

基于 JDK 25 / Maven 3.9 的可运行游戏服项目，接入三个组件：

- **game-network-netty**：游戏客户端通过 TCP 直接访问游戏服。
- **game-runtime**：按玩家有序执行业务，服间 RPC 回调重新进入发起方玩家路由。
- **game-rpc-netty**：游戏服务器之间的内部通信，使用独立端口。

```text
游戏客户端 -- game-network TCP --> 游戏服 A -- game-rpc --> 游戏服 B
                                   game-runtime             game-runtime
                                   玩家钱包                  玩家钱包
```

## 构建与运行

在仓库根目录执行：

```sh
mvn -pl game-demo -am verify
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar
```

默认在本地随机端口启动两个游戏服。游戏客户端直连节点 `20`，演示三次钱包请求；随后节点 `21` 向节点 `20` 通过独立的内部 RPC 协议发放 10 金币奖励。演示结束后关闭全部资源。每位玩家初始拥有 100 金币：

```text
Game client connected directly via game-network
player=10001 spent=30 gold=70 trace=20260913
player=10001 spent=50 gold=20 trace=20260913
Spend rejected: Game error 1002, args=[30, 20]
Server 21 -> server 20 grant RPC: player=20001 gold=110 trace=20260914
```

可执行 JAR 从旁边的 `target/lib/` 目录加载运行依赖，复制程序时需要一起携带该目录。保留原始依赖 JAR 也保留了 RPC Netty Provider 的服务发现声明。

### Windows

如果 JDK 初始化 Selector 时出现 `Unable to establish loopback connection` / `Invalid argument: connect`，可指定一个已存在的短目录存放本地 socket 文件：

```powershell
java "-Djdk.net.unixdomain.tmpdir=game-demo/target" -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar
```

Maven 测试沿用仓库网络/RPC 模块的做法，在 Windows 自动应用此配置。需要时，在后续命令的 `-jar` 前添加相同 JVM 参数。

### 独立进程

启动游戏服（客户端 TCP 端口 `7000`，内部 RPC 端口 `7070`，节点 ID `20`）：

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar server 7000 7070 20
```

在另一个终端运行普通游戏客户端：

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar client 7000
```

可选：启动临时游戏服节点 `21`，演示内部 RPC 调用：

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar peer 7070 20
```

模式后的参数均可省略，以上展示的是默认值。`peer` 使用节点 ID `21`，目标 ID 应与其不同，同时运行一个该模式实例。普通游戏客户端不需要 RPC 节点身份，可以同时连接多个。在游戏服终端按回车，等待已接收的 Runtime 任务执行后关闭两种网络入口。

每个游戏服独立保存自己的内存钱包。重复运行客户端会继续消费剩余余额；重启对应游戏服后重置。同一玩家 ID 在不同游戏服上对应各自的钱包。

也可用 IDE 导入根 Maven 工程，选择 JDK 25，直接运行 `cn.managame.demo.launcher.GameDemo`。

## 职责与依赖

一个 Maven 项目，按职责分包：

| 包/类 | 职责 |
|---|---|
| protocol/client | 客户端请求、响应、通知及 ClientCommands |
| protocol/rpc | 内部服务请求、响应及 RpcCommands |
| protocol | 通用 CommandBinding、TCP 包头、调用失败表达 |
| network / codec | TCP 分帧和包头校验 |
| serialization | 统一 Fory 消息体序列化，汇总两套固定类型 ID |
| client / DemoClient | TCP 请求响应关联、notify 分发 |
| server / DemoServer | 组装两套协议、Handler、共享状态及组件生命周期 |
| server / ServerIngress | 客户端与内部 RPC 各自的入口清单 |
| server / runtime | 玩家执行域、通用命令准入及调度 |
| server / network、rpc | 各自协议的解码和发送；RPC 回调返回玩家路由 |
| server / support | 上下文发送、Metadata、玩家路由、失败映射 |
| server / gameplay | WalletHandler 处理客户端操作，WalletRpcHandler 处理内部发奖，Wallets 保存共享状态 |

客户端协议与内部协议按业务目的划分，不把同一条请求开放到两种入口，也不把客户端 DTO 转换成另一份“内部请求”。两个 Handler 可以操作同一份钱包状态；DemoServer 显式注入同一个 Wallets，同一玩家的操作仍在同一 Runtime 路由串行执行。

## 协议与业务

| 入口 | 操作 | command | 请求 | 响应/通知 |
|---|---|---|---|---|
| 客户端 TCP | 消费金币 | 1001 | SpendGoldReq | SpendGoldRes |
| 客户端 TCP | 查询钱包 | 1002 | GetWalletReq | GetWalletRes |
| 服务端推送客户端 | 钱包变化通知 | 2001 | — | WalletChangedNotify |
| 内部 RPC | 发放金币奖励 | -1001 | GrantGoldReq | GrantGoldRes |

客户端协议号为正数，内部 RPC 协议号为负数，0 不分配。CommandBinding 允许非零 ID；GameTcpServer 构造时拒绝负数入口，GameRpcServer 构造时拒绝正数入口。两者均检查声明与 Runtime 注册一致，重复 ID 在启动前被拒绝。

客户端 DTO 在 protocol.client.ClientProtocol 中。当前 demo 未实现登录，SpendGoldReq/GetWalletReq 通过 PlayerRequest 的 playerId/traceId 数据字段提供路由与追踪信息；PlayerRequest 不包含转换或执行方法。

```java
var spend = client.call(ClientCommands.SPEND, new SpendGoldReq(7, 10, 123));
var wallet = client.call(ClientCommands.WALLET, new GetWalletReq(7, 124));
```

内部 DTO 在 protocol.rpc.RpcProtocol 中。GrantGoldReq 只有发奖 amount；目标玩家由 RPC routeKey 指定，traceId 由 RPC Metadata 传入，不复用客户端 PlayerRequest。

```java
server.rpc().connectPeer(peerId, peerAddress);
var reward = server.rpc().call(peerId, RpcCommands.GRANT, new GrantGoldReq(10),
        new Route(PlayerRoute.class, playerId),
        RpcOptions.builder().routeKey(playerId).putLong(RpcProtocol.TRACE_ID, traceId).build());
```

WalletHandler 处理消费和查询；WalletRpcHandler 校验奖励金额为正且不会溢出，再增加金币。内部发奖错误为 INVALID_GRANT=3001，客户端错误为 INVALID_REQUEST=1001、NOT_ENOUGH_GOLD=1002。RPC 畸形请求、非法玩家路由或追踪 Metadata 返回框架 PROTOCOL_ERROR。客户端请求通过 RPC 到达时找不到 Handler；内部消息无法通过客户端的正数协议号和 DTO 类型校验。

两个业务 Handler 都使用简洁的发送封装，编码由当前连接绑定的 Sender 完成：

```java
GameMessages.send(new SpendGoldRes(playerId, gold, traceId));
GameMessages.sendError(NOT_ENOUGH_GOLD, "101", "100");
```

新增客户端功能时增加客户端 Req/Res、正数 command、客户端 Handler，并加入客户端入口；新增内部服务功能时增加内部 Req/Res、负数 command、内部 Handler，并加入 RPC 入口。只共享底层序列化、调度和确有需要的业务状态。

## 主动通知（notify）

notify 用于游戏服向 TCP 客户端单向推送，不需要请求、响应或 pending。ClientCommands.NOTIFICATIONS 按通知对象类型查找 command，通知不注册为 Runtime 请求。

```java
// 处理客户端命令时，内部取得当前连接。
GameMessages.notify(new WalletChangedNotify(playerId, gold));

// 定时器等无当前命令的场景，传业务保存的客户端连接。
GameMessages.notify(clientConnection, new WalletChangedNotify(playerId, gold));

// 客户端建议在 connect 前注册。
client.onNotify(WalletChangedNotify.class, message ->
        System.out.println("wallet changed: " + message.gold()));
```

同一类型只能注册一个回调。回调在连接 I/O 线程执行，应及时返回；RuntimeException 被记录，后续通知和响应继续分发。合法未订阅通知在校验消息体后丢弃；未知通知 command、错误类型或损坏消息体关闭连接并结束 pending 请求。

发送返回值只表示接受写入，没有接收确认、重试或补发。此接口只面向客户端连接，在 RPC 连接上调用会抛出 UnsupportedOperationException。服间单向消息由 RpcNode.send 负责。NotifyIntegrationTest 演示响应前后通知及无请求上下文的推送。

## 传输与序列化

客户端 TCP 格式：

```text
length:int | command:int | requestId:int | code:int | flags:int | body:bytes
```

length 和包头采用大端，length 不含自身；整帧最多 4096 字节，body 最多 4076 字节。command 必须为正数。

- flags=0：请求，requestId>0、code=0。
- flags=1：响应，回传原 command/requestId；code=0 成功、code>0 失败。
- flags=2：notify，requestId=0、code=0，不经过 pending。
- 客户端向游戏服发送响应或 notify 会被拒绝。

TCP 与内部 RPC 都使用 requestId 表示请求响应关联号，由 CommandHandlerInvocation 的固定 requestId 字段承载，通过 CommandContext.requestId() 读取；Req/Res 消息体不重复携带它。两种连接的编号与 pending 独立，客户端请求触发内部调用时，由 RPC 分配新的 requestId；需要贯穿整条调用链的信息使用 traceId。CommandMetadata 只保存玩家路由与追踪等扩展数据，不再保存请求号。错误处理通过原 invocation.requestId() 取得关联号，包括 Context 创建前失败的情况。本次调整不改变 TCP/RPC 字节布局。

内部 RPC 使用自身的信封格式。game-rpc 基础层将 command 视为非零有符号 int32，允许正负两种编号；game-demo 在业务入口约束内部协议只用负数。requestId 规则保持不变：调用为正数、单向消息为 0。

MessageSerializer 复用 Apache Fory 1.7.1，开启跨语言模式、兼容模式、强制类型注册，关闭引用跟踪，使用容量为 4 的线程安全实例池。两套消息使用同一种序列化方式：

| 固定 Fory ID | 类型 |
|---|---|
| 3 / 4 | SpendGoldRes / ErrorRes |
| 7 / 8 | GetWalletRes / WalletChangedNotify |
| 9 / 10 | SpendGoldReq / GetWalletReq |
| 11 / 12 | GrantGoldReq / GrantGoldRes |

ClientCommands.MESSAGE_TYPES 与 RpcCommands.MESSAGE_TYPES 分别登记。Fory 类型 ID 与 command 独立，始终为上表的固定 ID，内部 command 为负不代表 Fory ID 也为负。旧 ID 1/2/5/6 已退役，不复用。

解码校验目标类型、截断和尾随字节；只有独立 Java 对象跨线程。借用 RPC ByteBuf 在回调返回前解码，出站 RPC 缓冲区在 call/reply 返回后释放，GamePacket 防御性复制 body。内部协议已改为负数发奖协议，通信游戏服需要同步更新；旧版 game-rpc 会拒绝负 command。

JDK 25 测试和可执行 JAR 已配置 java.lang.invoke 访问权限。IDE 或自定义 classpath 运行时添加 `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`。

## 回调与关闭

RpcRuntimeClient 解码借用响应，通过 RuntimeCallback 回到发起方玩家路由；RPC 负责超时与 pending。回调投递被拒绝或执行后端失败时，等待句柄直接失败，不运行替代业务回调。

关闭顺序：停止新命令和出站 RPC → 以 UNAVAILABLE 结束 pending 调用 → 排空 Runtime → 关闭传输。默认排空 30 秒，可用 server.close(Duration) 指定。排空超时抛出 RuntimeShutdownException，保留 TCP/RPC 供已接受任务回复，待任务结束后再次 close 释放资源。

demo 没有持久化、登录认证、分服或请求去重。超时和取消不会撤销远端操作，不自动重试发奖。

## 验证

`mvn -pl game-demo -am verify` 运行基础组件与 demo 回归。Demo 的 64 项测试覆盖协议入口隔离、正负编号、客户端消费与内部发奖共享玩家状态、RPC 回调与排空，以及 notify/Fory/错误参数。RPC 黄金用例包含 -1001 调用和 Integer.MIN_VALUE 通知，仍拒绝 command=0。测试启用 Netty paranoid 泄漏检测。

详见 [架构与接入说明](docs/integration-notes.zh-CN.md)。
