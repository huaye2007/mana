[English](README.md) | 中文

# mana

`mana` 是一组面向游戏服务器的基础组件，采用 Maven 多模块组织，覆盖网络通信、对内 RPC、序列化、注册发现、配置管理、运行时调度、ECS 场景模拟和 JPA 风格持久化。基于 [Apache-2.0](LICENSE) 许可证开源。

## 环境要求

- **JDK 25（硬性要求）**：`game-runtime` 使用 JDK 25 转正的 `ScopedValue` 做虚拟线程上下文传递，无法降级到更低版本；构建时由 maven-enforcer 强制校验
- Maven 3.9+
- Windows 和 Linux 均可构建；UDS、epoll、部分网络集成测试需要 Linux 或可用的 Netty selector 环境
- 可选：Docker，用于运行 MySQL、MongoDB 等集成测试

PowerShell 下建议给 Maven 属性加引号：

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

## 模块说明

| 模块 | 职责 |
| --- | --- |
| `game-network` | 网络接入层：统一 TCP/WebSocket Server、HTTP/1/HTTP/2 业务语义、连接生命周期和原生 Netty pipeline 扩展；Session 与连接映射由业务层负责（见 [game-network/README.zh-CN.md](game-network/README.zh-CN.md)） |
| `game-rpc` | 对内 RPC 统一入口；内部按 `game-rpc-core`、`game-rpc-netty` 分层，不依赖 `game-network`（见 [game-rpc/README.zh-CN.md](game-rpc/README.zh-CN.md)） |
| `game-serialization` | 序列化门面，统一 JSON、Protobuf、Apache Fory 三种实现（见 [game-serialization/README.zh-CN.md](game-serialization/README.zh-CN.md)） |
| `game-registry` | 统一的服务注册发现 API，通过 SPI 提供 memory、Nacos 与 Etcd 实现（见 [game-registry/README.zh-CN.md](game-registry/README.zh-CN.md)） |
| `game-config` | 基于不可变快照的配置中心，提供 local、Nacos、Etcd 后端、类型化读取与变更监听（见 [game-config/README.zh-CN.md](game-config/README.zh-CN.md)） |
| `game-runtime` | 统一运行时：命令/事件/定时器/回调四类入口收敛为任务，按 routerKey 哈希到组内固定 worker 串行执行（见 [game-runtime/README.zh-CN.md](game-runtime/README.zh-CN.md)） |
| `game-ecs` | 面向场景模拟的 ECS：实体/组件稀疏存储、快照查询、分阶段系统流水线、延迟结构变更，以及每个世界独立线程驱动的更新循环（见 [game-ecs/README.zh-CN.md](game-ecs/README.zh-CN.md)） |
| `game-gateway` | TCP/WebSocket 游戏边缘网关：接入防护、登录门禁、粘滞路由、服务发现及 RPC 双向透传（见 [game-gateway/README.zh-CN.md](game-gateway/README.zh-CN.md)） |
| `game-jpa` | 轻量持久化框架，含 RDB、DocDB、缓存、异步批量写、分片、starter 和 demo（见 [game-jpa/README.zh-CN.md](game-jpa/README.zh-CN.md)） |
| `game-dev` | 参考宿主/示例工程：外网 GamePacket 协议 + 登陆/顶号/空闲踢人等框架行为，演示 network/runtime/jpa/serialization 的桥接方式（当前为单进程宿主，尚未接入 registry/config） |

> `game-aoi` / `game-ai` / `game-match` / `game-idgen` 尚未纳入当前仓库。

## 构建与测试

全量单元测试：

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

CI 会在 Linux 和 Windows 上执行同一条 reactor 测试命令。

只跑 JPA 模块：

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-jpa\pom.xml test
```

注册中心默认测试覆盖 memory 行为以及基于模拟客户端的 Nacos、Etcd 适配；不会连接外部注册中心。MySQL / MongoDB 的持久化集成测试走 `game-jpa` 的 `integration-tests` profile（基于 Testcontainers，需要 Docker）。

## 依赖说明

- MySQL JDBC 驱动（`mysql-connector-j`）为 GPLv2（含 Universal FOSS Exception）许可证，`game-jpa-rdb-mysql` 以 `provided` 声明、**不随本项目传递分发**。使用 MySQL 后端时请在你自己的应用 POM 中显式加入驱动依赖。
- 其余第三方依赖均为 Apache-2.0 / MIT / BSD 系许可证。

## 开发约定

- Java 包名统一使用 `cn.managame` 前缀；各模块在其下按职责分包（如 `cn.managame.jpa.*`、`cn.managame.runtime.*`）。
- 测试统一使用 JUnit 5/Jupiter。
- 运行时按 routerKey 哈希到组内固定 worker，保证同一 routerKey（同一玩家/房间/公会）的任务串行执行，业务无需加锁（见 [game-runtime/README.zh-CN.md](game-runtime/README.zh-CN.md)）。
- 模块边界保持清晰：core 层不依赖具体实现，Netty、HTTP、MySQL、Mongo、注册中心/配置中心 provider 等放在独立实现模块；`game-network` / `game-rpc` / `game-runtime` 互不依赖，桥接代码放宿主进程。

## 常用入口

- Runtime（无统一装配门面，按需使用各组件单例）：命令注册 `cn.managame.runtime.command.CommandRegistry`（派发由宿主桥接：构造 `GameCommandTaskRunnable` → `ExecutorGroupRegistry.execute`）、事件 `cn.managame.runtime.event.EventBus`、定时 `cn.managame.runtime.timer.TimingWheel` / `CronTask`、执行器组注册 `cn.managame.runtime.executor.ExecutorGroupRegistry`
- ECS：核心世界 `cn.managame.ecs.World`、系统流水线 `cn.managame.ecs.SystemPipeline`、独立线程世界循环 `cn.managame.ecs.runtime.EcsWorldRunner`
- Network：通过 `NettyServer` 创建 TCP、WebSocket 或自定义 Server，通过 `NettyHttpServer` 创建协议透明的 HTTP/1/HTTP/2 Server
- RPC：`cn.managame.rpc.RpcClient`、`cn.managame.rpc.RpcServer`
- Registry：`cn.managame.registry.api.ServiceRegistry`，工厂 `cn.managame.registry.factory.RegistryFactory`
- Config：`cn.managame.config.ConfigCenter`，工厂 `cn.managame.config.ConfigFactory`
- Serialization：`cn.managame.serialization.SerializerManager`
- JPA starter：`cn.managame.jpa.starter.GameJpaBootstrap`

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。
