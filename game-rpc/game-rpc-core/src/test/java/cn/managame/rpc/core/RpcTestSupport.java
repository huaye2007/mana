package cn.managame.rpc.core;

import cn.managame.rpc.protocol.RpcProtocolException;
import cn.managame.rpc.protocol.DefaultRpcCodec;
import cn.managame.rpc.protocol.RpcHandshake;
import cn.managame.rpc.protocol.RpcLimits;
import cn.managame.rpc.protocol.RpcMessage;
import cn.managame.rpc.protocol.RpcMetadata;
import cn.managame.rpc.protocol.RpcOptions;
import cn.managame.rpc.protocol.RpcRequest;
import cn.managame.rpc.protocol.RpcResponse;
import cn.managame.rpc.protocol.RpcRouteMessage;
import cn.managame.rpc.transport.RpcNetworkConfig;
import cn.managame.rpc.transport.RpcNetworkProvider;
import cn.managame.rpc.transport.RpcTransport;

import cn.managame.network.*;

import io.netty.buffer.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class RpcTestSupport {
    // Message constructors for the shared wire vectors; serialization uses the production codec.
    static final class TestCodec extends DefaultRpcCodec {
        private final boolean gcManaged;

        TestCodec(ByteBufAllocator allocator, RpcLimits limits) {
            super(allocator, limits);
            gcManaged = allocator == ALLOCATOR;
        }

        @Override
        public ByteBuf encode(RpcMessage message) {
            var frame = super.encode(message);
            // Wire fixtures use GC-owned bytes. Ownership tests supply a real allocator.
            if (!gcManaged || !(frame instanceof CompositeByteBuf)) return frame;
            try {
                return raw(ByteBufUtil.getBytes(frame));
            } finally {
                frame.release();
            }
        }

        ByteBuf request(int command, int id, RpcOptions options, ByteBuf body) {
            return encode(new RpcRequest(command, id, options, body));
        }

        ByteBuf response(int id, int error, RpcMetadata metadata, ByteBuf body) {
            return encode(new RpcResponse(id, error, metadata, body));
        }

        ByteBuf route(int source, int target, ByteBuf inner) {
            return encode(new RpcRouteMessage(source, target, inner));
        }

        ByteBuf handshake(byte type, int source, int target, int slot, int count) {
            var kind =
                    switch (type) {
                        case 4 -> RpcHandshake.Kind.HELLO;
                        case 5 -> RpcHandshake.Kind.ACK;
                        case 6 -> RpcHandshake.Kind.REJECT_DIRECTION;
                        default -> throw new RpcProtocolException("Invalid handshake");
                    };
            return encode(new RpcHandshake(kind, source, target, slot, count));
        }
    }

    static final RpcLimits LIMITS = new RpcLimits(4096, 1024, 4000, 1024);

    static TestSignal<cn.managame.network.Connection> connectResult(
            RpcNode node, int target, String host, int port) {
        return connectResult(node, target, host, port, 1);
    }

    static TestSignal<cn.managame.network.Connection> connectResult(
            RpcNode node, int target, String host, int port, int count) {
        var result = new TestSignal<cn.managame.network.Connection>();
        node.connect(
                target,
                host,
                port,
                count,
                new cn.managame.network.ConnectCallback() {
                    public void onSuccess(cn.managame.network.Connection connection) {
                        result.complete(connection);
                    }

                    public void onFailure(Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    static ByteBuf body(String text) {
        return raw(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static ByteBuf raw(byte[] bytes) {
        return Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(bytes));
    }

    static RpcResult snapshot(RpcResult result) {
        if (!result.isSuccess()) return result;
        var r = result.value();
        return RpcResult.success(
                new RpcResponse(
                        r.requestId(),
                        r.errorCode(),
                        r.metadata(),
                        r.body() == null ? null : raw(ByteBufUtil.getBytes(r.body()))));
    }

    static RpcMessage snapshot(RpcMessage message) {
        if (message instanceof RpcRequest q)
            return new RpcRequest(
                    q.command(),
                    q.requestId(),
                    q.routeKey(),
                    q.businessId(),
                    q.businessIdType(),
                    q.metadata(),
                    raw(ByteBufUtil.getBytes(q.body())));
        if (message instanceof RpcRouteMessage r)
            return new RpcRouteMessage(
                    r.sourceNodeId(), r.targetNodeId(), raw(ByteBufUtil.getBytes(r.inner())));
        return message;
    }

    // In-memory fixtures have GC-managed heap storage; ownership tests use real allocators.
    static final ByteBufAllocator ALLOCATOR =
            new AbstractByteBufAllocator(false) {
                protected ByteBuf newHeapBuffer(int initial, int max) {
                    return Unpooled.unreleasableBuffer(Unpooled.buffer(initial, max));
                }

                protected ByteBuf newDirectBuffer(int initial, int max) {
                    return newHeapBuffer(initial, max);
                }

                public boolean isDirectBufferPooled() {
                    return false;
                }
            };

    static byte[] bytes(ByteBuf buffer) {
        return ByteBufUtil.getBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        org.junit.jupiter.api.Assertions.assertTrue(
                condition.getAsBoolean(), "condition did not become true");
    }

    static final class Network implements RpcNetworkProvider {
        private final Map<Integer, Transport> listeners = new ConcurrentHashMap<>();
        private final AtomicInteger ports = new AtomicInteger(15000);
        private final AtomicInteger connectionIds = new AtomicInteger();
        final Map<Integer, Transport> transports = new ConcurrentHashMap<>();

        public RpcTransport create(RpcNetworkConfig config) {
            var t = new Transport(config);
            transports.put(config.nodeId(), t);
            return t;
        }

        final class Transport implements RpcTransport {
            final RpcNetworkConfig config;
            java.util.function.Supplier<NetworkHandler> incomingHandler, outgoingHandler;
            final List<byte[]> sent = new CopyOnWriteArrayList<>();
            final Set<Conn> connections = ConcurrentHashMap.newKeySet();
            volatile boolean closed, dropBusiness, failStart;
            volatile Submission rejectNext;
            volatile java.util.function.BiPredicate<Connection, byte[]> dropFrame =
                    (c, frame) -> false;
            Runnable closeHook;
            int port;

            Transport(RpcNetworkConfig config) {
                this.config = config;
            }

            public ByteBufAllocator allocator() {
                return ALLOCATOR;
            }

            public void start(Listener listener) {
                this.incomingHandler = listener::newInboundHandler;
                this.outgoingHandler = listener::newOutboundHandler;
                if (failStart) throw new IllegalStateException("Injected bind failure");
                port =
                        config.listenAddress().getPort() == 0
                                ? ports.getAndIncrement()
                                : config.listenAddress().getPort();
                if (listeners.putIfAbsent(port, this) != null)
                    throw new IllegalStateException("Port in use");
            }

            public InetSocketAddress localAddress() {
                return new InetSocketAddress("127.0.0.1", port);
            }

            public void connect(InetSocketAddress address, ConnectCallback callback) {
                var target = listeners.get(address.getPort());
                if (target == null) {
                    callback.onFailure(new IllegalStateException("No listener"));
                    return;
                }
                var outgoing = new Conn(this, false);
                var incoming = new Conn(target, true);
                outgoing.other = incoming;
                incoming.other = outgoing;
                connections.add(outgoing);
                target.connections.add(incoming);
                try {
                    outgoing.handler.onConnected(outgoing);
                    incoming.handler.onConnected(incoming);
                    callback.onSuccess(outgoing);
                } catch (Exception failure) {
                    outgoing.close();
                    callback.onFailure(failure);
                }
            }

            public Submission write(Connection c, ByteBuf frame) {
                if (closed || !c.isActive()) return Submission.UNAVAILABLE;
                if (!c.isWritable()) return Submission.OVERLOADED;
                var rejection = rejectNext;
                rejectNext = null;
                if (rejection != null) return rejection;
                byte[] bytes = RpcTestSupport.bytes(frame);
                sent.add(bytes);
                if ((dropBusiness && bytes[0] <= 3) || dropFrame.test(c, bytes))
                    return Submission.ACCEPTED;
                var conn = (Conn) c;
                if (conn.other != null) {
                    try {
                        conn.other.handler.onMessage(conn.other, raw(bytes));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
                return Submission.ACCEPTED;
            }

            public void close() {
                closed = true;
                listeners.remove(port, this);
                for (var c : new ArrayList<>(connections)) c.close();
                if (closeHook != null) closeHook.run();
            }
        }

        final class Conn implements Connection {
            final String id = "test-connection-" + connectionIds.incrementAndGet();
            final AtomicInteger idReads = new AtomicInteger();
            final Transport owner;
            final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
            Conn other;
            volatile boolean active = true, writable = true;
            RuntimeException closeFailure;

            final NetworkHandler handler;

            Conn(Transport owner, boolean incoming) {
                this.owner = owner;
                handler = (incoming ? owner.incomingHandler : owner.outgoingHandler).get();
            }

            public String id() {
                idReads.incrementAndGet();
                return id;
            }

            public ConnectionType type() {
                return ConnectionType.TCP;
            }

            public boolean isActive() {
                return active;
            }

            public boolean isWritable() {
                return writable;
            }

            public boolean write(Object message) {
                throw new AssertionError("RPC uses its transport");
            }

            public void close() {
                if (closeFailure != null) {
                    var failure = closeFailure;
                    closeFailure = null;
                    throw failure;
                }
                if (!active) return;
                active = false;
                owner.connections.remove(this);
                disconnected();
                if (other != null && other.active) {
                    other.active = false;
                    other.owner.connections.remove(other);
                    other.disconnected();
                }
            }

            private void disconnected() {
                try {
                    handler.onDisconnected(this);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }

            public SocketAddress remoteAddress() {
                return new InetSocketAddress("127.0.0.1", 12345);
            }

            public <T> T get(AttributeKey<T> key) {
                return key.cast(attributes.get(key));
            }

            public <T> void set(AttributeKey<T> key, T value) {
                if (value == null) attributes.remove(key);
                else attributes.put(key, value);
            }

            public <T> T remove(AttributeKey<T> key) {
                return key.cast(attributes.remove(key));
            }

            public <T> boolean compareAndSet(AttributeKey<T> key, T a, T b) {
                synchronized (attributes) {
                    if (attributes.get(key) != a) return false;
                    set(key, b);
                    return true;
                }
            }
        }
    }
}
