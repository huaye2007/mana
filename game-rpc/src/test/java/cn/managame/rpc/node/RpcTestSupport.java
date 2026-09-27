package cn.managame.rpc.node;

import cn.managame.network.connection.*;
import cn.managame.rpc.call.*;
import cn.managame.rpc.message.*;
import cn.managame.rpc.netty.RpcWire;
import io.netty.buffer.*;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeAll;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class RpcTestSupport {
    static final InetSocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 0);
    @BeforeAll static void environment() throws Exception {
        System.setProperty("io.netty.eventLoopThreads", "2");
        System.setProperty("io.netty.leakDetection.level", "paranoid");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path marker = Path.of("target/tcp-pipe-only").toAbsolutePath();
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "test-only selector pipe TCP fallback");
            System.setProperty("jdk.net.unixdomain.tmpdir", marker.toString());
        }
    }
    static RpcNode node(int id, Probe probe) {
        RpcNode node = RpcNode.builder().nodeId(id).bindAddress(LOCAL).handler(probe).build();
        probe.node = node;
        node.start();
        return node;
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }
    static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(value);
        return value;
    }
    static void receive(RpcNode node, Fake connection, ByteBuf wire) {
        try { wire.skipBytes(4); node.receive(connection, wire); }
        finally { wire.release(); }
    }
    static Fake bind(RpcNode node, int peerId, int slot, int count) {
        Fake c = new Fake();
        node.connected(c);
        receive(node, c, RpcWire.encodeHandshake(new RpcHandshake(peerId, slot, count)));
        assertTrue(c.active);
        c.frames.clear();
        return c;
    }
    record Request(int source, int slot, int id, int command, long route, byte[] body) {}
    record Result(int source, int command, int id, int error, byte[] body) {}
    static class Probe implements RpcHandler {
        RpcNode node;
        volatile Consumer<Request> onRequest;
        volatile boolean throwResponse, throwFailure;
        final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        final BlockingQueue<Result> responses = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> failures = new LinkedBlockingQueue<>();
        public void onRequest(int source, int slot, RpcRequest request) {
            Request value = new Request(source, slot, request.requestId(), request.command(), request.routeKey(),
                    request.body() == null ? new byte[0] : ByteBufUtil.getBytes(request.body()));
            requests.add(value);
            if (onRequest != null) onRequest.accept(value);
        }
        public void onResponse(int source, int command, RpcResponse response, RpcCallback<?> callback) {
            responses.add(new Result(source, command, response.requestId(), response.errorCode(),
                    response.body() == null ? new byte[0] : ByteBufUtil.getBytes(response.body())));
            if (throwResponse) throw new IllegalStateException("response handler test");
        }
        public void onFail(int target, int command, int error, RpcCallback<?> callback) {
            failures.add(error);
            if (throwFailure) throw new IllegalStateException("failure handler test");
        }
    }
    static class Fake implements Connection {
        volatile boolean active = true, writable = true;
        volatile WriteStatus status = WriteStatus.ACCEPTED;
        volatile Consumer<ByteBuf> onWrite;
        final List<byte[]> frames = new CopyOnWriteArrayList<>();
        final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
        public boolean isActive() { return active; }
        public boolean isWritable() { return writable; }
        public WriteStatus write(Object message) {
            if (!active) return WriteStatus.INACTIVE;
            if (!writable) return WriteStatus.NOT_WRITABLE;
            if (status != WriteStatus.ACCEPTED) return status;
            ByteBuf b = (ByteBuf) message;
            frames.add(ByteBufUtil.getBytes(b));
            if (onWrite != null) onWrite.accept(b);
            b.release();
            return WriteStatus.ACCEPTED;
        }
        public void close() { active = false; }
        public SocketAddress localAddress() { return LOCAL; }
        public SocketAddress remoteAddress() { return LOCAL; }
        @SuppressWarnings("unchecked") public <T> T get(AttributeKey<T> key) { return (T) attributes.get(key); }
        public <T> void set(AttributeKey<T> key, T value) { attributes.put(key, value); }
        @SuppressWarnings("unchecked") public <T> T remove(AttributeKey<T> key) { return (T) attributes.remove(key); }
    }
}

