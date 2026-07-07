package cn.managame.jpa.docdb.mongo;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.WriteConcernError;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import cn.managame.jpa.core.exception.ConnectionException;
import cn.managame.jpa.core.exception.DuplicateKeyException;
import cn.managame.jpa.core.exception.GameJpaException;
import cn.managame.jpa.core.exception.RetriableWriteException;
import cn.managame.jpa.docdb.annotation.Document;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.docdb.metadata.DocEntityMetadataResolver;
import org.bson.BsonDocument;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

/**
 * Mongo 驱动异常 → game-jpa 异常分类的纯逻辑单元测试。
 * 与 FlushScheduler 的重试分流约定对齐：网络 / 选主切换 / 超时 → RetriableWriteException（重试），
 * 重复键 → DuplicateKeyException，其余确定性失败 → GameJpaException（异步路径丢弃）。
 * 真实 MongoDB 行为由集成测试覆盖。
 */
public class MongoExceptionTranslationTest {

    private static final ServerAddress ADDRESS = new ServerAddress();

    private static GameJpaException translate(com.mongodb.MongoException e) {
        return MongoDocExecutor.translateMongoException("save", "profiles", "default", 7L, e);
    }

    @Test
    public void socketErrorsAreRetriableConnectionErrors() {
        GameJpaException translated = translate(
                new MongoSocketOpenException("connect failed", ADDRESS, new IOException("reset")));
        assertInstanceOf(ConnectionException.class, translated);
        assertInstanceOf(RetriableWriteException.class, translated);
        assertEquals("default", ((ConnectionException) translated).dataSourceName());
    }

    @Test
    public void serverSelectionTimeoutIsRetriable() {
        assertInstanceOf(RetriableWriteException.class,
                translate(new MongoTimeoutException("no primary")));
    }

    @Test
    public void notPrimaryCommandErrorIsRetriable() {
        MongoCommandException notPrimary = new MongoCommandException(
                BsonDocument.parse("{ok: 0.0, code: 10107, errmsg: 'not writable primary'}"), ADDRESS);
        assertInstanceOf(RetriableWriteException.class, translate(notPrimary));
    }

    @Test
    public void retryableWriteErrorLabelIsRetriable() {
        MongoCommandException labeled = new MongoCommandException(
                BsonDocument.parse("{ok: 0.0, code: 112, errmsg: 'write conflict',"
                        + " errorLabels: ['RetryableWriteError']}"), ADDRESS);
        assertInstanceOf(RetriableWriteException.class, translate(labeled));
    }

    @Test
    public void deterministicCommandErrorIsDropped() {
        MongoCommandException unauthorized = new MongoCommandException(
                BsonDocument.parse("{ok: 0.0, code: 13, errmsg: 'unauthorized'}"), ADDRESS);
        GameJpaException translated = translate(unauthorized);
        assertFalse(translated instanceof RetriableWriteException);
    }

    @Test
    public void duplicateKeyWriteErrorMapsToDuplicateKeyException() {
        MongoWriteException dup = new MongoWriteException(
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument()), ADDRESS, Set.of());
        GameJpaException translated = translate(dup);
        assertInstanceOf(DuplicateKeyException.class, translated);
        assertEquals(7L, ((DuplicateKeyException) translated).entityId());
        assertFalse(translated instanceof RetriableWriteException);
    }

    @Test
    public void deterministicWriteErrorIsNotRetriable() {
        MongoWriteException validation = new MongoWriteException(
                new WriteError(121, "Document failed validation", new BsonDocument()), ADDRESS, Set.of());
        assertFalse(translate(validation) instanceof RetriableWriteException);
    }

    @Test
    public void transientWriteErrorIsRetriable() {
        MongoWriteException stepdown = new MongoWriteException(
                new WriteError(189, "primary stepped down", new BsonDocument()), ADDRESS, Set.of());
        assertInstanceOf(RetriableWriteException.class, translate(stepdown));
    }

    @Test
    public void bulkAllDuplicateKeyMapsToDuplicateKeyException() {
        assertInstanceOf(DuplicateKeyException.class, translate(bulk(
                List.of(bulkError(11000, 0), bulkError(11000, 1)), null)));
    }

    @Test
    public void bulkTransientErrorsAreRetriable() {
        assertInstanceOf(RetriableWriteException.class, translate(bulk(
                List.of(bulkError(91, 0)), null)));
    }

    @Test
    public void bulkMixedErrorsAreDeterministic() {
        GameJpaException translated = translate(bulk(
                List.of(bulkError(11000, 0), bulkError(121, 1)), null));
        assertFalse(translated instanceof RetriableWriteException);
        assertFalse(translated instanceof DuplicateKeyException);
    }

    @Test
    public void bulkWriteConcernErrorIsRetriable() {
        WriteConcernError writeConcernError =
                new WriteConcernError(64, "WriteConcernTimeout", "waiting for replication timed out",
                        new BsonDocument());
        assertInstanceOf(RetriableWriteException.class, translate(bulk(List.of(), writeConcernError)));
    }

    @Test
    public void executorWrapsDriverExceptionsOnWritePath() {
        MongoDocExecutor executor = new MongoDocExecutor(throwingDatabase(
                new MongoSocketOpenException("connect failed", ADDRESS, new IOException("reset"))));
        var metadata = new DocEntityMetadataResolver().resolve(Item.class);

        GameJpaException translated = assertThrows(ConnectionException.class,
                () -> executor.batchSave(metadata, List.of(new Item(1L)), null));
        assertInstanceOf(RetriableWriteException.class, translated);

        assertThrows(ConnectionException.class,
                () -> executor.insert(metadata, new Item(2L), null));
    }

    private static MongoBulkWriteException bulk(List<BulkWriteError> errors, WriteConcernError writeConcernError) {
        return new MongoBulkWriteException(BulkWriteResult.unacknowledged(), errors,
                writeConcernError, ADDRESS, Set.of());
    }

    private static BulkWriteError bulkError(int code, int index) {
        return new BulkWriteError(code, "error " + code, new BsonDocument(), index);
    }

    /** 所有集合操作都抛给定异常的 MongoDatabase 桩。 */
    private static MongoDatabase throwingDatabase(RuntimeException failure) {
        return (MongoDatabase) Proxy.newProxyInstance(
                MongoDatabase.class.getClassLoader(),
                new Class<?>[] { MongoDatabase.class },
                (proxy, method, args) -> {
                    if ("getCollection".equals(method.getName())) {
                        return Proxy.newProxyInstance(
                                MongoCollection.class.getClassLoader(),
                                new Class<?>[] { MongoCollection.class },
                                (p, m, a) -> {
                                    throw failure;
                                });
                    }
                    return null;
                });
    }

    @Document(collection = "items")
    private static class Item {
        @Id
        private long id;

        Item(long id) {
            this.id = id;
        }
    }
}
