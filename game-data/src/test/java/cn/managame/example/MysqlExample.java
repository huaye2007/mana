package cn.managame.example;

import cn.managame.core.GameData;

import cn.managame.rdb.DriverManagerDataSource;
import cn.managame.rdb.RdbDataAccess;
import cn.managame.rdb.dialect.MySqlDialect;

public final class MysqlExample {
    public static void main(String[] args) {
        var dataSource = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/game", "root", "root");

        try (var gameData = new GameData(new RdbDataAccess(dataSource, new MySqlDialect()))) {
            PlayerRepository players = gameData.repository(PlayerRepository.class);
            Player player = new Player(10001L, "alice", 1);
            players.insert(player);
            player.setLevel(2);
            players.update(player);
            gameData.flush();
        }
    }
}
