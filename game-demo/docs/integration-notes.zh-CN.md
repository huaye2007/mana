# game-demo 接入与架构说明

一个 Maven 项目。客户端通过 game-network 直连游戏服；game-rpc 仅服务服间通信；game-runtime 调度两类业务，但不决定业务协议。

## 协议边界

| 协议目录 | 入口 | command 规则 | Handler |
|---|---|---|---|
| protocol/client | 客户端 TCP | 正数：消费 1001、查询 1002、通知 2001 | WalletHandler |
| protocol/rpc | 内部 RPC | 负数：发奖 -1001 | WalletRpcHandler |

客户端消费请求只有 SpendGoldReq，不派生另一份内部消费请求。内部 GrantGoldReq 表达服务器发奖，有自己的响应、command 和错误码；它不实现客户端 PlayerRequest，也不携带重复的玩家和追踪字段。RPC routeKey/Metadata 提供这些调用信息。

ClientCommands 与 RpcCommands 分别声明各自契约；ServerIngress.TCP/RPC 分别引用对应集合。Runtime 注册表由 DemoServer 显式组装两套业务定义，但有能力执行某个命令不代表另一个入口可以调用它。

CommandBinding 允许非零 ID。TCP 构造时要求正号及客户端 PlayerRequest；RPC 构造时要求负号。声明必须与 Runtime 注册一致，重复 ID 在分配传输前被拒绝。线上的错误入口命令不会被派发到另一类 Handler；用合法客户端 command 携带内部 DTO 也会被类型校验拒绝。

## 业务状态与执行

DemoServer 创建一个 Wallets，并注入 WalletHandler 和 WalletRpcHandler。前者处理消费、查询，后者处理奖励发放，二者共享钱包状态，但不共用网络业务协议或处理函数。每个钱包由对应的玩家路由串行访问，跨 TCP/RPC 的更新不会丢失。

Wallets 只负责内存状态，不增加 Service/Repository 转发层。GameMessages.send/sendError 通过当前连接的 Sender 发回当前协议的响应。业务无需回复参数或连接包装类型。

ServerRuntime 保持通用：构造函数显式接收命令及 Handler，负责路由和准入。它不导入客户端或内部 DTO；客户端与 RPC 入口各自解码并构建 Metadata。

## 负数协议号与基础组件

game-rpc 原先三处限制 command 为正数：RpcMessages 发送准入、RpcChecks 消息校验、DefaultRpcCodec 解码。现改为非零有符号 int32，保留 requestId、错误码、业务身份和限额的原校验。基础层仍接受正号，demo 用入口规则限定内部只用负号。

RPC 规范和共享黄金数据已同步：新增 -1001 Call、Integer.MIN_VALUE 单向消息；零 command 仍非法。旧版本 RPC 拒绝负号，通信游戏服需同步更新。

game-runtime 已支持负协议 ID，无需为这次拆分修改。game-network 只负责传输，也无需改动。ClientCommands 与 RpcCommands 使用统一 Fory 序列化，分别登记固定类型 ID；负 command 与 Fory 类型 ID 无关。

## notify

ClientCommands.NOTIFICATIONS 保存客户端通知定义。flags=2、requestId=0、code=0；GameMessages.notify(message) 使用当前客户端连接，notify(connection, message) 支持定时器等无请求上下文的场景。DemoClient 按通知类型分发，不查找 pending，也不回包。

回调在客户端 I/O 线程执行，RuntimeException 被记录并隔离；畸形通知关闭连接，合法未订阅通知被丢弃。内部 RPC 连接不使用客户端通知接口，服间单向通信使用 RpcNode.send。

## 请求关联

请求号仅保存在 CommandHandlerInvocation.requestId，通过 CommandContext.requestId() 读取。TCP/RPC 入口将协议头中的 requestId 显式传入；GameMessages 使用固定属性发送响应。CommandMetadata.player 只组装玩家与追踪信息，已删除请求号 Metadata key。

本地或单向命令使用 0，不从父调用继承编号。解析失败没有 Context 时，通过 HandlerException.invocation().requestId() 回复；准入、执行失败也保留相同调用对象。TCP/RPC 各自管理编号和 pending，跨调用链追踪使用 traceId。

## 异步与关闭

RPC 回调借用 ByteBuf，返回前解码为独立对象，再通过 RuntimeCallback 回到发起方路由。RouteTask.completionStage 用于观察后端失败，确保已接受却未执行的回调不会留下永久等待。业务路由保证仍由 RuntimeCallback 提供。

关闭顺序为停止准入和出站调用、结束 pending、排空 Runtime、关闭传输。排空超时保留传输供已接受任务响应；待任务结束后重试 close。超时不等于取消已执行的发奖，也不自动重试。

## 验证

完整命令：`mvn -pl game-demo -am verify`。

Demo 有 64 项测试，覆盖独立协议、跨入口拒绝、正负编号、共享钱包并发、发奖溢出、客户端错误、RPC 回调终态、排空、notify、Fory 编解码。基础层新增负 command 的调用/单向发送测试与黄金字节用例，并保留零 command、非法 requestId 等拒绝验证。
