# 2026-09-06 Linux 回归与容量验证

本次三种 Server 的 Channel 创建失败清理问题已修复。Windows / Oracle JDK 25.0.3 与 Linux Docker / Temurin 25.0.4 的功能回归各 37 项通过，无失败、错误或跳过。功能覆盖包括 TCP、WS/WSS、HTTP、多目标并发、管线扩展、资源共享、DNS 故障、慢客户端和失败回滚。

Linux 环境为 Docker Desktop 上的 Linux x86_64 / WSL2 内核 6.6.114.1；使用固定 digest 的官方 Maven / Temurin 25 镜像，容器限制 4 CPU、6 GiB，nofile=65536。容量测试 JVM 参数为 -Xms512m -Xmx3g -XX:MaxDirectMemorySize=1g，Netty 4.2.15.Final、NIO、4 个共享 IO 线程，开启 paranoid 泄漏检测。WSS 使用 JDK TLS 和测试证书的 localhost 主机名验证。

## 容量用例结果

每连接每轮回写 16 字节业务负载，每秒最多开始一轮。客户端与服务端同 JVM；建连驱动每批最多并发 256 次，全部连接就绪后持续收发，最后关闭并重建四分之一连接并再次全量回写。各协议分别运行，未并行争抢测试容器资源。

| 协议 | 同时保持连接数 | 持续收发秒数 | 核对回写次数 | 重建连接数 | 结果 |
|---|---:|---:|---:|---:|---|
| TCP | 10000 | 120 | 1210000 | 2500 | 通过 |
| WS | 2000 | 60 | 122000 | 500 | 通过 |
| WSS | 1000 | 60 | 61000 | 250 | 通过 |

各次运行均未出现消息核对失败或 Netty 泄漏日志。以下延迟未剔除冷启动轮次，包含批量发送、测试驱动与 EventLoop 排队、回写开销，不是生产网络延迟承诺：

| 协议 | 全部建连耗时 ms | 回写 RTT P50 ms | 回写 RTT P99 ms |
|---|---:|---:|---:|
| TCP | 671 | 215.907 | 329.676 |
| WS | 935 | 49.258 | 148.09 |
| WSS | 3322 | 51.224 | 123.275 |

## 资源采样

读取 Channel 实际使用的 AdaptiveByteBufAllocator，避免把未使用的 PooledByteBufAllocator.DEFAULT 的零值误当成堆外内存用量。下列峰值是建连完成、每轮结束和关闭阶段的采样最大值，不是全程瞬时峰值；堆内存包含客户端、服务端、测试驱动和 GC 尚未回收的对象。

| 协议 | 堆内存采样峰值 MiB | allocator 直接内存采样峰值 MiB | 关闭后 allocator 直接内存 MiB | 线程采样峰值 | 持续阶段平均占用 CPU 核数 |
|---|---:|---:|---:|---:|---:|
| TCP | 533.5 | 3.5 | 0 | 14 | 1 |
| WS | 312.5 | 3.5 | 0 | 14 | 0.26 |
| WSS | 328.8 | 4.5 | 0 | 14 | 0.29 |

CPU 核数按相邻持续收发采样之间的进程累计 CPU 时间 / 墙钟时间计算，包含负载驱动，1.0 表示平均占用一个逻辑 CPU。直接内存归零是本次观察结果，不要求所有原生分配器在关闭后都立即释放复用池。

| 协议 | 建压前 FD | FD 采样峰值 | 资源关闭后 FD |
|---|---:|---:|---:|
| TCP | 32 | 20048 | 33 |
| WS | 32 | 4050 | 35 |
| WSS | 32 | 2050 | 35 |

FD 包含同 JVM 两端的套接字、Selector 和 JVM 文件，资源关闭后回到基线附近。测试同时确认服务端活动连接计数归零。

## 复现与边界

命令和接入配置见 [运行验证](operations.md)。固定保存的最终原始采样：[TCP](validation/2026-09-06/tcp.json)、[WS](validation/2026-09-06/ws.json)、[WSS](validation/2026-09-06/wss.json)。完整构建日志和 JUnit XML 位于本机 target/linux-verify-tcp、target/linux-load-tcp、target/linux-load-ws、target/linux-load-wss。

本次完成的是功能与短时容量回归，证明了上述规模、负载和环境下的连接、回写及关闭行为。尚不能据此给出真实业务的吞吐上限或线上 SLA；小时/天级稳定性、真实跨机网络、实际业务编解码及负载分布仍需在部署环境验收。未测试 epoll / io_uring、OpenSSL、IPv6 和所有网络故障组合。

GitHub Actions 的 Windows / Ubuntu 工作流已写入仓库目录，但本地尚无 Git 仓库及远程地址，因此没有触发云端 CI。Docker 测试容器均使用 --rm，完成后自动删除；镜像保留，便于复现。
