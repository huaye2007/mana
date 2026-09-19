# Mana

[包结构、职责与 import 迁移](docs/package-layout.md)

[English](README.md) | 简体中文

**面向 Java 游戏服务器的网络通信、RPC、业务执行与数据持久化组件。**

Mana 提供面向客户端网关的网络传输组件、游戏服务之间的 RPC 通信，以及通过 game-runtime 实现的有序业务执行。应用自行实现消息编解码、服务发现、业务路由解析和游戏逻辑。

当前仓库包含四个项目：**game-network**、**game-rpc**、**game-runtime** 和 **game-data**。

## 项目

| 项目 | 用途 | 文档 |
|---|---|---|
| **game-network** | TCP、WebSocket、加密 WebSocket 和 HTTP 服务端，以及 TCP 和 WebSocket 客户端，可直接使用原生 Netty Pipeline。 | [English](game-network/README.md) · [简体中文](game-network/README.zh-CN.md) |
| **game-rpc** | 基于 game-network 的 RPC，支持节点连接、调用、通知、显式回复、超时和 Metadata。 | [English](game-rpc/README.md) · [简体中文](game-rpc/README.zh-CN.md) |
| **game-runtime** | 仅依赖 JDK 的 OGBS 业务执行引擎，支持有序 Route、Command、Event、Cron、Timer、Callback、Metadata 和可替换时钟。 | [English](game-runtime/README.md) · [简体中文](game-runtime/README.zh-CN.md) |
| **game-data** | Caffeine 实体缓存、异步 MySQL/MongoDB 持久化，以及独立数据库的日志分表。 | [English](game-data/README.md) · [简体中文](game-data/README.zh-CN.md) |

### game-network

- 各支持协议具有独立的服务端和客户端实现。
- 一个服务端可监听多个地址，一个客户端可连接多个目标。
- 共享网络资源，显式管理启动和关闭。
- 支持原生 Netty Handler、编解码器、Channel 选项及 TLS 配置。
- 支持 HTTP 项目路径和独立 HTTP Pipeline。

适用于构建 TCP/WS/WSS 网关或提供 HTTP 接口。业务协议自行定义消息边界和序列化方式。

### game-rpc

- 通过 NodeId 标识逻辑 Peer，每个 Peer 可有一条或多条物理 TCP 连接。
- 支持请求响应调用、单向通知和显式回复。
- 支持调用超时、Metadata，以及带字符串参数的业务错误码。
- 支持心跳、逐连接 Slot 重连退避和可选的读空闲关闭。
- 支持自定义 Codec 和 Transport，提供默认 Netty 传输实现。

适用于直接相连的服务之间通信。服务发现、下一跳路由及转发由应用负责。

### game-runtime

- 相同 RouteGroup + long RouteKey 严格 FIFO 执行，虚拟线程阻塞期间保持逻辑 Route。
- 初始化验证 Command/Event 绑定，预绑定参数与路由解析器。
- 支持同 Route 同步 Event、跨 Route 投递、Cron、动态 Timer 与 Callback 重新进入 Route。
- 提供动态作用域 Context、不可变类型化 Metadata、可替换时钟与统一异常处理。

使用 `mvn -pl game-runtime verify` 构建并运行正确性测试。完整示例见 [RuntimeExample](game-runtime/src/test/java/cn/managame/runtime/execution/RuntimeExample.java)，用法见 [game-runtime 中文说明](game-runtime/README.zh-CN.md)。

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 25 |
| Maven | 3.9 |
| Netty | 4.2.15.Final，由项目统一管理 |
| 项目构件 | 0.1.0-SNAPSHOT |

可选的 RPC 性能基线和容器压力测试使用 Python 及运行 Linux 引擎的 Docker。标准 Maven 构建不需要这两个工具。

## 快速开始

克隆仓库并构建全部模块：

~~~sh
git clone https://github.com/huaye2007/mana.git
cd mana
mvn verify
~~~

以下命令均在仓库根目录运行。

仅构建并测试网络实现及其依赖：

~~~sh
mvn -pl game-network/game-network-netty -am verify
~~~

构建并测试 RPC 实现及其依赖：

~~~sh
mvn -pl game-rpc/game-rpc-netty -am verify
~~~

JAR 文件生成在各模块的 `target/` 目录中。若要供其他本地 Maven 项目引用，安装全部构件：

~~~sh
mvn install
~~~

### 引入依赖

完成本地安装后，在应用的 `pom.xml` 中添加所需组件。

网络通信：

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-network-netty</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

RPC 通信，包含其网络实现：

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-netty</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

### 运行示例

[GatewayExample](game-network/game-network-netty/src/test/java/cn/managame/network/tests/GatewayExample.java) 展示了共享网络资源的 TCP、WebSocket 和 HTTP 网关。在 IDE 中使用 JDK 25 运行其 `main` 方法，按回车停止。提供 PEM 证书链和私钥后，也可启动 WSS。

RPC 节点创建、Peer 连接、调用、通知和回复示例见 [game-rpc 使用说明](game-rpc/README.zh-CN.md)。

## 仓库结构

~~~text
mana/
├── pom.xml                         # 统一构建所有项目
├── game-network/
│   ├── game-network-api/           # 仅依赖 JDK 的公共网络接口
│   ├── game-network-netty/         # Netty 网络传输实现
│   ├── docs/                       # 规范、设计与验证记录
│   └── scripts/                    # Linux 验证辅助脚本
├── game-rpc/
│   ├── game-rpc-core/              # RPC 接口、调用、Peer 与消息处理
│   ├── game-rpc-netty/             # 默认 Netty 传输集成
│   ├── docs/                      # Wire 与 Java 实现规范
│   └── benchmarks/                # 本地性能基线和 Docker 压力测试
├── game-runtime/                  # 仅依赖 JDK 的有序业务运行时、测试与规范
└── game-data/                     # 实体 Repository 与异步持久化
~~~

## 文档与验证

| 主题 | 参考文档 |
|---|---|
| 网络使用与配置 | [game-network 中文说明](game-network/README.zh-CN.md) |
| 网络协议规范 | [Game Network Specification](game-network/docs/Game%20Network%20Specification.md) |
| 网络运行与容量测试 | [运行指南](game-network/docs/operations.md) |
| 网络实现与验证范围 | [实现记录](game-network/docs/implementation-status.md) |
| RPC 使用、性能基线与 Docker 故障测试 | [game-rpc 中文说明](game-rpc/README.zh-CN.md) |
| RPC 行为与 Wire 格式 | [Game RPC Specification](game-rpc/docs/OGBS%20Game%20RPC%20Specification%20v1.md) |
| RPC 实现与内存归属 | [Java 实现规范](game-rpc/docs/java/Java%20Implementation%20Specification.md) |
| RPC Wire 测试数据 | [Wire 样例](game-rpc/docs/OGBS%20Game%20RPC%20v1%20wire%20vectors.json) |
| Runtime 使用与执行语义 | [game-runtime 中文说明](game-runtime/README.zh-CN.md) |

标准 `mvn verify` 构建运行各项目的回归测试。容量测试、Docker 持续测试和故障测试是独立流程，具体命令见项目文档。阅读测量结果时，请结合文档记录的测试环境和适用范围。

## 文档语言

默认的 `README.md` 使用英文。各项目另提供简体中文 `README.zh-CN.md`，可通过 README 顶部链接切换。更新项目文档时，请同步维护两个版本。
