package com.github.huaye2007.mana.jpa.demo.domain;

import com.github.huaye2007.mana.jpa.rdb.annotation.Column;
import com.github.huaye2007.mana.jpa.rdb.annotation.Entity;
import com.github.huaye2007.mana.jpa.rdb.annotation.Id;
import com.github.huaye2007.mana.jpa.rdb.annotation.Index;
import com.github.huaye2007.mana.jpa.rdb.annotation.ShardKey;
import com.github.huaye2007.mana.jpa.rdb.annotation.Table;
import com.github.huaye2007.mana.jpa.rdb.annotation.Version;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "player_account")
@Index(name = "idx_server_level", columns = {"server_id", "level"})
@Index(name = "uk_name", columns = {"name"}, unique = true)
public class PlayerAccount {

    @Id
    @Column
    private long playerId;

    @ShardKey
    @Column(name = "server_id")
    private int serverId;

    @Column(length = 64)
    private String name;

    @Column
    private int level;

    @Column
    private long gold;

    // 复杂类型（Map）默认按 JSON 列存储；仍需 @Column 才会映射为列
    @Column
    private Map<String, Integer> bag = new LinkedHashMap<>();

    @Version
    @Column
    private long version;

    @Column
    private Instant updatedAt;

    public PlayerAccount() {
    }

    public PlayerAccount(long playerId, int serverId, String name, int level, long gold) {
        this.playerId = playerId;
        this.serverId = serverId;
        this.name = name;
        this.level = level;
        this.gold = gold;
        this.updatedAt = Instant.now();
    }

    public long getPlayerId() {
        return playerId;
    }

    public int getServerId() {
        return serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getGold() {
        return gold;
    }

    public void setGold(long gold) {
        this.gold = gold;
    }

    public Map<String, Integer> getBag() {
        return bag;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
