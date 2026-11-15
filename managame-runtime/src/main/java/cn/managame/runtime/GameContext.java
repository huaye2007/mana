package cn.managame.runtime;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import java.util.Objects;

/**
 * The plain Context: a route, whom it is for, and metadata. New work started from a running task carries that task's
 * metadata along by passing it in, for example {@code new GameContext(GUILD, guildId, GUILD_ID, guildId,
 * context.metadata())}. Only its metadata can change, through {@link #put}; two are equal when their fields are.
 */
public final class GameContext implements Context {
    private final int domain;
    private final long routeKey;
    private final byte businessType;
    private final long businessId;
    private volatile Metadata metadata;

    public GameContext(int domain, long routeKey, byte businessType, long businessId, Metadata metadata) {
        this.domain = domain;
        this.routeKey = routeKey;
        this.businessType = businessType;
        this.businessId = businessId;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    public GameContext(Route route, Metadata metadata) {
        this(route.domain(), route.routeKey(), route.businessType(), route.businessId(), metadata);
    }

    /** No business identity and empty metadata. */
    public static GameContext of(int domain, long routeKey) {
        return new GameContext(domain, routeKey, (byte) 0, 0, Metadata.empty());
    }

    /** Empty metadata. */
    public static GameContext of(int domain, long routeKey, byte businessType, long businessId) {
        return new GameContext(domain, routeKey, businessType, businessId, Metadata.empty());
    }

    /**
     * A plain copy of {@code context}, for new work about the same subject, such as an event about this player
     * or a timer for it, with the metadata the context has now: what is put in either afterwards is not in the other.
     * A copy of a RequestContext cannot answer the request: the answer stays with the original, which a callback
     * continuing that work keeps using.
     */
    public static GameContext copyOf(Context context) {
        return new GameContext(context.domain(), context.routeKey(), context.businessType(), context.businessId(),
                context.metadata());
    }

    @Override public int domain() { return domain; }
    @Override public long routeKey() { return routeKey; }
    @Override public byte businessType() { return businessType; }
    @Override public long businessId() { return businessId; }
    @Override public Metadata metadata() { return metadata; }

    @Override public <T> void put(MetadataKey<T> key, T value) { metadata = metadata.with(key, value); }

    @Override public boolean equals(Object other) {
        return other instanceof GameContext that && domain == that.domain && routeKey == that.routeKey
                && businessType == that.businessType && businessId == that.businessId && metadata.equals(that.metadata);
    }

    /** Of the fields that never change, so a context stays where a hash put it whatever is put in it. */
    @Override public int hashCode() { return Objects.hash(domain, routeKey, businessType, businessId); }

    @Override public String toString() {
        return "GameContext[domain=" + domain + ", routeKey=" + routeKey + ", businessType=" + businessType
                + ", businessId=" + businessId + ", metadata=" + metadata + "]";
    }
}
