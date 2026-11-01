[English](README.md) | 中文

# managame

基于 Java 25 和 Netty 4.2 的游戏服务器组件。每个组件都是独立的项目，业务按需选用：

| 项目 | 依赖 | 内容 |
| --- | --- | --- |
| [managame-common](managame-common/README.zh-CN.md) | 无 | `Metadata`、`MetadataKey`、唯一 id 生成器与共用的错误码 |
| [managame-network](managame-network/README.zh-CN.md) | Netty（只依赖用到的模块） | TCP / TLS / ws / wss 服务端、HTTP/1.1 服务端与自动重连客户端，可接入任意 Netty handler |
| [managame-runtime](managame-runtime/README.zh-CN.md) | common、network | 所有业务逻辑经同一个入口执行，同一 domain 与路由键的任务逐个执行：按 domain 路由、注解处理器（`Dispatcher`、`EventBus`、`HttpRoutes`）、拦截器与定时器 |
| [managame-rpc](managame-rpc/README.zh-CN.md) | common、network | 服务器进程之间的调用与通知：`RpcNode`、`RpcPeer`、基于 TCP 的对等连接、重启识别、准入检查 |
| [managame-data](managame-data/README.zh-CN.md) | common、Caffeine | 游戏数据放在内存里，在后台批量写到应用选定的地方：按 id 的实体（`SingleRepository`）、分组的实体（`GroupRepository`）与日志（`LogRepository`），经 `EntityMapper` 和 `LogWriter` 写入 |
| [managame-data-mysql](managame-data-mysql/README.zh-CN.md) | data | `MysqlEntityMapper` 与 `MysqlLogWriter`：实体和日志存进 MySQL 表 |
| [managame-data-mongo](managame-data-mongo/README.zh-CN.md) | data、MongoDB 驱动 | `MongoEntityMapper`：实体存进 MongoDB 集合 |

同时使用多个组件时，导入 [managame-bom](managame-bom/README.zh-CN.md) 统一版本：它只有版本号，包括各组件和它们共用的 Netty 模块。

[managame-benchmark](managame-benchmark/README.zh-CN.md) 为每个组件提供 JMH 基准测试，并各自记录基线，改动让组件变快或变慢都能立刻看出来。

runtime 和 rpc 互不知道对方。同时使用两者的业务在自己的代码里把它们连起来：自己的 `RpcHandler` 把对端请求交给自己的 `Dispatcher`，自己的 `RpcCallback` 把后续工作交给自己的 `GameRuntime` 执行。

## 日志

所有组件都通过 JDK 自带的日志门面 `System.Logger` 输出，不依赖任何日志库。默认输出到 java.util.logging；要输出到
Logback 等 SLF4J 实现或 Log4j 2，只需在业务里加一个桥接依赖，框架代码一行不用改：

```xml
<!-- Logback，或任意 SLF4J 2 实现 -->
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk-platform-logging</artifactId>
  <version>${slf4j.version}</version>
</dependency>

<!-- Log4j 2 -->
<dependency>
  <groupId>org.apache.logging.log4j</groupId>
  <artifactId>log4j-jpl</artifactId>
  <version>${log4j.version}</version>
</dependency>
```

Logger 按 `cn.managame.<组件>` 命名，下面再按领域细分，一个配置就能调高或调低一个组件的日志：

| Logger | 组件 |
| --- | --- |
| `cn.managame.network`、`.network.http` | network |
| `cn.managame.runtime`、`.runtime.dispatch`、`.runtime.event`、`.runtime.http`、`.runtime.timer` | runtime |
| `cn.managame.rpc` | rpc |
| `cn.managame.data`、`cn.managame.data.mysql` | data、data-mysql |

各组件的 README 列出了自己的 logger 会记录什么。各级别在所有组件里含义相同：

| 级别 | 何时 |
| --- | --- |
| ERROR | 有东西丢失，或交给了错误处理器 |
| WARNING | 工作被拒绝、跳过或重试 |
| INFO | 生命周期：服务开始监听或停止、节点加入或离开、组件启动或关闭、注册了什么 |
| DEBUG | 按连接、按调用、按写入轮次的细节；不会按消息打 INFO，DEBUG 关闭时几乎没有开销 |

例如用 Logback，框架整体 INFO、rpc 打开 DEBUG：

```xml
<logger name="cn.managame" level="INFO"/>
<logger name="cn.managame.rpc" level="DEBUG"/>
```

## 构建

在根目录一条命令按依赖顺序构建全部项目，最后打出基准测试的 jar：

```bash
mvn install
java -jar managame-benchmark/target/benchmarks.jar all
```

根目录的 `pom.xml` 只负责聚合，没有项目继承它；下层项目安装之后，每个项目仍然可以单独构建，例如 `cd managame-runtime && mvn install`。

测试会打开真实的回环连接，data 的测试使用内存数据库。贡献者与编码代理的约定见 [AGENTS.md](AGENTS.md)。
