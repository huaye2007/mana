package cn.managame.demo.protocol.rpc;

import cn.managame.demo.protocol.CommandBinding;
import java.util.List;
import java.util.Map;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;

/** Server-to-server commands use negative IDs and their own DTOs. */
public final class RpcCommands {
    public static final CommandBinding<GrantGoldReq, GrantGoldRes> GRANT =
            new CommandBinding<>(GRANT_GOLD, GrantGoldReq.class, GrantGoldRes.class);
    public static final List<CommandBinding<?, ?>> COMMANDS = List.of(GRANT);
    public static final Map<Integer, Class<?>> MESSAGE_TYPES = Map.of(11, GrantGoldReq.class, 12, GrantGoldRes.class);

    private RpcCommands() {}
}
