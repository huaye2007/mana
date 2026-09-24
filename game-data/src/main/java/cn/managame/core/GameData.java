package cn.managame.core;

import cn.managame.core.access.DataAccess;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.repository.DefaultGroupRepository;
import cn.managame.core.repository.DefaultSingleRepository;
import cn.managame.core.write.WriterState;
import cn.managame.core.write.WriteBehindEngine;
import cn.managame.core.write.WriteFailureHandler;
import cn.managame.core.log.DefaultLogRepository;

import java.time.Duration;
import java.util.*;

/** Main lifecycle owner for repositories, asynchronous writers and named DataAccess instances. */
public final class GameData implements AutoCloseable {
    public static final String DEFAULT_ACCESS = "default";

    private final MetadataRegistry metadata = new MetadataRegistry();
    private final Duration cacheExpireAfterAccess;
    private final boolean ensureSchema;
    private final Map<String, AccessContext> accesses;
    private final String defaultAccessName;
    private final Map<RepositoryKey, Object> repositories = new HashMap<>();
    private final Map<RepositoryKey, RepositoryBinding> bindings = new HashMap<>();
    private final Object closeMonitor = new Object();
    private volatile boolean closed;

    public GameData(DataAccess dataAccess) {
        this(Map.of(DEFAULT_ACCESS, Objects.requireNonNull(dataAccess)), DEFAULT_ACCESS,
                Duration.ofMinutes(30), true,
                65_536, 256, Duration.ofMillis(100), 3,
                131_072, 2_048, Duration.ofMillis(200),
                WriteFailureHandler.stderr());
    }

    public GameData(Map<String, DataAccess> dataAccesses) {
        this(dataAccesses, dataAccesses.containsKey(DEFAULT_ACCESS)
                        ? DEFAULT_ACCESS
                        : dataAccesses.keySet().stream().findFirst().orElseThrow(),
                Duration.ofMinutes(30), true,
                65_536, 256, Duration.ofMillis(100), 3,
                131_072, 2_048, Duration.ofMillis(200),
                WriteFailureHandler.stderr());
    }

    public GameData(
            DataAccess dataAccess,
            Duration cacheExpireAfterAccess,
            boolean ensureSchema,
            int writeQueueCapacity,
            int writeBatchSize,
            Duration flushInterval,
            int maxRetries,
            int logQueueCapacity,
            int logBatchSize,
            Duration logFlushInterval,
            WriteFailureHandler failureHandler) {
        this(Map.of(DEFAULT_ACCESS, Objects.requireNonNull(dataAccess)), DEFAULT_ACCESS,
                cacheExpireAfterAccess, ensureSchema,
                writeQueueCapacity, writeBatchSize, flushInterval, maxRetries,
                logQueueCapacity, logBatchSize, logFlushInterval, failureHandler);
    }

    public GameData(
            Map<String, DataAccess> dataAccesses,
            String defaultAccessName,
            Duration cacheExpireAfterAccess,
            boolean ensureSchema,
            int writeQueueCapacity,
            int writeBatchSize,
            Duration flushInterval,
            int maxRetries,
            int logQueueCapacity,
            int logBatchSize,
            Duration logFlushInterval,
            WriteFailureHandler failureHandler) {
        this(dataAccesses, defaultAccessName, cacheExpireAfterAccess, ensureSchema,
                writeQueueCapacity, writeBatchSize, flushInterval, maxRetries,
                logQueueCapacity, logBatchSize, logFlushInterval, failureHandler, Duration.ofMinutes(5), 2, 2);
    }

    private GameData(
            Map<String, DataAccess> dataAccesses, String defaultAccessName,
            Duration cacheExpireAfterAccess, boolean ensureSchema,
            int writeQueueCapacity, int writeBatchSize, Duration flushInterval, int maxRetries,
            int logQueueCapacity, int logBatchSize, Duration logFlushInterval,
            WriteFailureHandler failureHandler, Duration logWriterIdleTimeout, int writeThreads, int logWriteThreads) {
        Objects.requireNonNull(dataAccesses, "dataAccesses");
        if (dataAccesses.isEmpty()) throw new IllegalArgumentException("At least one DataAccess is required");
        this.defaultAccessName = requireAccessName(defaultAccessName);
        this.cacheExpireAfterAccess = cacheExpireAfterAccess;
        this.ensureSchema = ensureSchema;

        Map<String, AccessContext> built = new LinkedHashMap<>();
        for (Map.Entry<String, DataAccess> entry : dataAccesses.entrySet()) {
            String name = requireAccessName(entry.getKey());
            DataAccess access = Objects.requireNonNull(entry.getValue(), "DataAccess '" + name + "'");
            if (built.containsKey(name)) throw new IllegalArgumentException("Duplicate DataAccess name: " + name);
            built.put(name, new AccessContext(access,
                    new WriteBehindEngine(access, writeQueueCapacity, writeBatchSize,
                            flushInterval, maxRetries, failureHandler, Duration.ZERO, writeThreads),
                    WriteBehindEngine.logs(access, logQueueCapacity, logBatchSize,
                            logFlushInterval, maxRetries, failureHandler, logWriterIdleTimeout, logWriteThreads)));
        }
        if (!built.containsKey(this.defaultAccessName)) {
            throw new IllegalArgumentException("Default DataAccess does not exist: " + this.defaultAccessName);
        }
        this.accesses = Map.copyOf(built);
    }

    public static Builder builder() { return new Builder(); }

    /** Creates or returns a business interface extending exactly one repository mode. */
    public <R> R repository(Class<R> repositoryType) {
        return repository(defaultAccessName, repositoryType);
    }

    public <R> R repository(String accessName, Class<R> repositoryType) {
        ensureOpen();
        synchronized (this) {
            ensureOpen();
            AccessContext context = access(accessName);
            RepositoryKey key = new RepositoryKey(accessName, repositoryType);
            Object existing = repositories.get(key);
            if (existing != null) return repositoryType.cast(existing);
            RepositoryDefinition definition = RepositoryDefinition.inspect(repositoryType, metadata);
            if (definition.mode() == RepositoryDefinition.Mode.LOG) {
                R repository = definition.create(repositoryType,
                        new DefaultLogRepository<>(definition.entityType(), metadata, context.dataAccess, context.logWriter));
                repositories.put(key, repository);
                return repository;
            }
            RepositoryKey entityKey = new RepositoryKey(accessName, definition.entityType());
            RepositoryBinding binding = bindings.get(entityKey);
            if (binding != null && binding.mode() != definition.mode()) {
                throw new DataException("Entity already bound to " + binding.mode() + ": "
                        + definition.entityType().getName() + ", access=" + accessName);
            }
            if (binding == null) {
                Object delegate = definition.mode() == RepositoryDefinition.Mode.SINGLE
                        ? new DefaultSingleRepository<>(definition.entityType(), metadata, context.dataAccess,
                                context.writer, cacheExpireAfterAccess, ensureSchema)
                        : new DefaultGroupRepository<>(definition.entityType(), metadata, context.dataAccess,
                                context.writer, cacheExpireAfterAccess, ensureSchema);
                binding = new RepositoryBinding(definition.mode(), delegate);
            }
            R repository = definition.create(repositoryType, binding.delegate());
            bindings.put(entityKey, binding);
            repositories.put(key, repository);
            return repository;
        }
    }

    /** Drains log writes across all configured data sources; normal append does not need a flush. */
    public void flushLogs() {
        ensureOpen();
        RuntimeException failure = null;
        for (AccessContext context : accesses.values()) {
            try { context.logWriter.flush(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
        }
        if (failure != null) throw failure;
    }

    public void flushLogs(String accessName) {
        ensureOpen();
        access(accessName).logWriter.flush();
    }

    public void flush() {
        ensureOpen();
        RuntimeException failure = null;
        for (AccessContext context : accesses.values()) {
            try { context.writer.flush(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
            try { context.logWriter.flush(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
        }
        if (failure != null) throw failure;
    }

    public void flush(Class<?> entityType) { flush(defaultAccessName, entityType); }

    public void flush(String accessName, Class<?> entityType) {
        ensureOpen();
        AccessContext context = access(accessName);
        RuntimeException failure = null;
        try { context.writer.flush(entityType); }
        catch (RuntimeException e) { failure = e; }
        try { context.logWriter.flush(entityType); }
        catch (RuntimeException e) { failure = merge(failure, e); }
        if (failure != null) throw failure;
    }

    public WriterState writerState(Class<?> entityType) {
        return writerState(defaultAccessName, entityType);
    }

    public WriterState writerState(String accessName, Class<?> entityType) {
        return access(accessName).writer.state(entityType);
    }

    public Set<String> dataAccessNames() { return accesses.keySet(); }
    public String defaultDataAccessName() { return defaultAccessName; }

    @Override
    public void close() {
        for (AccessContext context : accesses.values()) {
            context.writer.checkNotWriterThread();
            context.logWriter.checkNotWriterThread();
        }
        synchronized (closeMonitor) {
            closeAccesses();
        }
    }

    private void closeAccesses() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        RuntimeException failure = null;
        for (AccessContext context : accesses.values()) {
            context.writer.stopAccepting();
            context.logWriter.stopAccepting();
        }
        for (AccessContext context : accesses.values()) {
            try { context.writer.close(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
            try { context.logWriter.close(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
        }
        Set<DataAccess> closedAccesses = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AccessContext context : accesses.values()) {
            if (!closedAccesses.add(context.dataAccess)) continue;
            try { context.dataAccess.close(); }
            catch (RuntimeException e) { failure = merge(failure, e); }
        }
        if (failure != null) throw failure;
    }

    private AccessContext access(String name) {
        String normalized = requireAccessName(name);
        AccessContext context = accesses.get(normalized);
        if (context == null) throw new DataException("Unknown DataAccess: " + normalized);
        return context;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GameData is closed");
    }

    private static String requireAccessName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("DataAccess name must not be blank");
        return name;
    }

    private static RuntimeException merge(RuntimeException current, RuntimeException next) {
        if (current == null) return next;
        if (current != next) current.addSuppressed(next);
        return current;
    }

    private record AccessContext(DataAccess dataAccess, WriteBehindEngine writer, WriteBehindEngine logWriter) { }
    private record RepositoryBinding(RepositoryDefinition.Mode mode, Object delegate) { }
    private record RepositoryKey(String accessName, Class<?> type) { }

    public static final class Builder {
        private final Map<String, DataAccess> dataAccesses = new LinkedHashMap<>();
        private String defaultAccessName;
        private Duration cacheExpireAfterAccess = Duration.ofMinutes(30);
        private boolean ensureSchema = true;
        private int writeQueueCapacity = 65_536;
        private int writeBatchSize = 256;
        private int writeThreads = 2;
        private int logWriteThreads = 2;
        private Duration flushInterval = Duration.ofMillis(100);
        private int maxRetries = 3;
        private int logQueueCapacity = 131_072;
        private int logBatchSize = 2_048;
        private Duration logFlushInterval = Duration.ofMillis(200);
        private Duration logWriterIdleTimeout = Duration.ofMinutes(5);
        private WriteFailureHandler failureHandler = WriteFailureHandler.stderr();

        public Builder dataAccess(String name, DataAccess access) {
            dataAccesses.put(requireAccessName(name), Objects.requireNonNull(access));
            if (defaultAccessName == null) defaultAccessName = name;
            return this;
        }

        public Builder defaultDataAccess(String name) { this.defaultAccessName = requireAccessName(name); return this; }
        public Builder cacheExpireAfterAccess(Duration value) { this.cacheExpireAfterAccess = value; return this; }
        public Builder ensureSchema(boolean value) { this.ensureSchema = value; return this; }
        public Builder writeQueueCapacity(int value) { this.writeQueueCapacity = value; return this; }
        /** Number of fixed save workers per DataAccess for ordinary entity tables. */
        public Builder writeThreads(int value) {
            if (value <= 0) throw new IllegalArgumentException("writeThreads must be > 0");
            this.writeThreads = value;
            return this;
        }
        /** Number of fixed save workers per DataAccess for log queues. */
        public Builder logWriteThreads(int value) {
            if (value <= 0) throw new IllegalArgumentException("logWriteThreads must be > 0");
            this.logWriteThreads = value;
            return this;
        }
        public Builder writeBatchSize(int value) { this.writeBatchSize = value; return this; }
        public Builder flushInterval(Duration value) { this.flushInterval = value; return this; }
        public Builder maxRetries(int value) { this.maxRetries = value; return this; }
        public Builder logQueueCapacity(int value) { this.logQueueCapacity = value; return this; }
        public Builder logBatchSize(int value) { this.logBatchSize = value; return this; }
        public Builder logFlushInterval(Duration value) { this.logFlushInterval = value; return this; }
        /** Idle routed log writers release their queues; shared save threads remain until close; zero disables retirement. */
        public Builder logWriterIdleTimeout(Duration value) {
            Objects.requireNonNull(value, "logWriterIdleTimeout");
            if (value.isNegative()) throw new IllegalArgumentException("logWriterIdleTimeout must be >= 0");
            this.logWriterIdleTimeout = value;
            return this;
        }
        public Builder failureHandler(WriteFailureHandler value) { this.failureHandler = value; return this; }

        public GameData build() {
            if (dataAccesses.isEmpty()) throw new IllegalStateException("At least one DataAccess is required");
            String defaultName = defaultAccessName == null ? dataAccesses.keySet().iterator().next() : defaultAccessName;
            return new GameData(dataAccesses, defaultName, cacheExpireAfterAccess, ensureSchema,
                    writeQueueCapacity, writeBatchSize, flushInterval, maxRetries,
                    logQueueCapacity, logBatchSize, logFlushInterval, failureHandler, logWriterIdleTimeout, writeThreads, logWriteThreads);
        }
    }
}
