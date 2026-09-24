# 包结构与职责

四个基础项目按职责划分 Java 包，沿用现有 Maven 模块和依赖。应用负责把网络入口、内部 RPC、业务执行和数据持久化组合起来。

## 项目边界

| 项目 | 负责 | 由使用方负责 |
| --- | --- | --- |
| game-network | 连接、监听、网络事件、传输资源与原生 Netty 扩展 | 客户端报文格式、序列化、登录与业务协议 |
| game-rpc | 游戏服务之间的连接、调用关联、超时、框架协议和错误 | 服务发现、业务路由、业务对象序列化 |
| game-data | Repository、实体身份、Caffeine、异步持久化、数据库适配与独立日志分表 | 同对象业务顺序、数据库连接资源、故障入口协调与格式迁移 |
| game-runtime | Handler 绑定、实体串行执行、事件、定时任务和执行诊断 | 网络接入、业务响应、连接的具体类型 |

game-runtime 仅依赖 JDK，不依赖 game-network 或 game-rpc。应用的接入层把两种网络入口转换为 Runtime 命令。客户端协议和内部服务协议仍各自登记，不因为使用同一执行器而混为一种协议。

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

## game-data

Java 包前缀与 Maven groupId 统一为 `cn.managame`。

只保留一个 Maven 模块和一个发布构件：`cn.managame:game-data:0.1.0-SNAPSHOT`。源码位于 `game-data/src/main/java`，测试和示例位于 `game-data/src/test/java`；功能通过包划分。

| 功能 | 包 | 职责 |
| --- | --- | --- |
| 核心入口 | cn.managame.core | GameData、DataException 和包内可见的 RepositoryDefinition |
| 注解 | cn.managame.annotation | 实体、字段、键与索引声明 |
| access | cn.managame.core.access | 数据库访问接口与查询条件 |
| cache | cn.managame.core.cache | 实体缓存与常驻模式 |
| key | cn.managame.core.key | 复合 GroupKey 与 MapKey |
| log | cn.managame.core.log | LogRepository 业务接口、按日志时间字段自动分表 |
| mapping | cn.managame.core.mapping | 实体及字段转换、JSON Codec 接口 |
| metadata | cn.managame.core.metadata | 实体及字段元数据、索引与存储类型 |
| repository | cn.managame.core.repository | Single/Group Repository 与缓存 |
| write | cn.managame.core.write | EntityWriter 双缓冲、LogWriter 队列、批量保存及失败处理 |
| JDBC | cn.managame.rdb、cn.managame.rdb.dialect | JDBC 访问、MySQL/MariaDB/PostgreSQL 方言、SQL Plan 与 Schema 管理 |
| MongoDB | cn.managame.docdb.mongodb | MongoDB 查询、扫描、ordered bulk 与索引初始化 |
| JSON | cn.managame.codec.jackson | Jackson JSON Codec |
| 测试目录中的 Spring 示例 | cn.managame.spring | 仅测试使用的 Repository 扫描和 Bean 注册，不进入发布 JAR |
| 测试目录中的示例 | cn.managame.example | MySQL、MongoDB 接入示例及业务 Repository 接口 |

MongoDB 驱动与 Jackson 为可选 Maven 依赖，使用相应功能的应用显式添加。所有生产模块均不使用 Spring；Spring 仅以 test scope 用于测试和示例，相关适配器位于测试源码目录，不进入发布 JAR。

业务接口继承 SingleRepository、GroupRepository 或 LogRepository，统一通过 `gameData.repository(业务接口.class)` 获取实例。同数据源名称、同实体、同模式共享底层缓存，禁止混用 Single 和 Group。GroupRepository<T, ID> 返回缓存中的 Map<Object, T>，增删改原地执行，同组业务访问由上层保证串行。

Column/ColumnType、Id、GroupKey、MapKey 等注解位于 cn.managame.annotation，EntityMapper 与公共标量转换位于 cn.managame.core.mapping，RdbValueConverter 与 MongoValueConverter 分别位于 cn.managame.rdb 和 cn.managame.docdb.mongodb，元数据位于 cn.managame.core.metadata；Jackson 实现在同一模块的独立包中。当前共享 mapper 将 JSON 字段编码为 JSON 文本，不能按旧版原生 BSON 编码说明推断存储格式。

game-data 不依赖 game-runtime。保存时直接编码活实体并缓冲批量写入；最终数据库失败只记录日志，后续 batch 继续；正常关闭停止新提交并处理完已接收工作。日志分表提前创建，append 不执行 DDL。默认文档为 [English](../game-data/README.md)，中文见 [简体中文](../game-data/README.zh-CN.md)。

## 接入迁移

这是包名调整，已有使用方需要更新 import 并重新编译；Maven 坐标和网络报文格式没有因本次整理而改变。仓库内的示例、测试、可选适配器和可执行基准脚本已同步。

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
