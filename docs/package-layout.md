# 包结构与职责

三个基础项目按职责划分 Java 包，沿用现有 Maven 模块和依赖。game-demo 仍是一个 Maven 项目，负责把网络入口、内部 RPC 和业务执行组合起来。

## 项目边界

| 项目 | 负责 | 由使用方负责 |
| --- | --- | --- |
| game-network | 连接、监听、网络事件、传输资源与原生 Netty 扩展 | 客户端报文格式、序列化、登录与业务协议 |
| game-rpc | 游戏服务之间的连接、调用关联、超时、框架协议和错误 | 服务发现、业务路由、业务对象序列化 |
| game-runtime | Handler 绑定、实体串行执行、事件、定时任务和执行诊断 | 网络接入、业务响应、连接的具体类型 |

game-runtime 仅依赖 JDK，不依赖 game-network 或 game-rpc。game-demo 的接入层把两种网络入口转换为 Runtime 命令。客户端协议和内部服务协议仍各自登记，不因为使用同一执行器而混为一种协议。

## game-runtime

包前缀为 `cn.managame.runtime`。

| 包 | 主要类型与职责 |
| --- | --- |
| `annotation` | Handler、HandlerMethod、EventHandler、EventMethod、Cron：业务声明 |
| `context` | HandlerContext、CommandContext、HandlerInvocation、Metadata：调用数据与元数据 |
| `protocol` | Protocol、ProtocolRegistry、ProtocolType：命令类型登记 |
| `route` | Route、RouteType、RouteKeyResolver：实体标识与路由键约定 |
| `clock` | GameClock、MutableGameClock：时间来源与测试时钟 |
| `diagnostics` | 指标、诊断快照、执行异常和关闭报告 |
| `execution` | GameRuntime、ExecutionDomain、RouteTask、RuntimeCallback，以及绑定、队列、调度和生命周期实现 |

`execution` 内部的 Scanner、Binder、Registry、RouteDomain 和 Scheduler 共同完成一次执行。它们继续通过包内类型和方法协作，避免为了目录层次扩大内部 API。`HandlerContexts` 负责当前执行作用域的进入、退出和读取，因此属于执行包；`context` 保存调用数据。

诊断快照只描述数据，队列扫描与快照排序由执行内核完成。可选 Disruptor 后端仍在独立适配器项目的 `cn.managame.runtime.disruptor` 包中。

## game-rpc

包前缀为 `cn.managame.rpc`。

| Maven 模块 | 包 | 职责 |
| --- | --- | --- |
| game-rpc-core | `core` | RpcNode、RpcPeer、RpcFuture、回调与结果；内部调用、连接和消息处理 |
| game-rpc-core | `protocol` | 框架报文、编解码、元数据、错误码、参数校验与协议限制 |
| game-rpc-core | `transport` | RpcTransport、RpcNetworkProvider、RpcNetworkConfig：传输契约与 SPI |
| game-rpc-netty | `netty` | 默认 Netty 传输、Provider 和报文长度解码 |

`core` 使用 `protocol` 和 `transport`；协议模型不引用 RpcNode/RpcPeer 等调用状态。默认传输通过 SPI 提供，调用内核不依赖其实现类。

RpcException 和 RpcConnectionConflictException 属于调用内核；RpcProtocolException 属于协议。RpcChecks 的共享校验入口供编解码和调用准入使用，避免规则分叉。没有按“异常”“工具”等技术名词再拆一层包。

## game-network

| Maven 模块 | 包 | 职责 |
| --- | --- | --- |
| game-network-api | `cn.managame.network` | Connection、NetworkHandler、客户端/服务端生命周期等小型公共契约 |
| game-network-netty | `cn.managame.network.netty.connection` | NettyConnection、NettyAccess 和包内 NetworkHandlerBridge |
| game-network-netty | `cn.managame.network.netty.transport` | TCP/WS/HTTP 客户端与服务端、Builder、选项和共享网络资源 |

传输实现使用连接桥接，连接包不反向依赖传输实现。NetworkHandlerBridge 保持包内可见，由 NettyAccess 创建。TCP、WS、HTTP 共用构建与资源管理逻辑，现阶段一起维护即可。只有少量接口的 API 模块保留原包。

## 接入迁移

这是包名调整，已有使用方需要更新 import 并重新编译；Maven 坐标和网络报文格式没有因本次整理而改变。仓库内的 Demo、测试、可选适配器和可执行基准脚本已同步。

常用入口的新位置：

~~~java
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.context.CommandContext;
import cn.managame.runtime.execution.GameRuntime;
import cn.managame.runtime.execution.HandlerContexts;
import cn.managame.runtime.route.RouteType;
import cn.managame.rpc.core.RpcNode;
import cn.managame.rpc.protocol.RpcError;
import cn.managame.rpc.transport.RpcNetworkProvider;
import cn.managame.network.netty.connection.NettyAccess;
import cn.managame.network.netty.transport.TcpNetworkServer;
~~~

使用自定义 RpcNetworkProvider 时，除了实现类的 import，还需把 ServiceLoader 描述文件改为：

~~~text
META-INF/services/cn.managame.rpc.transport.RpcNetworkProvider
~~~

文件内容仍填写 Provider 实现类的完整类名。默认 Netty Provider 已同步此路径。

历史基准结果中的脚本副本与生成字节码记录保留当时的包名，不作为当前源码执行。
