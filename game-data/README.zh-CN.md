# game-data

[English](README.md) | 简体中文

面向游戏服务器的 Java 实体加载、Caffeine 缓存与异步批量持久化框架。默认入口为英文 README，中文版与英文版说明相同的 API 和行为。

## 环境与目录

game-data 独立构建的目标版本为 Java 21，需要 JDK 21 或更高版本及 Maven。仓库其他项目可能要求更高版本的 JDK。运行数据库示例需要准备数据库；回归测试使用受控的内存 DataAccess 实现。

game-data 只保留一个 Maven 模块、一个 JAR。核心、JDBC、MongoDB、JSON 与可选 Spring 支持通过 Java 包组织，不再拆分子 Maven 模块。测试与可运行示例放在测试源码目录，不打入库 JAR。

```text
game-data/
  pom.xml
  README.md
  README.zh-CN.md
  src/main/java/cn/managame/
    annotation/
    core/
      GameData.java
      DataException.java
      RepositoryDefinition.java
      access/
      cache/
      key/
      log/
      mapping/
      metadata/
      repository/
      write/
    rdb/
    docdb/mongodb/
    codec/jackson/
    spring/
  src/test/java/cn/managame/
    core/mapping/
    core/repository/
    core/write/
    support/
    docdb/mongodb/
    example/
```

| 功能 | Java 包 | 职责 |
| --- | --- | --- |
| 核心入口 | `cn.managame.core` | GameData 生命周期、公共异常及包内工厂解析 |
| 注解 | `cn.managame.annotation` | 实体、字段、键与索引声明 |
| access | `cn.managame.core.access` | 数据库访问接口与查询条件 |
| cache | `cn.managame.core.cache` | 实体缓存与常驻模式 |
| key | `cn.managame.core.key` | 复合 GroupKey 与 MapKey |
| log | `cn.managame.core.log` | 业务日志 Repository 与时间字段分表 |
| mapping | `cn.managame.core.mapping` | 实体及字段转换、JSON Codec 接口 |
| metadata | `cn.managame.core.metadata` | 实体及字段元数据、索引与存储类型 |
| repository | `cn.managame.core.repository` | Single/Group Repository 与缓存 |
| write | `cn.managame.core.write` | 写队列、批量保存、结果、失败处理及指标 |
| JDBC | `cn.managame.rdb`, `cn.managame.rdb.dialect` | MySQL/MariaDB/PostgreSQL 方言及 Schema 管理 |
| MongoDB | `cn.managame.docdb.mongodb` | MongoDB 访问 |
| JSON | `cn.managame.codec.jackson` | Jackson JSON Codec |
| Spring | `cn.managame.spring` | 可选的 Spring Repository 扫描和 Bean 注册 |
| 示例（测试源码） | `cn.managame.example` | MySQL 和 MongoDB 入口 |

Java 包仍位于 `cn.managame`，统一构件坐标为 `cn.managame:game-data:0.1.0-SNAPSHOT`。其他项目使用该快照前，可先安装到本地仓库：

```sh
mvn -f game-data/pom.xml install
```

使用方只需引入一个 game-data 依赖：

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-data</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

RDB 和 DocDB 都是可选后端。单 JAR 包含两套实现，但 GameData 只使用应用显式传入的 DataAccess 实例。可以只用 RDB、只用 DocDB、通过命名 DataAccess 同时使用两者，或者使用自定义 DataAccess 而不启用内置后端。RDB 基于 JDK 自带的 JDBC API，只需添加所选数据库驱动，不依赖 MongoDB；DocDB 不需要 JDBC 驱动。

Caffeine 会作为传递依赖引入。MongoDB、Jackson 和 Spring 为 Maven 可选依赖：适配器类在 JAR 内，使用方仅在使用对应类时显式添加相应依赖。JDBC 使用方添加所选数据库驱动。这里的 MySQL Connector/J 为测试依赖，便于在测试 classpath 下运行示例。

| 功能 | 使用方额外添加的依赖 |
| --- | --- |
| JDBC/MySQL | `com.mysql:mysql-connector-j:9.7.0`（runtime） |
| MongoDB | `org.mongodb:mongodb-driver-sync:5.6.5` |
| Jackson JSON Codec | `com.fasterxml.jackson.core:jackson-databind:2.22.2` |
| Spring 集成 | `org.springframework:spring-context`（由应用管理版本） |

## 快速开始

先定义具有主键和无参构造器的实体，再定义业务 Repository 接口。下面每个 public 类型分别放在独立 Java 文件中。

```java
import cn.managame.annotation.Id;
import cn.managame.annotation.Table;

@Table("player")
public class Player {
    @Id
    private long id;
    private int level;

    public Player() { }
    public Player(long id, int level) { this.id = id; this.level = level; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
}
```

```java
import cn.managame.core.repository.SingleRepository;

public interface PlayerRepository extends SingleRepository<Player, Long> {
    default boolean exists(long id) { return get(id).isPresent(); }
}
```

准备好 MySQL 并将 JDBC 驱动加入 classpath 后，初始化 GameData，通过业务接口获取 Repository。插入示例请使用尚未存在的 ID，下面的连接凭据为占位值。

```java
import cn.managame.core.GameData;
import cn.managame.rdb.DriverManagerDataSource;
import cn.managame.rdb.RdbDataAccess;
import cn.managame.rdb.dialect.MySqlDialect;

public class QuickStart {
    public static void main(String[] args) {
        var source = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3306/game", "game", "change-me");
        try (var gameData = new GameData(new RdbDataAccess(source, new MySqlDialect()))) {
            PlayerRepository players = gameData.repository(PlayerRepository.class);
            Player player = new Player(10001L, 1);
            players.insert(player);
            player.setLevel(2);
            players.update(player);
            gameData.flush();
        }
    }
}
```

`insert()`、`update()` 更新缓存并提交异步保存。`flush()` 等待 pending 工作处理结束，不表示所有 batch 都保存成功；失败需通过日志和指标观察。可运行示例见 [MySQL](src/test/java/cn/managame/example/MysqlExample.java) 和 [MongoDB](src/test/java/cn/managame/example/MongoExample.java)。

## 业务 Repository 接口

两种模式使用统一入口：

- `SingleRepository<Entity, ID>`：按主键加载单个实体。
- `GroupRepository<Entity, ID>`：加载并管理完整分组。

`gameData.repository(MyRepository.class)` 使用默认数据源，`gameData.repository("game", MyRepository.class)` 使用命名数据源。工厂从泛型接口继承关系解析实体及 key 类型，首次获取时初始化对应实现。

同一数据源名称下，重复获取同一接口返回相同实例；同实体、同模式的不同接口共享底层实现及缓存。同一实体不能同时绑定 Single 和 Group 模式。

业务接口必须为非 sealed 接口，实体和 key 泛型参数必须绑定为具体类。原始泛型、未绑定泛型、传入实体类及同时继承两种模式都会在初始化时报错。自定义辅助方法使用 `default` 实现；额外抽象查询方法会被拒绝，不会自动生成 SQL。CRUD 直接分派到底层实现，默认方法在初始化时绑定 MethodHandle。

## Spring Repository

Spring 项目直接使用原生 `org.springframework.stereotype.Repository` 标记业务接口，通过构造器或 `@Autowired` 注入。配置一次 `@EnableGameDataRepositories` 开启接口扫描；Spring 普通组件扫描不会实例化 Repository 接口。适配器注册单例 FactoryBean，底层调用 `GameData.repository(...)`，复用现有 Repository 实例、缓存和异步保存逻辑。

添加 `org.springframework:spring-context`（编译版本为 6.2.19，集成测试已通过 6.2.19 和 7.0.8），或使用应用管理的 Spring 版本。game-data 将其声明为可选依赖，普通 Java 项目不会被传递引入 Spring。Spring Boot 项目可把开启注解放在启动类或配置类上，不需要额外的 Boot starter。

```java
import cn.managame.core.repository.SingleRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerRepository extends SingleRepository<Player, Long> {
}
```

```java
import cn.managame.core.GameData;
import cn.managame.core.access.DataAccess;
import cn.managame.spring.EnableGameDataRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableGameDataRepositories(basePackageClasses = PlayerRepository.class)
public class GameDataConfig {
    @Bean(destroyMethod = "close")
    GameData gameData(DataAccess dataAccess) {
        return new GameData(dataAccess);
    }
}
```

```java
import org.springframework.stereotype.Service;

@Service
public class PlayerService {
    private final PlayerRepository players;

    public PlayerService(PlayerRepository players) {
        this.players = players;
    }
}
```

应用提供已配置的 `DataAccess` Bean。GameData 负责关闭它；若该 DataAccess 也是独立 Bean，应使用 `@Bean(destroyMethod = "")` 避免重复关闭。外部 JDBC 连接池仍由应用管理。关闭 Spring 容器之前，先停止业务入口并排空 Runtime；随后 GameData Bean 销毁时排空已接收的写入。

- `basePackageClasses` 扫描标记类所在包；`basePackages` 接受包名。都不指定时，扫描配置类所在包。
- 只注册带注解的 SingleRepository/GroupRepository/LogRepository 业务接口。普通 `@Repository` 实现类仍由 Spring 组件扫描处理。即使尚未被业务引用，接口也会在容器启动时完成创建和校验。
- `@Repository("playerRepository")` 指定的是 **Spring Bean 名称**，可以通过 `@Qualifier("playerRepository")` 注入，也可以在首选接口上标注 `@Primary`。
- `gameDataRef = "gameData"` 指定 GameData Bean；`dataAccess = "archive"` 指定其内部命名 DataAccess，空值使用默认数据源。不同数据源使用独立配置类和 Repository 包绑定，Bean 名称冲突会在启动时报错。

适配器使用 Spring 的 [FactoryBean 契约](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/beans/factory/FactoryBean.html)和[接口扫描扩展点](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/context/annotation/ClassPathScanningCandidateComponentProvider.html)。

## 普通 Java Repository 组装

非 Spring 场景通过 `gameData.repository(...)` 获取业务 Repository 接口，再通过构造器传给服务或 Handler。命名数据源使用 `repository("archive", PlayerRepository.class)`。

```java
public class PlayerService {
    private final PlayerRepository players;

    public PlayerService(PlayerRepository players) {
        this.players = players;
    }
}
```

```java
PlayerService service = new PlayerService(gameData.repository(PlayerRepository.class));
```

## 运行时所有权与持久化模型

```text
READ:  Application -> Repository -> Caffeine -> cache miss -> DataAccess -> Database
WRITE: Modify entity -> Repository.update() -> TableWriter -> Batch -> DataAccess -> Database
```

运行期间以内存为权威状态，数据库异步更新。

- 登录、认证、服务器分配及数据的单进程独占所有权由业务负责。
- 同一实体的业务修改必须串行；分组访问还包括组内遍历。活对象编码不提供某一业务时刻的快照，更强的一致性及集合并发安全要求由业务保证。
- 每次有效修改实体后必须调用 `update()`。
- 不使用 `@Version`，不提供跨进程所有权协议或跨表事务。
- 进程异常退出可能丢失 pending 写入，正常关闭会处理完已接收工作。
- 框架不引入快照、WAL 或业务响应等待持久化的机制。

## 字段映射与 Codec

注解位于 `cn.managame.annotation`，不依赖 Spring 容器。`@Table`、`@Document` 指定存储名称，`@Id` 指定主键，`@Column`、`@Field` 指定字段名称，`@Transient` 排除字段。字段访问器通过 MethodHandle 预绑定，实体必须具有无参构造器。

`ColumnType.AUTO` 自动推导存储形态。集合、Map、非 byte 数组及自定义对象默认按 JSON 处理，`byte[]` 默认按二进制处理。可以显式指定字符串长度、文本、JSON 或二进制：

```java
@Column(value = "name", type = ColumnType.STRING, length = 64)
private String name;

@Column(type = ColumnType.TEXT)
private String description;

@Column(type = ColumnType.JSON)
private java.util.List<String> rewards;

@Column(type = ColumnType.BINARY)
private byte[] payload;
```

JSON 字段需要显式配置 JsonCodec。添加可选 Jackson 依赖后配置 EntityMapper：

```java
import cn.managame.rdb.RdbValueConverter;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.codec.jackson.JacksonJsonCodec;

var mapper = new EntityMapper(new RdbValueConverter(), new JacksonJsonCodec());
var access = new RdbDataAccess(dataSource, new MySqlDialect(), mapper);
```

RdbDataAccess 默认使用 RdbValueConverter，负责 JDBC Date/Timestamp 和十进制数值；MongoDataAccess 默认使用 MongoValueConverter，负责 BSON 日期、Decimal128 和 Binary 解码。core 的 DefaultValueConverter 只处理与数据库无关的标量转换，不选择数据库存储表示。显式传入自定义 mapper 时，必须使用对应后端的转换器，或提供等价转换。MongoDB 配置 JSON 字段示例：

```java
import cn.managame.core.mapping.EntityMapper;
import cn.managame.docdb.mongodb.MongoValueConverter;
import cn.managame.codec.jackson.JacksonJsonCodec;

var mapper = new EntityMapper(new MongoValueConverter(), new JacksonJsonCodec());
var access = new MongoDataAccess(mongoClient, "game", mapper, false);
```

当前共享 mapper 的 JSON 编码结果为 JSON 文本，不应假设 Java 集合会自动保存为原生 BSON 结构。修改现有 Schema 前，应确认所选后端的实际字段表示。

BigInteger/BigDecimal 转换保留数值精度，不经过 long/double 中转。BigInteger 以精确十进制值写入；数据库中的小数不能读取成 BigInteger，会报错而不是截断。数据库字段的精度和小数位数仍限制可保存的值。MongoDB 使用 BSON Decimal128 保存这些十进制数值，无法精确表示的值会被拒绝。MongoValueConverter 在按 ID 查询、条件查询和扫描时，将 BSON Binary 还原为 byte[]，将 Decimal128 还原为声明的 BigInteger/BigDecimal；传入使用 MongoValueConverter 的自定义 EntityMapper 时同样适用。

MongoDB 的 LocalDate 使用 BSON 日期保存，并按 JVM 默认时区还原，与既有写入转换一致。读写这些数据的进程必须使用相同的默认时区，例如 `-Duser.timezone=Asia/Shanghai`；此次修复不改变已有存储格式。按 ID 查询、条件查询、扫描以及配置 JacksonJsonCodec 的 mapper 都使用相同转换。

内置 Repository 初始化时会调用一次 `DataAccess.validateMapping(metadata)`，在建表、常驻加载和接收变更之前检查配置。`ensureSchema(false)` 和 LogRepository 也会执行校验，不连接数据库或创建日志分表。缺少 JsonCodec、不支持的标量类型会带实体及字段名报错。自定义 DataAccess 可覆盖这个钩子，自定义 ValueConverter 可覆盖 `validate(Class<?>)`。

| 字段类型 | RDB | MongoDB |
| --- | --- | --- |
| 数值基本类型及包装类型、Boolean、String、枚举、UUID、char/Character | 支持；char 使用 CHAR(1) | 支持；枚举、UUID 和 char 使用字符串 |
| BigInteger / BigDecimal | 精确十进制转换，受列精度和小数位数限制 | 精确 Decimal128 转换，受其范围和精度限制 |
| byte[] | 二进制列 | BSON 二进制 |
| Instant、LocalDateTime、LocalDate | JDBC 时间戳或日期 | BSON 日期；时间戳精度为毫秒 |
| LocalTime | JDBC 4.2 类型化绑定和读取，列精度为 TIME(6) | 初始化时报错 |
| OffsetDateTime / OffsetTime | 内置转换器在初始化时报错 | 内置转换器在初始化时报错 |
| 以 JSON 映射的 List、Map、数组及对象字段 | 必须配置 JsonCodec | 必须配置 JsonCodec；保存为 JSON 文本 |

RDB 使用 `ResultSet.getObject(column, LocalTime.class)` 读取 LocalTime，保留驱动能提供的小数秒；RdbValueConverter 也支持 java.sql.Time 输入。PostgreSQL 的原生 UUID 和 JSON/JSONB 字段在查询和写入时使用 JDBC OTHER 参数类型；显式声明为 STRING/TEXT 的 UUID 仍使用字符串参数。原有日期、数值和 JSON 存储格式保持不变。

## 单实体与分组缓存

SingleRepository 提供 `get`、`insert`、`update`、`delete`、`deleteById` 和 `resident`。`get()` 返回 Optional，不存在或已删除实体会缓存为空值。Caffeine 的 `Cache.get(key, loader)` 协调同 key 的并发缓存加载，DataAccess 本身不负责加载合并。

Repository 的 insert/update/delete 在提交成功后才更新缓存成员和替换实体；关闭或中断导致提交被拒绝时，这些缓存项保持原状。业务已经直接修改的活实体字段不会回滚，同实体或同组操作仍需由业务串行执行。提交被接收后继续异步保存，不等待数据库成功。

SingleRepository<T, ID> 和 GroupRepository<T, ID> 在初始化时校验 ID 与实体 @Id 类型一致（基本类型与包装类型对应，例如 long/Long）。错误声明在建表或常驻加载前报错，通过泛型父接口继承的业务接口也会检查。

GroupRepository 只保留 T、ID 两个泛型；ID 始终代表主键，不代表 GroupKey 或 MapKey。接口为 `Map<Object, T> getGroup(Object groupKey)` 和 `deleteGroup(Object groupKey)`，组键和 Map 键的实际类型由注解确定。单字段组键必须匹配注解字段类型，错误参数在查询或提交删除前报错。

分组实体必须声明 `@GroupKey`，缓存中每组直接保存一个 `Map<Object, T>`。没有 `@MapKey` 时默认使用 `@Id`；一个 `@MapKey` 时使用对应字段值及其类型；多个注解时按 `order` 用英文冒号 `:` 拼接成 `String`。不维护额外的组内 key 到主键 ID 映射。下面使用默认 ID key：

```java
import cn.managame.annotation.GroupKey;
import cn.managame.annotation.Id;
import cn.managame.annotation.Table;

@Table("player_task")
public class PlayerTask {
    @Id
    public long id;
    @GroupKey
    public long playerId;
    public int progress;
    public PlayerTask() { }
}
```

```java
import cn.managame.core.repository.GroupRepository;

public interface TaskRepository extends GroupRepository<PlayerTask, Long> { }
```

```java
TaskRepository tasks = gameData.repository(TaskRepository.class);
var group = tasks.getGroup(10001L);
PlayerTask task = group.get(7L);
if (task != null) {
    task.progress = 100;
    tasks.update(task);
}
tasks.deleteGroup(10001L);
```

- 对单实体 insert、update、delete 前先调用 `getGroup(groupKey)`，保证缓存代表完整分组。
- 实体加载或插入后，由业务保证 `@Id`、`@GroupKey`、`@MapKey` 不变；Repository 不保存原始身份值，也不跟踪字段变化。
- `getGroup()` 直接返回缓存 Map，不包装、不复制；缓存过期前重复读取返回同一个 Map。Repository 原地增删改。需要持久化的变更应通过 Repository 方法提交，直接修改 Map 条目不会产生数据库写入。同组访问（包括遍历）由业务保证串行。
- `deleteGroup()` 无需先加载。先将后端整组删除提交到写缓冲区，成功后原地清空已有 Map，否则缓存空组。
- 普通缓存过期后需要重新获取组。过期时间应明显长于正常缓冲保存延迟；缓存过期不构成持久化屏障。

复合分组键使用 `@GroupKey(order = 0)`、`@GroupKey(order = 1)` 等字段顺序。复合分组 key 的实际类型为 `String`，例如玩家 ID 10001、活动 ID 7 对应 `getGroup("10001:7")`。复合 MapKey 使用相同规则，实际类型也为 `String`。`GroupKeys.of(...)`、`MapKeys.of(...)` 仅为构建工具：单值原样返回，多值拼接为冒号字符串。字段值不可为 null，复合键各部分不可包含 `:`，不引入转义；枚举使用 name。查询分组及删除整组时，会将字符串各部分还原为声明的标量字段类型后传给数据库。

## 常驻 Repository

实体添加 `@Resident` 后，在 Repository 初始化阶段加载完整表或集合。启用 Schema 初始化时先处理 Schema，再调用 `DataAccess.scan()`。

常驻缓存不过期，key 缺失也不回源数据库。扫描失败会导致初始化失败，不发布部分加载的 Repository。写操作仍异步保存。RDB scan 将 ResultSet 行映射为实体，MongoDB scan 遍历 Cursor；接口避免构建完整中间 List，但 JDBC 驱动实际如何缓冲还取决于驱动配置。

## 缓冲保存与批量合并

一个写引擎内，每个物理 writer key 对应两个复用缓冲区和一个虚拟保存线程。业务向当前缓冲区追加操作；后台交换缓冲区并保存取出的缓冲区，业务继续写另一份。每张表只在缓冲区变更时短暂同步，不使用全局准入读写锁。通过 volatile 接收标志停止新提交，仅创建 writer 时与关闭流程协调注册。每张表自行排空已经接收的操作，包括已在等待缓冲区容量的提交；关闭开始时仍在解析 writer 的提交会被拒绝。编码和数据库 I/O 在同步区外执行。不同物理表可以并行保存。普通数据与日志使用独立引擎和配置；应分别使用不同表，并为每个物理表配置唯一写入管理方。

达到 batch 大小或 flush 间隔时执行批次。当前写入缓冲区满时阻塞提交产生背压。后台按有效 batch 大小分批处理取出的缓冲区；容量配置限制当前写入缓冲区，不是两个缓冲区合计的 pending 上限。insert/update 保存活实体引用和身份值；字段编码发生在后端执行 batch 时，而不是业务提交时。

同 batch 中同 ID 的操作按以下规则合并：

```text
INSERT + UPDATE -> INSERT using the latest entity
UPDATE + UPDATE -> latest UPDATE
UPDATE + DELETE -> DELETE
INSERT + DELETE -> no operation
DELETE + DELETE -> DELETE
DELETE + INSERT -> keep DELETE followed by INSERT
```

整组删除构成顺序屏障。不同 ID 保留下来的操作保持顺序，RDB 仅合并连续使用相同 SQL 的操作。默认表 SQL Plan 在 Schema 初始化时缓存；动态日志 INSERT 复用实体模板，不保留每个分表的 Plan。ResultSet 字段直接映射到实体。

## 失败、flush 与正常关闭

数据库失败只影响当前 batch。DataAccess 判定当前尝试可以安全重放时，writer 进行有限重试。RDB 重试要求 commit 尚未开始、rollback 明确成功且错误属于可重试事务错误。MongoDB 默认不重放整个 ordered bulk，因为可能已有部分操作成功。

最终失败后，WriteFailureHandler 同步记录非成功操作，后续 batch 继续。框架不保存最近一次数据库异常、历史异常链或用于未来重放的失败 batch，仅保留计数和时间指标。默认处理器将身份、当前字段值和异常写入 stderr。自定义处理器应及时记录，不长期持有实体或异常；处理器抛错时回退到 stderr。

`BatchResult` 区分 SUCCESS、FAILED、UNEXECUTED 和 UNKNOWN。MongoDB 有索引的 ordered-write 错误可区分失败项和未执行后缀；同时检查报错前已执行部分的 UPDATE 匹配数量。如果匹配数量不足，因为汇总结果无法定位具体未命中项，这部分 UPDATE 全部标记 UNKNOWN，已确认执行成功的 INSERT 仍保留成功状态。write concern 失败时，已执行部分保持 UNKNOWN。无法确定的结果保守标记 UNKNOWN。RDB 在 commit 成功后返回全成功；明确回滚报告 FAILED，事务执行前失败报告 UNEXECUTED，commit 结果不明或 rollback 失败通过 BatchWriteException 报告 UNKNOWN，且不自动重试。这不是自动修复机制：此前失败的 insert 或 delete 仍可能影响后续写入。

RDB 在提交事务前校验 JDBC batch 返回的影响行数。UPDATE 返回 0 或 SUCCESS_NO_INFO 时，在同一连接、同一事务内检查 ID 是否存在：记录存在且值未变化视为成功，记录缺失则让整批失败并回滚。明确更新 1 行不增加查询。DELETE 删除已不存在的 ID 仍视为幂等成功；INSERT 的异常影响行数和 batch 失败项同样触发回滚。

事务结果未明确时，不通过恢复 auto-commit 清理连接，而是先 abort 再 close。JDBC 驱动及连接池应支持 Connection.abort；如果 abort 本身失败，适配器记录错误且不把该未解决事务连接归还连接池，该连接可能占用池容量，直至应用处置。

```java
gameData.flush();
gameData.flush(Player.class);
gameData.flush("game", Player.class);
```

flush 传入的是**实体类**，Repository 获取传入的是**业务接口**。flush 等待对应 writer 的 pending 归零，持续提交可能延长等待。普通数据库 batch 失败只记录日志，不再由 flush 或 close 重新抛出；框架级终止错误仍可能抛出。

正常停止顺序：

1. 停止业务流量和实体修改。
2. 调用 `GameData.close()`，所有已配置引擎停止接收新提交。
3. 等待表级 writer 已接收的提交完成入队，包括正在等待缓冲区容量的提交；尚未进入 writer 的提交被拒绝。
4. 排空两个缓冲区及正在执行的批次，不中断数据库调用或安全重试。
5. writer 完成后关闭 DataAccess。

并发 close 等待同一关闭过程。关闭调用线程被中断时仍等待 drain，返回时保留中断标志。不要从保存线程的失败回调中调用阻塞的 flush 或 close。框架不为卡住的数据库调用设置关闭超时，应配置数据库超时；外部持有的连接池或 MongoClient 生命周期仍由业务负责。

## 日志 Repository 与自动分表

业务日志接口继承 `LogRepository<T>`，与 SingleRepository/GroupRepository 共用统一工厂和 Spring 注册机制。业务只调用 `logs.append(log)`。Repository 绑定实体类型和 DataAccess，具体分表由日志时间字段值生成，调用方不传表名，也不配置 router。

```java
import cn.managame.annotation.Id;
import cn.managame.annotation.LogPartition;
import cn.managame.annotation.Table;
import java.time.LocalDateTime;

@Table("player_action_log")
public class PlayerActionLog {
    @Id
    public long id;

    @LogPartition(LogPartition.Period.MONTH)
    public LocalDateTime createTime;
}
```

```java
import cn.managame.core.log.LogRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerActionLogRepository extends LogRepository<PlayerActionLog> {
}
```

```java
@Autowired
private PlayerActionLogRepository logs;

// Inside a business method:
logs.append(log);
```

`createTime` 为 2026 年 9 月时写入 `player_action_log_202609`。在唯一的已映射实例字段上声明 `@LogPartition(Period.DAY)`、`MONTH` 或 `YEAR`，分别使用 `_yyyyMMdd`、`_yyyyMM`、`_yyyy` 后缀。没有 `@LogPartition` 时使用实体默认表或集合名，包括 `@Table`/`@Document` 配置。

时间字段支持 LocalDate、LocalDateTime、Instant、毫秒时间戳 long/Long。Instant 和毫秒时间戳按注解的 `zone` 转换，默认 `UTC`；例如 `@LogPartition(value = LogPartition.Period.DAY, zone = "Asia/Shanghai")`。LocalDate 和 LocalDateTime 直接使用其日期。非法字段声明或时区在 Repository 初始化时报错；追加时不允许分表时间为空。支持继承的已映射字段。追加时根据事件时间确定目标分表，追加后不要修改该字段。

Spring 中由 `@EnableGameDataRepositories` 注册业务接口，通过构造器或 `@Autowired` 正常注入。普通 Java 项目使用统一工厂：

```java
PlayerActionLogRepository logs = gameData.repository("log", PlayerActionLogRepository.class);
logs.append(log);
```

两个参数的工厂指定命名 DataAccess，`repository(PlayerActionLogRepository.class)` 使用默认数据源。业务辅助方法可以写成接口 default 方法。日志实体需主键，不进入实体缓存，后台批量写入时直接编码活对象。

append 自动攒批，不要每条日志都 flush。确实需要等待时，`logs.flush()` 排空该数据源上该日志实体的写入，`gameData.flushLogs()` 排空全部日志。正常 `GameData.close()` 自动处理完已接收写入。

动态分表默认空闲 5 分钟且没有待写工作后，释放队列和虚拟线程。再次追加历史时间的日志会重新创建对应 writer。正在执行的 batch、重试和失败回调阻止回收。通过 Builder 的 `logWriterIdleTimeout(Duration)` 配置，零表示禁用回收。未分表的固定表 writer 保留。实体指标保留数值累计，物理指标只列出当前 writer。

RDB 按实体缓存 INSERT 模板，每个 batch 填入方言引用后的分表名，不保留历史分表 SQL Plan。纯 INSERT 批次保留每条日志并跳过实体状态合并。日志表或集合提前创建，Repository 初始化和 append 都不执行日志 DDL。可在部署时预建，或初始化时调用 `dataAccess.ensureSchema(metadata, physicalName)`。分表保留和删除仍由应用负责。

## 多个索引与复合索引

使用 game-data 自己的 `@Table(indexes = {...})` 和 `@Index` 注解，声明形式参考 JPA，不引入 JPA 依赖：

```java
import cn.managame.annotation.Column;
import cn.managame.annotation.Id;
import cn.managame.annotation.Index;
import cn.managame.annotation.Table;

@Table(value = "player_item", indexes = {
    @Index(name = "uk_player_slot", columnList = "player_id, slot", unique = true),
    @Index(name = "idx_item_count", columnList = "item_id, count DESC")
})
public class PlayerItem {
    @Id public long id;
    @Column("player_id") public long playerId;
    public int slot;
    @Column("item_id") public int itemId;
    public int count;
}
```

`columnList` 使用映射后的 RDB 列名，以逗号分隔，可选 ASC/DESC，默认 ASC。不填 name 时生成 `idx_<table>_<columns>`。不存在或重复的列、无效方向及重复索引名在元数据初始化时直接报错；原有单字段 `@Indexed` 继续支持。

同一声明同时支持 RDB 和 MongoDB 的有序复合索引；MongoDB 会将列映射到对应文档字段。旧版 JSON `@CompoundIndex(def = "...")` 仅用于 MongoDB，RDB Schema 初始化会明确拒绝，避免静默忽略。已有索引只检查和报告，不自动替换。

## 数据库适配器与 Schema 管理

RdbDataAccess 接收业务提供的 DataSource 和数据库方言，支持 `cn.managame.rdb.dialect` 下的 `MySqlDialect`、`MariaDbDialect`、`PostgreSqlDialect`。MongoDB 实现位于 `cn.managame.docdb.mongodb.MongoDataAccess`：

```java
try (var gameData = new GameData(
        new cn.managame.docdb.mongodb.MongoDataAccess("mongodb://localhost:27017", "game"))) {
    PlayerRepository players = gameData.repository(PlayerRepository.class);
    players.get(10001L).ifPresent(player -> System.out.println(player.getLevel()));
}
```

DataAccess 负责加载、查询、扫描、批量执行及 Schema 初始化，不负责缓存、业务路由、调度或跨进程锁。自定义实现需要提供 `findById`、`find`、`scan`、`applyBatch` 和 `ensureSchema`，物理路由及失败分类可通过扩展方法定义。

Schema 检查还会报告主键缺失或定义不符、主键字段可空，以及十进制字段整数容量或小数位数不足。更大的十进制定义只有在整数和小数容量都足够时才被接受，驱动没有提供的元数据不会按零处理。DATETIME/TIMESTAMP 与 DATE/TIME 分别判断，避免正常 DATETIME 字段误报。上述差异只报告，不自动重建约束或修改已有字段。

自动 Schema 操作仅做增量：创建缺失表或集合、添加缺失 RDB 字段、创建索引。不自动删除字段或索引，也不执行破坏性类型变更。RdbSchemaReporter 报告检测到的类型、字符串长度及同名索引的列、顺序、方向和 unique 差异（JDBC 元数据提供方向时才比较方向）；`access.schemaManager().inspect(metadata)` 返回检测结果，但不是完整的 Schema 迁移或差异引擎。MongoDB 索引定义冲突由驱动报告。不兼容变更应在普通 Repository 初始化之外显式执行。

## 命名数据源与配置

Repository 初始化时绑定命名 DataAccess，普通操作不动态选择数据源。下面的 gameAccess、logAccess 为预先配置的 DataAccess 实例：

```java
var gameData = GameData.builder()
        .dataAccess("game", gameAccess)
        .dataAccess("log", logAccess)
        .defaultDataAccess("game")
        .cacheExpireAfterAccess(java.time.Duration.ofMinutes(30))
        .writeQueueCapacity(65_536)
        .writeBatchSize(256)
        .flushInterval(java.time.Duration.ofMillis(100))
        .maxRetries(3)
        .logQueueCapacity(131_072)
        .logBatchSize(2_048)
        .logFlushInterval(java.time.Duration.ofMillis(200))
        .logWriterIdleTimeout(java.time.Duration.ofMinutes(5))
        .ensureSchema(true)
        .build();

PlayerRepository players = gameData.repository("game", PlayerRepository.class);
```

| 配置 | 默认值 | 作用范围 |
| --- | --- | --- |
| `cacheExpireAfterAccess` | 30 分钟 | 普通缓存；常驻缓存不过期 |
| `writeQueueCapacity` | 65,536 | 每个普通数据物理 writer |
| `writeBatchSize` | 256 | 普通数据 batch 上限 |
| `flushInterval` | 100 ms | 普通数据凑批间隔 |
| `maxRetries` | 3 | 仅在判定安全时进行的额外重试次数 |
| `logQueueCapacity` | 131,072 | 每个日志物理 writer |
| `logBatchSize` | 2,048 | 日志 batch 上限 |
| `logWriterIdleTimeout` | 5 分钟 | 空闲路由日志 Writer 回收；零表示关闭 |
| `logFlushInterval` | 200 ms | 日志凑批间隔 |
| `ensureSchema` | `true` | Repository 初始化；不作用于路由日志 append |
| `failureHandler` | `WriteFailureHandler.stderr()` | 同步记录最终失败 |

实际 batch 大小还受 `DataAccess.maxBatchSize()` 限制。队列满时可能阻塞提交线程，flush 间隔属于凑批设置，不是持久化延迟上限保证。停止业务流量后关闭 GameData，并单独管理外部资源的生命周期。

## 运行指标

```java
var metrics = gameData.writerMetrics("game", Player.class);
var logsMetrics = gameData.logWriterMetrics("log", PlayerActionLog.class);
var physicalWriters = gameData.physicalWriterMetrics("game");
var physicalLogs = gameData.physicalLogWriterMetrics("log");
var state = gameData.writerState("game", Player.class);
```

指标包括队列大小、pending 数量、成功及失败 batch 数、成功操作数、最近 batch 大小、flush 耗时及最近失败时间。按实体汇总的指标可能涵盖多个物理表，分表排查时使用物理 writer 指标。框架级终止错误将状态置为 FAILED，普通数据库 batch 失败不会。监控系统接入由业务完成。

## 构建、验证与 API 迁移

在仓库根目录执行模块构建：

```sh
mvn -f game-data/pom.xml verify
```

现有回归测试覆盖数据库阻塞时的双缓冲提交、提交被拒绝时缓存成员保持不变、JDBC 回滚与提交不确定结果、批量影响行数校验、多索引声明和 Schema 差异、凑批时关闭、延迟注册 writer、满队列、关闭中断与并发关闭、安全重试、失败日志、接口解析及缓存共享、组内原地修改。测试还覆盖分表 Writer 回收与重建、并发提交及关闭、精确数值转换，以及使用模拟集合和 MongoDB 驱动 BSON 编解码器的二进制与十进制往返。这些测试不替代真实数据库集成测试。仓库根目录执行 `mvn verify` 还会构建并检查其他项目，也可使用 `mvn -pl game-data -am verify` 单独选择此模块。在 IDE 中使用 game-data 测试 classpath 运行 `cn.managame.example.MysqlExample` 或 `MongoExample`；verify 会编译示例，但测试不会因此连接数据库。

从旧 API 迁移时：

- core 类型按职责迁入子包，例如 `SingleRepository` 位于 `cn.managame.core.repository`、`DataAccess` 位于 `cn.managame.core.access`、`EntityMapper` 位于 `cn.managame.core.mapping`。`GameData` 和 `DataException` 仍在 `cn.managame.core`，Maven 坐标保持不变。

- Java import 从 `io.gamedata` 改为 `cn.managame`。原有子构件依赖统一改为 `cn.managame:game-data:0.1.0-SNAPSHOT`，后端及 Codec 的可选依赖按前文添加。
- 将 `repository(Entity.class)`、`groupRepository(Entity.class)` 改为业务接口及 `repository(MyRepository.class)`。
- flush 和指标 API 仍传入实体类。
- 使用 WriteFailureHandler 日志及失败指标，替代 `writerFailure()` 轮询及 flush/close 的 batch 异常汇总。
- 在 append 前预建日志分表。
- 组 Map 就是缓存中的原始 Map；保证同组访问串行，通过 Repository 方法持久化变更。

实现参考：[GameData](src/main/java/cn/managame/core/GameData.java)、[WriteBehindEngine](src/main/java/cn/managame/core/write/WriteBehindEngine.java)、[测试](src/test/java/cn/managame/core)。
