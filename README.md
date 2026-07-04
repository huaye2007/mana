[English](README.en.md) | 中文

# mana

`mana` 是一组面向游戏服务器的基础组件。Java 侧采用 Maven 多模块组织，覆盖网络通信、对内 RPC、序列化、注册发现、配置管理、运行时调度和 JPA 风格持久化；网关、路由、本机 agent 与一套服务注册发现以独立的 Rust sidecar 形式提供。基于 [Apache-2.0](LICENSE) 许可证开源。

## 环境要求

- **JDK 25（硬性要求）**：`game-runtime` 使用 JDK 25 转正的 `ScopedValue` 做虚拟线程上下文传递，无法降级到更低版本；构建时由 maven-enforcer 强制校验
- Maven 3.9+
- Rust sidecar 模块需要 Rust 工具链（`cargo`）
- Windows 和 Linux 均可构建；UDS、epoll、部分网络集成测试需要 Linux 或可用的 Netty selector 环境
- 可选：Docker，用于运行 MySQL、MongoDB 等集成测试

PowerShell 下建议给 Maven 属性加引号：

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

## 模块说明

### Java 模块（Maven reactor）

| 模块 | 职责 |
| --- | --- |
| `game-network` | 网络基础层（core + Netty）：TCP/WebSocket/HTTP 接入、连接与会话生命周期、传输 pipeline，把解码后的消息投递给 `INetworkHandler`（HTTP 走 `IHttpHandler`）。零依赖，不做协议路由与命令分发（见 [game-network/README.md](game-network/README.md)） |
| `game-rpc` | 对内 RPC：协议编解码、握手/心跳/重连、oneway/future/callback 调用、背压与在途上限保护、按服务广播（见 [game-rpc/README.md](game-rpc/README.md)） |
| `game-serialization` | 序列化门面，统一 JSON、Protobuf、Fury 三种实现（见 [game-serialization/README.md](game-serialization/README.md)） |
| `game-registry` | 注册发现抽象（`Registry` / `Discovery`），通过 SPI 接入 ZooKeeper、Etcd、Nacos、Consul（见 [game-registry/README.md](game-registry/README.md)） |
| `game-config` | 多源配置加载（本地文件/classpath/命令行/JVM/环境变量/默认值/远端），类型安全快照、变更监听与热加载（见 [game-config/README.md](game-config/README.md)） |
| `game-runtime` | 统一运行时：命令/事件/定时器/回调四类入口收敛为任务，按 routerKey 哈希到组内固定 worker 串行执行（见 [game-runtime/README.md](game-runtime/README.md)） |
| `game-jpa` | 轻量持久化框架，含 RDB、DocDB、缓存、异步批量写、分片、starter 和 demo（见 [game-jpa/README.md](game-jpa/README.md)） |
| `game-dev` | 参考宿主/示例工程：外网 GamePacket 协议 + 登陆/顶号/空闲踢人等框架行为，演示 network/runtime/jpa/serialization 的桥接方式（当前为单进程宿主，尚未接入 registry/config 与 Rust 数据面） |

### Rust sidecar 模块

| 模块 | 职责 |
| --- | --- |
| `game-gateway-rust` | 玩家网关：承载海量客户端长连接，负责接入、鉴权、会话、限流，业务 payload 透明转发到后端 |
| `game-router-rust` | 服务内部消息转发：游戏服连接后动态注册，router 间经服务发现互联并跨节点转发 |
| `game-agent-rust` | 宿主机本地 agent：本机入口路由头处理与跨 agent 骨干转发 |
| `game-registry-rust` | Rust 服务注册与发现库，各 provider 为对应第三方 SDK 的薄层封装 |

> Rust sidecar 模块尚未纳入本仓库。早期版本中的 `game-gateway` / `game-router` / `game-agent` 已由上面的 Rust 实现取代；`game-aoi` / `game-ecs` / `game-ai` / `game-match` / `game-idgen` 尚未纳入当前仓库。

## 构建与测试

全量单元测试：

```powershell
mvn "-Dmaven.repo.local=.m2" test
```

CI 会在 Linux 和 Windows 上执行同一条 Java reactor 测试命令；Rust sidecar 模块会执行 `cargo fmt --check`、`cargo test --locked` 和 `cargo clippy -D warnings`。

只跑 JPA 模块：

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-jpa\pom.xml test
```

注册中心集成测试：ZooKeeper 用 curator-test 内嵌服务、自包含，默认构建即运行；Etcd / Nacos / Consul 需要本机跑对应真实服务，默认关闭，用 `game-registry` POM 中的 profile 打开（`registry-it-etcd`、`registry-it-nacos`、`registry-it-consul`，或 `registry-it-all` 一次性全开）。MySQL / MongoDB 的持久化集成测试走 `game-jpa` 的 `integration-tests` profile（基于 Testcontainers，需要 Docker）。

## 依赖说明

- MySQL JDBC 驱动（`mysql-connector-j`）为 GPLv2（含 Universal FOSS Exception）许可证，`game-jpa-rdb-mysql` 以 `provided` 声明、**不随本项目传递分发**。使用 MySQL 后端时请在你自己的应用 POM 中显式加入驱动依赖。
- 其余第三方依赖均为 Apache-2.0 / MIT / BSD 系许可证。

## 开发约定

- Java 包名统一使用 `com.github.huaye2007.mana` 前缀；各模块在其下按职责分包（如 `com.github.huaye2007.mana.jpa.*`、`com.github.huaye2007.mana.runtime.*`）。
- 测试统一使用 JUnit 5/Jupiter。
- 运行时按 routerKey 哈希到组内固定 worker，保证同一 routerKey（同一玩家/房间/公会）的任务串行执行，业务无需加锁（见 [game-runtime/README.md](game-runtime/README.md)）。
- 模块边界保持清晰：core 层不依赖具体实现，Netty、HTTP、MySQL、Mongo、注册中心/配置中心 provider 等放在独立实现模块；`game-network` / `game-rpc` / `game-runtime` 互不依赖，桥接代码放宿主进程。

## 常用入口

- Runtime（无统一装配门面，按需使用各组件单例）：命令注册 `com.github.huaye2007.mana.runtime.command.CommandRegistry`（派发由宿主桥接：构造 `GameCommandTaskRunnable` → `ExecutorGroupRegistry.execute`）、事件 `com.github.huaye2007.mana.runtime.event.EventBus`、定时 `com.github.huaye2007.mana.runtime.timer.TimingWheel` / `CronTask`、执行器组注册 `com.github.huaye2007.mana.runtime.executor.ExecutorGroupRegistry`
- Network：`com.github.huaye2007.mana.network.server.NettyTcpServer` / `NettyWebSocketServer` / `NettyHttpServer`（实现 `INetworkServer`），业务侧实现 `INetworkHandler` / `IHttpHandler`
- RPC：`com.github.huaye2007.mana.rpc.RpcClient`、`com.github.huaye2007.mana.rpc.RpcServer`
- Registry：`com.github.huaye2007.mana.registry.api.Registry` / `com.github.huaye2007.mana.registry.api.Discovery`，工厂 `com.github.huaye2007.mana.registry.factory.RegistryFactory`，starter `com.github.huaye2007.mana.registry.starter.GameRegistryStarter`
- Config：`com.github.huaye2007.mana.config.manager.GameConfigManager`，工厂 `com.github.huaye2007.mana.config.factory.GameConfigFactory`
- Serialization：`com.github.huaye2007.mana.serialization.SerializerManager`
- JPA starter：`com.github.huaye2007.mana.jpa.starter.GameJpaBootstrap`

## License

本项目基于 [Apache License 2.0](LICENSE) 开源。
