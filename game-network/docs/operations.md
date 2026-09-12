# 接入与运行验证

本文对应 Java 25 / Netty 4.2，补充应用接入和部署验证，不向组件增加收发限额、队列或业务状态机。

## 原生监控 Handler

可编译示例：[TransportMetricsHandler](../game-network-netty/src/test/java/cn/managame/network/tests/TransportMetricsHandler.java)。它位于测试源码，不进入组件 JAR；应用可以复制并接入自己的指标系统。

```java
var counters = new TransportMetricsHandler.Counters();
var server = TcpNetworkServer.builder()
    .listen("0.0.0.0", 7000)
    .handlerFactory(GameHandler::new)
    .pipeline((connection, pipeline) -> {
        pipeline.addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
        pipeline.addLast("frameEncoder", new LengthFieldPrepender(4));
        pipeline.addLast("metrics", new TransportMetricsHandler(counters));
    }).build();
```

WS/WSS 同样在 pipeline 回调里添加 metrics，位于原生协议处理器之后、NetworkHandlerBridge 之前。应在连接激活前安装，每个 Channel 一个实例；Counters 可以按监听端口、协议共享，避免为每个连接标识创建指标标签。示例不支持运行中添加/移除时自动校正连接计数。

- activeTransports 统计 TCP 传输连接，不等于玩家数或完成握手的连接数。业务就绪数可以在 NetworkHandler.onConnected / onDisconnected 中统计。
- websocketHandshakes 统计成功握手；tlsFailures 和 handshakeTimeouts 分别统计 TLS 失败、原生 WS 超时。客户端所有建连失败仍以 ConnectCallback.onFailure 为完整入口，包括 DNS、拒绝连接和管线初始化失败。
- writeFailures 监听经过该 Handler 的原生写 Promise，不合成 NetworkHandler.onException。已关闭连接的 connection.write(false) 没有提交原生写入，应由调用方处理。位于其前方的 Handler 从自身 ctx 发出的写操作可能绕过该指标。
- nonWritableTransitions 记录不可写状态变化次数，不实施限流或丢弃。慢客户端处理由应用选择原生 channelWritabilityChanged、AUTO_READ、水位配置或业务降速策略。组件保持 Netty 默认收发行为。
- idleEvents / exceptions 观察并继续传播原生事件。空闲时是否发送业务心跳或关闭连接由 NetworkHandler.onIdle 决定。

应用若选择读空闲即断开，可以在自己的 NetworkHandler 中实现下面的方法，并显式配置 READ_IDLE；这不是组件默认策略：

~~~java
@Override
public void onIdle(Connection connection, IdleType type) {
    if (type == IdleType.READ) connection.close();
}
~~~

定时从这些计数器采样并交给应用已有的监控系统；不要在 IO 线程里同步请求监控服务或为每条消息打印日志。示例没有绑定 Micrometer、Prometheus 或特定日志实现。

## DNS TCP 回退

默认解析器仍遵循 Netty 默认配置。需要在 DNS UDP 响应被截断时回退 TCP，可通过已有 resolver 入口配置原生 DnsAddressResolverGroup：

```java
var resolver = new DnsAddressResolverGroup(new DnsNameResolverBuilder()
    .datagramChannelType(NioDatagramChannel.class)
    .socketChannelType(NioSocketChannel.class));
try (var resources = NetworkResources.builder().resolver(resolver).build()) {
    var client = TcpNetworkClient.builder().resources(resources)
        .handlerFactory(GameHandler::new).build();
    try {
        client.init();
        client.connect("game.internal", 7000, callback);
        // 应用在这里保持运行。
    } finally {
        try { client.destroy(); } finally { resolver.close(); }
    }
}
```

外部 resolver 由调用方负责关闭。配置对应 NIO；使用其他原生 transport 时，解析器 Channel 工厂应与 EventLoop 配套。是否还在 DNS 超时时回退 TCP、查询期限、服务器地址、缓存和 IPv4/IPv6 策略，均通过 Netty 的 DnsNameResolverBuilder 配置，不增加组件 DNS 配置层。[Netty 原生说明](https://netty.io/4.2/api/io/netty/resolver/dns/DnsNameResolverBuilder.html)

DnsFailureTest 使用本机临时 DNS 服务验证截断响应的 TCP 回退、NXDOMAIN、查询超时及解析期间销毁，覆盖 TCP 和 WS 客户端，不访问公网 DNS。这里的 UDP 仅用于 DNS 测试，不表示 game-network 增加了 UDP 协议入口。

## Windows 与 Linux 功能回归

日常执行 `mvn verify`。GitHub Actions 配置位于 [.github/workflows/verify.yml](../.github/workflows/verify.yml)，对独立 game-network 仓库运行 Windows / Ubuntu、Temurin 25 测试，并上传报告。当前工作目录尚无 Git 仓库和远程地址，CI 文件已准备好，未实际触发云端 Actions。若作为父仓库子目录使用，需要将 workflow 放到父仓库的 .github/workflows，并设置工作目录。

本地 Docker Desktop 使用 Linux containers，执行：

```powershell
./scripts/test-linux.ps1
# 已缓存全部 Maven 依赖时可加 -Offline。
```

脚本使用固定 digest 的官方 Maven / Temurin 25 镜像；仅只读挂载源码与本地 .m2 缓存，在容器内独立复制、编译，报告输出到 target/linux-verify-tcp。容器限制 4 CPU、6 GiB 内存，nofile=65536，不映射业务监听端口、不挂载 Docker socket，完成后自动删除容器。它不会关闭其他容器或修改宿主机系统网络参数。

## 容量与持续收发

[NetworkLoadIT](../game-network-netty/src/test/java/cn/managame/network/tests/NetworkLoadIT.java) 不参加默认 verify，通过显式 test 参数运行。可调整协议、总连接数、持续秒数：

```powershell
./scripts/test-linux.ps1 -Mode load -Protocol tcp -Connections 10000 -Seconds 60 -Offline
./scripts/test-linux.ps1 -Mode load -Protocol ws -Connections 2000 -Seconds 60 -Offline
./scripts/test-linux.ps1 -Mode load -Protocol wss -Connections 1000 -Seconds 60 -Offline
```

也可以直接在已安装 JDK 25 / Maven 的目标 Linux 环境执行：

```bash
mvn -B -Dtest=NetworkLoadIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dnetwork.load.protocol=tcp -Dnetwork.load.connections=10000 \
  -Dnetwork.load.seconds=60 test
```

测试驱动每批最多并发建立 256 条连接，最终保持指定总数；这是驱动的建压方式，不是组件限额。每轮每连接回写 16 字节业务负载，校验连接索引、轮次和重复消息，每秒最多启动一轮。结束后关闭并重建四分之一连接，再对所有连接回写验证；关闭使用原生路径，并在该批连接上配置 SO_LINGER=0。WSS 使用测试证书、JDK TLS 和主机名验证。

JSON 报告记录建连时间、回写数量与 RTT P50/P99，以及各轮堆内存、直接缓冲池、实际 Channel 分配器的堆/直接内存、线程数、文件描述符、累计进程 CPU 时间。FD 在资源关闭后应回到基线附近。报告记录实际 allocator 类型；JDK 直接缓冲指标未必包含 Netty 的全部堆外分配，应同时查看 allocator 指标。池化内存可能为复用而保留，不以“关闭后池化内存立即为零”为验收条件。SlowPeerTest 单独验证客户端停止读取时的原生水位通知、待写缓冲以及关闭释放。

测试开启 paranoid 泄漏检测；Linux 脚本和 CI 在检测到 Netty 泄漏日志时失败。不过一次未报泄漏不等于已经证明不存在泄漏。客户端和服务端运行在同一个 JVM、同一台机器，RTT 包含驱动提交、IO 排队和回写，不能当作跨机生产延迟或吞吐上限。

本地短时容量回归之后，上线验收仍应使用实际业务编解码、负载分布、目标硬件和网络，延长持续时间并检查资源趋势。epoll / io_uring、OpenSSL、IPv6 和长时间网络故障属于独立验证组合；没有运行的组合不写成已通过。
