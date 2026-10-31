package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOG_SAVE_FAILED;
import static cn.managame.FrameworkErrorCodes.DATA_SAVE_FAILED;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The repositories of a game server and the threads that write what they change. The application picks where each
 * repository's data goes: an {@link EntityMapper} for entities, such as {@code MysqlEntityMapper} or
 * {@code MongoEntityMapper}, and a {@link LogWriter} for logs, such as {@code MysqlLogWriter} or its own:
 *
 * <pre>{@code
 * GameData data = GameData.builder()
 *         .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
 *         .logRepositories((logType, logs) -> logService.post(logType, logs), List.of(ItemLogs.class))
 *         .errorHandler(failures::save)
 *         .build();
 * Players players = data.repository(Players.class);
 * }</pre>
 *
 * <p>Every {@code flushInterval} one thread starts a round that writes the entity changes made since the last one,
 * rows changed several times written once, in batches of {@code batchSize}, each entity type on one of
 * {@code writeThreads} threads. A crash loses at most about one interval of changes. Each repository keeps its entities
 * or groups in a Caffeine cache, which drops those unused for {@code cacheExpire}; the same thread cleans the caches up
 * every round. Logs are written apart, in rounds of their own on a thread of their own, one log type after another, so
 * a log destination that is slow or cannot be reached never delays or fails the writing of entities.
 */
public final class GameData implements AutoCloseable {
    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(1);
    public static final Duration DEFAULT_CACHE_EXPIRE = Duration.ofMinutes(30);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final int DEFAULT_WRITE_THREADS = 4;
    public static final int DEFAULT_LOG_QUEUE_CAPACITY = 100_000;
    private static final System.Logger LOG = System.getLogger("cn.managame.data");
    private final Map<Class<?>, Object> repositories;
    private final List<SingleRepository<?, ?>> singleRepositories;
    private final List<GroupRepository<?, ?>> groupRepositories;
    private final List<LogRepository<?>> logRepositories;
    /** Write the entities' changes and the logs, each in rounds of its own, on threads of its own. */
    private final ChangeWriter writer, logWriter;
    private final ScheduledThreadPoolExecutor scheduler, logScheduler;
    private final long flushIntervalNanos, shutdownTimeoutNanos;
    private volatile boolean closed;

    private GameData(Builder builder, Map<Class<?>, Object> repositories, ChangeWriter writer, ChangeWriter logWriter) {
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
        this.logWriter = logWriter;
        flushIntervalNanos = builder.flushInterval.toNanos();
        shutdownTimeoutNanos = builder.shutdownTimeout.toNanos();
        scheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("game-data").factory());
        scheduler.scheduleWithFixedDelay(this::writeAndCleanUp, flushIntervalNanos, flushIntervalNanos, TimeUnit.NANOSECONDS);
        logScheduler = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().name("game-data-log").factory());
        logScheduler.scheduleWithFixedDelay(this::writeLogs, flushIntervalNanos, flushIntervalNanos, TimeUnit.NANOSECONDS);
    }

    public static Builder builder() { return new Builder(); }

    /** The repository of this class, as registered with the builder. */
    public <R> R repository(Class<R> type) {
        Object repository = repositories.get(type);
        if (repository == null) throw new IllegalArgumentException("no repository " + type.getName());
        return type.cast(repository);
    }

    /**
     * Writes every entity change made before the call, now, on the write threads, and returns when they are written;
     * logs are not waited for. Throws {@link DataException} ({@code DATA_SAVE_FAILED}) when some failed: those failing for
     * a retryable reason wait for the next round, the others went to the DataErrorHandler. Use it before another process
     * reads the data, such as when a player moves to another server.
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
     * Writes every log inserted before the call, now, on this thread, and returns when they are written. Throws
     * {@link DataException} ({@code DATA_LOG_SAVE_FAILED}) when some failed: those failing for a retryable reason wait for
     * the next round, the others went to the DataErrorHandler.
     */
    public void flushLogs() {
        if (closed) throw new IllegalStateException("GameData is closed");
        ChangeWriter.Round round = logWriter.writeAll();
        int dropped = round.dropped(), unwritten = round.unwritten();
        if (dropped > 0 || unwritten > 0)
            throw new DataException(DATA_LOG_SAVE_FAILED, "flushLogs left " + unwritten + " logs for the next round after failures and dropped "
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
     * {@code shutdownTimeout} while writes fail; entities and logs at once, on threads of their own, so neither takes
     * the other's time. What is still not written then goes, one by one, to the DataErrorHandler, and close throws
     * {@link DataException} ({@code DATA_SAVE_FAILED}). Reads keep working.
     */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        writer.refuseChanges();
        logWriter.refuseChanges();
        scheduler.shutdown();
        logScheduler.shutdown();
        long deadline = System.nanoTime() + shutdownTimeoutNanos;
        FutureTask<Integer> logs = new FutureTask<>(() -> finish(logWriter, deadline));
        Thread.ofPlatform().name("game-data-log").start(logs);
        int notWritten = finish(writer, deadline);
        boolean interrupted = false;
        while (true) {
            try {
                notWritten += logs.get();
                break;
            } catch (InterruptedException e) {
                interrupted = true;   // the logs stop at the deadline in any case
            } catch (ExecutionException e) {
                LOG.log(System.Logger.Level.ERROR, "writing logs at close failed", e.getCause());
                break;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (notWritten > 0)
            throw new DataException(DATA_SAVE_FAILED, notWritten + " changes and logs were not written before close; each went to the DataErrorHandler", null);
    }

    /**
     * Writes what {@code writer} has waiting, trying again every flush interval until {@code deadline} while writes fail,
     * then hands what is left to the DataErrorHandler and stops the writer; returns how many it handed over.
     */
    private int finish(ChangeWriter writer, long deadline) {
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
        return notWritten;
    }

    /** Runs every flush interval on the scheduler's thread. */
    private void writeAndCleanUp() {
        try {
            if (!closed) writer.writeAll();
            for (SingleRepository<?, ?> repository : singleRepositories) repository.cleanUp();
            for (GroupRepository<?, ?> repository : groupRepositories) repository.cleanUp();
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "data write round failed", e);
        }
    }

    /** Runs every flush interval on the log scheduler's thread, apart from the entities' rounds. */
    private void writeLogs() {
        try {
            if (!closed) logWriter.writeAll();
        } catch (Throwable e) {
            LOG.log(System.Logger.Level.ERROR, "log write round failed", e);
        }
    }

    public static final class Builder {
        /** Each repository class with the EntityMapper or LogWriter registered for it, in registration order. */
        private final Map<Class<?>, Object> registrations = new LinkedHashMap<>();
        /** The repository classes registered with preloadedRepositories. */
        private final Set<Class<?>> preloaded = new HashSet<>();
        private Duration flushInterval = DEFAULT_FLUSH_INTERVAL, cacheExpire = DEFAULT_CACHE_EXPIRE,
                shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        private int batchSize = DEFAULT_BATCH_SIZE, writeThreads = DEFAULT_WRITE_THREADS, logQueueCapacity = DEFAULT_LOG_QUEUE_CAPACITY;
        private DataErrorHandler errorHandler;

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

        /**
         * As {@link #repositories(EntityMapper, Collection)}, for tables kept whole in memory, such as guilds: build reads
         * every row through the mapper's {@code loadTable}, and nothing leaves memory after, whatever the
         * {@code cacheExpire}, so no read asks the database. Only for tables that fit.
         */
        public Builder preloadedRepositories(EntityMapper mapper, Collection<? extends Class<?>> types) {
            repositories(mapper, types);
            preloaded.addAll(types);
            return this;
        }

        /** {@link #preloadedRepositories(EntityMapper, Collection)} for classes listed one by one. */
        public Builder preloadedRepositories(EntityMapper mapper, Class<?>... types) {
            return preloadedRepositories(mapper, List.of(types));
        }

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

        /** Entity types written at once; 4 by default. Logs are written on a thread of their own, one type after another. */
        public Builder writeThreads(int threads) {
            writeThreads = positive(threads, "writeThreads");
            return this;
        }

        /** How long an entity or group stays in memory unused; 30 minutes by default. */
        public Builder cacheExpire(Duration expire) {
            cacheExpire = positive(expire, "cacheExpire");
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

        /**
         * Receives every change and log given up on, such as those close could not write while the database was down.
         * Required: logging them loses them, so save them where they can be written again, such as a local file.
         */
        public Builder errorHandler(DataErrorHandler handler) {
            errorHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /**
         * Reads every entity class, has each mapper and writer prepare for its types, loads the preloaded tables, then
         * starts writing. A class that does not fit throws IllegalArgumentException, a missing setting
         * IllegalStateException, what a mapper or writer throws while preparing comes out as it is, and a table that
         * cannot be loaded throws DataException ({@code DATA_LOAD_FAILED}); nothing is started then.
         */
        public GameData build() {
            if (registrations.isEmpty()) throw new IllegalStateException("no repositories");
            if (errorHandler == null) throw new IllegalStateException("no errorHandler: changes given up on would be lost");
            Map<Class<?>, Object> repositories = new LinkedHashMap<>();
            Set<Class<?>> entityClasses = new HashSet<>();
            List<ChangeSet> changeSets = new ArrayList<>();
            Map<LogRepository<?>, LogQueue> logRepositories = new LinkedHashMap<>();
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
                boolean preload = preloaded.contains(type);
                if (single) {
                    SingleRepository<?, ?> byId = (SingleRepository<?, ?>) repository;
                    byId.bind(changes, keys, cacheExpire, preload);
                    if (preload) byId.preload();
                } else {
                    GroupRepository<?, ?> inGroups = (GroupRepository<?, ?>) repository;
                    inGroups.bind(changes, keys, cacheExpire, preload);
                    if (preload) inGroups.preload();
                }
            }
            // One failure at a time, whether the entities' writer, the logs' or a business thread reports it.
            DataErrorHandler handler = errorHandler;
            DataErrorHandler oneAtATime = new DataErrorHandler() {
                @Override public synchronized void onError(DataFailure failure) { handler.onError(failure); }
            };
            ChangeWriter writer = new ChangeWriter(changeSets, List.of(), batchSize, writeThreads, oneAtATime);
            // Logs have a writer of their own, on the log scheduler's thread, so their destination never holds up entities.
            ChangeWriter logWriter = new ChangeWriter(List.of(), List.copyOf(logRepositories.values()), batchSize, 1, oneAtATime);
            logRepositories.forEach((repository, queue) -> repository.bind(queue, logWriter));
            return new GameData(this, repositories, writer, logWriter);
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
