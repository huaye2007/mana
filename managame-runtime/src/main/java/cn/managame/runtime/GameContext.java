package cn.managame.runtime;

import cn.managame.Metadata;
import java.util.Objects;

/**
 * The plain immutable Context. New work started from a running task carries that task's metadata along by
 * passing it in, for example {@code new GameContext(GUILD, guildId, GUILD_ID, guildId, context.metadata())}.
 */
public record GameContext(int domain, long routeKey, byte businessType, long businessId, Metadata metadata)
        implements Context {
    public GameContext {
        Objects.requireNonNull(metadata, "metadata");
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
     * or a timer for it. A copy of a RequestContext cannot answer the request: the answer stays with the
     * original, which a callback continuing that work keeps using. A GameContext is returned as is.
     */
    public static GameContext copyOf(Context context) {
        if (context instanceof GameContext plain) return plain;
        return new GameContext(context.domain(), context.routeKey(), context.businessType(), context.businessId(),
                context.metadata());
    }
}
