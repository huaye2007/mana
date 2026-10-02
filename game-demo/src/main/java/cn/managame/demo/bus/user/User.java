package cn.managame.demo.bus.user;

import cn.managame.data.annotation.Id;
import cn.managame.data.mysql.Column;
import cn.managame.data.mysql.ColumnType;
import cn.managame.data.mysql.Table;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Table(name="user")
public class User {

    @Id
    @Column(name="user_id")
    private long userId;

    @Column(name="server_role_id", type = ColumnType.JSON)
    private Map<Integer,Long> serverRoleIdMap = new ConcurrentHashMap<>();


    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public Map<Integer, Long> getServerRoleIdMap() {
        return serverRoleIdMap;
    }

    public void setServerRoleIdMap(Map<Integer, Long> serverRoleIdMap) {
        this.serverRoleIdMap = serverRoleIdMap;
    }
}
