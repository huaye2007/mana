package com.github.huaye2007.mana.jpa.demo.domain;

import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Field;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.docdb.annotation.Indexed;
import com.github.huaye2007.mana.jpa.docdb.annotation.ShardKey;

import java.util.ArrayList;
import java.util.List;

@Document(collection = "player_profile")
public class PlayerProfile {

    @Id
    private long playerId;

    @ShardKey
    @Field(name = "server_id")
    private int serverId;

    @Indexed
    private String nickname;

    // 嵌套 POJO 自动映射为嵌套文档，无需注解
    private ProfileStats stats = new ProfileStats();

    private List<String> achievements = new ArrayList<>();

    public PlayerProfile() {
    }

    public PlayerProfile(long playerId, int serverId, String nickname) {
        this.playerId = playerId;
        this.serverId = serverId;
        this.nickname = nickname;
    }

    public long getPlayerId() {
        return playerId;
    }

    public int getServerId() {
        return serverId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public ProfileStats getStats() {
        return stats;
    }

    public List<String> getAchievements() {
        return achievements;
    }

    public static class ProfileStats {
        private int arenaRank;
        private int power;

        public int getArenaRank() {
            return arenaRank;
        }

        public void setArenaRank(int arenaRank) {
            this.arenaRank = arenaRank;
        }

        public int getPower() {
            return power;
        }

        public void setPower(int power) {
            this.power = power;
        }
    }
}
