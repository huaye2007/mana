package cn.managame.network.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An immutable, ordered list of named Netty handlers, defined once and installed into any number of
 * pipelines: several servers, or a pipeline the application bootstraps itself. A {@code @Sharable} instance is
 * installed as is into every pipeline; a factory is called once per pipeline for handlers that keep
 * per-connection state, such as decoders.
 */
public final class HandlerChain {
    private static final HandlerChain EMPTY = new HandlerChain(List.of());
    private final List<Entry> entries;

    private HandlerChain(List<Entry> entries) { this.entries = entries; }

    public static HandlerChain empty() { return EMPTY; }
    public static Builder builder() { return new Builder(); }

    /** A single {@code @Sharable} handler. */
    public static HandlerChain of(String name, ChannelHandler sharable) { return builder().addLast(name, sharable).build(); }

    /** A single handler created per pipeline. */
    public static HandlerChain of(String name, Supplier<? extends ChannelHandler> factory) {
        return builder().addLast(name, factory).build();
    }

    public List<String> names() { return entries.stream().map(Entry::name).toList(); }
    public boolean isEmpty() { return entries.isEmpty(); }

    /** Appends this chain's handlers to the end of {@code pipeline}, in order. */
    public void addTo(ChannelPipeline pipeline) {
        for (Entry entry : entries) {
            ChannelHandler handler = entry.factory.get();
            if (handler == null) throw new IllegalStateException("Handler factory '" + entry.name + "' returned null");
            pipeline.addLast(entry.name, handler);
        }
    }

    private record Entry(String name, Supplier<? extends ChannelHandler> factory) {}

    /** Names must be unique within the chain. */
    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private final Set<String> names = new HashSet<>();

        private Builder() {}

        /** Adds a handler shared by every pipeline; it must be annotated {@code @Sharable}. */
        public Builder addLast(String name, ChannelHandler sharable) {
            Objects.requireNonNull(sharable, "handler");
            boolean isSharable = sharable instanceof ChannelHandlerAdapter adapter ? adapter.isSharable()
                    : sharable.getClass().isAnnotationPresent(ChannelHandler.Sharable.class);
            if (!isSharable)
                throw new IllegalArgumentException(sharable.getClass().getName() + " is not @Sharable; "
                        + "pass a factory such as () -> new " + sharable.getClass().getSimpleName() + "(...) instead");
            return add(name, () -> sharable);
        }

        /** Adds a handler created by {@code factory} for each pipeline. */
        public Builder addLast(String name, Supplier<? extends ChannelHandler> factory) {
            return add(name, Objects.requireNonNull(factory, "factory"));
        }

        public Builder addLast(HandlerChain chain) {
            for (Entry entry : chain.entries) add(entry.name, entry.factory);
            return this;
        }

        public HandlerChain build() { return entries.isEmpty() ? EMPTY : new HandlerChain(List.copyOf(entries)); }

        private Builder add(String name, Supplier<? extends ChannelHandler> factory) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Handler name must not be blank");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate handler name: " + name);
            entries.add(new Entry(name, factory));
            return this;
        }
    }
}
