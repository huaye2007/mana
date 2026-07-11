[English](README.en.md) | 中文

# game-config

`game-config` 是一个面向消费端的配置中心客户端。它把一个或多个 Java Properties 文档按声明顺序合并为不可变快照，后声明的文档覆盖先声明的同名配置，并通过统一 API 提供类型化读取、刷新和变更监听。

当前实现四个模块：

- `game-config-core`：`ConfigCenter`、不可变 `ConfigSnapshot`、SPI 与工厂。
- `game-config-local`：本地 Properties 文件，使用文件系统事件热更新。
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
        .resource("config/application.properties")
        .build())) {
    int port = config.snapshot().getInt("game.server.port", 8080);
    AutoCloseable listener = config.listen(change ->
            System.out.println(change.changedKeys()));
}
```

Nacos：

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

`properties` 中除 `group`、`timeoutMillis` 外的 Nacos 客户端参数也会传给 Nacos，例如 `namespace`、`username`、`password`。

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

## 语义

- `ConfigFactory.open` 在返回前同步完成首次加载；失败时不会返回半初始化实例。
- 推送只在合并后的内容真正变化时生成新版本和 `ConfigChange`。
- listener 异常彼此隔离，不会阻断快照发布。
- Nacos 的删除/空内容、Etcd key 删除都视为空文档；其他 resource 的内容仍保留。
- `close()` 会注销监听并关闭文件 watcher 或远程客户端。
