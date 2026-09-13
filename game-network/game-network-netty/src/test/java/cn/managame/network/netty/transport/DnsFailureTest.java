package cn.managame.network.netty.transport;

import cn.managame.network.netty.connection.NettyAccess;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.*;

import io.netty.bootstrap.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.*;
import io.netty.channel.socket.nio.*;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.GlobalEventExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local DNS fixture: no public DNS, search domains, or external services. */
@Timeout(30)
class DnsFailureTest {
    @Test
    void truncatedUdpAnswerFallsBackToNativeTcpForBothClients() throws Exception {
        try (var fixture = new DnsFixture();
                var resolver = fixture.resolver();
                var resources =
                        NetworkResources.builder().ioThreads(2).resolver(resolver).build()) {
            var tcp =
                    TcpNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 0)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            var ws =
                    WsNetworkServer.builder()
                            .resources(resources)
                            .listen("127.0.0.1", 0)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            var tcpClient =
                    TcpNetworkClient.builder()
                            .resources(resources)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            var wsClient =
                    WsNetworkClient.builder()
                            .resources(resources)
                            .handlerFactory(() -> (c, m) -> {})
                            .build();
            try {
                tcp.start();
                ws.start();
                tcpClient.init();
                wsClient.init();
                var first = new Result();
                tcpClient.connect(
                        "tcp.fallback.test.",
                        tcp.boundAddresses().values().iterator().next().getPort(),
                        first);
                assertEquals(ConnectionType.TCP, first.await().type());
                var second = new Result();
                wsClient.connect(
                        URI.create(
                                "ws://ws.fallback.test.:"
                                        + ws.boundAddresses().values().iterator().next().getPort()
                                        + "/"),
                        second);
                assertEquals(ConnectionType.WS, second.await().type());
                assertEquals(1, first.calls.get());
                assertEquals(1, second.calls.get());
                assertTrue(fixture.tcpQueries.get() >= 2);
            } finally {
                wsClient.destroy();
                tcpClient.destroy();
                ws.stop();
                tcp.stop();
            }
        }
    }

    @Test
    void dnsFailureTimeoutAndDestroyReportOnceAndCloseChannels() throws Exception {
        try (var fixture = new DnsFixture();
                var resolver = fixture.resolver();
                var resources =
                        NetworkResources.builder().ioThreads(2).resolver(resolver).build()) {
            for (boolean websocket : new boolean[] {false, true}) {
                var created = new ConcurrentLinkedQueue<Channel>();
                var tcp =
                        TcpNetworkClient.builder()
                                .resources(resources)
                                .pipeline((c, p) -> created.add(NettyAccess.channel(c)))
                                .handlerFactory(() -> (c, m) -> {})
                                .build();
                var ws =
                        WsNetworkClient.builder()
                                .resources(resources)
                                .pipeline((c, p) -> created.add(NettyAccess.channel(c)))
                                .handlerFactory(() -> (c, m) -> {})
                                .build();
                NetworkClient client = websocket ? ws : tcp;
                client.init();
                try {
                    for (String host : new String[] {"missing.test.", "silent.test."}) {
                        var result = new Result();
                        if (websocket) ws.connect(URI.create("ws://" + host + ":12345/"), result);
                        else tcp.connect(host, 12345, result);
                        var failure = assertThrows(ExecutionException.class, result::await);
                        assertInstanceOf(UnknownHostException.class, failure.getCause());
                        assertEquals(1, result.calls.get());
                    }
                    var pending = new Result();
                    if (websocket)
                        ws.connect(URI.create("ws://destroy.silent.test.:12345/"), pending);
                    else tcp.connect("destroy.silent.test.", 12345, pending);
                    client.destroy();
                    assertThrows(ExecutionException.class, pending::await);
                    for (var channel : created) {
                        assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS));
                        channel.eventLoop().submit(() -> {}).sync();
                    }
                    assertEquals(1, pending.calls.get());
                    assertFalse(resources.isClosed());
                } finally {
                    client.destroy();
                }
            }
        }
    }

    private static final class Result implements ConnectCallback {
        final CompletableFuture<Connection> future = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();

        public void onSuccess(Connection connection) {
            calls.incrementAndGet();
            future.complete(connection);
        }

        public void onFailure(Throwable failure) {
            calls.incrementAndGet();
            future.completeExceptionally(failure);
        }

        Connection await() throws Exception {
            return future.get(5, TimeUnit.SECONDS);
        }
    }

    private static final class DnsFixture implements AutoCloseable {
        final NetworkResources resources = NetworkResources.builder().ioThreads(1).build();
        final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final AtomicInteger tcpQueries = new AtomicInteger();
        final InetSocketAddress address;

        DnsFixture() throws Exception {
            try {
                var tcp =
                        new ServerBootstrap()
                                .group(resources.io())
                                .channel(NioServerSocketChannel.class)
                                .childHandler(
                                        new ChannelInitializer<Channel>() {
                                            protected void initChannel(Channel ch) {
                                                channels.add(ch);
                                                ch.pipeline()
                                                        .addLast(
                                                                new TcpDnsQueryDecoder(),
                                                                new TcpDnsResponseEncoder(),
                                                                new SimpleChannelInboundHandler<
                                                                        DnsQuery>() {
                                                                    protected void channelRead0(
                                                                            ChannelHandlerContext
                                                                                    ctx,
                                                                            DnsQuery query) {
                                                                        tcpQueries
                                                                                .incrementAndGet();
                                                                        var response =
                                                                                new DefaultDnsResponse(
                                                                                        query.id());
                                                                        answer(query, response);
                                                                        ctx.writeAndFlush(response);
                                                                    }
                                                                });
                                            }
                                        })
                                .bind("127.0.0.1", 0)
                                .sync()
                                .channel();
                channels.add(tcp);
                address = (InetSocketAddress) tcp.localAddress();
                var udp =
                        new Bootstrap()
                                .group(resources.io())
                                .channel(NioDatagramChannel.class)
                                .handler(
                                        new ChannelInitializer<Channel>() {
                                            protected void initChannel(Channel ch) {
                                                ch.pipeline()
                                                        .addLast(
                                                                new DatagramDnsQueryDecoder(),
                                                                new DatagramDnsResponseEncoder(),
                                                                new SimpleChannelInboundHandler<
                                                                        DatagramDnsQuery>() {
                                                                    protected void channelRead0(
                                                                            ChannelHandlerContext
                                                                                    ctx,
                                                                            DatagramDnsQuery
                                                                                    query) {
                                                                        DnsQuestion question =
                                                                                query.recordAt(
                                                                                        DnsSection
                                                                                                .QUESTION);
                                                                        if (question.name()
                                                                                .contains("silent"))
                                                                            return;
                                                                        var response =
                                                                                new DatagramDnsResponse(
                                                                                        query
                                                                                                .recipient(),
                                                                                        query
                                                                                                .sender(),
                                                                                        query.id());
                                                                        response.addRecord(
                                                                                DnsSection.QUESTION,
                                                                                question);
                                                                        if (question.name()
                                                                                .startsWith(
                                                                                        "missing"))
                                                                            response.setCode(
                                                                                    DnsResponseCode
                                                                                            .NXDOMAIN);
                                                                        else
                                                                            response.setTruncated(
                                                                                    true);
                                                                        ctx.writeAndFlush(response);
                                                                    }
                                                                });
                                            }
                                        })
                                .bind(address)
                                .sync()
                                .channel();
                channels.add(udp);
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        DnsAddressResolverGroup resolver() {
            return new DnsAddressResolverGroup(
                    new DnsNameResolverBuilder()
                            .datagramChannelType(NioDatagramChannel.class)
                            .socketChannelType(NioSocketChannel.class)
                            .nameServerProvider(
                                    new SingletonDnsServerAddressStreamProvider(address))
                            .resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                            .searchDomains(List.of())
                            .queryTimeoutMillis(150)
                            .maxQueriesPerResolve(2));
        }

        private static void answer(DnsQuery query, DnsResponse response) {
            DnsQuestion question = query.recordAt(DnsSection.QUESTION);
            response.addRecord(DnsSection.QUESTION, question);
            response.addRecord(
                    DnsSection.ANSWER,
                    new DefaultDnsRawRecord(
                            question.name(),
                            DnsRecordType.A,
                            60,
                            Unpooled.wrappedBuffer(new byte[] {127, 0, 0, 1})));
        }

        public void close() {
            channels.close().awaitUninterruptibly();
            resources.close();
        }
    }
}
