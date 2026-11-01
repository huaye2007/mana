package cn.managame.example;

import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.IdGenerator;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.example.Messages.AskRank;
import cn.managame.example.Messages.Buy;
import cn.managame.example.Messages.Login;
import cn.managame.example.Messages.Logout;
import cn.managame.example.Messages.ShowBag;
import cn.managame.example.Model.Bags;
import cn.managame.example.Model.GoldLog;
import cn.managame.example.Model.GoldLogs;
import cn.managame.example.Model.Item;
import cn.managame.example.Model.Player;
import cn.managame.example.Model.Players;
import cn.managame.network.connection.Connection;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.time.GameTime;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every player request, on the player's own route: one player's requests run one at a time, so a handler changes the
 * player's data without locks. A non-null result is the answer; a handler that answers later returns nothing.
 */
@Domain(GameServer.PLAYER)
final class PlayerHandlers {
    private final GameRuntime runtime;
    private final Players players;
    private final Bags bags;
    private final GoldLogs goldLogs;
    private final RpcPeer world;
    private final IdGenerator itemIds;
    private final Map<Long, Connection> online;

    PlayerHandlers(GameRuntime runtime, GameData data, RpcPeer world, IdGenerator itemIds, Map<Long, Connection> online) {
        this.runtime = runtime;
        players = data.repository(Players.class);
        bags = data.repository(Bags.class);
        goldLogs = data.repository(GoldLogs.class);
        this.world = world;
        this.itemIds = itemIds;
        this.online = online;
    }

    /** Routed by the player logging in, the one request a connection sends before it acts for anyone. */
    @HandleMethod String login(HandleContext request, Login login) {
        Player player = players.getOrCreate(login.playerId(), id -> new Player(id, login.name()));
        Connection connection = ((Session) request.source()).connection();
        connection.set(Session.KEY, new Session(connection, player.id));
        online.put(player.id, connection);
        return "welcome " + player.name + ", gold " + player.gold;
    }

    @HandleMethod String buy(HandleContext request, Buy buy) {
        Integer price = Model.PRICES.get(buy.configId());
        if (price == null) {
            request.fail(GameServer.UNKNOWN_ITEM);
            return null;
        }
        Player player = players.get(request.businessId());
        if (player.gold < price) {
            request.fail(GameServer.NOT_ENOUGH_GOLD);
            return null;
        }
        player.gold -= price;
        players.update(player);
        Item item = bags.get(GroupKeys.of(player.id), buy.configId());
        if (item == null) {
            bags.insert(new Item(itemIds.generateId(), player.id, buy.configId()));
        } else {
            item.amount++;
            bags.update(item);
        }
        goldLogs.insert(new GoldLog(player.id, -price, "buy " + buy.configId(), GameTime.currentTimeMillis()));
        return "bought " + buy.configId() + ", gold " + player.gold;
    }

    @HandleMethod String bag(ShowBag show) {
        Map<Integer, Item> items = bags.getGroup(GroupKeys.of(Context.current().businessId()));
        if (items.isEmpty()) return "empty";
        return items.values().stream().map(item -> item.configId + "x" + item.amount).sorted().collect(Collectors.joining(" "));
    }

    /**
     * Asks the world server for the player's rank by gold. The answer arrives on an RPC thread, so the rest goes back
     * to the player's route with {@code runtime.execute(request, ...)} before it touches the player.
     */
    @HandleMethod void rank(HandleContext request, AskRank ask) {
        long playerId = request.businessId(), gold = players.get(playerId).gold;
        world.call(RpcRequest.of(WorldServer.RANK, playerId, Unpooled.buffer(8).writeLong(gold)), (call, response) -> {
            // The answer's body is released when this callback returns: read it here.
            int code = response.code();
            long rank = response.isSuccess() ? response.body().readLong() : 0, ranked = response.isSuccess() ? response.body().readLong() : 0;
            runtime.execute(request, _ -> {
                if (code != SUCCESS) {
                    request.fail(code);
                    return;
                }
                Player player = players.get(playerId);
                if (player.bestRank == 0 || rank < player.bestRank) {
                    player.bestRank = (int) rank;
                    players.update(player);
                }
                request.reply("rank " + rank + " of " + ranked);
            });
        });
    }

    @HandleMethod void logout(Logout logout) {
        players.update(Context.current().businessId(), player -> player.lastLogout = GameTime.currentTimeMillis());
    }
}
