# game-demo

[English](README.md) | **[简体中文](README.zh-CN.md)**

根 Maven 构建中的普通 Spring 应用，使用 JDK 25 和仓库父 POM，应用源码位于 `cn.managame.demo`。`spring-context:7.0.9` 提供依赖注入；应用层 HikariCP 与 MySQL Driver 提供连接池和 JDBC 实现。

`org.apache.fory:fory-core:1.7.6` 在此应用内提供业务 body 的二进制序列化。按照 [Fory 的 JDK 配置说明](https://fory.apache.org/docs/object-serialization/java/)，在 IDE 中使用 JDK 25 启动 GameDemo 时增加 VM 参数 `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`；demo POM 已为 Surefire 测试配置该参数。

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) 启动 Spring Context 并注册关闭钩子。[MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) 加载 `application.properties` 并创建应用持有的连接池。业务 Repository 类使用 Spring 注解：

```java
@Repository
public class UserRepository extends SingleRepository<Long, User> {
}
```

在 `src/main/resources/application.properties` 配置 `game.db.url`、`game.db.username`、`game.db.password` 后，使用 JDK 25 在 IDE 启动 `cn.managame.demo.GameDemo`。数据库需预先存在，Data 会初始化缺失的状态表/列/索引。JDBC URL 缺失或为空时，在创建连接池前报错并明确提示配置 `game.db.url`。也可通过 JVM 系统属性和环境变量向 Spring Environment 提供这些配置键。

`@PropertySource` 加载文件，Spring 默认的内嵌值解析器负责注入 `@Value`，无需显式声明 `PropertySourcesPlaceholderConfigurer` Bean。初始化成功后，入口输出 `game-demo started. Press Ctrl+C to stop.`，并返回 main 在 9000 端口启动 packet 分发 TCP 服务。Netty 持有的线程使进程持续运行。当前入口不阻塞主线程；JVM 退出时触发 Spring 关闭钩子。Context 关闭先关闭监听器及其持有的网络资源，再关闭 Data 和应用持有的连接池。不新增保活线程；目前尚未创建 HTTP 监听器。

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 收集扫描到的、继承 SingleRepository、GroupRepository 或 LogRepository 的 `@Repository` Bean，不提前实例化。它将类型注册到 GameDataBuilder，并把 Bean 的实例供应器改为 `data.repository(type)`，保留 Bean 名称、限定符及 Spring 字段/setter 注入。Data 初始化实例后才进入 Spring 注入回调或供业务使用；在扫描范围内新增 Repository 无需逐个注册类型或声明 `@Bean` 方法。Repository 必须为单例作用域，其他作用域在启动时拒绝；仍遵守 Data 直接继承具体参数化基类及无参构造的要求。实例由 GameData 构造，因此不支持构造器依赖注入。此适配位于 demo 应用层，game-data 不依赖 Spring。

User 的 JSON Map 初始化为 ConcurrentHashMap<Integer,Long>，默认加载保留具体实现及泛型类型。空 Map 默认值由 Java 初始化和全字段 INSERT 提供；`defaultValue="{}"` 不是 SQL DDL 表达式，已省略。详见 [默认 JSON 类型绑定与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)。自定义行为通过 `jsonCodec(...)` 配置。

## 发布 Runtime 事件

[GameRuntimeConfig](src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 构建由 Spring 管理的 GameRuntime，为 Domain 1（`ROLE`）注册虚拟线程 RouteExecutor，并收集标记 `@EventHandler` 的 Spring Bean，通过 `builder.eventHandlers(...)` 注册。业务监听器只需 `@EventHandler`：配置为 `cn.managame.demo` 增加注解 include filter，Spring 即可将这些类扫描为 Bean，无需 `@Component`。扫描适配属于 demo，Runtime 注解本身不依赖 Spring。配置在启动阶段调用 Events.bind(runtime)，使外部业务也能通过静态入口发布。默认绑定与其他实例冲突时关闭新构建的 Runtime。Context 关闭时关闭 Runtime、解除其默认事件绑定，并关闭其持有的执行/定时器资源。

TCP 监听器启动后，GameDemo 发布一条不可变的 [DemoEvent](src/main/java/cn/managame/demo/event/DemoEvent.java)：

```java
Events.publish(new DemoEvent(1001L, "hello game-runtime"));
```

[DemoEventHandler](src/main/java/cn/managame/demo/event/DemoEventHandler.java) 通过 `@EventMethod` 接收事件，使用 `Contexts.current()` 读取绑定的 EventContext。可见输出为：

```text
DemoEvent received: routeDomain=1, routeKey=1001, message=hello game-runtime, virtualThread=true
```

主线程发布进入 Runtime 已配置的 RouteExecutor，不增加额外事件线程，也不使用 Spring ApplicationEvent。DemoEvent 的 Domain 固定为 1，Key 由调用方提供，0 会被 Runtime 拒绝。`publish` 返回不代表监听器已完成。这是进程内本地事件，不持久化到数据库，也不远程投递。契约沿用 [Runtime 事件语义](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#7-本地事件) 与 [Java EventBus 绑定](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#9-eventbus)。

[DemoEventTest](src/test/java/cn/managame/demo/DemoEventTest.java) 只注册 GameRuntimeConfig，由其扫描发现仅标记事件注解的监听器，无需 MySQL 或监听 socket，等待顺序靠后的监听器完成，并验证事件实例、目标 Domain/Key、虚拟线程及 Runtime 关闭并解除默认绑定后静态发布被拒绝。事件测试已通过并打印以上结果。完整应用启动仍需既有数据库配置。

## Handler 分发

[GameDomain](src/main/java/cn/managame/demo/common/runtime/GameDomain.java) 是应用自定义的执行域枚举，ROLE 使用显式 ID 1，LOGIN 使用显式 ID 2；GameRuntimeConfig 将枚举值转为 RouteDomain 注册，再把它们的 ID 绑定到执行器。Handler 注解使用编译期常量 `@Handler(domain = GameDomain.ROLE_ID)`，因为 Java 注解元素不能接收任意应用枚举类型，也不能使用 ROLE.id() 等方法调用。ID 不使用 ordinal()，枚举调整顺序不会改变路由。Domain 组织执行，本身不决定角色身份、不要求鉴权，也不自动选择 routeKey；routeKey 与鉴权后的 roleId 均由业务接入明确传入。

GameRuntimeConfig 也通过扫描发现仅标记 `@Handler` 的 Bean，再通过 `builder.handlers(...)` 注册。[UserHandler](src/main/java/cn/managame/demo/bus/user/UserHandler.java) 使用 `@Handler(domain=GameDomain.ROLE_ID)`，并在 `login(DefaultHandlerContext context, LoginReq request)` 上用 `@HandlerMethod(domain=GameDomain.LOGIN_ID)` 覆盖登录域，通过 `context.connection()` 获取连接，不使用独立 Connection 方法参数。GameProtocols 在协议号 1003 注册 LoginReq/LoginRes、关联响应，并分配 Fory 类型 ID 3/4。

调用方通常已知 Key，只需要：

```java
runtime.dispatch(connection, routeKey, loginReq);
```

Runtime 选择 Handler 的 Domain 并创建上下文；协议 userId 不覆盖外部传入的 Key，无需配置提取规则。只有少数按协议路由的场景才使用 `@HandlerMethod(routeKey="userId")` 或 `routeKeyMethod="getUserId"`，在未配置 HandlerContextFactory 的 builder 上再调用 `runtime.dispatch(connection, loginReq)`。demo 已配置工厂，由它按 Domain 明确选择路由，见下文。这些名字对应 LoginReq 的字段或 public 无参 getter。此匿名登录使用身份 0/0、空 Metadata；Metadata/自定义字段仍可通过显式 Context 分发。

业务 Handler 直接从 Context 获取身份。[RoleHandler](src/main/java/cn/managame/demo/bus/role/RoleHandler.java) 声明 `ping(DefaultHandlerContext context, PingMessage request)`，通过 `long roleId = context.businessId()` 获取角色 ID。无需定义 RoleId/GuildId/RoomId 包装类型，也无需注册 handlerArguments。GameDomain.ROLE_BUSINESS_ID_TYPE=1 表示应用的角色身份类别，与执行 Domain 独立；可信接入层也可以明确传入身份：

```java
runtime.dispatch(connection, 99L, GameDomain.ROLE_BUSINESS_ID_TYPE, 10001L, new PingMessage(1234));
```

Domain 1 来自 @Handler，Key 为 99，context.businessId() 为 10001；Key 和协议 timestamp 均不选择身份。接入层鉴权并提供可信身份。网络入口的 ROLE 策略在入队前拒绝缺少会话的请求，再将角色类别和 ID 捕获到 Context。显式 Key/身份/上下文重载绕过该策略，调用方必须传入正确的可信身份；Context 参数本身不会自动校验业务类别或完成鉴权。只有 Handler 在自己的 Route 执行。[DemoIdentityTest](src/test/java/cn/managame/demo/DemoIdentityTest.java) 无需数据库/socket，验证无需参数绑定的 Spring 分发、身份/Key/协议值独立、虚拟线程执行及缺少会话时拒绝。见 [完整参数契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-arguments)。

[DemoHandlerTest](src/test/java/cn/managame/demo/DemoHandlerTest.java) 只装配 Runtime 配置，用手动测试探针替换空的登录实现，验证 LoginReq.userId=10001 时使用调用方 Key 99、Domain 2、原连接/请求及虚拟线程；还验证未绑定 LOGIN 按 userId 仅选择路由、不访问连接属性，以及 Key 0 在接纳前拒绝，无需数据库或 socket。TCP 入口现已将解码消息分发到 Runtime Handler；UserHandler 登录业务仍为空，不自动生成 LoginRes。完整 demo 启动仍需数据库配置。

## Fory 业务 body

[GameProtocols](src/main/java/cn/managame/demo/common/protocol/GameProtocols.java) 是应用中协议号、消息类型及稳定 Fory 类型 ID 的唯一配置入口。GameRuntimeConfig 通过 `builder.protocols(...)` 注册它；[ForyConfig](src/main/java/cn/managame/demo/common/serialization/ForyConfig.java) 在暴露由 Spring 管理的共享 ThreadSafeFory 之前，注册相同的消息类型与类型 ID。当前注册为：

| 请求协议号 | 消息类型 | Fory 类型 ID |
| --- | --- | --- |
| 1001 | [DemoMessage](src/main/java/cn/managame/demo/network/message/DemoMessage.java) | 1 |
| 1002 | [PingMessage](src/main/java/cn/managame/demo/network/message/PingMessage.java) | 2 |
| 1003 | [LoginReq](src/main/java/cn/managame/demo/bus/user/LoginReq.java) | 3 |

LoginRes 单独注册为 RESPONSE 协议号 1003、Fory 类型 ID 4；服务端入站仍按 REQUEST 查询。

协议号与 Fory 类型 ID 是不同标识。增加协议时在 GameProtocols 中添加绑定，不在连接 Handler 内增加消息类型分支。Runtime 在启动时按既有规则校验协议唯一性。Fory 类型 ID 必须唯一，并在通信两端保持稳定；请求和响应可以共用协议号，但使用不同消息类及不同 Fory 类型 ID。

[GamePacketHandler](src/main/java/cn/managame/demo/network/GamePacketHandler.java) 是注入共享序列化器和 Runtime 的 Spring Bean。服务端将入站 packet 视为 REQUEST，在反序列化前从 Runtime 既有 ProtocolRegistry 按协议号查询预期类型：

```java
var protocol = protocols.get(ProtocolType.REQUEST, packet.getCommand());
if (protocol == null) {
    throw new IllegalArgumentException("Unknown request command: " + packet.getCommand());
}
Class<?> type = protocol.messageType();
Object decodedMessage = type.cast(fory.deserialize(packet.getBody()));
runtime.dispatch(connection, decodedMessage);
```

预期类型由协议号确定，不写死 DemoMessage。cast 校验实际解码根对象：已注册的 PingMessage 若以协议号 1001 发送，会被拒绝，不会被当作 DemoMessage 读取。未知协议号在解码前拒绝。随后通过 Runtime 在配置的 Route 执行器上调用精确类型对应的 HandlerMethod，不再由网络回调回传。注册协议不代表有 Handler：DemoMessage 仍是序列化测试消息，以正确 body 分发协议号 1001 当前会拒绝 HANDLER_NOT_FOUND。LoginReq 协议号 1003 进入 UserHandler.login；PingMessage 协议号 1002 进入 RoleHandler.ping。原始空 body 是有效分帧，但不是已序列化的 Fory 对象。

[GameSession](src/main/java/cn/managame/demo/network/GameSession.java) 是存储在 Connection 的 Netty AttributeKey 中的不可变应用 record。它保存业务选择的非零 Key 和long 类型的已鉴权 roleId。onConnected 为空，不分配 Key，不绑定或覆盖会话；鉴权成功前没有会话。只能在 login 的业务 token 校验成功后手动绑定。下例是业务接入示意，tokenVerifier 和 Key 选择由应用实现，当前骨架未实现：

```java
@HandlerMethod(domain = GameDomain.LOGIN_ID)
public void login(DefaultHandlerContext context, LoginReq loginReq) {
    // verify 必须拒绝无效 token，并返回服务端核实的身份。
    var identity = tokenVerifier.verify(loginReq.getToken());
    long selectedRouteKey = selectRoleRouteKey(identity);
    context.connection().set(GameSession.KEY,
            new GameSession(selectedRouteKey, identity.roleId()));
}
```

角色身份必须由业务在鉴权后明确提供，不从 loginReq.userId/token 复制。GameRuntimeConfig 注册 HandlerContextFactory：Runtime 先解析 Handler 注解的 Domain，再由工厂调用 GameDomain.fromId(domain).handlerContext(...)。LOGIN（ID 2）明确选择 LoginReq.userId 仅作为排队 Key，身份为 0/0，不读写会话；客户端值不代表已鉴权身份，Key 0 在接纳前拒绝。ROLE（ID 1）读取一次 GameSession，缺少会话时在接纳前拒绝，保留业务传入的 Key 与 GameDomain.ROLE_BUSINESS_ID_TYPE/角色值，不将 Key 固定为 roleId。token 校验失败时未绑定连接继续保持未绑定；绑定成功只影响后续接纳，不改变之前已接纳的上下文；新 Domain 明确定义自己的策略。可将属性查询替换成线程安全的 Connection 到角色 Map，不需要修改 GamePacketHandler 或 Runtime，不强制具体存储或固定的 Domain 到角色规则。Handler 通过 Context 读取身份。会话是否存在表示业务是否成功绑定；roleId 为 long，不以 0/null 作为鉴权标记，业务可自行约束 ID 范围。见 [工厂契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-context-factory)。

即使之后连接会话或原始 packet 改变，已接纳任务仍使用原值与解码对象。改变 Key 只影响后续接纳，不迁移旧任务，状态交接由应用协调。已配置工厂的入口不隐式提取消息 Key，也不在会话缺失时回退。无效 Fory body、类型不匹配、ROLE 会话缺失、工厂/身份解析失败、Handler 缺失、过载及 Runtime 关闭等接纳失败交给 Network 的 onException 回调并关闭连接；Handler 执行失败走 RuntimeErrorHandler，不自动关闭连接或重试。断连不取消已接纳任务。鉴权、身份存储清理、packet seq/code 关联及响应构造/发送仍属于业务接入；此入口不自动回传或产生响应 DTO。显式 Key/身份及显式上下文分发继续可用，并绕过工厂。

Fory 使用 Java native 模式（`withXlang(false)`），要求注册类。通信两端使用相同版本、配置及类型标识；payload 引用其他自定义嵌套类时也需注册。此前任意原始字节不再是此监听器的有效业务 payload。[共享 ThreadSafeFory 池](https://fory.apache.org/docs/object-serialization/java/virtual-threads/) 支持多个 EventLoop 和虚拟线程并发，不为每个虚拟线程保留一个序列化器。禁用异步编译，序列化由调用方执行，不新增业务执行器。复用该 Bean，不逐包创建 Fory，也不在接收流量期间修改注册。Data 的 MySQL JSON 列继续使用其 JSON codec；此依赖仅位于 game-demo。

[ForySerializationTest](src/test/java/cn/managame/demo/ForySerializationTest.java) 验证 Spring 注入、独立写端/读端配置、Unicode/long、128 个虚拟线程并发、未注册类型、无效 body、未知协议号，以及协议号/body 类型不匹配。这些检查不证明跨语言兼容、任意 schema 升级或容量限制。

## GamePacket TCP 分帧

[GamePacket](src/main/java/cn/managame/demo/network/GamePacket.java) 携带 `int command`、`int seq`、`int code` 和原始 `byte[] body`。编解码属于此应用，不定义 RPC 线格式，也不新增框架协议。每个整数占四字节，使用大端序：

| 偏移 | 字段 | 含义 |
| --- | --- | --- |
| 0 | length | 12 + body.length，不包含长度字段自身四字节 |
| 4 | command | 应用命令 ID |
| 8 | seq | 应用请求/响应序号 |
| 12 | code | 应用结果码 |
| 16 | body | 原始字节；分帧 codec 不解释内容，应用使用 Fory |

[GamePacketDecoder](src/main/java/cn/managame/demo/network/GamePacketDecoder.java) 累积半包，并按顺序输出粘在一起的多个完整包。数据不完整时不输出 packet。两个 codec 的默认最大**完整帧大小**为 1 MiB，包含 16 字节头；构造器可指定其他至少为 16 的限制。length 小于 12 时抛 CorruptedFrameException；超过配置限制时，长度字段到达即抛 TooLongFrameException。EOF 时头部或 body 不完整则抛 CorruptedFrameException。demo 的 onException 关闭连接。command/seq/code 原样传输，codec 不校验其业务含义。

decoder 将 body 复制到独立堆数组，输入缓冲区通过 Netty decoder 生命周期释放。GamePacket 不使用引用计数，无需 retain/release。空 body 使用空数组；`setBody(null)` 抛 NullPointerException。setter/getter 不复制数组。`Connection.write(packet)` 返回 ACCEPTED 后，不再修改 packet 或其 body 数组；接纳仍不代表对端已收到。encoder 输出字节前校验完整帧大小。decoder 每个连接独立创建；无状态 encoder 可以共享。

入口通过 `pipeline(p -> p.addLast(new GamePacketDecoder(), new GamePacketEncoder()))` 安装两个 codec，并从 Spring 获取 GamePacketHandler。应用在写入前将已注册消息序列化到原始 body：

```java
GamePacket packet = new GamePacket();
packet.setCommand(1003);
packet.setSeq(42);
packet.setCode(0);
LoginReq login = new LoginReq();
login.setUserId(10001L);
login.setToken("application-token");
packet.setBody(fory.serialize(login));
if (connection.write(packet) != WriteStatus.ACCEPTED) connection.close();
```

[GamePacketCodecTest](src/test/java/cn/managame/demo/network/GamePacketCodecTest.java) 验证确切线字节、半包/粘包、空及二进制 body、大小限制、EOF 和输入引用释放。[GamePacketNetworkTest](src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) 使用实际 Spring GamePacketHandler、借用的 EventLoopGroup 和显式清理，在真实本地 TCP 上验证。仅标记 Handler 的测试探针在各自 HandlerMethod 中接收解码后的 LoginReq（1003）及 PingMessage（1002），验证虚拟线程、服务端连接与会话 Route/身份。login 测试探针使用仅用于测试的 token 校验：无效 token 保持无会话，校验成功在 HandlerMethod 内绑定后再发送 ROLE ping；该夹具不实现生产鉴权。协议号 1001 下的 PingMessage 会关闭连接，不再调用业务，也不自动响应。[GamePacketDispatchTest](src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) 验证建连不创建或覆盖会话、无会话 LOGIN 路由、角色身份要求在入队前拒绝、排队期间会话/解码对象值保留、Handler/会话缺失拒绝及不自动写回。Windows 上只有 TCP 测试沿用 game-network 对 JDK Selector AF_UNIX 唤醒管道的处理，生产代码不改 JVM 属性。采用业务枚举配置的十六项 Handler/身份/Fory/packet/事件相关测试通过。TCP 验证不代表 TLS/WebSocket 或生产性能认证。

在仓库根目录构建：

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) 使用内存 JDBC 桩验证无需显式占位符配置器的属性注入、组件扫描、已初始化 Data Repository 的注入及 Data 关闭。同时验证 URL 缺失时拒绝启动，以及入口持续运行直至 Context 关闭或主线程中断。[DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) 覆盖三种 Repository、只构造一次、注入回调中使用已初始化 Repository，以及拒绝 prototype 作用域。当前 demo 验证为十九项通过、一项生命周期测试失败：测试要求主线程等待及处理中断，而入口在初始化后返回。生命周期测试的同步调整尚未完成。桩测试不验证原生 MySQL；此前使用阻塞入口验证过配置的本地 MySQL 启动和关闭；本次未对当前包含 Spring/Data 的完整 TCP 入口执行真实 MySQL 启动验证。packet TCP 测试独立于 MySQL 运行。这些检查不代表所有数据库操作和故障场景均已验证。根 `clean verify` 仍被引用 `maxPendingCalls` 等已移除 API 的既有 RPC 测试挡住；demo 生产源码可编译，但当前 `clean verify` 因生命周期测试而失败。模块使用既有 [OGBS 规范](../docs/ogbs/README.zh-CN.md)，不新增框架契约；组件示例仍位于 [game-example](../game-example/README.zh-CN.md)。
