package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_SAVE_FAILED;

import cn.managame.FrameworkErrorCodes;
import cn.managame.data.internal.ChangeSet;
import cn.managame.data.internal.ChangeWriter;
import cn.managame.data.internal.EntityKeys;
import cn.managame.data.internal.LogQueue;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import cn.managame.data.spi.LogWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The repositories of a game server and the threads that write what they change. The application picks where each
 * repository's data goes: an {@link EntityMapper} for entities, such as {@code MysqlEntityMapper} or
 * {@code MongoEntityMapper}, and a {@link LogWriter} for logs, such as {@code MysqlLogWriter} or its own:
 *
 * <pre>{@code
 * GameData data = GameData.builder()
 *         .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
 *         .logRepositories((logType, logs) -> logService.post(logType, logs), List.of(ItemLogs.class))
 *         .build();
 * Players players = data.repository(Players.class);
 * }</pre>
 *
 * <p>Every {@code flushInterval} one thread starts a round that writes what changed since the last one, rows changed
 * several times written once, in batches of {@code batchSize}, each entity or log type on one of {@code writeThreads}
 * threads. A crash loses at most about one interval of changes. Each repository keeps its entities or groups in a
 * Caffeine cache, which drops those unused for {@code cacheExpire} and the least used beyond {@code maxCacheEntries};
 * the same thread sets the caches' clock and cleans them up every round. Loads run on virtual threads of their own.
 */
public final class GameData implements AutoCloseable {
    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_CACHE_EXPIRE = Duration.ofMinutes(30);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int DEFAULT_WRITE_THREADS = 4;
    public static final int DEFAULT_LOG_QUEUE_CAPACITY = 100_000;
    public static final long DEFAULT_MAX_CACHE_ENTRIES = 1_000_000;
    private static final System.Logger LOG = System.getLogger("cn.managame.data");
    private final Map<Class<?>, Object> repositories;
    private final List<SingleRepository<?, ?>> singleRepositories;
    private final List<GroupRepository<?, ?>> groupRepositories;
    private final List<LogRepository<?>> logRepositories;
    private final ChangeWriter writer;
    private final AtomicLong clock;
    private final ScheduledThreadPoolExecutor scheduler;
    private final long flushIntervalNanos, shutdownTimeoutNanos;
    private volatile boolean closed;

    private GameData(Builder builder, Map<Class<?>, Object> repositories, ChangeWriter writer, AtomicLong clock) {
        this.repositories = Map.copyOf(repositories);
        List<SingleRepository<?, ?>> singles = new ArrayList<>();
        List<GroupRepository<?, ?>> groups = new ArrayList<>();
        List<LogRepository<?>> logs = new ArrayList<>();
        for (Object repository : repositories.values()) {
            if (repository instanceof SingleRepository<?, ?> single) singles.add(single);
            if (repository instanceof GroupRepository<?, ?> group) groups.add(group);
            if (repository instanceof LogRepository<?> log) logs.add(log);
        }
        singleRepositories = List.copyOf(singles);
        groupRepositories = List.copyOf(groups);
        logRepositories = List.copyOf(logs);
        this.writer = writer;
        this.clock = clock;
        flushIntervalNanos = builder.flushInterval.toNanos();
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();
        scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("game-data").factory());
        scheduler.scheduleWithFixedDelay(this::writeAndCleanUp, flushIntervalNanos, flushIntervalNanos, TimeUnit.NANOSECONDS);
    }

    public static Builder builder() { return new Builder(); }

    /** The repository of this class, as registered with the builder. */
    public <R> R repository(Class<R> type) {
        Object repository = repositories.get(type);
        if (repository == null) throw new IllegalArgumentException("no repository " + type.getName());
        return type.cast(repository);
    }

    /**
     * Writes every change and log made before the call, now, on the write threads, and returns when they are written.
     * Throws {@link DataException} ({@code DATA_SAVE_FAILED}) when some failed: those failing for a retryable reason
     * wait for the next round, the others went to the DataErrorHandler. Use it before another process reads the data,
     * such as when a player moves to another server.
     */
    public void flush() {
        if (closed) throw new IllegalStateException("GameData is closed");
        ChangeWriter.Round round = writer.writeAll();
        int dropped = round.dropped(), unwritten = round.unwritten();
        if (dropped > 0 || unwritten > 0)
            throw new DataException(DATA_SAVE_FAILED, "flush left " + unwritten + " changes for the next round after failures and dropped "
                    + dropped + ", which went to the DataErrorHandler", null);
    }

    /**
     * What each repository holds and what the rounds did with it since build, read now: repositories by id, grouped ones
     * and log repositories, each in the order registered. Any thread may call it, closed or not; each number is read on
     * its own, so they need not be from the same instant. Counting the changes waiting briefly takes each lock business
     * threads take to record one.
     */
    public DataStats stats() {
        List<EntityStats> entities = new ArrayList<>(singleRepositories.size() + groupRepositories.size());
        for (SingleRepository<?, ?> repository : singleRepositories) entities.add(repository.stats());
        for (GroupRepository<?, ?> repository : groupRepositories) entities.add(repository.stats());
        List<LogStats> logs = new ArrayList<>(logRepositories.size());
        for (LogRepository<?> repository : logRepositories) logs.add(repository.stats());
        return new DataStats(entities, logs);
    }

    /**
     * Refuses further changes and logs, then writes what is waiting, trying again every flush interval for up to
     * {@code shutdownTimeout} while writes fail. What is still not written then goes, one by one, to the
     * DataErrorHandler, and close throws {@link DataException} ({@code DATA_SAVE_FAILED}). Reads keep working.
     */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        writer.refuseChanges();
        scheduler.shutdown();
        long deadline = System.nanoTime() + shutdownTimeoutNanos;
        boolean interrupted = false;
        while (true) {
            ChangeWriter.Round round = writer.writeAllBefore(deadline);
            if (writer.isEmpty()) break;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            if (round.unwritten() == 0) continue;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(flushIntervalNanos, remaining));
            } catch (InterruptedException e) {
                interrupted = true;
                break;
            }
        }
        int notWritten = writer.dropAll(new IllegalStateException("GameData closed before this was written"));
        writer.shutdown();
        if (interrupted) Thread.currentThread().interrupt();
        if (notWritten > 0)
            throw new DataException(DATA_SAVE_FAILED, notWritten + " changes were not written before close; each went to the DataErrorHandler", null);
    }

    /** Runs every flush interval on the scheduler's thread. */
    private void writeAndCleanUp() {
        try {
            clock.set(System.nanoTime());
            if (!closed) writer.writeAll();
            for (SingleRepository<?, ?> repository : singleRepositories) repository.cleanUp();
            for (GroupRepository<?, ?> repository : groupRepositories) repository.cleanUp();
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "data write round failed", e);
        }
    }

    public static final class Builder {
        /** Each repository class with the EntityMapper or LogWriter registered for it, in registration order. */
        private final Map<Class<?>, Object> registrations = new LinkedHashMap<>();
        private Duration flushInterval = DEFAULT_FLUSH_INTERVAL, cacheExpire = DEFAULT_CACHE_EXPIRE,
                shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        private int batchSize = DEFAULT_BATCH_SIZE, writeThreads = DEFAULT_WRITE_THREADS, logQueueCapacity = DEFAULT_LOG_QUEUE_CAPACITY;
        private long maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES;
        private DataErrorHandler errorHandler = failure -> LOG.log(System.Logger.Level.ERROR,
                FrameworkErrorCodes.describe(failure.errorCode()) + ": " + failure.operation() + " of "
                        + failure.entityType().getName() + " dropped: " + failure.value(), failure.cause());

        private Builder() {}

        /**
         * Repository classes whose entities {@code mapper} loads and writes, each directly extending SingleRepository or
         * GroupRepository with concrete type arguments and having a no-argument constructor; the builder creates them.
         */
        public Builder repositories(EntityMapper mapper, Collection<? extends Class<?>> types) {
            return register(Objects.requireNonNull(mapper, "mapper"), types);
        }

        /** {@link #repositories(EntityMapper, Collection)} for classes listed one by one. */
        public Builder repositories(EntityMapper mapper, Class<?>... types) { return repositories(mapper, List.of(types)); }

        /** Log repository classes whose logs {@code writer} writes, each directly extending LogRepository. */
        public Builder logRepositories(LogWriter writer, Collection<? extends Class<?>> types) {
            return register(Objects.requireNonNull(writer, "writer"), types);
        }

        /** {@link #logRepositories(LogWriter, Collection)} for classes listed one by one. */
        public Builder logRepositories(LogWriter writer, Class<?>... types) { return logRepositories(writer, List.of(types)); }

        /** How often changes are written; a crash loses about this much. 1 second by default. */
        public Builder flushInterval(Duration interval) {
            flushInterval = positive(interval, "flushInterval");
            return this;
        }

        /** Changes or logs written per batch; 500 by default. */
        public Builder batchSize(int size) {
            batchSize = positive(size, "batchSize");
            return this;
        }

        /** Entity and log types written at once; 4 by default. */
        public Builder writeThreads(int threads) {
            writeThreads = positive(threads, "writeThreads");
            return this;
        }

        /**
         * How long an entity or group stays in memory unused; 30 minutes by default. The caches read a clock set every
         * flush interval, so an entry may stay up to one interval longer.
         */
        public Builder cacheExpire(Duration expire) {
            cacheExpire = positive(expire, "cacheExpire");
            return this;
        }

        /**
         * Entities, or groups, one repository keeps in memory at most; beyond it the cache drops those least likely to be
         * used again. 1,000,000 by default. Changes of a dropped entity are written all the same, and a reload sees them.
         */
        public Builder maxCacheEntries(long entries) {
            if (entries <= 0) throw new IllegalArgumentException("maxCacheEntries must be positive");
            maxCacheEntries = entries;
            return this;
        }

        /** How long close keeps trying to write; 30 seconds by default. */
        public Builder shutdownTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "shutdownTimeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("shutdownTimeout must not be negative");
            shutdownTimeout = timeout;
            return this;
        }

        /** Logs one LogRepository holds waiting at most; more are refused. 100,000 by default. */
        public Builder logQueueCapacity(int capacity) {
            logQueueCapacity = positive(capacity, "logQueueCapacity");
            return this;
        }

        /** Receives every change and log given up on; logs it as an error by default. */
        public Builder errorHandler(DataErrorHandler handler) {
            errorHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Reads every entity class, has each mapper and writer prepare for its types, then starts writing. A
         * class that does not fit throws IllegalArgumentException, a missing setting IllegalStateException, and what a
         * mapper or writer throws while preparing comes out as it is; nothing is started then.
         */
        public GameData build() {
            if (registrations.isEmpty()) throw new IllegalStateException("no repositories");
            Map<Class<?>, Object> repositories = new LinkedHashMap<>();
            Set<Class<?>> entityClasses = new HashSet<>();
            List<ChangeSet> changeSets = new ArrayList<>();
            Map<LogRepository<?>, LogQueue> logRepositories = new LinkedHashMap<>();
            // Each load on a virtual thread of its own: it blocks while the database answers, outside the caches' locks.
            Executor loads = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("game-data-load-", 0).factory());
            // The caches' clock, read on every access and set every flush interval: a volatile read costs less than
            // System.nanoTime, and expiry needs no finer time.
            AtomicLong clock = new AtomicLong(System.nanoTime());
            for (Map.Entry<Class<?>, Object> registration : registrations.entrySet()) {
                Class<?> type = registration.getKey();
                if (!(type.getGenericSuperclass() instanceof ParameterizedType superclass))
                    throw new IllegalArgumentException(type.getName() + " must directly extend SingleRepository, GroupRepository or LogRepository");
                Type base = superclass.getRawType();
                Type[] arguments = superclass.getActualTypeArguments();
                if (!(arguments[arguments.length - 1] instanceof Class<?> entityClass))
                    throw new IllegalArgumentException(type.getName() + " needs a concrete entity class");
                if (!entityClasses.add(entityClass)) throw new IllegalArgumentException(entityClass.getName() + " has two repositories");
                Object repository = newInstance(type);
                repositories.put(type, repository);
                if (registration.getValue() instanceof LogWriter logWriter) {
                    // A log is never loaded back, so any class will do, records included; its writer decides what it reads.
                    if (base != LogRepository.class) throw new IllegalArgumentException(type.getName() + " is registered with a LogWriter but is no LogRepository");
                    logWriter.prepare(entityClass);
                    logRepositories.put((LogRepository<?>) repository, new LogQueue(entityClass, logWriter, logQueueCapacity));
                    continue;
                }
                EntityType entityType = EntityType.of(entityClass);
                EntityKeys keys = new EntityKeys(entityType);
                EntityMapper mapper = (EntityMapper) registration.getValue();
                boolean single = base == SingleRepository.class, group = base == GroupRepository.class;
                if (!single && !group)
                    throw new IllegalArgumentException(type.getName() + " is registered with an EntityMapper but is no SingleRepository or GroupRepository");
                boolean keyedById = entityType.groupKeys().isEmpty() && entityType.mapKeys().isEmpty();
                boolean keyedInGroups = !entityType.groupKeys().isEmpty() && !entityType.mapKeys().isEmpty();
                if (entityType.id() == null || (single ? !keyedById : !keyedInGroups))
                    throw new IllegalArgumentException(entityClass.getName() + (single ? " needs one @Id and no @GroupKey or @MapKey"
                            : " needs one @Id, a @GroupKey and a @MapKey"));
                if (arguments[0] != keys.keyType())
                    throw new IllegalArgumentException(type.getName() + " must be keyed by " + keys.keyType().getSimpleName() + ", not " + arguments[0].getTypeName());
                keys.warnNonThreadSafeCollections();
                mapper.prepare(entityType);
                ChangeSet changes = new ChangeSet(entityType, mapper);
                changeSets.add(changes);
                if (single) ((SingleRepository<?, ?>) repository).bind(changes, keys, cacheExpire, maxCacheEntries, clock::get, loads);
                else ((GroupRepository<?, ?>) repository).bind(changes, keys, cacheExpire, maxCacheEntries, clock::get, loads);
            }
            ChangeWriter writer = new ChangeWriter(changeSets, List.copyOf(logRepositories.values()), batchSize, writeThreads, errorHandler);
            logRepositories.forEach((repository, queue) -> repository.bind(queue, writer));
            return new GameData(this, repositories, writer, clock);
        }

        private Builder register(Object target, Collection<? extends Class<?>> types) {
            for (Class<?> type : types)
                if (registrations.putIfAbsent(Objects.requireNonNull(type, "repository"), target) != null)
                    throw new IllegalArgumentException("repository registered twice: " + type.getName());
            return this;
        }

        private static Object newInstance(Class<?> type) {
            try {
                return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .findConstructor(type, MethodType.methodType(void.class)).invoke();
            } catch (Throwable e) {
                throw new IllegalArgumentException(type.getName() + " needs a no-argument constructor", e);
            }
        }

        private static Duration positive(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static int positive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }
    }
}
