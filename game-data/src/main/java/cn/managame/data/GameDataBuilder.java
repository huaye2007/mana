package cn.managame.data;

import cn.managame.data.codec.*;
import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.mapper.SchemaMode;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mysql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

public final class GameDataBuilder {
    private record Binding(Object backend, Class<?> type, boolean log) {}
    private record Prepared(Binding binding, Object repository, EntityMeta meta, MysqlLogWriter logWriter) {}
    private final Map<Class<?>, Binding> bindings = new LinkedHashMap<>();
    private Duration cacheExpire = Duration.ofMinutes(30), flushInterval = Duration.ofSeconds(1);
    /** Default bound of queued logs per LogRepository. */
    public static final int DEFAULT_LOG_QUEUE_CAPACITY = 100_000;
    /** Default bound of distinct entities waiting to be saved, across all state repositories. */
    public static final int DEFAULT_MAX_PENDING_CHANGES = 100_000;
    /** Default bound of cached entries per state repository. */
    public static final int DEFAULT_MAX_CACHE_ENTRIES = 1_000_000;
    private int batchSize = 500;
    private int logQueueCapacity = DEFAULT_LOG_QUEUE_CAPACITY;
    private int maxPendingChanges = DEFAULT_MAX_PENDING_CHANGES;
    private int maxCacheEntries = DEFAULT_MAX_CACHE_ENTRIES;
    private Duration pendingChangeWait = Duration.ofSeconds(5);
    private Duration shutdownTimeout = Duration.ofSeconds(30);
    private SchemaMode schema = SchemaMode.CREATE;
    private ZoneId partitionZone = ZoneOffset.UTC;
    private JsonCodec jsonCodec;
    private BinaryCodec binaryCodec;
    private JsonCodec logJsonCodec;
    private BinaryCodec logBinaryCodec;
    private boolean logCodecsConfigured;
    private DataSource mysqlSource;
    private DataSource logMysqlSource;
    private DataErrorHandler errorHandler = f -> System.getLogger("cn.managame.data")
            .log(System.Logger.Level.ERROR, f.operation() + " failed for " + f.entityType(), f.cause());
    private RetryPolicy retryPolicy = RetryPolicy.transientFailures();
    private GameDataBuilder() {}
    public static GameDataBuilder builder() { return new GameDataBuilder(); }
    /** Borrowed DataSource for state repositories registered without a backend. It never serves logs. */
    public GameDataBuilder mysql(DataSource source) { mysqlSource = Objects.requireNonNull(source); return this; }
    /**
     * Borrowed DataSource for log repositories registered without a backend. State and logs are separate
     * stores, usually separate databases, and neither is ever derived from the other: one database for both
     * is said by passing the same DataSource here and to {@link #mysql(DataSource)}.
     */
    public GameDataBuilder logMysql(DataSource source) { logMysqlSource = Objects.requireNonNull(source); return this; }
    public GameDataBuilder repositories(List<Class<?>> types) {
        for (Class<?> type : types) register(null, type, false); return this;
    }
    public GameDataBuilder repositories(Class<?>... types) { return repositories(List.of(types)); }
    public GameDataBuilder logRepositories(List<Class<?>> types) {
        for (Class<?> type : types) register(null, type, true); return this;
    }
    public GameDataBuilder logRepositories(Class<?>... types) { return logRepositories(List.of(types)); }
    public GameDataBuilder jsonCodec(JsonCodec value) { jsonCodec = Objects.requireNonNull(value); return this; }
    public GameDataBuilder binaryCodec(BinaryCodec value) { binaryCodec = Objects.requireNonNull(value); return this; }
    public GameDataBuilder repositories(EntityMapper mapper, List<Class<?>> types) {
        Objects.requireNonNull(mapper); for (Class<?> type : types) register(mapper, type, false); return this;
    }
    public GameDataBuilder repositories(EntityMapper mapper, Class<?>... types) { return repositories(mapper, List.of(types)); }
    public GameDataBuilder logRepositories(MysqlAccess access, List<Class<?>> types) {
        Objects.requireNonNull(access); for (Class<?> type : types) register(access, type, true); return this;
    }
    public GameDataBuilder logRepositories(MysqlAccess access, Class<?>... types) { return logRepositories(access, List.of(types)); }
    /**
     * A registration that names a backend replaces an earlier one that did not, keeping its position. This is
     * how a container integration that discovered a repository lets the application redirect it to another
     * backend afterwards. Any other repeat of the same repository type is rejected.
     */
    private void register(Object backend, Class<?> type, boolean log) {
        Binding added = new Binding(backend, Objects.requireNonNull(type, "repository type"), log);
        Binding previous = bindings.putIfAbsent(type, added);
        if (previous == null) return;
        if (backend == null || previous.backend() != null) throw new IllegalArgumentException("Duplicate repository: " + type);
        bindings.put(type, added);
    }
    public GameDataBuilder cacheExpire(Duration value) { cacheExpire = positive(value); return this; }
    public GameDataBuilder flushInterval(Duration value) { flushInterval = positive(value); return this; }
    public GameDataBuilder batchSize(int value) { if (value < 1) throw new IllegalArgumentException("batchSize"); batchSize = value; return this; }
    /**
     * Logs one LogRepository may hold waiting for storage (default {@value #DEFAULT_LOG_QUEUE_CAPACITY}).
     * Beyond it {@code insert} returns false and reports the log to the DataErrorHandler instead of growing memory.
     */
    public GameDataBuilder logQueueCapacity(int value) {
        if (value < 1) throw new IllegalArgumentException("logQueueCapacity");
        logQueueCapacity = value; return this;
    }
    /**
     * Distinct entities that may wait to be saved, across all state repositories
     * (default {@value #DEFAULT_MAX_PENDING_CHANGES}). A change to an entity that is already waiting always
     * merges and is always accepted; one that would add another entity waits up to
     * {@link #pendingChangeWait(Duration)} for a save pass to make room and is then refused with
     * DataOperationException, so memory is a setting rather than an outcome of how fast business code writes.
     */
    public GameDataBuilder maxPendingChanges(int value) {
        if (value < 1) throw new IllegalArgumentException("maxPendingChanges");
        maxPendingChanges = value; return this;
    }
    /** How long a write waits for room in a full dirty set before it is refused (default 5 s; 0 refuses at once). */
    public GameDataBuilder pendingChangeWait(Duration value) {
        Objects.requireNonNull(value); if (value.isNegative()) throw new IllegalArgumentException("pendingChangeWait");
        value.toNanos(); pendingChangeWait = value; return this;
    }
    /**
     * Entries one state repository may cache (default {@value #DEFAULT_MAX_CACHE_ENTRIES}). Over it, the
     * clean entries that went longest unread are evicted on the persistence thread; entries with unsaved
     * changes are never evicted, so keep this above maxPendingChanges for the bound to be reachable.
     */
    public GameDataBuilder maxCacheEntries(int value) {
        if (value < 1) throw new IllegalArgumentException("maxCacheEntries");
        maxCacheEntries = value; return this;
    }
    /**
     * What build may do to storage structure (default {@link SchemaMode#CREATE}). One setting for every
     * backend: it reaches each Mapper through {@link EntityMapper#initialize}, including a Mapper the
     * application supplied, so a deployment that manages its schema elsewhere turns DDL off in one place.
     */
    public GameDataBuilder schema(SchemaMode value) { schema = Objects.requireNonNull(value); return this; }
    /** How long close keeps retrying retryable failures before dropping and reporting the rest. */
    public GameDataBuilder shutdownTimeout(Duration value) {
        Objects.requireNonNull(value); if (value.isNegative()) throw new IllegalArgumentException("shutdownTimeout");
        value.toNanos(); shutdownTimeout = value; return this;
    }
    public GameDataBuilder retryPolicy(RetryPolicy value) { retryPolicy = Objects.requireNonNull(value); return this; }
    public GameDataBuilder errorHandler(DataErrorHandler value) { errorHandler = Objects.requireNonNull(value); return this; }
    public GameDataBuilder partitionZone(ZoneId value) { partitionZone = Objects.requireNonNull(value); return this; }
    public GameDataBuilder logCodecs(JsonCodec json, BinaryCodec binary) {
        logJsonCodec = json; logBinaryCodec = binary; logCodecsConfigured = true; return this;
    }
    private static Duration positive(Duration value) {
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("Duration must be positive");
        value.toNanos(); return value;
    }
    public GameData build() {
        if (cacheExpire.compareTo(flushInterval) <= 0) throw new IllegalArgumentException("cacheExpire must exceed flushInterval");
        Map<Class<?>,Object> repositories = new LinkedHashMap<>();
        Map<EntityMeta,EntityMapper> mappers = new LinkedHashMap<>();
        Set<Class<?>> stateTypes = new HashSet<>();
        List<Prepared> prepared = new ArrayList<>();
        MysqlAccess mysqlAccess = mysqlSource == null ? null : new JdbcMysqlAccess(mysqlSource);
        EntityMapper mysqlMapper = mysqlAccess == null ? null : new MysqlEntityMapper(mysqlAccess, jsonCodec, binaryCodec);
        MysqlAccess logAccess = logMysqlSource == null ? null : new JdbcMysqlAccess(logMysqlSource);
        for (Binding configured : bindings.values()) {
            Binding binding = configured;
            if (binding.backend() == null) {
                Object backend = binding.log() ? logAccess : mysqlMapper;
                if (backend == null) throw new IllegalArgumentException(binding.log()
                        ? "logMysql(DataSource) or an explicit MysqlAccess required for " + binding.type()
                        : "mysql(DataSource) or an explicit EntityMapper required for " + binding.type());
                binding = new Binding(backend, binding.type(), binding.log());
            }
            Class<?> type = Objects.requireNonNull(binding.type());
            if (!(type.getGenericSuperclass() instanceof ParameterizedType parent))
                throw new IllegalArgumentException("Repository must directly extend a parameterized repository base: " + type);
            Type base = parent.getRawType();
            boolean single = base == SingleRepository.class, group = base == GroupRepository.class;
            if (binding.log() ? base != LogRepository.class : !(single || group))
                throw new IllegalArgumentException("Unsupported repository base: " + type);
            Type[] args = parent.getActualTypeArguments();
            if (!(args[args.length - 1] instanceof Class<?> entity))
                throw new IllegalArgumentException("Concrete entity class required: " + type);
            Object repository = EntityMeta.construct(EntityMeta.constructorOf(type));
            repositories.put(type, repository);
            if (binding.log()) {
                prepared.add(new Prepared(binding, repository, null,
                        new MysqlLogWriter(entity, (MysqlAccess) binding.backend(),
                                logCodecsConfigured ? logJsonCodec : jsonCodec,
                                logCodecsConfigured ? logBinaryCodec : binaryCodec, partitionZone, schema)));
            } else {
                if (!stateTypes.add(entity)) throw new IllegalArgumentException("Duplicate state entity registration: " + entity);
                EntityMeta meta = new EntityMeta(entity, single ? EntityMeta.Kind.SINGLE : EntityMeta.Kind.GROUP);
                if (args[0] != meta.keyType()) throw new IllegalArgumentException("Repository K must be " + meta.keyType());
                mappers.put(meta, (EntityMapper) binding.backend());
                prepared.add(new Prepared(binding, repository, meta, null));
            }
        }
        // No background resources exist until all mappings and schemas initialize successfully.
        for (var entry : mappers.entrySet()) entry.getValue().initialize(entry.getKey(), schema);
        WriteBehindManager writer = new WriteBehindManager(mappers, flushInterval, batchSize, shutdownTimeout,
                cacheExpire, errorHandler, retryPolicy, maxPendingChanges, pendingChangeWait);
        for (Prepared p : prepared) {
            if (p.repository() instanceof SingleRepository<?,?> repository)
                repository.initialize(p.meta(), (EntityMapper) p.binding().backend(), writer, cacheExpire, maxCacheEntries);
            else if (p.repository() instanceof GroupRepository<?,?> repository)
                repository.initialize(p.meta(), (EntityMapper) p.binding().backend(), writer, cacheExpire, maxCacheEntries);
            else if (p.repository() instanceof LogRepository<?> repository) {
                repository.initialize(p.logWriter(), writer, logQueueCapacity); writer.addLog(repository);
            }
        }
        GameData result = new GameData(repositories, writer);
        writer.start();
        return result;
    }
}
