package cn.managame.demo.protocol.client;

import cn.managame.demo.protocol.CommandBinding;

import java.util.List;
import java.util.Map;

import static cn.managame.demo.protocol.client.ClientProtocol.*;

/** Client-facing contracts only; internal RPC has a separate catalog. */
public final class ClientCommands {
    public static final CommandBinding<SpendGoldReq, SpendGoldRes> SPEND =
            new CommandBinding<>(SPEND_GOLD, SpendGoldReq.class, SpendGoldRes.class);
    public static final CommandBinding<GetWalletReq, GetWalletRes> WALLET =
            new CommandBinding<>(GET_WALLET, GetWalletReq.class, GetWalletRes.class);

    public static final List<CommandBinding<?, ?>> COMMANDS = List.of(SPEND, WALLET);

    // Wire type IDs are independent of commands. Retired IDs 1, 2, 5, 6 must not be reused.
    public static final Map<Integer, Class<?>> MESSAGE_TYPES = Map.of(
            3, SpendGoldRes.class, 4, ErrorRes.class, 7, GetWalletRes.class, 8, WalletChangedNotify.class,
            9, SpendGoldReq.class, 10, GetWalletReq.class);

    /** Server-to-client messages with no request or response contract. */
    public static final Map<Integer, Class<?>> NOTIFICATIONS = Map.of(WALLET_CHANGED, WalletChangedNotify.class);

    public static int notificationId(Class<?> type) {
        return NOTIFICATIONS.entrySet().stream().filter(entry -> entry.getValue() == type)
                .mapToInt(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown notification type: " + type.getName()));
    }

    private ClientCommands() {}
}
