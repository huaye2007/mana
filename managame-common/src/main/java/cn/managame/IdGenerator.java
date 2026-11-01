package cn.managame;

/**
 * Makes unique long ids. The framework has two:
 *
 * <ul>
 *   <li>{@link SnowflakeIdGenerator}: unique across servers and their merges, ordered by time, holding some data of the
 *       application's own, laid out by an {@link IdLayout}; for what is stored, such as items, mails and guilds.</li>
 *   <li>{@link SequenceIdGenerator}: counting up in this process only; for what lives no longer than the process, such
 *       as rooms, battles or drops on a map.</li>
 * </ul>
 *
 * An application may give its own, for example ids reserved in blocks from its database or a service:
 * {@code IdGenerator ids = () -> idService.next();}. One used from several threads must be safe to call from them.
 */
@FunctionalInterface
public interface IdGenerator {
    /** A new id, never one this generator gave before. */
    long generateId();
}
