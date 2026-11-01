[English](README.md) | 中文

# managame-example

一个小游戏：一台世界服加一台游戏服，按真实游戏服务器的方式搭建：消息用 protobuf，装在游戏自己编解码的帧里；用纯 Spring
（spring-context，不用 Spring Boot）在游戏自己的代码里把所有组件连起来。框架本身没有任何项目去做这件事，组件也完全不知道
Spring 和 protobuf。可以照着复制。没有项目依赖它，它也从不安装到本地仓库。

## 运行

```bash
mvn install                                  # 在工作区根目录；第一次构建会下载 protoc
java -jar managame-example/target/example.jar
```

在另一个终端用示例客户端发几个请求，客户端本身也基于 managame-network：

```
$ java -cp managame-example/target/example.jar cn.managame.example.ExampleClient 1001 ann
> LoginRequest player_id: 1001 name: "ann"
< LoginResponse name: "ann" gold: 100
> BuyRequest config_id: 5001
< BuyResponse config_id: 5001 gold: 70
> BuyRequest config_id: 5003
< error 10001
> BagRequest
< BagResponse items { config_id: 5001 amount: 1 }
> RankRequest
< RankResponse rank: 1 ranked: 1
```

游戏服每分钟在日志里报告在线人数和等待写入的数据量。按 Ctrl+C 关闭 Spring 容器：服务器按顺序停下，关闭前所有修改都会写完。
端口和数据库 URL 在 `example.properties` 里。JDK 25 上 protobuf-java 会提示它用了 `sun.misc.Unsafe`，加 JVM 参数
`--sun-misc-unsafe-memory-access=allow` 即可去掉。

## 协议

消息定义在 `src/main/proto/game.proto`，构建时生成 Java 类。每条消息装在一帧里：

```
| 长度 int | 消息 id int | 序号 int | 错误码 int | protobuf 消息体 |
```

network 的 `lengthFieldFraming` 把字节流切成帧；游戏自己的 `PacketCodec` 把一帧转成 `Packet`（序号、错误码、protobuf
消息），反过来也一样，消息体直接从 Netty 的缓冲区读、往缓冲区写，中间不经过 byte 数组。`Protocol` 给每种消息类型分配 id。
请求的错误码为 0；应答带回请求的序号，成功时带响应消息，失败时不带消息、只带错误码。编解码器读不懂的帧会关闭连接。

## 每部分演示什么

| 文件 | 演示 |
| --- | --- |
| `GameConfig` | 每个组件都是一个 bean：数据、runtime、世界服与 RPC、Dispatcher 与定时器，最后是网关，网关管线是长度分帧加游戏自己的包编解码器。Spring 会先关闭一个 bean，再关闭它依赖的 bean，所以依赖关系就决定了关闭顺序（网关最先、数据最后；构造参数体现不出来的地方用 `@DependsOn`）。关闭的正确性靠这个顺序保证，没有组件统计进行中的工作。Dispatcher 和定时器各自把所有组件作为集合接收，只处理标给自己的注解方法。 |
| `Protocol`、`Packet`、`PacketCodec` | 基于 protobuf 的游戏自有协议：消息 id、帧格式、Netty 编解码器。 |
| `Gateway`、`Session` | 把解码出的每条消息交给 `Dispatcher`，回调按请求的序号以包的形式应答；会话保存在连接上；玩家连接断开时登出。 |
| `PlayerHandlers` | 一个处理器组件，在玩家自己的路由上逐个执行请求，改数据不用加锁：每个处理器接收 protobuf 请求、返回 protobuf 响应；`fail` 用游戏自己的错误码应答。`rank` 调用世界服，再用 `runtime.execute(request, ...)` 回到玩家的路由，之后才碰玩家数据。 |
| `WorldServer` | RPC 与 runtime 连起来，消息体也是 protobuf：`RpcHandler` 在 EventLoop 上读出请求，把工作交给 runtime，排行榜在唯一的世界路由上维护，不用加锁。 |
| `Model`、`Schema` | 实体、按组存放的背包、日志，都存在游戏自己建的 MySQL 表里；道具 id 来自 `SnowflakeIdGenerator`，数据部分是服务器 id。 |
| `OnlinePlayers`、`Jobs` | 游戏自己保存的在线玩家连接；system domain 上的一个 `@Cron` 方法。 |
| `GameClient`、`ExampleClient` | 基于 managame-network 的客户端，就像游戏客户端或压测机器人那样：`NetworkClient` 保持连接、断线重连，复用服务端同样的分帧和 `PacketCodec`，请求返回 future，按序号与应答对应；以及用它玩游戏的 main。 |
| `ExampleMain` | 在 `GameTime` 上统一设置一次游戏时区，启动容器并注册关闭钩子。 |
| `ExampleTest` | 在空闲端口上启动容器，用 `GameClient` 通过真实连接把游戏玩一遍（其中包括同时发出两个请求），验证破坏协议的客户端会被关闭，再检查容器关闭时保存下来的数据行。 |

`pom.xml` 按业务同时使用多个组件的方式引入组件：导入 `managame-bom`，引用组件时不写版本号；protobuf 类由
`protobuf-maven-plugin` 生成。

## 换成 MySQL

示例把数据存在内存里、以 MySQL 模式运行的 H2 中，所以在哪里都能跑。正式游戏把 `dataSource` bean 换成基于 MySQL 驱动的连接池，
URL 参数按 data-mysql 的 README 设置，并用 `Schema` 建表，它就是 MySQL 语法。
