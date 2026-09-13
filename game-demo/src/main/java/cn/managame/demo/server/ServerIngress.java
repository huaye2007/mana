package cn.managame.demo.server;

import cn.managame.demo.protocol.CommandBinding;
import cn.managame.demo.protocol.client.ClientCommands;
import cn.managame.demo.protocol.rpc.RpcCommands;

import java.util.List;

/** Independent transport exposure lists for separate client and internal contracts. */
public final class ServerIngress {
    public static final List<CommandBinding<?, ?>> TCP = List.of(ClientCommands.SPEND, ClientCommands.WALLET);
    public static final List<CommandBinding<?, ?>> RPC = RpcCommands.COMMANDS;

    private ServerIngress() {}
}
