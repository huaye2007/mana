package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_SAVE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.managame.data.Fixtures.Player;
import cn.managame.data.Fixtures.Players;
import cn.managame.data.Fixtures.ReportedFailures;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What writing does when the database cannot be reached, refuses every write, loses an acknowledgement, or refuses a row. */
class FailureTest {
    private final MemoryMapper database = new MemoryMapper();
    private final ReportedFailures reported = new ReportedFailures();
    private final GameData data = GameData.builder().repositories(database, List.of(Players.class)).errorHandler(reported)
            .flushInterval(Duration.ofMinutes(1)).cacheExpire(Duration.ofMinutes(2)).shutdownTimeout(Duration.ZERO).build();
    private final Players players = data.repository(Players.class);

    @AfterEach
    void close() {
        database.unreachable = false;
        database.readOnly = false;
        try {
            data.close();
        } catch (DataException e) {
            // a test that leaves changes unwritten on purpose
        }
    }

    private List<Object> names() { return database.rows(Player.class).stream().map(row -> row.get("name")).toList(); }

    @Test
    void dropsARowItsDataBreaksAndWritesTheOthers() {
        database.badRows = entity -> ((Player) entity).name.length() > 8;
        players.insert(new Player(1, "ann"));
        Player longName = new Player(2, "a name longer than eight");
        players.insert(longName);
        players.insert(new Player(3, "cid"));
        DataException failure = assertThrows(DataException.class, data::flush);
        assertEquals(DATA_SAVE_FAILED, failure.errorCode());
        assertEquals(List.of("ann", "cid"), names());
        assertEquals(1, reported.all().size());
        DataFailure failed = reported.all().getFirst();
        assertSame(longName, failed.value());
        assertEquals(DataOperation.INSERT, failed.operation());
        assertInstanceOf(IllegalArgumentException.class, failed.cause());
    }

    @Test
    void keepsChangesWhileTheDatabaseCannotBeReached() {
        Player player = new Player(1, "ann");
        players.insert(player);
        database.unreachable = true;
        assertThrows(DataException.class, data::flush);
        player.level = 2;
        players.update(player);
        assertThrows(DataException.class, data::flush);
        database.unreachable = false;
        data.flush();
        assertEquals(2, database.rows(Player.class).getFirst().get("level"));
        assertEquals(List.of(), reported.all());
    }

    @Test
    void writesAgainAnInsertWhoseAcknowledgementWasLost() {
        players.insert(new Player(1, "ann"));
        database.failAfterNextWrite = true;   // the row is stored, but the write sees the connection fail
        assertThrows(DataException.class, data::flush);
        assertEquals(1, database.rows(Player.class).size());
        data.flush();   // written again as DELETE_INSERT, so the stored row is no duplicate
        assertEquals(List.of("ann"), names());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void reportsAnUpdateOfARowNoLongerStored() {
        database.put(Player.class, new Player(1, "ann"));
        database.put(Player.class, new Player(2, "bob"));
        Player gone = players.get(1L), stored = players.get(2L);
        database.remove(Player.class, 1L);   // another process deletes the row
        gone.level = 3;
        players.update(gone);
        stored.level = 3;
        players.update(stored);
        assertThrows(DataException.class, data::flush);
        assertEquals(3, database.rows(Player.class).getFirst().get("level"), "the other row of the batch is written");
        assertEquals(1, reported.all().size());
        assertSame(gone, reported.all().getFirst().value());
        assertEquals(DataOperation.UPDATE, reported.all().getFirst().operation());
    }

    @Test
    void keepsEveryRowOfABatchOfWhichNoneCanBeWritten() {
        players.insert(new Player(1, "ann"));
        players.insert(new Player(2, "bob"));
        database.readOnly = true;   // an error no mapper knows: nothing says whether the rows or the database fail
        assertThrows(DataException.class, data::flush);
        assertThrows(DataException.class, data::flush);
        assertEquals(List.of(), reported.all(), "no row of the batch was written, so none is given up");
        assertEquals(2, data.stats().entities().getFirst().waiting());
        database.readOnly = false;
        data.flush();
        assertEquals(List.of("ann", "bob"), names());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void keepsARowThatFailsAloneUntilAnotherOfItsBatchIsWritten() {
        database.put(Player.class, new Player(1, "ann"));
        database.put(Player.class, new Player(2, "bob"));
        database.badRows = entity -> ((Player) entity).name.length() > 8;
        Player longName = players.get(1L);
        longName.name = "a name longer than eight";
        players.update(longName);
        assertThrows(DataException.class, data::flush);
        assertEquals(List.of(), reported.all(), "alone in its batch, its failure may as well be the database's");
        assertEquals(1, data.stats().entities().getFirst().waiting());

        players.update(2L, player -> player.level = 2);
        assertThrows(DataException.class, data::flush);
        assertEquals(1, reported.all().size(), "the other row was written, so the database works and the row is refused for what it is");
        assertSame(longName, reported.all().getFirst().value());
        assertEquals(0, data.stats().entities().getFirst().waiting());
        assertEquals(List.of("ann", "bob"), names());
    }

    @Test
    void writesAChangeMadeWhileItsRowIsWrittenInTheNextRound() throws Exception {
        Player player = new Player(1, "ann");
        players.insert(player);
        database.writeGate = new CountDownLatch(1);
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            Future<?> round = thread.submit(data::flush);
            while (database.writes.get() == 0) Thread.onSpinWait();   // the round has read the player and waits to store it
            player.level = 2;
            players.update(player);
            database.writeGate.countDown();
            round.get();
        }
        assertEquals(1, database.rows(Player.class).getFirst().get("level"), "as the round read it");
        assertEquals(1, data.stats().entities().getFirst().waiting(), "the change made meanwhile");
        data.flush();
        assertEquals(2, database.rows(Player.class).getFirst().get("level"));
        assertEquals(List.of(), reported.all());
    }

    @Test
    void deletesARowDeletedWhileItsInsertIsWritten() throws Exception {
        players.insert(new Player(1, "ann"));
        database.writeGate = new CountDownLatch(1);
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            Future<?> round = thread.submit(data::flush);
            while (database.writes.get() == 0) Thread.onSpinWait();
            players.delete(1L);   // not dropped with the insert: that insert is on its way to the database
            database.writeGate.countDown();
            round.get();
        }
        assertEquals(List.of("ann"), names());
        data.flush();
        assertEquals(List.of(), names());
        assertEquals(List.of(), reported.all());
    }

    @Test
    void closeHandsOverWhatItCouldNotWrite() {
        Player player = new Player(1, "ann");
        players.insert(player);
        database.unreachable = true;
        DataException failure = assertThrows(DataException.class, data::close);
        assertEquals(DATA_SAVE_FAILED, failure.errorCode());
        assertEquals(1, reported.all().size());
        assertSame(player, reported.all().getFirst().value());
        assertInstanceOf(IllegalStateException.class, reported.all().getFirst().cause());
    }

    @Test
    void refusesAChangeThatCannotFollowTheWaitingOne() {
        database.put(Player.class, new Player(1, "ann"));
        Player player = players.get(1L);
        players.delete(1L);
        players.evict(1L);   // memory no longer knows the row is gone, but its waiting delete does
        assertThrows(IllegalStateException.class, () -> players.update(player));
    }
}
