[English](README.md) | 中文

# managame-data

游戏服务器的数据放在内存里，写入在后台进行。业务代码只在内存里读写实体；每秒一轮，把变化的数据批量写到应用选定的地方。
游戏数据只有两种形状，各有一个 Repository，另有一个用于日志：

| Repository | 内存里 | 按什么加载 | 例如 |
| --- | --- | --- | --- |
| `SingleRepository<K, E>` | `Map<K, E>`，按 `@Id` | id | 玩家、公会 |
| `GroupRepository<K, E>` | `Map<GroupKeys, Map<K, E>>`：按 `@GroupKey` 的值分组，每组按 `@MapKey` 成一个 Map | 所有 `@GroupKey` 字段相等 | 玩家的物品、任务、邮件 |
| `LogRepository<E>` | 不缓存 | 从不读回 | 物品流水、登录日志 |

只依赖 managame-common。这个项目不认识任何数据库：`EntityMapper` 负责加载和写入实体，`LogWriter` 负责写日志，由应用为
每个 Repository 选定：

| 项目 | 提供 |
| --- | --- |
| [managame-data-mysql](../managame-data-mysql/README.zh-CN.md) | `MysqlEntityMapper` 和 `MysqlLogWriter` |
| [managame-data-mongo](../managame-data-mongo/README.zh-CN.md) | `MongoEntityMapper` |
| 应用自己 | 任意 `LogWriter`，例如通过 HTTP 上报日志；或自己的 `EntityMapper` |

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
        .build();
Players players = data.repository(Players.class);

Player player = players.getOrCreate(id, Player::new);    // 数据库里没有时创建并插入
players.update(id, p -> p.levelUp());                    // 修改和登记一步完成
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

- **读取只碰内存。** 不在内存里的实体或组在调用线程上加载，等待数据库返回时会阻塞：在虚拟线程上没有问题（managame-runtime
  的 Route 默认就是虚拟线程），绝不能在 Netty EventLoop 上调用。一组数据总是整组加载，读和写都一样。`getGroup` 返回实时的
  只读视图；修改组要通过 Repository。
- **修改实体后要登记**：`update(entity)`，或通过 `update(id, change)` / `update(groupKey, mapKey, change)` 修改，这样不会
  忘记登记。写入的是写入那一刻实体的状态，由写入线程在那时读取。
- **实体的集合字段要线程安全**（ConcurrentHashMap、CopyOnWriteArrayList），因为写入线程可能在业务代码修改它们时读取。集合
  字段用普通 java.util 类初始化或没有初始化时，`build` 会记一条 WARNING。
- **同一实体一次只改一处。** 每个方法都可以在任何线程调用，但同一个实体的修改由应用保证串行，例如放在实体所属的 Route 上
  执行。
- **内存知道哪些行存在。** 对内存中已有的 id 调用 `insert` 会抛 `IllegalStateException`；对已知不存在的 id 调用 `update`
  会抛异常，调用 `delete` 则什么都不做。`get` 一个数据库里没有的 id 时会记住这一点，再次询问不会再查数据库。修改不在内存里的
  id 从不读取数据库。
- **离开内存。** 超过 `cacheExpire`（30 分钟）没有用到的实体或组会离开内存，被 `evict` 的也一样。之后再读会重新加载，并把
  还没写入的修改叠加在数据库返回的结果上：用的是同一批实例，所以重新加载永远不会比内存里的数据旧。

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
实体（删除一个从未进过内存的实体时是它的 id）或日志，以及原因。它一次只处理一个失败；默认记录一条错误日志。

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

## 自己的 EntityMapper

一个 `EntityMapper` 服务注册给它的所有实体类型：build 时每种类型调用一次 `prepare(EntityType)`，之后是 `load`、`loadGroup`
和 `write(type, operation, batch)`。`EntityType` 列出要保存的字段（`EntityField`：名字、类型、`valueType`、读、写，以及用于
读注解的 Java 字段）、key 字段，并能创建新实例。失败后重写同一批必须无害：一批在一个事务里写，或者每个实体都幂等地写。更新
时发现实体不存在应当失败。

## 配置

| 设置 | 默认值 | |
| --- | --- | --- |
| `repositories(EntityMapper, Collection)` | | 实体 Repository 及保存它们的 Mapper；也可以逐个列出 |
| `logRepositories(LogWriter, Collection)` | | 日志 Repository 及写它们的 Writer；也可以逐个列出 |
| `flushInterval` | 1 秒 | 多久写一次；崩溃时大约丢失这么多 |
| `batchSize` | 500 | 每批的修改或日志数 |
| `writeThreads` | 4 | 同时写入的实体和日志类型数 |
| `cacheExpire` | 30 分钟 | 未使用的实体或组在内存里保留多久 |
| `shutdownTimeout` | 30 秒 | close 最多尝试多久 |
| `logQueueCapacity` | 100,000 | 每个 LogRepository 最多等待的日志数 |
| `errorHandler` | 记日志 | 接收被放弃的数据 |

`build` 在启动任何东西之前读取所有实体类，并让每个 Mapper 和 Writer 做准备：类不合适抛 `IllegalArgumentException`，缺少
设置抛 `IllegalStateException`，Mapper 或 Writer 准备时抛出的异常原样抛出。加载失败抛 `DataException`（`DATA_LOAD_FAILED`）。

## 线程

业务线程读取并登记修改；一个名为 `game-data` 的线程发起每一轮并淘汰不用的条目，`game-data-write-N` 线程写入一轮里的各种
实体和日志。登记修改时只锁住一种类型六十四分之一的行，所以业务线程之间很少互相等待；业务线程做的任何事都不会等待写入。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common。测试运行在一个内存版的 `EntityMapper` 上，它表现得像数据库一样（保存行的副本，一批要么全写要么
不写，拒绝重复 id 和更新不存在的行），并能模拟连不上、确认丢失和拒绝坏数据。覆盖：
- 写入插入、更新和删除；只加载一次并保留实体；记住不存在的 id；
- 一行改多次只写一次，插入后删除不写，删除后插入被替换；
- 分组：加载、修改、多字段的 key，被淘汰的组带着未写入的修改回来；
- 不用的条目离开内存，被淘汰的实体以同一个实例带着未写入的修改回来；
- 通过应用自己的 Writer 写日志：按顺序写入，Writer 判为可重试的失败会保留，被拒的日志丢弃而其他照常写入，队列满时拒绝；
- 失败：坏行丢弃而其他照常写入，连不上数据库期间保留修改，确认丢失的插入重写而不产生重复，更新已不存在的行被报告，close
  交出未能写入的数据；
- 构建时拒绝不合适的类和注册，原样抛出 Mapper 的异常，以集合传入 Repository；
- 业务线程修改玩家和物品的同时，每隔几毫秒写入一轮。
