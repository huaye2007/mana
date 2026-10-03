package cn.managame.demo.common.protocol;

import cn.managame.demo.network.message.DemoMessage;
import cn.managame.demo.network.message.PingMessage;
import cn.managame.demo.bus.user.LoginReq;
import cn.managame.demo.bus.user.LoginRes;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.protocol.ProtocolRegistrar;
import cn.managame.runtime.protocol.Protocols;
import org.apache.fory.ThreadSafeFory;

import java.util.List;

/** One bootstrap definition for Runtime commands and Fory type identities. */
public final class GameProtocols implements ProtocolProvider {
    private record Binding(ProtocolDescriptor<?> protocol, int foryTypeId) {}

    private static final List<Binding> BINDINGS = List.of(
            new Binding(Protocols.request(1001, DemoMessage.class), 1),
            new Binding(Protocols.request(1002, PingMessage.class), 2),
            new Binding(Protocols.request(1003, LoginReq.class), 3),
            new Binding(Protocols.response(1003, LoginRes.class), 4));

    @Override
    public void register(ProtocolRegistrar registrar) {
        BINDINGS.forEach(binding -> registrar.register(binding.protocol()));
        registrar.bindResponse(LoginReq.class, LoginRes.class);
    }

    public void registerFory(ThreadSafeFory fory) {
        BINDINGS.forEach(binding -> fory.register(binding.protocol().messageType(), binding.foryTypeId()));
    }
}
