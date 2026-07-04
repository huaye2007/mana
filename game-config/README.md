[English](README.en.md) | 中文

# game-config

`game-config` 是游戏服务框架的统一配置模块，提供本地文件、classpath、命令行、JVM、环境变量、默认值和远端配置源的组合加载能力。业务侧通过 `GameConfigManager` 获取类型安全的配置快照，并可监听配置变更、执行热加载和运行时覆盖。

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `game-config-core` | 核心 API、配置管理器、本地配置加载器、配置源抽象、远端 provider SPI |
| `game-config-local` | 基于本地文件或目录的业务配置 provider，类型为 `local`，支持手动加载或提交信号触发重新加载 |
| `game-config-nacos` | 基于 Nacos ConfigService 的远端配置实现，支持 push |
| `game-config-apollo` | 基于 Apollo Config Service 的配置实现，支持 notification 长轮询 |
| `game-config-consul` | 基于 Consul KV 的配置实现，支持 blocking query 长轮询 |
| `game-config-etcd` | 基于 jetcd 的远端配置实现，支持 watch/push |
| `game-config-starter` | 聚合 starter，默认传递引入 core；内置远端 provider 在该模块中以 optional 依赖声明，业务项目需按需显式引入 |

## 设计原则

- 业务侧只依赖 `game-config-core` 暴露的 `GameConfigManager`、`GameConfigOptions`、`ConfigSource` 和监听器 API。
- 配置源按添加顺序决定优先级，索引越小优先级越高。
- 运行时覆盖层优先级最高，高于所有 `ConfigSource`。
- 远端实现通过 Java SPI 挂载，新增远端 provider 不需要修改 core。
- 配置快照不可变，变更通过 `reloadNow()`、热加载轮询、远端 push 或动态覆盖 API 生效。

## 快速使用

### 1. 引入依赖

只使用本地配置和通用 API：

```xml
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-config-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

使用 starter 获取 core 入口：

```xml
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-config-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

provider 实现依赖是 optional，不会通过 starter 自动传递到业务项目。接入本地或远端配置后端时，需要按需显式引入对应模块：

```xml
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-config-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.github.huaye2007.mana</groupId>
    <artifactId>game-config-local</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`GameConfigStarter` 默认不会把全部 JVM 系统属性或环境变量加入配置快照，避免 `snapshot()` 暴露运行时敏感信息或引入非业务 key。确实需要兼容这类来源时显式开启：

```java
GameConfigManager manager = GameConfigStarter.builder()
        .systemProperties(true)
        .environmentVariables(true)
        .start();
```

starter 默认优先级为：

```text
运行时覆盖层 > 命令行 > 显式 JVM > 显式环境变量 > 远端配置 > 本地文件 > 本地目录 > classpath > 默认值
```

### 2. 创建配置管理器

```java
GameConfigOptions options = new GameConfigOptions();
options.setHotReloadEnabled(true);
options.setRefreshIntervalMillis(3000);

options.addSource(new CommandLineConfigSource(args));
options.addSource(new JvmConfigSource(true));
options.addSource(new LocalFileConfigSource("config/application.properties"));
options.addSource(new EnvironmentConfigSource());
options.addSource(new DefaultConfigSource(Map.of(
        "server.port", "8080",
        "feature.match.enabled", "false"
)));

GameConfigManager manager = GameConfigFactory.create(options);
manager.start();
```

上例优先级为：

```text
运行时覆盖层 > 命令行 > JVM > 本地文件 > 环境变量 > 默认值
```

### 3. 读取配置

```java
String host = manager.get("server.host", "127.0.0.1");
int port = manager.getInt("server.port", 8080);
long timeout = manager.getLong("rpc.timeoutMillis", 3000L);
double rate = manager.getDouble("drop.rate", 0.0D);
boolean enabled = manager.getBoolean("feature.match.enabled", false);

Map<String, String> snapshot = manager.snapshot();
```

类型转换失败或 key 不存在时会返回传入的默认值。`getBoolean` 将 `true`/`1` 识别为 `true`，`false`/`0` 识别为 `false`（忽略大小写）；其他无法识别的值与解析失败一样返回传入的默认值，而不是 `false`。

### 4. 监听配置变更

```java
manager.addChangeListener(event -> {
    for (String key : event.getChangedKeys()) {
        System.out.println(key + " " + event.getChangeType(key));
    }
});
```

变更类型包括：

| 类型 | 说明 |
| --- | --- |
| `ADDED` | 新增 key |
| `UPDATED` | 已有 key 的值变化 |
| `DELETED` | key 被删除 |

### 5. 动态覆盖配置

```java
manager.put("feature.match.enabled", "true");
manager.putAll(Map.of("server.port", "9000", "room.maxPlayers", "100"));

manager.update("room.maxPlayers", oldValue -> String.valueOf(Integer.parseInt(oldValue) + 10));
manager.removeOverride("server.port");
manager.clearOverrides();
```

动态覆盖层适合 GM 指令、灰度开关、运行时实验参数等场景。覆盖层中的值会立即生效并触发一次变更通知。

### 6. 配置校验

```java
options.addValidator(config -> {
    int port = Integer.parseInt(config.getOrDefault("server.port", "8080"));
    if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("server.port out of range");
    }
});
```

校验器在配置快照生效前执行，抛出异常时本次变更不会生效。

## 本地配置格式

### properties

```properties
server.host=127.0.0.1
server.port=8080
feature.match.enabled=true
```

### JSON

```json
{
  "server": {
    "host": "127.0.0.1",
    "port": 8080
  },
  "feature": {
    "match": {
      "enabled": true
    }
  }
}
```

JSON 会被扁平化为点号分隔的 key：

```text
server.host=127.0.0.1
server.port=8080
feature.match.enabled=true
```

数组使用下标作为 key，例如 `servers.0.host`。

### conf/cfg/ini

```ini
[server]
host = 127.0.0.1
port = 8080

[feature.match]
enabled = true
```

section 会作为 key 前缀，例如 `server.port=8080`。支持 `=` 和 `:` 分隔符，忽略空行、`#` 注释和 `//` 注释。

### 目录加载

```java
options.addSource(new LocalDirectoryConfigSource("config/conf.d"));
options.addSource(new LocalDirectoryConfigSource("config/conf.d", true));
```

目录源会加载目录下所有普通文件，按相对路径字典序合并，后加载文件覆盖先加载文件的同名 key。默认不递归；第二个构造参数为 `true` 时递归加载子目录。目录不存在、路径为空或空白时返回空配置；路径存在但不是目录时抛出 `ConfigOperationException`。
需要限制文件类型时，可使用 `LocalDirectoryConfigSource(String directoryPath, boolean recursive, Set<String> allowedExtensions)`，例如只加载 `properties/json/conf`。

## 远端配置

### 通过 SPI 创建 provider

```java
RemoteConfigProvider provider = RemoteConfigProviderFactory.create("local");

Properties local = new Properties();
local.setProperty("directory", "config/conf.d");
local.setProperty("recursive", "true");
local.setProperty("watchMode", "manual");

options.addSource(new RemoteConfigSource(provider, local));
```

本地 provider 类型为 `local`，也支持别名 `file` 和 `directory`。参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `file` | 无 | 单个本地配置文件 |
| `files` | 无 | 多个本地配置文件，逗号分隔，按声明顺序合并 |
| `directory` | 无 | 本地配置目录，按相对路径字典序加载普通文件 |
| `path` | 无 | 自动判断文件或目录 |
| `recursive` | `false` | 目录加载时是否递归子目录，支持 `true` 或 `1` |
| `extensions` | 无 | 目录加载时允许的扩展名，逗号分隔；不配置时加载所有普通文件 |
| `failIfMissing` | `false` | 文件、目录或 path 不存在时是否抛出 `ConfigOperationException` |
| `watchMode` | `manual` | 本地变更触发模式：`manual` 或 `signal` |
| `signalFile` / `reloadSignalFile` / `triggerFile` | 无 | `watchMode=signal` 时监听的提交完成标记文件；该文件变化后才整包 reload |
| `watchDebounceMillis` | `50` | `signal` 模式下触发 reload 前的防抖等待时间 |

`file/files/directory/path` 至少需要配置一个；同名 key 按加载顺序后者覆盖前者。
本地 provider 默认不监听配置文件变化，适合在外部发布系统完成整包同步后由业务收到信号并调用 `GameConfigManager#reloadNow()`。
如果希望由文件系统信号自动触发，推荐使用 `watchMode=signal`，让发布系统在所有文件同步完成后再更新一个标记文件：

```java
local.setProperty("directory", "config/conf.d");
local.setProperty("watchMode", "signal");
local.setProperty("signalFile", "config/conf.d/.ready");
```

不支持逐文件 watcher：任意配置文件变化都不会直接触发 reload，避免读取到发布过程中的半同步快照。启用 `GameConfigOptions#hotReloadEnabled` 也会周期性读取本地文件；只想通过外部信号加载时应关闭热加载。

### Nacos

```java
RemoteConfigProvider provider = RemoteConfigProviderFactory.create("nacos");

Properties remote = new Properties();
remote.setProperty("serverAddr", "127.0.0.1:8848");
remote.setProperty("dataIds", "DEFAULT_GROUP:game-common,game-room");

options.addSource(new RemoteConfigSource(provider, remote));
```

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `serverAddr` | 无 | Nacos 服务地址，传给 Nacos client |
| `dataIds` | 无 | 多配置声明，格式为 `GROUP:dataId,GROUP:dataId,dataId` |
| `defaultGroup` | `DEFAULT_GROUP` | `dataIds` 中省略 group 时使用 |
| `dataId` | 无 | 单配置兼容写法 |
| `group` | `DEFAULT_GROUP` | 单配置兼容写法的 group |
| `timeoutMs` | `3000` | 拉取超时时间 |

Nacos 支持 push 模式，`GameConfigManager.start()` 会自动订阅变更。
`dataIds` 和 `dataId` 都为空或空白时会抛出 `ConfigOperationException`。

### Etcd

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoints` | `http://localhost:2379` | etcd endpoint，多个用逗号分隔 |
| `dataIds` | 无 | 多 key 声明，多个用逗号分隔 |
| `dataId` | 无 | 单 key 兼容写法 |
| `timeoutMs` | `3000` | 拉取超时时间 |

Etcd 支持 watch/push 模式，value 内容按 properties 文本解析。
`dataIds` 和 `dataId` 都为空或空白时会抛出 `ConfigOperationException`。

### Apollo

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `configServiceUrl` / `serverAddr` | `http://localhost:8080` | Apollo Config Service 地址 |
| `appId` | 无 | Apollo 应用 ID |
| `cluster` / `clusterName` | `default` | Apollo cluster |
| `namespaces` / `namespace` / `namespaceName` / `dataIds` / `dataId` | `application` | namespace，多个用逗号分隔 |
| `ip` | 无 | 透传给 Apollo config API 的客户端 IP |
| `label` | 无 | 透传给 Apollo config API 的 label |
| `accessKeySecret` / `apolloAccessKeySecret` / `secret` | 无 | Apollo access key secret；配置后自动生成 `Authorization` 和 `Timestamp` 请求头 |
| `accessKeySecretEnv` / `secretEnv` | 无 | 从环境变量读取 Apollo access key secret |
| `timeoutMs` | `3000` | 配置拉取超时时间 |
| `connectTimeoutMs` | `3000` | HTTP 连接超时时间 |
| `longPollTimeoutMs` | `60000` | notification 长轮询请求超时时间 |
| `retryDelayMs` | `1000` | 长轮询异常后的重试等待时间 |

Apollo provider 类型为 `apollo`。配置拉取使用 `/configs/{appId}/{cluster}/{namespace}`，响应优先读取 `configurations` 字段；非 JSON 响应会按 properties 文本解析。订阅使用 `/notifications/v2` 长轮询，复用 HTTP client，并在 namespace 变更后只重拉变更的 namespace，再按声明顺序合并完整快照。Apollo 返回空 `configurations` 时，该 namespace 对应的 key 会从最终快照中删除；拉取异常时仍由 `GameConfigManager` 的 last-good 机制兜底。

```java
Properties apollo = new Properties();
apollo.setProperty("configServiceUrl", "http://apollo-config:8080");
apollo.setProperty("appId", "game-room");
apollo.setProperty("cluster", "default");
apollo.setProperty("namespaces", "application,game-room");
apollo.setProperty("accessKeySecretEnv", "APOLLO_ACCESS_KEY_SECRET");

GameConfigManager manager = GameConfigStarter.builder()
        .remote("apollo", apollo)
        .hotReload(true)
        .start();
```

### Consul

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `endpoint` / `address` | `http://127.0.0.1:8500` | Consul HTTP API 地址 |
| `key` / `keys` / `dataId` / `dataIds` | 无 | KV key，多个用逗号分隔 |
| `prefix` | 无 | KV prefix；设置后使用单次 `recurse` 请求加载整个前缀 |
| `token` / `aclToken` | 无 | Consul ACL token |
| `dc` / `datacenter` | 无 | Consul datacenter |
| `timeoutMs` | `3000` | 普通请求超时时间 |
| `connectTimeoutMs` | `3000` | HTTP 连接超时时间 |
| `waitSeconds` | `55` | blocking query 的 `wait` 参数 |
| `retryDelayMs` | `1000` | blocking query 异常后的重试等待时间 |
| `watchThreads` | `16` | 多 key blocking query 后台线程上限；超过上限会启动失败 |

Consul provider 类型为 `consul`。单 key 模式读取 `/v1/kv/{key}?raw`，prefix 模式读取 `/v1/kv/{prefix}?recurse` 并解析 Consul 返回的 Base64 value。订阅使用 `X-Consul-Index` blocking query，不做固定间隔轮询；value 内容按 properties 文本解析。大量 key 建议放在同一个 prefix 下，用 `prefix` 模式把 watch 压缩成一个 blocking query。Consul 返回 404 或 prefix 下没有 value 时，对应 source 快照会变为空，从最终配置中删除这些 key；网络/解析异常不会删除 last-good 快照。

```java
Properties consul = new Properties();
consul.setProperty("endpoint", "http://consul-agent:8500");
consul.setProperty("prefix", "game/config/room");
consul.setProperty("token", System.getenv("CONSUL_HTTP_TOKEN"));
consul.setProperty("waitSeconds", "55");

GameConfigManager manager = GameConfigStarter.builder()
        .remote("consul", consul)
        .hotReload(true)
        .start();
```

## 扩展本地 loader

新增本地格式时实现 `LocalConfigLoader`：

```java
public class YamlLocalConfigLoader implements LocalConfigLoader {
    @Override
    public boolean supports(String extension) {
        return "yaml".equals(extension) || "yml".equals(extension);
    }

    @Override
    public void init(String filePath) {
        // 保存路径
    }

    @Override
    public Map<String, String> load() {
        // 返回扁平化 key-value
    }
}
```

并在资源文件中注册：

```text
META-INF/services/com.github.huaye2007.mana.config.loader.LocalConfigLoader
```

## 扩展远端 provider

新增远端配置中心时实现 `RemoteConfigProvider`：

```java
public class MyRemoteConfigProvider implements RemoteConfigProvider {
    @Override
    public String type() {
        return "my-config";
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) {
        return Map.of();
    }
}
```

并在资源文件中注册：

```text
META-INF/services/com.github.huaye2007.mana.config.spi.RemoteConfigProvider
```

需要主动推送能力时，重写 `supportsPush()` 和 `subscribe(...)`。

## 生产注意事项

- 明确配置源优先级，避免把环境变量放在过高优先级导致覆盖本地或远端配置。
- 对关键配置添加 `ConfigValidator`，防止热加载把非法快照应用到运行中服务。
- 远端配置源建议配置 `errorHandler`，用于接入告警或降级日志。
- push 源在 `hotReloadEnabled=true` 时仍会周期性主动回源探活以发现断连。push 到达会立即生效，与轮询无关；为避免 push 与轮询叠加成高频重复拉取，`GameConfigStarter` 默认把 push 源的探活回源间隔（`pushLivenessRefreshMillis`）设为 60s，断连探测因此最长延迟一个该间隔。手动组装 source 时可用 `new RemoteConfigSource(provider, props, pushLivenessRefreshMillis)` 控制，设为 `0` 恢复每次 reload 都回源的旧行为。
- `failFast=true` 适合启动期必须拿到配置的服务；`failFast=false` 适合允许缺省值兜底的服务。
- 动态覆盖层只存在于当前进程内，进程重启后不会自动恢复。
- 使用完毕或服务停机时调用 `manager.close()`，释放远端订阅、watcher 和内部线程。
- 当前源码中的部分 Java 注释存在中文编码显示异常，不影响 API 行为；后续可以单独清理注释编码。

## 测试

在 `game-config` 目录运行：

```bash
mvn "-Dmaven.repo.local=..\\.m2" test
```

当前测试覆盖 core 的配置源优先级、classpath/local/remote 加载、失败降级、fail-fast、JSON 解析失败、轮询热加载，以及 starter 的内置 provider SPI 发现。

说明：`game-config-starter` 的测试能发现内置 provider，是因为 starter 模块自身声明了这些 optional 依赖；业务项目依赖 starter 时仍需显式添加所需 provider 模块。

CI 使用 Java 25 编译和运行 `game-config` 测试，保持与根 `pom.xml` 的 `maven.compiler.release=25` 一致。

可选集成测试默认跳过，需要本地服务和显式开关：

| 开关 | 目标 | 常用参数 |
| --- | --- | --- |
| `GAME_CONFIG_INTEGRATION_APOLLO=true` 或 `-Dgame.config.integration.apollo=true` | Apollo Config Service | `GAME_CONFIG_APOLLO_ENDPOINT`、`GAME_CONFIG_APOLLO_APP_ID`、`GAME_CONFIG_APOLLO_NAMESPACE`、`GAME_CONFIG_APOLLO_ACCESS_KEY_SECRET`、`GAME_CONFIG_APOLLO_EXPECTED_KEY` |
| `GAME_CONFIG_INTEGRATION_APOLLO_PUSH=true` 或 `-Dgame.config.integration.apollo.push=true` | Apollo Config Service + Portal OpenAPI push 验收 | `GAME_CONFIG_APOLLO_PORTAL_URL`、`GAME_CONFIG_APOLLO_OPENAPI_TOKEN`、`GAME_CONFIG_APOLLO_ENV`、`GAME_CONFIG_APOLLO_OPERATOR`、`GAME_CONFIG_APOLLO_PUSH_KEY` |
| `GAME_CONFIG_INTEGRATION_CONSUL=true` 或 `-Dgame.config.integration.consul=true` | Consul Agent | `GAME_CONFIG_CONSUL_ENDPOINT`、`GAME_CONFIG_CONSUL_TOKEN`、`GAME_CONFIG_CONSUL_KEY` |
| `GAME_CONFIG_INTEGRATION_NACOS=true` 或 `-Dgame.config.integration.nacos=true` | Nacos Config Service | `GAME_CONFIG_NACOS_SERVER_ADDR`、`GAME_CONFIG_NACOS_GROUP`、`GAME_CONFIG_NACOS_DATA_ID`、`GAME_CONFIG_NACOS_NAMESPACE`、`GAME_CONFIG_NACOS_USERNAME`、`GAME_CONFIG_NACOS_PASSWORD` |
| `GAME_CONFIG_INTEGRATION_ETCD=true` 或 `-Dgame.config.integration.etcd=true` | Etcd | `GAME_CONFIG_ETCD_ENDPOINTS`、`GAME_CONFIG_ETCD_DATA_ID` |
