[English](README.md) | 中文

# managame

基于 Java 25 和 Netty 4.2 的游戏服务器组件。每个组件都是独立的项目，业务按需选用：

| 项目 | 依赖 | 内容 |
| --- | --- | --- |
| [managame-common](managame-common/README.zh-CN.md) | 无 | `Metadata`、`MetadataKey`、唯一 id 生成器、共用的错误码，以及 `Metrics`：由应用接到自己后端的计数器、gauge 和计时器 |
| [managame-network](managame-network/README.zh-CN.md) | common、Netty（只依赖用到的模块） | TCP / TLS / ws / wss 服务端、HTTP/1.1 服务端与自动重连客户端，可接入任意 Netty handler |
| [managame-runtime](managame-runtime/README.zh-CN.md) | common、network | 所有业务逻辑经同一个入口执行，同一 domain 与路由键的任务逐个执行：按 domain 路由、注解处理器（`Dispatcher`、`EventBus`、`HttpRoutes`）、拦截器与定时器 |
| [managame-rpc](managame-rpc/README.zh-CN.md) | common、network | 服务器进程之间的调用与通知：`RpcNode`、`RpcPeer`、基于 TCP 的对等连接、重启识别、准入检查 |
| [managame-data](managame-data/README.zh-CN.md) | common、Caffeine | 游戏数据放在内存里，在后台批量写到应用选定的地方：按 id 的实体（`SingleRepository`）、分组的实体（`GroupRepository`）与日志（`LogRepository`），经 `EntityMapper` 和 `LogWriter` 写入 |
| [managame-data-mysql](managame-data-mysql/README.zh-CN.md) | data | `MysqlEntityMapper` 与 `MysqlLogWriter`：实体和日志存进 MySQL 表 |
| [managame-data-mongo](managame-data-mongo/README.zh-CN.md) | data、MongoDB 驱动 | `MongoEntityMapper`：实体存进 MongoDB 集合 |

同时使用多个组件时，导入 [managame-bom](managame-bom/README.zh-CN.md) 统一版本：它只有版本号，包括各组件和它们共用的 Netty 模块。

[managame-benchmark](managame-benchmark/README.zh-CN.md) 为每个组件提供 JMH 基准测试，并各自记录基线，改动让组件变快或变慢都能立刻看出来。

runtime 和 rpc 互不知道对方。同时使用两者的业务在自己的代码里把它们连起来：自己的 `RpcHandler` 把对端请求交给自己的 `Dispatcher`，自己的 `RpcCallback` 把后续工作交给自己的 `GameRuntime` 执行；见[把组件拼到一起](#把组件拼到一起)。

## 把组件拼到一起

组件之间互不知道对方，所以由应用把它们连起来。应用要写的不多，各个游戏也大同小异；[managame-example](managame-example) 里有一份写出来的。

```
clients          --> network --> gateway    --+
                                              +--> Dispatcher --> GameRuntime --> handlers --> data repositories
other processes  --> rpc     --> RpcHandler --+                       ^              |
                                                                      +--------------+
                                              RPC answers, timers and events come back as tasks
```

客户端和其它进程的消息都经应用自己的入口交给 `Dispatcher`，在 `GameRuntime` 的路由上逐个执行；处理器读写仓库、调用别的进程、设定时器、发事件，这些的结果又作为路由上的任务回来。

### 应用要写的部分

| 两个组件相接的地方 | 应用自己的 | 做什么 |
| --- | --- | --- |
| 客户端进来 | 一个 `ConnectionHandler`，也就是网关 | 每个连接保存一个会话，按会话给每条消息建上下文，交给 Dispatcher，再按自己的包格式写回应答（[runtime：Dispatcher](managame-runtime/README.zh-CN.md)） |
| 其它进程进来 | 一个 `RpcHandler` | 解析请求，和网关一样交给 Dispatcher，用的是同一批处理器，再用 `rpcPeer.reply(connection, response)` 应答（[rpc](managame-rpc/README.zh-CN.md)） |
| 任务调用别的进程 | 对 `peer.call` 的一层封装 | 在回调里把应答解析完（回调返回时应答就释放了），再用 `runtime.execute(context, task)` 回到发起调用的任务的路由上：回调在 EventLoop 上执行，不在路由上 |
| 谁在线 | 一张角色到会话的表 | 在请求之外给玩家推送；新登录时找到要被顶掉的旧会话 |
| 一个在线玩家 | 应用自己的、每个玩家一个的内存对象 | 保存和在线同寿命的东西，比如会话和一个 `TimerGroup`；不保存 `Context`，上下文是每条消息带来的 |

会话是应用自己的，不是上下文：网关为每条消息建上下文，说明此刻是谁在操作（`GameContext.of(PLAYER, roleId, ROLE, roleId)`），任务再把这个上下文带给它发起的东西：事件、定时器、对别的进程的调用（应答回来后在它上面继续）。

### 玩家上线和下线

- **登录。** 还没登录的连接发来的消息不代表任何人，所以处理器要写明它按哪个键执行，比如账号（`@HandleMethod(routeKey = "userId")`）。网关在写出应答之前把角色绑定到会话上，这样客户端接下来发的消息就以这个角色执行；同时把会话放进角色表，表里已有的同角色会话就是被这次登录顶掉的连接，由网关关闭。
- **在线。** 实体在每个任务里按 id 取，不跨任务持有；玩家对象里只放仓库里没有的东西。
- **下线。** `onInActive` 在连接的 EventLoop 上执行，所以它只把会话从表里拿掉，剩下的交给玩家的路由，在那里取消玩家的定时器：

  ```java
  @Override public void onInActive(Connection connection) {
      Session session = connection.get(SESSION);
      if (session.roleId() == 0 || !sessions.remove(session.roleId(), session)) return;   // 没登录过，或者已被新登录顶掉
      try {
          runtime.execute(GameContext.of(PLAYER, session.roleId(), ROLE, session.roleId()), context -> players.leave(session.roleId()));
      } catch (GameRuntimeException refused) {
          // 和其它入口一样：玩家的路由满了，或者 runtime 已经关闭，就会被拒绝
      }
  }

  void leave(long roleId) {                    // 在玩家的路由上
      online.remove(roleId).timers().cancelAll();
  }
  ```

  数据不用做任何事：玩家改过的东西不管还在不在内存里，下一轮都会写入；没人用满 `cacheExpire` 后自己离开内存。只有玩家要转到别的进程时才先 flush 再 evict，让那个进程读到最新的。

### 启动和关闭

从底层开始启动，最后才开门，这样消息一到，它后面的东西都已经就绪：

1. `GameData.build()`：读实体类，让每个 mapper 检查自己的表，加载整表常驻的表。
2. `GameRuntime.build()`，然后是建在它上面的 `Dispatcher`、`EventBus`、`HttpRoutes` 和 `GameTimer`。
3. 带着 `RpcHandler` 的 `RpcNode.start()`，再对要主动连接的节点 `createPeer`。对外服务之前必须先和某个对端做完的事，比如向世界服注册，从 `RpcListener.onConnected` 开始。
4. 给客户端的 `NetworkServer.start(...)`。

关闭顺序相反。每一步都先结束下一步本来要等待或者会丢掉的东西：

1. **关闭客户端连接的服务器。** 不再有消息进来；用的是服务器自己的线程时，它的连接也一并关闭，`close()` 返回时每个连接的 `onInActive` 都已执行，所有玩家都按平常的路径下线。
2. **关闭 `RpcNode`。** 等待中的调用以 `RPC_UNAVAILABLE` 失败，它们的回调还能把任务交给 runtime。`GameTimer` 也在这时关闭；之后再设的定时器拿到的是已取消的。
3. **关闭 `GameRuntime`。** 它拒绝外部来的任务，等所有已接受的任务以及它们交出去的工作执行完。不能在它自己的任务里调用。
4. **最后关闭 `GameData`**，这时已经没有东西再改数据：它把任务改过的都写入，有写不进去的就抛异常，抛之前把每一条都交给 `DataErrorHandler`。

按依赖的反序关闭 bean 的容器会自动做到这一点，只要 runtime 依赖 data、Dispatcher 依赖 runtime、服务器和节点依赖 Dispatcher，示例就是这样装配的。

### 代码在哪里执行

| 线程 | 执行什么 | 规则 |
| --- | --- | --- |
| EventLoop（network、rpc） | 解码、网关、`RpcHandler`、RPC 的回调和监听器 | 不能阻塞：解析完就把工作交给 runtime |
| runtime 的一条路由 | 所有处理器、事件、定时器和续接的工作，同一 domain 和路由键逐个执行 | 业务逻辑，而且只在这里执行。从数据库加载会阻塞所在的路由，`RouteExecutors.virtualThreads()` 就是为此准备的 |
| 时间轮（runtime、rpc） | 把到期的定时器交给 runtime；超时的 RPC 调用的回调 | 不能阻塞 |
| data 的写线程 | 写入修改，写的时候读实体 | 实体里的集合要线程安全 |

请求一定会得到应答：要么是处理器返回的结果，要么是 common 的 `FrameworkErrorCodes` 里的错误码，所有组件共用这一张表：RPC 2001–2999，runtime 3001–3999，data 4001–4999。`Metrics` 和日志桥接也只需各绑定一次，所有组件共用。

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
