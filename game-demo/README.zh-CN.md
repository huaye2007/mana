# game-demo

[English](README.md) | **[简体中文](README.zh-CN.md)**

根 Maven 构建中的普通 Spring 应用，使用 JDK 25 和仓库父 POM，应用源码位于 `cn.managame.demo`。`spring-context:7.0.9` 提供依赖注入；应用层 HikariCP 与 MySQL Driver 提供连接池和 JDBC 实现。

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) 启动 Spring Context 并注册关闭钩子。[MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) 加载 `application.properties` 并创建应用持有的连接池。业务 Repository 类使用 Spring 注解：

```java
@Repository
public class UserRepository extends SingleRepository<Long, User> {
}
```

在 `src/main/resources/application.properties` 配置 `game.db.url`、`game.db.username`、`game.db.password` 后，使用 JDK 25 在 IDE 启动 `cn.managame.demo.GameDemo`。数据库需预先存在，Data 会初始化缺失的状态表/列/索引。JDBC URL 缺失或为空时，在创建连接池前报错并明确提示配置 `game.db.url`。也可通过 JVM 系统属性和环境变量向 Spring Environment 提供这些配置键。

`@PropertySource` 加载文件，Spring 默认的内嵌值解析器负责注入 `@Value`，无需显式声明 `PropertySourcesPlaceholderConfigurer` Bean。初始化成功后，入口输出 `game-demo started. Press Ctrl+C to stop.`，并返回 main 在 9000 端口启动 packet 回传 TCP 服务。Netty 持有的线程使进程持续运行。当前入口不阻塞主线程；JVM 退出时触发 Spring 关闭钩子。Context 关闭先关闭监听器及其持有的网络资源，再关闭 Data 和应用持有的连接池。不新增保活线程；目前尚未创建 HTTP 监听器。

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 收集扫描到的、继承 SingleRepository、GroupRepository 或 LogRepository 的 `@Repository` Bean，不提前实例化。它将类型注册到 GameDataBuilder，并把 Bean 的实例供应器改为 `data.repository(type)`，保留 Bean 名称、限定符及 Spring 字段/setter 注入。Data 初始化实例后才进入 Spring 注入回调或供业务使用；在扫描范围内新增 Repository 无需逐个注册类型或声明 `@Bean` 方法。Repository 必须为单例作用域，其他作用域在启动时拒绝；仍遵守 Data 直接继承具体参数化基类及无参构造的要求。实例由 GameData 构造，因此不支持构造器依赖注入。此适配位于 demo 应用层，game-data 不依赖 Spring。

User 的 JSON Map 初始化为 ConcurrentHashMap<Integer,Long>，默认加载保留具体实现及泛型类型。空 Map 默认值由 Java 初始化和全字段 INSERT 提供；`defaultValue="{}"` 不是 SQL DDL 表达式，已省略。详见 [默认 JSON 类型绑定与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)。自定义行为通过 `jsonCodec(...)` 配置。

## GamePacket TCP 分帧

[GamePacket](src/main/java/cn/managame/demo/network/GamePacket.java) 携带 `int command`、`int seq`、`int code` 和原始 `byte[] body`。编解码属于此应用，不定义 RPC 线格式，也不新增框架协议。每个整数占四字节，使用大端序：

| 偏移 | 字段 | 含义 |
| --- | --- | --- |
| 0 | length | 12 + body.length，不包含长度字段自身四字节 |
| 4 | command | 应用命令 ID |
| 8 | seq | 应用请求/响应序号 |
| 12 | code | 应用结果码 |
| 16 | body | 不解释的字节，不进行 JSON/字符串/对象序列化 |

[GamePacketDecoder](src/main/java/cn/managame/demo/network/GamePacketDecoder.java) 累积半包，并按顺序输出粘在一起的多个完整包。数据不完整时不输出 packet。两个 codec 的默认最大**完整帧大小**为 1 MiB，包含 16 字节头；构造器可指定其他至少为 16 的限制。length 小于 12 时抛 CorruptedFrameException；超过配置限制时，长度字段到达即抛 TooLongFrameException。EOF 时头部或 body 不完整则抛 CorruptedFrameException。demo 的 onException 关闭连接。command/seq/code 原样传输，codec 不校验其业务含义。

decoder 将 body 复制到独立堆数组，输入缓冲区通过 Netty decoder 生命周期释放。GamePacket 不使用引用计数，无需 retain/release。空 body 使用空数组；`setBody(null)` 抛 NullPointerException。setter/getter 不复制数组。`Connection.write(packet)` 返回 ACCEPTED 后，不再修改 packet 或其 body 数组；接纳仍不代表对端已收到。encoder 输出字节前校验完整帧大小。decoder 每个连接独立创建；无状态 encoder 可以共享。

入口通过 `pipeline(p -> p.addLast(new GamePacketDecoder(), new GamePacketEncoder()))` 安装两个 codec。onMessage 收到 GamePacket，通过 `connection.write(packet)` 回传。应用也可按同样方式发送自己的数据：

```java
GamePacket packet = new GamePacket();
packet.setCommand(1001);
packet.setSeq(42);
packet.setCode(0);
packet.setBody(new byte[]{1, 2, 3});
if (connection.write(packet) != WriteStatus.ACCEPTED) connection.close();
```

[GamePacketCodecTest](src/test/java/cn/managame/demo/network/GamePacketCodecTest.java) 验证确切线字节、半包/粘包、空及二进制 body、大小限制、EOF 和输入引用释放。[GamePacketNetworkTest](src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) 使用借用的 EventLoopGroup 和显式清理，在真实本地 TCP 上验证 GamePacket 回调及写入。Windows 上仅此测试沿用 game-network 对 JDK Selector AF_UNIX 唤醒管道的处理，生产代码不改 JVM 属性。七项 packet 测试通过；TCP 验证不代表 TLS/WebSocket 或生产性能认证。

在仓库根目录构建：

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) 使用内存 JDBC 桩验证无需显式占位符配置器的属性注入、组件扫描、已初始化 Data Repository 的注入及 Data 关闭。同时验证 URL 缺失时拒绝启动，以及入口持续运行直至 Context 关闭或主线程中断。[DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) 覆盖三种 Repository、只构造一次、注入回调中使用已初始化 Repository，以及拒绝 prototype 作用域。当前 demo 验证为十一项通过、一项生命周期测试失败：测试要求主线程等待及处理中断，而入口在初始化后返回。生命周期测试的同步调整尚未完成。桩测试不验证原生 MySQL；此前使用阻塞入口验证过配置的本地 MySQL 启动和关闭；本次未对当前包含 Spring/Data 的完整 TCP 入口执行真实 MySQL 启动验证。packet TCP 测试独立于 MySQL 运行。这些检查不代表所有数据库操作和故障场景均已验证。根 `clean verify` 仍被引用 `maxPendingCalls` 等已移除 API 的既有 RPC 测试挡住；demo 生产源码可编译，但当前 `clean verify` 因生命周期测试而失败。模块使用既有 [OGBS 规范](../docs/ogbs/README.zh-CN.md)，不新增框架契约；组件示例仍位于 [game-example](../game-example/README.zh-CN.md)。
