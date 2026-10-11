# game-data

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Data Specification](../docs/ogbs/OGBS-Data-1.0.zh-CN.md) | [OGBS Data Java Development Specification](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

OGBS Data 的 Java 25 参考实现：Single/Group 缓存、异步批量写回、MySQL/JDBC、MongoDB，以及 MySQL 追加日志。

Caffeine 3.2.3 通过 `game-core` 传递引入；Data 负责自身缓存及其生命周期。共享依赖的归属见 [Core Java 开发规范](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md#1-模块与职责)。

- [语义规范](../docs/ogbs/OGBS-Data-1.0.zh-CN.md)
- [Java 开发规范、默认配置与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md)
- DataMemoryDemo 尚未落入当前仓库；内存 Mapper 用法见 [DataContractTest](src/test/java/cn/managame/data/DataContractTest.java)。

```shell
mvn -pl game-data -am test
mvn clean verify
```

业务 Repository 直接继承 SingleRepository<K,E> / GroupRepository<K,E> / LogRepository<E>。使用 GameDataBuilder 注册 Class，build 后通过 GameData.repository 获取。Group 修改前先 getGroup；单 MapKey 使用字段类型，多 MapKey 使用 String 与 MapKeys.of。关闭 GameData 同步处理已接纳写入，并报告最终保存失败。

MySQL 可通过 `GameDataBuilder.builder().mysql(dataSource).repositories(PlayerRepository.class).build()` 接收应用 DataSource，内部装配 Access 和 Mapper。JDBC Driver 和连接池配置仍由应用负责。JSON 列默认按字段完整类型及非 null 状态初始化实现绑定，例如 ConcurrentHashMap<Integer,Long>，不要求手动注册类型。需要覆盖时使用 `jsonCodec(...)`，也可保留显式 Mapper 构造以接入自定义 backend。详见 [JSON 类型绑定与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding) 和 [MysqlBuilderTest](src/test/java/cn/managame/data/MysqlBuilderTest.java)。

MySQL 使用应用提供的 DataSource/JDBC Driver；Mongo 使用应用提供的 MongoDatabase/CodecRegistry。框架不会关闭这些外部资源。Mongo driver 在 game-data 中为 optional，消费方使用 Mongo 时需显式声明 org.mongodb:mongodb-driver-sync。

## 实机测试

默认测试不需要外部服务，包含 H2 JDBC 回滚和 BSON 编解码。实机测试默认跳过，必须显式提供环境变量：

```powershell
$env:OGBS_DATA_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/ogbs_data_test'
$env:OGBS_DATA_MYSQL_USER = 'test_user'
$env:OGBS_DATA_MYSQL_PASSWORD = 'test_password'
$env:OGBS_DATA_MONGO_URI = 'mongodb://127.0.0.1:27017'
mvn -pl game-data -am test "-Dtest=DatabaseIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false
```

MySQL 使用专门的可丢弃测试数据库：测试创建并删除 ogbs_data_probe 表，检测到已有同名表会拒绝运行。Mongo 创建随机 ogbs_data_test_* 数据库并在结束后删除。不要指向生产库。

2026-10-11 写回改为"认领 + 失败分类"后，game-data 的 51 项本地测试通过、2 项实机测试按环境条件跳过（该轮在 JDK 21 预览模式下逐模块编译运行，未经 Maven/JDK 25；以 `mvn clean verify` 结果为准）。H2、映射桩和 BSON 测试通过；真实 MySQL/MongoDB 测试需配置环境变量。可重试的保存失败会保留到下一轮，不可重试的坏行被拆出单独丢弃并报告，缓存未命中先读未保存变更；MySQL 驱动须保持 `useAffectedRows=false`。机制与边界见 Java 开发规范第 5 节。

GameData.flush() 在保持开放的情况下处理两个缓冲区；稳定屏障需先暂停写入，历史最终失败仍抛 DataSaveException。GameData.stats() 提供待处理/日志/缓存/失败近似计数。直接修改实体仍需 update，不新增自动脏检测或快照。可选 [game-spring](../game-spring/README.zh-CN.md) 使用应用 DataSource 初始化 @Repository Bean。见 [Java flush 契约](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#11-显式-flush-与-datastats)。
