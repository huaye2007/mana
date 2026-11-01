package cn.managame.example;

import static cn.managame.FrameworkErrorCodes.SUCCESS;

import cn.managame.IdGenerator;
import cn.managame.data.GameData;
import cn.managame.data.GroupKeys;
import cn.managame.example.Model.Bags;
import cn.managame.example.Model.GoldLog;
import cn.managame.example.Model.GoldLogs;
import cn.managame.example.Model.Item;
import cn.managame.example.Model.Player;
import cn.managame.example.Model.Players;
import cn.managame.example.proto.BagRequest;
import cn.managame.example.proto.BagResponse;
import cn.managame.example.proto.BuyRequest;
import cn.managame.example.proto.BuyResponse;
import cn.managame.example.proto.ItemStack;
import cn.managame.example.proto.LoginRequest;
import cn.managame.example.proto.LoginResponse;
import cn.managame.example.proto.RankAnswer;
import cn.managame.example.proto.RankQuery;
import cn.managame.example.proto.RankRequest;
import cn.managame.example.proto.RankResponse;
import cn.managame.network.connection.Connection;
import cn.managame.rpc.RpcPeer;
import cn.managame.rpc.RpcRequest;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.time.GameTime;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Every player request, on the player's own route: one player's requests run one at a time, so a handler changes the
 * player's data without locks. A handler takes the protobuf request and returns the protobuf response, which the
 * gateway sends back; a handler that answers later returns nothing. A Spring component, given what it uses through its
 * constructor.
 */
@Component
@Domain(GameConfig.PLAYER)
final class PlayerHandlers {
    private final GameRuntime runtime;
    private final Players players;
    private final Bags bags;
    private final GoldLogs goldLogs;
    private final RpcPeer world;
    private final IdGenerator itemIds;
    private final OnlinePlayers online;

    PlayerHandlers(GameRuntime runtime, GameData data, RpcPeer world, IdGenerator itemIds, OnlinePlayers online) {
        this.runtime = runtime;
        players = data.repository(Players.class);
        bags = data.repository(Bags.class);
        goldLogs = data.repository(GoldLogs.class);
        this.world = world;
        this.itemIds = itemIds;
        this.online = online;
    }

    /** Routed by the player logging in, the one request a connection sends before it acts for anyone. */
    @HandleMethod LoginResponse login(HandleContext request, LoginRequest login) {
        Player player = players.getOrCreate(login.getPlayerId(), id -> new Player(id, login.getName()));
        Connection connection = ((Session) request.source()).connection();
        connection.set(Session.KEY, new Session(connection, player.id));
        online.add(player.id, connection);
        return LoginResponse.newBuilder().setName(player.name).setGold(player.gold).build();
    }

    @HandleMethod BuyResponse buy(HandleContext request, BuyRequest buy) {
        Integer price = Model.PRICES.get(buy.getConfigId());
        if (price == null) {
            request.fail(GameConfig.UNKNOWN_ITEM);
            return null;
        }
        Player player = players.get(request.businessId());
        if (player.gold < price) {
            request.fail(GameConfig.NOT_ENOUGH_GOLD);
            return null;
        }
        player.gold -= price;
        players.update(player);
        Item item = bags.get(GroupKeys.of(player.id), buy.getConfigId());
        if (item == null) {
            bags.insert(new Item(itemIds.generateId(), player.id, buy.getConfigId()));
        } else {
            item.amount++;
            bags.update(item);
        }
        goldLogs.insert(new GoldLog(player.id, -price, "buy " + buy.getConfigId(), GameTime.currentTimeMillis()));
        return BuyResponse.newBuilder().setConfigId(buy.getConfigId()).setGold(player.gold).build();
    }

    @HandleMethod BagResponse bag(BagRequest show) {
        BagResponse.Builder bag = BagResponse.newBuilder();
        bags.getGroup(GroupKeys.of(Context.current().businessId())).values().stream()
                .sorted(Comparator.comparingInt(item -> item.configId))
                .forEach(item -> bag.addItems(ItemStack.newBuilder().setConfigId(item.configId).setAmount(item.amount)));
        return bag.build();
    }

    /**
     * Asks the world server for the player's rank by gold. The answer arrives on an RPC thread, so the rest goes back
     * to the player's route with {@code runtime.execute(request, ...)} before it touches the player.
     */
    @HandleMethod void rank(HandleContext request, RankRequest ask) {
        long playerId = request.businessId();
        RankQuery query = RankQuery.newBuilder().setGold(players.get(playerId).gold).build();
        world.call(RpcRequest.of(WorldServer.RANK, playerId, Protocol.body(query)), (call, response) -> {
            // The answer's body is released when this callback returns: read it here.
            int code = response.code();
            RankAnswer answer = response.isSuccess() ? Protocol.read(RankAnswer.parser(), response.body()) : null;
            runtime.execute(request, _ -> {
                if (code != SUCCESS) {
                    request.fail(code);
                    return;
                }
                Player player = players.get(playerId);
                if (player.bestRank == 0 || answer.getRank() < player.bestRank) {
                    player.bestRank = (int) answer.getRank();
                    players.update(player);
                }
                request.reply(RankResponse.newBuilder().setRank(answer.getRank()).setRanked(answer.getRanked()).build());
            });
        });
    }

    @HandleMethod void logout(Gateway.Logout logout) {
        players.update(Context.current().businessId(), player -> player.lastLogout = GameTime.currentTimeMillis());
    }
}
