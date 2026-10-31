[English](README.md) | 中文

# managame-data

游戏服务器的数据放在内存里，写入在后台进行。业务代码只在内存里读写实体；每秒一轮，把变化的数据批量写到应用选定的地方。
游戏数据只有两种形状，各有一个 Repository，另有一个用于日志：

| Repository | 内存里 | 按什么加载 | 例如 |
| --- | --- | --- | --- |
| `SingleRepository<K, E>` | `Map<K, E>`，按 `@Id` | id | 玩家、公会 |
| `GroupRepository<K, E>` | `Map<GroupKeys, Map<K, E>>`：按 `@GroupKey` 的值分组，每组按 `@MapKey` 成一个 Map | 所有 `@GroupKey` 字段相等 | 玩家的物品、任务、邮件 |
| `LogRepository<E>` | 不缓存 | 从不读回 | 物品流水、登录日志 |

依赖 managame-common 和 [Caffeine](https://github.com/ben-manes/caffeine)，后者在内存里保存各 Repository 的实体。这个项目
不认识任何数据库：`EntityMapper` 负责加载和写入实体，`LogWriter` 负责写日志，由应用为每个 Repository 选定：

| 项目 | 提供 |
| --- | --- |
| [managame-data-mysql](../managame-data-mysql/README.zh-CN.md) | `MysqlEntityMapper` 和 `MysqlLogWriter` |
| [managame-data-mongo](../managame-data-mongo/README.zh-CN.md) | `MongoEntityMapper` |
| 应用自己 | 任意 `LogWriter`，例如通过 HTTP 上报日志；或自己的 `EntityMapper` |

| 包 | 内容 |
| --- | --- |
| `cn.managame.data` | 业务代码使用的：`GameData`、各 Repository、`GroupKeys`、`MapKeys`、`DataException`、`DataFailure`、`DataErrorHandler`、`DataOperation` |
| `cn.managame.data.annotation` | `@Id`、`@GroupKey`、`@MapKey` |
| `cn.managame.data.spi` | 数据库或日志去处要实现的：`EntityMapper`、`LogWriter`，以及它们读取的 `EntityType`、`EntityField`、`Change`，还有默认的重试规则 `RetryableFailures` |
| `cn.managame.data.internal` | 内部实现；只是为了让其他包能调用才是 public，不是 API，任何版本都可能变 |

## 快速上手

实体是普通的类：既不是 static 也不是 transient 的字段都会保存。

```java
public class Player {                // 存 MySQL 时加 @Table(name = "player")，存 MongoDB 时加 @Collection(name = "player")
    @Id private long id;
    private String name;
    private int level;
    private Map<Integer, Long> items = new ConcurrentHashMap<>();
    private transient long lastSeen;                                // 不保存
    Player() {}
}

public class Item {
    @Id private long uid;            // 实体的主键
    @GroupKey private long playerId; // 按它加载一组
    @MapKey private int slot;        // 在组内唯一，组的 Map 以它为 key
    private int configId, count;
    Item() {}
}

public record ItemLog(long playerId, int configId, int delta) {}    // 日志可以是任意类
```

Repository 是只带类型参数、不用写方法的子类：

```java
public class Players extends SingleRepository<Long, Player> {}
public class Items extends GroupRepository<Integer, Item> {}    // K 是 @MapKey 字段的类型
public class ItemLogs extends LogRepository<ItemLog> {}
```

启动时构建一次，并指定每个 Repository 的数据写到哪里；类可以用集合传入（方便容器集成扫描后传入），也可以逐个列出：

```java
LogWriter logService = (logType, logs) -> http.post("/logs/" + logType.getSimpleName(), json.encode(logs));

GameData data = GameData.builder()
        .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
        .logRepositories(logService, List.of(ItemLogs.class))
        .errorHandler(failures::save)                     // 必填：保存未能写入的数据
        .build();
Players players = data.repository(Players.class);

Player player = players.getOrCreate(id, Player::new);    // 数据库里没有时创建并插入
players.update(id, p -> p.levelUp());                    // 修改和登记一步完成
Map<Long, Player> friends = players.getAll(friendIds);   // 不在内存的一次加载
Map<Integer, Item> bag = data.repository(Items.class).getGroup(GroupKeys.of(id));
data.repository(ItemLogs.class).insert(new ItemLog(id, 5001, -1));
...
data.close();                                            // 写完剩下的修改
```

## 实体

- **字段。** 既不是 static 也不是 transient 的实例字段都会保存，父类字段在前；不能是 final。字段的命名和存储方式由 Mapper
  决定。
- **无参构造器**，可以是私有的。加载时用它创建实体，因此在构造时初始化的集合字段加载后仍是同一种类：
  `items = new ConcurrentHashMap<>()` 加载回来还是 ConcurrentHashMap（`EntityField.valueType()` 把这个类型告诉 Mapper）。
- **Key。** `@Id`、`@GroupKey`、`@MapKey` 字段是整数类型或 String，由应用分配（没有自增 id，因为插入是之后才写的），且永不
  修改。多个 GroupKey 或 MapKey 字段各需一个不同的 `order`：组 key 按这个顺序写成 `GroupKeys.of(a, b)`，map key 写成
  `MapKeys.of(a, b)`，即各值用 `':'` 连接。

## 读取和修改

- **读取只碰内存。** 不在内存里的实体或组在请求的线程上加载，无论同时有多少线程在要都只加载一次：它们都等这一次加载，拿到
  同一批实例。请求的线程在等待数据库返回时会阻塞：在虚拟线程上没有问题（managame-runtime 的 Route 默认就是虚拟线程），绝不能
  在 Netty EventLoop 上调用。一组数据总是整组加载，读和写都一样。读取返回的数据是实时的，只用于读；修改实体和组要通过
  Repository。
- **一次读多个。** `getAll(ids)` 返回这些 id 中存在的实体，按传入顺序以 id 为 key。不在内存的那些通过 Mapper 的一次
  `loadAll` 读出（MySQL 里每一千个 id 一条 `WHERE id IN (...)`）。这期间对其中某个 id 的 `get` 也可能去读它；谁先存进内存，
  两边返回的就是谁的实例。
- **修改实体后要登记**：`update(entity)`，或通过 `update(id, change)` / `update(groupKey, mapKey, change)` 修改，这样不会
  忘记登记。写入的是写入那一刻实体的状态，由写入线程在那时读取。
- **实体的集合字段要线程安全**（ConcurrentHashMap、CopyOnWriteArrayList），因为写入线程可能在业务代码修改它们时读取。集合
  字段用普通 java.util 类初始化或没有初始化时，`build` 会记一条 WARNING。
- **同一实体一次只改一处。** 每个方法都可以在任何线程调用，但同一个实体的修改由应用保证串行，例如放在实体所属的 Route 上
  执行。
- **每个任务都用 id 去 `get` 实体**，不要跨任务持有，例如存在会话对象里：超过 `cacheExpire` 没用到的实体会离开内存，之后的
  `get` 会加载出另一个实例，这时再改手里那个旧实例，就会把它替换掉，丢失在另一个实例上做的修改。
- **内存知道哪些行存在。** 对内存中已有的 id 调用 `insert` 会抛 `IllegalStateException`；对已知不存在的 id 调用 `update`
  会抛异常，调用 `delete` 则什么都不做。`get` 一个数据库里没有的 id 时会记住这一点，再次询问不会再查数据库。修改不在内存里的
  id 从不读取数据库。
- **离开内存。** 超过 `cacheExpire`（30 分钟）没有用到的实体或组会离开内存，被 `evict` 的也一样。之后再读会重新加载，并把
  还没写入的修改叠加在数据库返回的结果上：用的是同一批实例，所以重新加载永远不会比内存里的数据旧。
- **整表常驻。** 能放进内存又一直要读的表（例如公会）用 `preloadedRepositories(mapper, types)` 注册：build 时通过 Mapper 的
  `loadTable` 整表读入，之后什么都不离开内存。内存里没有的 id 或组就是不存在，所以读取从不查库；`all()` 列出全部实体（或组）。
  修改照常写入；`evict` 会抛异常，因为丢掉一个实体会让它看起来像被删除了。

## 写入

每隔 `flushInterval`（1 秒）一轮，写入自上一轮以来变化的数据：

- **每一行每轮只写一次**，写它的最新状态，不管改了多少次。插入后又更新，写为一次插入；插入后又删除，什么都不写；删除后又
  插入，写为 `DELETE_INSERT`，即替换。与等待中的修改不能衔接的修改（例如更新一行已删除的数据）会在调用线程上抛异常。
- **不同类型并行写入**，每种实体或日志同一时刻只由 `writeThreads`（4）个线程中的一个写入，因此同一行的修改按顺序写入。每种
  类型先写删除，所以一行可以使用同一轮里被删掉那行的唯一值。修改按 `batchSize`（500）分批交给 Mapper，每批只有一种操作。
- **失败。** Mapper 或 Writer 判为可重试的失败（`isRetryable`：默认是 JDBC、MongoDB 或 `java.net` 的连接断开或被拒、超时、
  死锁或锁等待）会把这批留到下一轮；同一个 Mapper 或 Writer 连续失败两次后，本轮不再经它写入，所以出故障时每轮大约只花两次
  超时。因为这类失败而不知道是否已写入的插入，会以 `DELETE_INSERT` 重写，永远不会撞上自己之前写入的行。其他失败会把这批拆成
  单条；单条仍然失败的交给 `DataErrorHandler` 并丢弃，其他照常写入。
- **`flush()`** 立刻写入调用之前的所有修改，写完才返回；有失败时抛 `DataException`（`DATA_SAVE_FAILED`）。在其他进程要读
  这些数据之前使用，例如玩家换服时：先 `flush()`，再 `evict`。
- **`close()`** 拒绝之后的修改，然后写入等待中的修改；写入失败时每个 flush 间隔重试一次，最多持续 `shutdownTimeout`
  （30 秒）。仍未写入的逐条交给 `DataErrorHandler`，应用可以把它们存到别处，随后 close 抛出异常。关闭进程时要调用它：在那之前
  它的线程会让 JVM 一直运行。
- **崩溃**时最多丢失大约一个 flush 间隔的修改。

`DataErrorHandler` 收到一个 `DataFailure`：错误码（`DATA_SAVE_FAILED`，日志是 `DATA_LOG_SAVE_FAILED`）、实体类型、操作、
实体（删除一个从未进过内存的实体时是它的 id）或日志，以及原因。它一次只处理一个失败。它是必填的，没有设置时 `build` 抛
`IllegalStateException`：只记日志等于丢掉数据，所以要存到之后还能重写的地方，例如本地文件。

## 日志

`LogRepository` 把日志放进队列（最多 `logQueueCapacity`，100,000 条），每轮按插入顺序分批交给它的 `LogWriter`；队列满时
（例如目的地不可用期间），`insert` 返回 false 并把日志交给 `DataErrorHandler`。日志从不读回，所以可以是任意类，包括 record，
读取哪些内容由 Writer 决定。日志插入后不要再修改。

通过 HTTP 上报的 Writer 写成一个 lambda 即可。若希望 503 这类应答在下一轮重试，就为它们抛出自己的异常，并在 `isRetryable`
里识别：

```java
class LogService implements LogWriter {
    public void write(Class<?> logType, List<Object> logs) throws Unavailable { ... }
    public boolean isRetryable(Throwable failure) { return failure instanceof Unavailable || LogWriter.super.isRetryable(failure); }
}
```

## 直接操作数据库

应用保留交给 Mapper 的 `DataSource` 或 `MongoDatabase`，想怎么用就怎么用，例如管理后台的查询和报表；框架不包装其中任何东西。
Repository 持有的数据以内存为准，数据库落后它一轮：

- **要读到最新状态**，先调用 `flush()`。
- **Repository 持有的行只通过 Repository 修改**，并且在持有它们的那台服上修改，不要用 SQL 或驱动直接改：下一轮写入会用内存
  里的版本覆盖数据库里的。没有注册给任何 Repository 的表，应用可以随意修改。

## 统计

`data.stats()` 可以在任何线程调用，读出每个 Repository 当前保存了什么、build 以来各轮写入做了什么，开销小到可以每隔几秒读一次，
交给应用自己的监控：

```java
for (EntityStats stats : data.stats().entities())
    if (stats.waiting() > 100_000) alert(stats.entityType().getSimpleName() + ": " + stats.waiting() + " changes waiting");
```

| `EntityStats` | |
| --- | --- |
| `cached` | 内存里的实体数（GroupRepository 为组数），Caffeine 的估计值 |
| `waiting` | 等待写入的修改，含正在写的这一轮：一直上涨说明数据库跟不上 |
| `written`、`failures`、`dropped` | build 以来：写入的修改、失败的写入（不论是否重试）、交给 DataErrorHandler 的修改 |
| `cache` | Caffeine 的 `CacheStats`：命中、未命中、加载、加载失败、加载耗时和淘汰数；`minus` 算出两次读数之间的变化 |

`LogStats` 有 `queued`、`written`、`refused`（队列满被拒）、`failures` 和 `dropped`。每个数各自读取，彼此不保证同一时刻。

## 自己的 EntityMapper

一个 `EntityMapper`（在 `cn.managame.data.spi` 包里）服务注册给它的所有实体类型：build 时每种类型调用一次
`prepare(EntityType)`，之后是 `load`、`loadAll`（默认逐个 `load`；能一条查询读完的覆盖它）、`loadTable`（只用于整表常驻的
Repository；默认抛异常，让 build 失败）、`loadGroup` 和
`write(type, operation, batch)`，批里的每个 `Change` 给出实体的 id 和
要读取的实体。`EntityType` 列出要保存的字段（`EntityField`：名字、类型、`valueType`、读、写，以及用于读注解的 Java 字段）、
key 字段，并能创建新实例。失败后重写同一批必须无害：一批在一个事务里写，或者每个实体都幂等地写。更新一个数据库里已没有的
实体时，可以把这一行重新写入（MySQL 的 Mapper 是这样），也可以失败（MongoDB 的是这样）。`isRetryable` 的默认规则是
`RetryableFailures.isRetryable`，Mapper 可以在它的基础上扩展。

## 配置

| 设置 | 默认值 | |
| --- | --- | --- |
| `repositories(EntityMapper, Collection)` | | 实体 Repository 及保存它们的 Mapper；也可以逐个列出 |
| `preloadedRepositories(EntityMapper, Collection)` | | 整表常驻内存、build 时读入的实体 Repository；也可以逐个列出 |
| `logRepositories(LogWriter, Collection)` | | 日志 Repository 及写它们的 Writer；也可以逐个列出 |
| `flushInterval` | 1 秒 | 多久写一次；崩溃时大约丢失这么多 |
| `batchSize` | 500 | 每批的修改或日志数 |
| `writeThreads` | 4 | 同时写入的实体和日志类型数 |
| `cacheExpire` | 30 分钟 | 未使用的实体或组在内存里保留多久 |
| `shutdownTimeout` | 30 秒 | close 最多尝试多久 |
| `logQueueCapacity` | 100,000 | 每个 LogRepository 最多等待的日志数 |
| `errorHandler` | 必填 | 接收被放弃的数据，由应用保存 |

`build` 在启动任何东西之前读取所有实体类，并让每个 Mapper 和 Writer 做准备：类不合适抛 `IllegalArgumentException`，缺少
设置抛 `IllegalStateException`，Mapper 或 Writer 准备时抛出的异常原样抛出。加载失败抛 `DataException`（`DATA_LOAD_FAILED`）。

## 线程

业务线程读取、加载不在内存的数据并登记修改；一个名为 `game-data` 的线程发起每一轮并清除过期的条目，`game-data-write-N` 线程
写入一轮里的各种实体和日志。
登记修改时只锁住一种类型六十四分之一的行，所以业务线程之间很少互相等待；业务线程做的任何事都不会等待写入。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common。测试运行在一个内存版的 `EntityMapper` 上，它表现得像数据库一样（保存行的副本，一批要么全写要么
不写，拒绝重复 id 和更新不存在的行），并能模拟连不上、确认丢失和拒绝坏数据。覆盖：
- 写入插入、更新和删除；只加载一次并保留实体；记住不存在的 id；
- 一行改多次只写一次，插入后删除不写，删除后插入被替换；
- 分组：加载、修改、多字段的 key，被淘汰的组带着未写入的修改回来；
- 多个线程同时要同一个 id 或组时只加载一次；不用的条目离开内存，被淘汰的实体以同一个实例带着未写入的修改回来；
- `getAll`：既不在内存也没有修改的 id 一次加载，`get` 和 `getAll` 同时加载同一个 id 时最后只有一个实例，失败的 `getAll`
  不在内存留下任何东西；
- 统计：等待、写入、失败和放弃的修改，缓存的命中、未命中和加载，日志的排队、拒绝和写入；
- 整表常驻：build 时读入整表、之后读取从不查库，`all()`，修改照常写入，拒绝 `evict`，没有 `loadTable` 的 Mapper 让 build 失败；
- 通过应用自己的 Writer 写日志：按顺序写入，Writer 判为可重试的失败会保留，被拒的日志丢弃而其他照常写入，队列满时拒绝；
- 失败：坏行丢弃而其他照常写入，连不上数据库期间保留修改，确认丢失的插入重写而不产生重复，更新已不存在的行被报告，close
  交出未能写入的数据；
- 构建时拒绝不合适的类和注册，原样抛出 Mapper 的异常，以集合传入 Repository；
- 业务线程修改玩家和物品的同时，每隔几毫秒写入一轮。
