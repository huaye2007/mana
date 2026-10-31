[English](README.md) | 中文

# managame-data-mongo

把 [managame-data](../managame-data/README.zh-CN.md) 的实体存进 MongoDB：`MongoEntityMapper` 用于 SingleRepository 和
GroupRepository。依赖 managame-data 和 MongoDB 同步驱动。日志由应用用任意 `LogWriter` 自己决定写到哪里。

```java
MongoDatabase database = MongoClients.create("mongodb://127.0.0.1:27017").getDatabase("game");
GameData data = GameData.builder()
        .repositories(new MongoEntityMapper(database), List.of(Players.class, Mails.class))
        .build();
```

## 集合

```java
@Collection(name = "player")
public class Player {
    @Id private long id;                                            // 存为 _id
    @Field(name = "nick") private String name;                     // 字段名另取
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // 存为文档
    Player() {}
}
```

- 实体需要 `@Collection(name)`；没有时 `prepare` 抛 `IllegalArgumentException`。`@Id` 存为 `_id`，其他要保存的字段名是字段名
  的下划线形式，或由 `@Field(name)` 指定。注解在 `cn.managame.data.mongo.annotation` 包里，Mapper 在 `cn.managame.data.mongo`。
- 集合和 Map（key 为 String、Integer 或 Long）存为由元素组成的数组和文档，加载时创建成字段初始化时的那个类。byte、short、
  float、char 分别存为 int32、double 和 string。其他类型来自数据库的 codec registry，应用在那里注册自己的类。
- 文档里缺少的字段保留构造器给的值，所以加字段不需要迁移数据。

## 读取

`load` 按 `_id` 查文档，`loadGroup` 按分组字段查，`loadAll`（`getAll` 用它）用一条 `_id $in` 查询，`loadTable`（整表常驻的
Repository 用它）读出全部文档。

## 写入

每个写入都是幂等的，所以写到一半失败的批次直接整批重写，不需要记录哪些文档已经写进去：

| 操作 | 写法 |
| --- | --- |
| INSERT、DELETE_INSERT | 替换这个 `_id` 的文档，不存在就创建 |
| UPDATE | 替换这个 `_id` 的文档；匹配到的文档数少于这批的条数时失败 |
| DELETE | 按这批的 id `deleteMany` |

因此插入也会替换别的写入方用同一 `_id` 存的文档；id 由应用保证唯一。失败的 UPDATE 批次会拆成单条，缺失的那个文档交给
DataErrorHandler。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-data。测试在不需要服务器的情况下覆盖各种字段的编码和解码、保留初始化时的集合类，以及拒绝
没有 `@Collection` 的类。提供地址时（`MANAGAME_DATA_MONGO_URI=mongodb://127.0.0.1:27017`），`MongoIntegrationTest` 会在真实
MongoDB 上运行，使用自己新建的库并在结束后删除。
