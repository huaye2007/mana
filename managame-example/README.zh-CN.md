[English](README.md) | 中文

# managame-example

一个小游戏：一台世界服加一台游戏服，像业务应用那样在自己的代码里把所有组件连起来，框架本身没有任何项目去做这件事。可以照着
复制。没有项目依赖它，它也从不安装到本地仓库。

## 运行

```bash
mvn install                                  # 在工作区根目录
java -jar managame-example/target/example.jar
```

然后用 telnet 玩，每行一个请求，格式为 `<序号> <命令> [参数]`：

```
$ telnet 127.0.0.1 9001
1 login 1001 ann
1 ok welcome ann, gold 100
2 buy 5001
2 ok bought 5001, gold 70
3 bag
3 ok 5001x1
4 rank
4 ok rank 1 of 1
5 buy 9999
5 err 10002
```

游戏服每分钟在日志里报告在线人数和等待写入的数据量。按 Ctrl+C 两台服务器会按顺序关闭，关闭前所有修改都会写完。

## 每部分演示什么

| 文件 | 演示 |
| --- | --- |
| `GameServer` | 把所有组件连起来：数据、runtime、RPC、定时器，最后是网关。启动自下而上，关闭自上而下、数据最后；关闭的正确性靠这个顺序保证，没有组件统计进行中的工作。 |
| `Gateway`、`Messages`、`Session` | 游戏自己的协议：把行解码成消息，会话保存在连接上，每条消息交给 `Dispatcher`，回调按游戏的格式应答；玩家连接断开时登出。 |
| `PlayerHandlers` | 处理器在玩家自己的路由上逐个执行请求，改数据不用加锁。返回非 null 即为应答；`fail` 用游戏自己的错误码应答。`rank` 调用世界服，再用 `runtime.execute(request, ...)` 回到玩家的路由，之后才碰玩家数据。 |
| `WorldServer` | RPC 与 runtime 连起来：`RpcHandler` 在 EventLoop 上读出请求，把工作交给 runtime，排行榜在唯一的世界路由上维护，不用加锁。 |
| `Model`、`Schema` | 实体、按组存放的背包、日志，都存在游戏自己建的 MySQL 表里；道具 id 来自 `SnowflakeIdGenerator`，数据部分是服务器 id。 |
| `Jobs` | system domain 上的一个 `@Cron` 方法。 |
| `ExampleMain` | 在 `GameTime` 上统一设置一次游戏时区；关闭钩子先关游戏服，再关它调用的世界服。 |
| `ExampleTest` | 通过真实连接把游戏玩一遍，再检查游戏服关闭时保存下来的数据行。 |

`pom.xml` 按业务同时使用多个组件的方式引入组件：导入 `managame-bom`，引用组件时不写版本号。

## 换成 MySQL

示例把数据存在内存里、以 MySQL 模式运行的 H2 中，所以在哪里都能跑。正式游戏改为给 `GameServer` 传入基于 MySQL 驱动的连接池
`DataSource`，URL 参数按 data-mysql 的 README 设置，并用 `Schema` 建表，它就是 MySQL 语法。
