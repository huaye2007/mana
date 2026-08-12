[English](README.md) | 中文

# game-config

`game-config` 是一个面向消费端的配置中心客户端。它把一个或多个配置文档按声明顺序合并为不可变快照，后声明的文档覆盖先声明的同名配置，并通过统一 API 提供类型化读取、刷新和变更监听。

当前实现四个模块：

- `game-config-core`：`ConfigCenter`、不可变 `ConfigSnapshot`、SPI、工厂，以及 `properties`/`yaml`/`json` 三种文档格式。
- `game-config-local`：本地文件，使用文件系统事件热更新。
- `game-config-nacos`：Nacos Config，resource 写作 `group:dataId`；未写 group 时使用 `group` 属性或 `DEFAULT_GROUP`。
- `game-config-etcd`：每个 resource 对应一个 Etcd key。

## 使用

应用编译依赖 `game-config-core`，并把需要的后端模块放入运行时 classpath：

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-core</artifactId>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-local</artifactId>
    <scope>runtime</scope>
</dependency>
```

```java
try (ConfigCenter config = ConfigFactory.open(ConfigOptions.builder("local")
        .resource("config/base.properties")
        .resource("config/application.yml")
        .require("game.db.password")
        .validator(candidate -> {
            if (candidate.getInt("game.server.port", 0) <= 0) {
                throw new IllegalArgumentException("game.server.port must be positive");
            }
        })
        .build())) {
    int port = config.snapshot().getInt("game.server.port", 8080);
    AutoCloseable listener = config.listen(change ->
            System.out.println(change.changedKeys()));
}
```

一个配置中心对应一个后端。换后端就是改 `type`、`endpoint` 和 `resource`，其他都不动。当文件、`-D`、环境变量都可能提供某个值时，该听谁的由应用自己决定，这个库不接管这件事。

`require(keys...)`：键缺失或为空时启动即失败，后续把它改空的推送也会被拒绝。

Nacos：

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

`properties` 中除本库使用的 `group`、`timeoutMillis`、`format` 外，其他参数会传给 Nacos 客户端，例如 `namespace`、`username`、`password`。

Etcd：

```java
ConfigOptions.builder("etcd")
        .endpoint("http://127.0.0.1:2379")
        .resource("/game/config/base")
        .resource("/game/config/application")
        .property("username", "game")
        .property("password", "secret")
        .build();
```

多个 endpoint 使用逗号分隔。`timeoutMillis` 默认 `3000`。local 可设置 `required=false` 允许文件暂时不存在，`debounceMillis`（默认 `100`）是合并一次保存产生的一串事件所用的静默窗口。

### 文档格式

内置 `properties`、`yaml`、`json` 三种。resource 的格式由名字决定——`.yml`/`.yaml` 按 YAML、`.json` 按 JSON、其余按 Properties——也可以用 `property("format", "yaml")` 固定。不同格式的 resource 可以混合声明，仍由后声明的覆盖同名键。

结构化文档的根节点必须是对象，且 YAML 与 JSON 的展开结果完全一致——两者走同一套展开逻辑，所以互相切换不算代码改动。嵌套对象展开为点号键，下面这份文档通过 `game.server.port` 读取：

```yaml
game:
  server:
    port: 8080
regions: [cn, us]
```

列表既保留为基础键上的紧凑文本，也展开为索引键，例如 `regions[0]`、`servers[0].host`，`ConfigSnapshot.getList("regions")` 可以把它们读回成列表。

YAML 使用 SnakeYAML 的 `SafeConstructor` 解析，只识别标准的标量与集合标签。文档里无法指定要实例化的 Java 类，因此一个配置文件——包括从远端后端拉下来的——不会把一次加载变成任意对象构造。

需要读取内置之外的文档类型，实现 `ConfigFormat` 并用 `ServiceLoader` 注册即可，任何 provider 都不需要改动，新格式对所有后端同时生效：

```java
public final class TomlConfigFormat implements ConfigFormat {
    @Override public String name() { return "toml"; }
    @Override public boolean claims(String resource) { return resource.endsWith(".toml"); }
    @Override public Map<String, String> parse(String content) { /* 展开成点号键 */ }
}
```

```
src/main/resources/META-INF/services/cn.managame.config.spi.ConfigFormat
    com.example.TomlConfigFormat
```

固定一个 classpath 上不存在的格式会在启动时失败，并列出当前可用的格式。

### 给依赖配置的代码写测试

`ConfigFactory.open(ConfigSource)` 直接接收一个 source，`MemoryConfigSource` 负责在测试里驱动它——不需要文件、容器或 SPI 注册：

```java
MemoryConfigSource source = new MemoryConfigSource(Map.of("game.server.port", "8080"));
try (ConfigCenter config = ConfigFactory.open(source)) {
    source.emit(Map.of("game.server.port", "9090"));
}
```

### 多资源一致性

local 与 Nacos 不提供源 revision。声明多个 resource 时，每次加载会按声明顺序读取并合并当时的内容；跨 resource 的更新不是原子的，可能短暂产生混合新旧内容的快照。需要原子发布时，应把相关配置放在同一个文档中，或使用提供原生一致性保证的后端。

Etcd 在一次 transaction 中读取全部 key，因此所有 key 落在同一个 revision 上，且无论声明多少 resource，一次加载都只有一个 RTT。多 key 发布应使用一个 Etcd transaction，使整个变更只占用一个 revision。

## 语义

- `ConfigFactory.open` 在返回前同步完成首次加载，失败时不会返回半初始化实例。watch 在这次加载之前就已注册，因此启动期间下发的更新不会丢失，启动也只需要读一次而不是两次。
- 初始快照和每次更新都会先经过可选 `ConfigValidator`；校验失败时保留 last-known-good 快照，并可通过 `isHealthy()` / `lastError()` 查看状态。
- 推送只在合并后的内容真正变化时生成新版本和 `ConfigChange`。
- 每个 listener 独立运行，最多保留一个执行中的通知和一个待处理通知；慢 listener 会合并中间变更并最终收到最新快照，不会阻塞其他 listener、快照发布或 provider 线程。
- `listen(listener, true)` 会把当前快照作为第一个事件投递，且与注册在同一把锁内完成，消除了「先 `snapshot()` 再订阅」之间丢事件的窗口。首个事件的 `previous` 为空快照，因此 `changedKeys()` 列出全部键。
- provider watch 异常后会按指数退避重新建立监听并重新加载当前快照，且跑在独立线程上，慢健康检查不会拖住恢复。
- 存活探测与新鲜度是两件事：每 30 秒调用一次 `ping()`，不传输任何文档；每 5 分钟整体重新加载一次，作为漏掉推送的安全网。连续 90 秒无法成功联系源即标记为不健康。可通过 `healthCheckInterval(Duration)`、`refreshInterval(Duration)` 和 `staleAfter(Duration)` 调整；`Duration.ZERO` 分别关闭对应机制。
- `isHealthy()` 同时要求 watch 正常、最近加载/探测/校验均无错误且数据未过期；`lastError()` 返回最近错误或过期原因。
- Nacos 删除/空内容和 Etcd key 删除视为空文档；local 中设置 `required=false` 的缺失文件也视为空文档。
- 键缺失以及类型化读取解析失败都抛 `ConfigException`，并在消息中带上键名。
- 同名键按声明顺序决定：最后一个定义该键的 resource 生效。
- `close()` 会注销监听并关闭文件 watcher 或远程客户端；`snapshot()` 在关闭后仍可读取，关停代码不必围绕配置中心排先后。

## 测试

普通测试不需要外部服务：

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml test
```

Nacos 与 Etcd 的真实读写、监听和多资源一致性测试使用 Testcontainers，需要 Docker：

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml -Pintegration-tests verify
```
