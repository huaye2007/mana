package com.github.huaye2007.mana.dev.bus.login;

import com.github.huaye2007.mana.jpa.rdb.annotation.Column;
import com.github.huaye2007.mana.jpa.rdb.annotation.ColumnType;
import com.github.huaye2007.mana.jpa.rdb.annotation.Entity;
import com.github.huaye2007.mana.jpa.rdb.annotation.Id;
import com.github.huaye2007.mana.jpa.rdb.annotation.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name="user")
public class User {

    @Id
    @Column(name="user_id")
    private long userId;

    @Column(name="role_ids",defaultValue = "{}")
    private Map<Integer,Long> roleMap = new HashMap<>();

    @Column(name="create_time",defaultValue = "0")
    private long createTime;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Map<Integer, Long> getRoleMap() {
        return roleMap;
    }

    public void setRoleMap(Map<Integer, Long> roleMap) {
        this.roleMap = roleMap;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
