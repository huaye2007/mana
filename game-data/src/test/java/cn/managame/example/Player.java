package cn.managame.example;

import cn.managame.annotation.*;

@Table("player")
@Document("player")
public class Player {
    @Id
    private long id;

    @Indexed(name = "idx_player_name")
    @Column("name")
    @Field("name")
    private String name;

    private int level;

    @Transient
    private long runtimeOnlyValue;

    public Player() {}

    public Player(long id, String name, int level) {
        this.id = id;
        this.name = name;
        this.level = level;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
}
