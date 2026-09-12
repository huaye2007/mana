package cn.managame.rpc;

import cn.managame.network.ConnectCallback;
import cn.managame.network.Connection;
import cn.managame.network.NetworkHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.net.InetSocketAddress;

/** Network-provider SPI: TCP lifecycle and framed byte writes, with no RPC protocol/call state. */
public interface RpcTransport extends AutoCloseable {
    /**
     * Local write admission only. ACCEPTED does not imply delivery or a successful native write.
     */
    enum Submission {
        ACCEPTED,
        UNAVAILABLE,
        OVERLOADED
    }

    /** Installed once at start. Factories return a fresh handler for every physical connection. */
    interface Listener {
        NetworkHandler newInboundHandler();

        NetworkHandler newOutboundHandler();

        /** Reports failure after write admission; the transport must also close that connection. */
        void onWriteFailure(Connection connection, Throwable failure);
    }

    /** Available before start; the allocator is borrowed and is never closed by Core. */
    ByteBufAllocator allocator();

    /**
     * Synchronously initializes the client and binds the configured listener. Called once by Core.
     * Partial startup must remain closeable if this throws.
     */
    void start(Listener listener);

    /**
     * Starts one physical TCP attempt, with no handshake or retry. The unresolved address must not
     * trigger caller-thread DNS resolution. Report success/failure exactly once via callback.
     */
    void connect(InetSocketAddress address, ConnectCallback callback);

    /** Actual bound endpoint after start; null before binding. */
    InetSocketAddress localAddress();

    /**
     * Writes one encoded RPC frame; transport owns TCP length framing and flush policy. Borrows
     * frame without changing indices. ACCEPTED acquires an independent output reference; rejection
     * acquires none. Later failure is reported to Listener and never replayed.
     */
    Submission write(Connection connection, ByteBuf frame);

    /**
     * Checks whether synchronous lifecycle operations may block on this thread. Core calls this
     * before changing node state. Implementations with no event-loop restriction may use the
     * default.
     */
    default void checkLifecycleThread() {}

    /**
     * Synchronously releases owned network resources, including after failed start; safe to repeat.
     * Borrowed resources remain open.
     */
    @Override
    void close();
}
