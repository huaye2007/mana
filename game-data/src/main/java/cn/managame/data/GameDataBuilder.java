package cn.managame.data;

import cn.managame.data.codec.*;
import cn.managame.data.error.*;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.data.meta.EntityMeta;
import cn.managame.data.mysql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

public final class GameDataBuilder {
    private record Binding(Object backend, Class<?> type, boolean log) {}
    private record Prepared(Binding binding, Object repository, EntityMeta meta, MysqlLogWriter logWriter) {}
    private final List<Binding> bindings = new ArrayList<>();
    private Duration cacheExpire = Duration.ofMinutes(30), flushInterval = Duration.ofSeconds(1);
    private int batchSize = 500, maxAttempts = 3;
    private ZoneId partitionZone = ZoneOffset.UTC;
    private JsonCodec jsonCodec;
    private BinaryCodec binaryCodec;
    private DataErrorHandler errorHandler = f -> System.getLogger("cn.managame.data")
            .log(System.Logger.Level.ERROR, f.operation() + " failed for " + f.entityType(), f.cause());
    private RetryPolicy retryPolicy = f -> false;
    private GameDataBuilder() {}
    public static GameDataBuilder builder() { return new GameDataBuilder(); }
    public GameDataBuilder repositories(EntityMapper mapper, List<Class<?>> types) {
        Objects.requireNonNull(mapper); for (Class<?> type : types) bindings.add(new Binding(mapper, type, false)); return this;
    }
    public GameDataBuilder repositories(EntityMapper mapper, Class<?>... types) { return repositories(mapper, List.of(types)); }
    public GameDataBuilder logRepositories(MysqlAccess access, List<Class<?>> types) {
        Objects.requireNonNull(access); for (Class<?> type : types) bindings.add(new Binding(access, type, true)); return this;
    }
    public GameDataBuilder logRepositories(MysqlAccess access, Class<?>... types) { return logRepositories(access, List.of(types)); }
    public GameDataBuilder cacheExpire(Duration value) { cacheExpire = positive(value); return this; }
    public GameDataBuilder flushInterval(Duration value) { flushInterval = positive(value); return this; }
    public GameDataBuilder batchSize(int value) { if (value < 1) throw new IllegalArgumentException("batchSize"); batchSize = value; return this; }
    public GameDataBuilder maxAttempts(int value) { if (value < 1) throw new IllegalArgumentException("maxAttempts"); maxAttempts = value; return this; }
    public GameDataBuilder retryPolicy(RetryPolicy value) { retryPolicy = Objects.requireNonNull(value); return this; }
    public GameDataBuilder errorHandler(DataErrorHandler value) { errorHandler = Objects.requireNonNull(value); return this; }
    public GameDataBuilder partitionZone(ZoneId value) { partitionZone = Objects.requireNonNull(value); return this; }
    public GameDataBuilder logCodecs(JsonCodec json, BinaryCodec binary) { jsonCodec = json; binaryCodec = binary; return this; }
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
        for (Binding binding : bindings) {
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
            if (repositories.putIfAbsent(type, repository) != null) throw new IllegalArgumentException("Duplicate repository: " + type);
            if (binding.log()) {
                prepared.add(new Prepared(binding, repository, null,
                        new MysqlLogWriter(entity, (MysqlAccess) binding.backend(), jsonCodec, binaryCodec, partitionZone)));
            } else {
                if (!stateTypes.add(entity)) throw new IllegalArgumentException("Duplicate state entity registration: " + entity);
                EntityMeta meta = new EntityMeta(entity, single ? EntityMeta.Kind.SINGLE : EntityMeta.Kind.GROUP);
                if (args[0] != meta.keyType()) throw new IllegalArgumentException("Repository K must be " + meta.keyType());
                mappers.put(meta, (EntityMapper) binding.backend());
                prepared.add(new Prepared(binding, repository, meta, null));
            }
        }
        // No background resources exist until all mappings and schemas initialize successfully.
        for (var entry : mappers.entrySet()) entry.getValue().initialize(entry.getKey());
        WriteBehindManager writer = new WriteBehindManager(mappers, flushInterval, batchSize, maxAttempts, errorHandler, retryPolicy);
        for (Prepared p : prepared) {
            if (p.repository() instanceof SingleRepository<?,?> repository)
                repository.initialize(p.meta(), (EntityMapper) p.binding().backend(), writer, cacheExpire);
            else if (p.repository() instanceof GroupRepository<?,?> repository)
                repository.initialize(p.meta(), (EntityMapper) p.binding().backend(), writer, cacheExpire);
            else if (p.repository() instanceof LogRepository<?> repository) {
                repository.initialize(p.logWriter(), writer); writer.addLog(repository);
            }
        }
        GameData result = new GameData(repositories, writer);
        writer.start();
        return result;
    }
}
