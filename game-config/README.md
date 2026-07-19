[English](README.en.md) | 中文

# game-config

`game-config` 是一个面向消费端的配置中心客户端。它把一个或多个配置文档按声明顺序合并为不可变快照，后声明的文档覆盖先声明的同名配置，并通过统一 API 提供类型化读取、刷新和变更监听。

当前实现四个模块：

- `game-config-core`：`ConfigCenter`、不可变 `ConfigSnapshot`、SPI 与工厂。
- `game-config-local`：本地 Properties 或 JSON 文件，使用文件系统事件热更新。
- `game-config-nacos`：Nacos Config，resource 写作 `group:dataId`；未写 group 时使用 `group` 属性或 `DEFAULT_GROUP`。
- `game-config-etcd`：每个 resource 对应一个 Etcd key，value 是 Properties 文档。

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
        .resource("config/application.json")
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

local 根据 `.json` 扩展名识别 JSON，其他文件按 Properties 解析。JSON 根节点必须是对象；嵌套对象会展开为点号键，例如 `{"game":{"server":{"port":8080}}}` 可通过 `game.server.port` 读取。数组既保留为紧凑 JSON 字符串，也展开为索引键，例如 `regions[0]`、`servers[0].host`；嵌套数组同样支持。Properties 与 JSON 可以混合声明，仍由后声明的文件覆盖同名键。

Nacos：

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

`properties` 中除本库使用的 `group`、`timeoutMillis`、`revisionKey` 外，其他参数会传给 Nacos 客户端，例如 `namespace`、`username`、`password`。

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

多个 endpoint 使用逗号分隔。`timeoutMillis` 默认 `3000`。local 可设置 `required=false` 允许文件暂时不存在。

### 多资源一致性

local 或 Nacos 声明多个 resource 时，每个文档必须携带相同的非负整数 `_revision`，例如：

```properties
_revision=42
game.server.port=8080
```

发布下一版本时依次写入带有新 revision 的全部文档；在所有文档 revision 一致前，客户端持续使用 last-known-good 快照。`_revision` 只用于发布协调，不会出现在应用快照中；可通过 `.property("revisionKey", "configRevision")` 改名。需要发布空文档时仍须保留 revision 行。

Etcd 会在同一个服务端 revision 读取全部 key。多 key 发布应使用一个 Etcd transaction，使整个变更只占用一个 revision。

## 语义

- `ConfigFactory.open` 在返回前同步完成首次加载；失败时不会返回半初始化实例。
- 初始快照和每次更新都会先经过可选 `ConfigValidator`；校验失败时保留 last-known-good 快照，并可通过 `isHealthy()` / `lastError()` 查看状态。
- 推送只在合并后的内容真正变化时生成新版本和 `ConfigChange`。
- 每个 listener 独立运行，最多保留一个执行中的通知和一个待处理通知；慢 listener 会合并中间变更并最终收到最新快照，不会阻塞其他 listener、快照发布或 provider 线程。
- provider watch 异常后会按指数退避重新建立监听并重新加载当前快照。
- 默认每 30 秒主动加载一次源，并在连续 90 秒无法成功联系源时标记为不健康。可通过 `healthCheckInterval(Duration)` 和 `staleAfter(Duration)` 调整；`Duration.ZERO` 分别关闭对应机制。
- `isHealthy()` 同时要求 watch 正常、最近加载或校验无错误且数据未过期；`lastError()` 返回最近错误或过期原因。
- 单 resource 的 Nacos 删除/空内容和 Etcd key 删除视为空文档。多 resource 的 local/Nacos 空文档仍需保留共同 revision。
- `close()` 会注销监听并关闭文件 watcher 或远程客户端。

## 测试

普通测试不需要外部服务：

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml test
```

Nacos 与 Etcd 的真实读写、监听和多资源一致性测试使用 Testcontainers，需要 Docker：

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml -Pintegration-tests verify
```
