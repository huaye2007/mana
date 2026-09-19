package cn.managame.example;

import cn.managame.core.GameData;

import cn.managame.docdb.mongodb.MongoDataAccess;

public final class MongoExample {
    public static void main(String[] args) {
        try (var gameData = new GameData(
                new MongoDataAccess("mongodb://127.0.0.1:27017", "game"))) {
            var players = gameData.repository(PlayerRepository.class);
            players.get(10001L).ifPresent(System.out::println);
        }
    }
}
