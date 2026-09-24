package cn.managame.docdb.mongodb;

import cn.managame.core.write.*;
import com.mongodb.*;
import com.mongodb.bulk.*;
import com.mongodb.client.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class MongoBatchFailureTest {
    private static WriteOperation<?> update(long id) {
        return WriteOperation.update(METADATA, new Row(id), id, 1L);
    }
    private static MongoBulkWriteException error(int matched, int index, boolean concern) {
        return new MongoBulkWriteException(
                BulkWriteResult.acknowledged(0, matched, 0, 0, List.of(), List.of()),
                index < 0 ? List.of() : List.of(new BulkWriteError(11000, "duplicate", new BsonDocument(), index)),
                concern ? new WriteConcernError(64, "WriteConcernFailed", "timeout", new BsonDocument()) : null,
                new ServerAddress("localhost", 27017), Set.of());
    }
    private static MongoDataAccess access(MongoBulkWriteException failure) {
        var collection = proxy(MongoCollection.class, (p, m, a) -> {
            if (m.getName().equals("bulkWrite")) throw failure;
            throw new AssertionError(m);
        });
        var database = proxy(MongoDatabase.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> "test";
            case "getCollection" -> collection;
            default -> throw new AssertionError(m);
        });
        var client = proxy(MongoClient.class, (p, m, a) -> switch (m.getName()) {
            case "getDatabase" -> database;
            case "close" -> null;
            default -> throw new AssertionError(m);
        });
        return new MongoDataAccess(client, "test");
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static BatchResult result(MongoBulkWriteException failure, List<WriteOperation<?>> operations) {
        try (var access = access(failure)) {
            return assertThrows(BatchWriteException.class, () -> access.applyBatch(operations)).result();
        }
    }
    private static void states(BatchResult result, BatchItemState... expected) {
        assertEquals(expected.length, result.operationCount());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], result.stateAt(i), "operation " + i);
    }

    @Test void missingUpdateBeforeIndexedFailureIsNotSuccessful() {
        states(result(error(0, 1, false), List.of(update(1), insert(2), update(3))),
                BatchItemState.UNKNOWN, BatchItemState.FAILED, BatchItemState.UNEXECUTED);
    }
    @Test void allExecutedUpdatesAreUnknownWhenAggregateCountCannotLocateTheMiss() {
        states(result(error(1, 3, false), List.of(update(1), insert(2), update(3), insert(4))),
                BatchItemState.UNKNOWN, BatchItemState.SUCCESS, BatchItemState.UNKNOWN, BatchItemState.FAILED);
    }
    @Test void matchedButUnmodifiedPrefixSucceedsAndUnexecutedUpdatesAreNotCounted() {
        states(result(error(1, 1, false), List.of(update(1), insert(2), update(3))),
                BatchItemState.SUCCESS, BatchItemState.FAILED, BatchItemState.UNEXECUTED);
    }
    @Test void firstOperationFailureLeavesAllLaterUpdatesUnexecuted() {
        states(result(error(0, 0, false), List.of(insert(1), update(2))),
                BatchItemState.FAILED, BatchItemState.UNEXECUTED);
    }
    @Test void writeConcernFailureKeepsPrefixUnknownEvenWhenAllUpdatesMatched() {
        states(result(error(1, 2, true), List.of(update(1), insert(2), insert(3), update(4))),
                BatchItemState.UNKNOWN, BatchItemState.UNKNOWN, BatchItemState.FAILED, BatchItemState.UNEXECUTED);
        states(result(error(1, -1, true), List.of(update(1), insert(2))),
                BatchItemState.UNKNOWN, BatchItemState.UNKNOWN);
    }
    @Test void writerReportsMissingPrefixUpdateToFailureHandlerWithoutRetrying() {
        try (var access = access(error(0, 1, false))) {
            var logged = new ArrayList<Long>();
            try (var writer = new WriteBehindEngine(access, 10, 3, Duration.ofSeconds(1), 3,
                    (operations, failure) -> operations.forEach(op -> logged.add((Long) op.id())))) {
                writer.submit(update(1)); writer.submit(insert(2)); writer.submit(insert(3));
                writer.flush();
                assertEquals(List.of(1L, 2L, 3L), logged.stream().sorted().toList());
            }
        }
    }
}
