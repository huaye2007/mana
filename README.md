English | [中文](README.zh-CN.md)

# managame

Game server components on Java 25 and Netty 4.2. Each is a project of its own, so an application takes only the
ones it needs:

| Project | Depends on | What it is |
| --- | --- | --- |
| [managame-common](managame-common/README.md) | nothing | `Metadata`, `MetadataKey`, unique id generators and the shared error codes |
| [managame-network](managame-network/README.md) | Netty, the modules it uses | TCP / TLS / ws / wss servers, an HTTP/1.1 server and a reconnecting client, with any Netty handler |
| [managame-runtime](managame-runtime/README.md) | common, network | Every piece of business logic through one entry, with tasks of one domain and route key run one at a time: routing per domain, annotated handlers (`Dispatcher`, `EventBus`, `HttpRoutes`), interceptors and timers |
| [managame-rpc](managame-rpc/README.md) | common, network | Calls and notifies between server processes: `RpcNode`, `RpcPeer`, symmetric connections over TCP, restart detection, admission |
| [managame-data](managame-data/README.md) | common, Caffeine | Game data in memory, written behind it in batches wherever the application chooses: entities by id (`SingleRepository`), groups of entities (`GroupRepository`) and logs (`LogRepository`), through an `EntityMapper` and a `LogWriter` |
| [managame-data-mysql](managame-data-mysql/README.md) | data | `MysqlEntityMapper` and `MysqlLogWriter`: entities and logs in MySQL tables |
| [managame-data-mongo](managame-data-mongo/README.md) | data, MongoDB driver | `MongoEntityMapper`: entities in MongoDB collections |

An application that takes several components imports [managame-bom](managame-bom/README.md) for one version of
all: it holds versions only, of the components and the Netty modules they share.

[managame-benchmark](managame-benchmark/README.md) holds JMH benchmarks for every component, with a recorded
baseline each, so that a change that makes a component faster or slower shows at once.

runtime and rpc know nothing of each other. An application that uses both joins them in its own code: its
`RpcHandler` hands peers' requests to its `Dispatcher`, and its `RpcCallback`s run their work through its
`GameRuntime`.

## Logging

Every component logs through `System.Logger`, the JDK's own logging facade, and depends on no logging library. By
default it goes to java.util.logging; to send it to Logback or another SLF4J backend, or to Log4j 2, add one bridge to
the application, without changing a line of the framework:

```xml
<!-- Logback, or any SLF4J 2 backend -->
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

Loggers are named `cn.managame.<component>`, and below it by area, so one setting turns a component up or down:

| Logger | Component |
| --- | --- |
| `cn.managame.network`, `.network.http` | network |
| `cn.managame.runtime`, `.runtime.dispatch`, `.runtime.event`, `.runtime.http`, `.runtime.timer` | runtime |
| `cn.managame.rpc` | rpc |
| `cn.managame.data`, `cn.managame.data.mysql` | data, data-mysql |

Each component's README lists what its loggers say. Levels mean the same everywhere:

| Level | When |
| --- | --- |
| ERROR | Something is lost, or handed to an error handler |
| WARNING | Work refused, skipped or tried again |
| INFO | Lifecycle: a server listening or stopped, a peer joining or leaving, a component started or closed, what was registered |
| DEBUG | Detail per connection, call or write round; nothing per message at INFO, and DEBUG costs nothing while off |

For example with Logback, INFO for the framework and DEBUG for rpc:

```xml
<logger name="cn.managame" level="INFO"/>
<logger name="cn.managame.rpc" level="DEBUG"/>
```

## Build

One command at the root builds every project in dependency order, the benchmarks' jar last:

```bash
mvn install
java -jar managame-benchmark/target/benchmarks.jar all
```

The root `pom.xml` only aggregates: no project inherits from it, and each still builds on its own once the projects
below it are installed, such as `cd managame-runtime && mvn install`.

Tests open real loopback connections, and the data tests use an in-memory database. Conventions for contributors and coding agents are in [AGENTS.md](AGENTS.md).
