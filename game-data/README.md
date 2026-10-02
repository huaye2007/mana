# game-data

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Data Specification](../docs/ogbs/OGBS-Data-1.0.md) | [OGBS Data Java Development Specification](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

Java 25 reference implementation of OGBS Data: Single/Group caches, asynchronous batch write-behind, MySQL/JDBC, MongoDB, and append-only MySQL logs.

Caffeine 3.2.3 is supplied transitively by `game-core`; Data owns its caches and their lifecycle. Shared dependency ownership is defined in the [Core Java development specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md#1-模块与职责).

- [Semantic specification](../docs/ogbs/OGBS-Data-1.0.md)
- [Java development specification, defaults, and boundaries](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md)
- DataMemoryDemo is not in this repository; see [DataContractTest](src/test/java/cn/managame/data/DataContractTest.java) for in-memory Mapper usage.

```shell
mvn -pl game-data -am test
mvn clean verify
```

Business repositories directly extend SingleRepository<K,E> / GroupRepository<K,E> / LogRepository<E>. Register Class objects with GameDataBuilder and obtain them through GameData.repository after build. Call getGroup before modifying a Group. A single MapKey uses its field type; multiple MapKeys use String and MapKeys.of. GameData.close synchronously processes admitted writes and reports final persistence failures.

MySQL uses application-supplied DataSource/JDBC Driver; Mongo uses application-supplied MongoDatabase/CodecRegistry. The framework does not close these external resources. The Mongo driver is optional in game-data; consumers using Mongo must explicitly declare org.mongodb:mongodb-driver-sync.

<a id="实机测试"></a>

## Live database tests

Default tests need no external services and include H2 JDBC rollback and BSON encoding/decoding. Live tests are skipped by default and require explicit environment variables:

```powershell
$env:OGBS_DATA_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/ogbs_data_test'
$env:OGBS_DATA_MYSQL_USER = 'test_user'
$env:OGBS_DATA_MYSQL_PASSWORD = 'test_password'
$env:OGBS_DATA_MONGO_URI = 'mongodb://127.0.0.1:27017'
mvn -pl game-data -am test "-Dtest=DatabaseIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Use a dedicated disposable MySQL test database. The test creates and drops ogbs_data_probe and refuses to run if it already exists. Mongo creates a random ogbs_data_test_* database and drops it afterward. Do not use production databases.

The recorded root `mvn clean verify` passed: game-data's 38 local tests passed and 2 live tests were skipped according to environment conditions. H2, mapping-stub, and BSON tests passed; real MySQL/MongoDB tests were skipped because their environment variables were not configured. The fixed 100ms double-buffer grace period accepts unusually long thread-pause risk; cache TTL must be much longer than write-behind latency. See the Java specification for these constraints and partial-success retry risks.
