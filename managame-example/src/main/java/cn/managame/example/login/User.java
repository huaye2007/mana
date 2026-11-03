package cn.managame.example.login;

import cn.managame.data.annotation.Id;
import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.mysql.annotation.Table;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Table(name = "user")
public class User {

    @Id
    @Column(name = "user_id")
    private long userId;

    @Column(name = "role_id_map")
    private Map<Integer,Long> roleIdMap = new ConcurrentHashMap<>();

    public Map<Integer, Long> getRoleIdMap() {
        return roleIdMap;
    }

    public void setRoleIdMap(Map<Integer, Long> roleIdMap) {
        this.roleIdMap = roleIdMap;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }
}
