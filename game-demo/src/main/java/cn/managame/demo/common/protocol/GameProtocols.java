package cn.managame.demo.common.protocol;

import cn.managame.demo.network.message.DemoMessage;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.bus.user.LoginRes;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import org.apache.fory.ThreadSafeFory;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** One bootstrap definition for Runtime commands and Fory type identities. */
public final class GameProtocols {
    private record Binding(ProtocolDescriptor protocol, int foryTypeId) {}

    private static final List<Binding> BINDINGS = List.of(
            new Binding(ProtocolDescriptor.request(1001, DemoMessage.class), 1),
            new Binding(ProtocolDescriptor.request(1002, PingMessage.class), 2),
            new Binding(ProtocolDescriptor.request(1003, LoginReq.class, LoginRes.class), 3),
            new Binding(ProtocolDescriptor.response(1003, LoginRes.class), 4));
    private static final ProtocolRegistry REGISTRY =
            ProtocolRegistry.of(BINDINGS.stream().map(Binding::protocol).toList());

    @Bean
    public ProtocolRegistry registry() { return REGISTRY; }

    public void registerFory(ThreadSafeFory fory) {
        BINDINGS.forEach(binding -> fory.register(binding.protocol().messageType(), binding.foryTypeId()));
    }
}
