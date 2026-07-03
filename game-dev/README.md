# game-dev

完整可运行的游戏服务器宿主：把 game-network（Netty 接入）、game-runtime（线程组/命令/事件/定时器）、
game-serialization（Fury）、game-jpa（异步落库）组装成一个开箱即用的单进程游戏服，
同时作为「宿主该怎么接这些框架」的标准样板。

## 架构

```
客户端 ──TCP──▶ Netty IO 线程
                 │  IdleStateHandler + IdleKickHandler   读空闲踢人
                 │  GamePacketEncoder / GamePacketDecoder 帧编解码 + Fury 反序列化
                 │  GameHandler (INetworkHandler)         连接/断线/异常
                 ▼
           GameRouterManager.handleGameMsg               登陆 gate → 按 command 路由
                 │ 封装 GameCommandTaskRunnable
                 ▼
           ExecutorGroupRegistry                          LOGIN / PLAYER 执行器组
                 │ 同 routerKey 串行（虚拟线程 worker）
                 ▼
           @GameController 业务 handler                    读写 game-jpa 缓存/仓库
                 │ reply / push / broadcast                EventBus.publishEvent
                 ▼                                          ▼
           session.writeMsg（IO 线程写回）             @EventHandler 监听者
```

- **IO 线程只做编解码和路由校验**，业务全部在执行器组里执行；同一 routerKey（如同一玩家）严格串行，业务代码不需要加锁。
- **回包/推送写 session 即返回**，写失败由网络层丢弃——转发失败不重试、不缓冲。

## 外网帧格式

```
bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)
```

| 字段 | 说明 |
|---|---|
| bodyLength | body 字节数，上限 1 MiB（`GamePacketConstant.MAX_BODY_LENGTH`），超限断连 |
| command | 业务命令号；`1~999` 保留给服务端主动推送的系统命令（如踢下线=1），业务从 `1000` 起 |
| seq | 客户端请求序号，回包原样带回用于对包；服务端主动推送恒为 `0` |
| code | 错误码（见下表），`0`=成功；非 0 时 body 为空 |
| flags | 预留（压缩/加密标记） |
| body | Fury 序列化的业务对象；所有外网 DTO 放 `com.github.huaye2007.mana.dev.message` 包，启动期由 `FuryMessageRegistrar` 整包登记（默认 Fury 要求类注册，未登记类型直接抛异常，挡住任意类反序列化攻击面） |

### 错误码（`GameErrorCode`）

| code | 含义 |
|---|---|
| 0 | OK |
| 1 | INTERNAL_ERROR：handler 抛未识别异常 |
| 2 | UNKNOWN_COMMAND：command 未注册 |
| 3 | BAD_REQUEST：body 反序列化失败或参数非法 |
| 4 | NOT_LOGGED_IN：未登陆调用 LOGIN 组之外的命令 |
| 5 | SERVER_BUSY：worker 队列满，任务被丢弃（终态，不重试） |
| 6 | DUPLICATE_LOGIN：踢下线原因——同账号在别处登陆 |

客户端不会被无声挂起：未知命令、坏包、未登陆、业务拒绝、handler 崩溃、服务器繁忙都会按原 command/seq 回一帧错误码。

## 会话生命周期

- **登陆 gate**：`GameRouterManager` 对 LOGIN 组之外的命令要求 session 已绑定 roleId，否则回 `NOT_LOGGED_IN`。
- **顶号**：`PlayerSessionManager.bind` 发现同 roleId 已在线时，先给旧连接推踢下线帧
  （`command=1, seq=0, code=DUPLICATE_LOGIN`），写完成后关闭旧连接；条件解绑保证顶号重连不误删新 session。
- **空闲踢人**：读空闲超过 180 秒（`CustomTcpPipelineConfigurator.DEFAULT_READER_IDLE_SECONDS`）直接断连，
  客户端靠心跳（command=1001）保活。链路不健康时不推帧，直接关。

## 任务失败统一处理（`GameTaskFailureReplier`）

启动期注册为全局 `GameTaskExceptionHandler` + `GameTaskMonitor`：

- handler 抛 `GameBusinessException(code, msg)` → 按 code 回包，不打堆栈（业务拒绝是正常流程）；
- handler 抛其它异常 → 记错误日志 + 回 `INTERNAL_ERROR`；
- worker 队列满任务被丢弃 → 回 `SERVER_BUSY`；
- 慢任务（≥1s）打 warn。

只有 COMMAND 任务有请求方可回；EVENT/TIMER 失败仅记日志。

## 写业务的方式

**命令 handler**：类上 `@GameController(group=...)`，方法上 `@GameMethod(value=命令号)`。
LOGIN 组方法第一个参数是 `PlayerSession`（未绑定 roleId），其余组是 `Long roleId`；第二个参数是消息 DTO。
LOGIN 组用 `routerKeyMethod` 从消息里取键打散（如 `getUserId`），避免登陆洪峰串行到单 worker：

```java
@GameController(group = ExecutorGroups.LOGIN)
public class LoginController {
    @GameMethod(value = 1000, routerKeyMethod = "getUserId")
    public void login(PlayerSession session, LoginReq req) {
        if (req.getUserId() <= 0) {
            throw new GameBusinessException(GameErrorCode.BAD_REQUEST, "invalid userId");
        }
        // ... 绑定、读写 Repository ...
        gameRouterManager.reply(loginRes);                       // 回当前请求
        EventBus.getInstance().publishEvent(new PlayerLoginEvent(...)); // 旁路逻辑走事件
    }
}
```

**回包与推送**（`GameRouterManager`）：

| 方法 | 语义 |
|---|---|
| `reply(msg)` | 回当前请求（command/seq 取任务上下文，code=OK），仅 handler 线程可调 |
| `replyError(code)` | 当前请求错误码回包；业务上更推荐直接抛 `GameBusinessException` |
| `push(roleId, command, msg)` | 服务端主动推送，seq=0，离线丢弃；任意线程可调 |
| `broadcast(command, msg)` | 全服在线广播，body 只序列化一次 |

**事件**：`IGameEvent.routerKey()` 决定串行键；监听类 `@EventHandler(group=...)` + 方法 `@EventMethod`，
由 Spring 扫描托管、启动期注册进 `EventBus`。与发布方同组同 routerKey 时内联执行，否则投递到监听者所在组。
样例：`PlayerLoginEvent` / `PlayerLoginEventHandler`。

**定时器**：统一用 `TimingWheel.getInstance()`（哈希时间轮，禁用 ScheduledExecutorService）。
周期任务 = 一次性 schedule + 任务内自续期（见 `Game.scheduleOnlineReport`）。
轮线程上只做轻量打点，重活封装成任务投递到执行器组。

**消息 DTO**：放 `com.github.huaye2007.mana.dev.message` 包即可，`FuryMessageRegistrar` 启动期按类名排序整包登记，无需手工注册。

## 启动与停机

启动顺序（`Game.main`）：Spring 扫描（include filter 接管 `@GameController`/`@EventHandler`）→
refresh 前注册 game-jpa Repository 单例 → refresh → 注册执行器组 → 注册命令/事件 →
Fury 登记消息类型 → 装配任务失败回包 → 起 Netty → 定时任务。

停机是**单一 shutdown hook 里的有序序列**（不拆多个 hook，JVM 并行执行会乱序）：

```
停时间轮（不再产生新任务）→ 停 Netty（关连接、不再收请求）→
排空执行器组（在途业务写完缓存）→ 关 GameJpaContext（刷异步写队列落库）→ 关 Spring 容器
```

## 配置

优先 `-D` 系统属性，其次环境变量，最后默认值：

| 系统属性 | 环境变量 | 默认 |
|---|---|---|
| `game.server.port` | `GAME_SERVER_PORT` | `8080` |
| `game.db.url` | `GAME_DB_URL` | `jdbc:mysql://localhost:3306/test` |
| `game.db.username` | `GAME_DB_USERNAME` | `root` |
| `game.db.password` | `GAME_DB_PASSWORD` | `123456` |

## 运行

```bash
# 服务端（需要本地 MySQL，按实体自动补建表）
mvn -q -pl game-dev exec:java -Dexec.mainClass=com.github.huaye2007.mana.dev.Game

# 交互式测试客户端（login / ping / send / raw，见 GameClientMain 帮助）
mvn -q -pl game-dev exec:java -Dexec.mainClass=com.github.huaye2007.mana.dev.client.GameClientMain

# 测试（全链路集成测试不依赖 MySQL）
mvn -pl game-dev test
```

`ServerFrameworkIntegrationTest` 用真实管线覆盖框架行为：登陆 gate、未知命令/坏包错误帧、
业务异常与崩溃回包、顶号踢下线推送、空闲踢人。
