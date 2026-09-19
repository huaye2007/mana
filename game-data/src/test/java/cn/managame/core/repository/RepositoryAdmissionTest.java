package cn.managame.core.repository;

import cn.managame.core.DataException;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.write.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static cn.managame.support.DataTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RepositoryAdmissionTest {
    @Test void interruptedFullBufferDoesNotPublishAnUnacceptedInsert() {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var access = new Access() {
            @Override public BatchResult applyBatch(List<WriteOperation<?>> ops) {
                if (ops.getFirst().id().equals(1L)) { entered.countDown(); await(release); }
                return super.applyBatch(ops);
            }
        };
        try (var writer = engine(access, 1, 1, Duration.ofMillis(1), (ops, error) -> fail(error))) {
            var repo = new DefaultSingleRepository<Row, Long>(Row.class, new MetadataRegistry(), access, writer, Duration.ofMinutes(30), false);
            try {
                repo.insert(new Row(1)); await(entered); repo.insert(new Row(2));
                Thread.currentThread().interrupt();
                assertThrows(DataException.class, () -> repo.insert(new Row(3)));
                Thread.interrupted();
                assertTrue(repo.get(3L).isEmpty());
            } finally { Thread.interrupted(); release.countDown(); }
            writer.flush(); assertEquals(List.of(1L, 2L), access.saved);
        }
    }
    @Test void closedAdmissionLeavesSingleAndGroupCacheMembershipUnchanged() {
        var access = new Access();
        try (var writer = engine(access, 10, 1, Duration.ofMillis(1), (ops, error) -> fail(error))) {
            var registry = new MetadataRegistry();
            var single = new DefaultSingleRepository<Row, Long>(Row.class, registry, access, writer, Duration.ofMinutes(30), false);
            var group = new DefaultGroupRepository<Row, Long>(Row.class, registry, access, writer, Duration.ofMinutes(30), false);
            var first = new Row(1); var second = new Row(2);
            single.insert(first); var view = group.getGroup(1L); group.insert(second); writer.flush(); writer.stopAccepting();
            assertThrows(IllegalStateException.class, () -> single.deleteById(1L));
            assertSame(first, single.get(1L).orElseThrow());
            assertThrows(IllegalStateException.class, () -> single.update(new Row(1)));
            assertSame(first, single.get(1L).orElseThrow());
            assertThrows(IllegalStateException.class, () -> group.insert(new Row(3)));
            assertThrows(IllegalStateException.class, () -> group.update(new Row(2)));
            assertThrows(IllegalStateException.class, () -> group.delete(second));
            assertThrows(IllegalStateException.class, () -> group.deleteGroup(1L));
            assertSame(view, group.getGroup(1L)); assertEquals(1, view.size()); assertSame(second, view.get(2L));
        }
    }
}
