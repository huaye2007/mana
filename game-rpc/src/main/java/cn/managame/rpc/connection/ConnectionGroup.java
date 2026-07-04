package cn.managame.rpc.connection;

import java.util.Arrays;


public final class ConnectionGroup {

    static final RpcConnection[] EMPTY = new RpcConnection[0];

    private volatile RpcConnection[] connections = EMPTY;

    /**
     * 按 routeKey 环形取模选一条活跃连接：从 {@code routeKey % len} 起向后环形扫描，
     * 跳过 null 槽位与不活跃连接，全不可用返回 null。读传入的快照、热路径零分配。
     */
    public static RpcConnection ring(RpcConnection[] connections, long routeKey) {
        int count = connections.length;
        if (count == 0) {
            return null;
        }
        int start = (int) Math.floorMod(routeKey, count);
        for (int i = 0; i < count; i++) {
            RpcConnection connection = connections[(start + i) % count];
            if (connection != null && connection.isActive()) {
                return connection;
            }
        }
        return null;
    }

    /** 读 volatile 快照后环形选择，零分配。 */
    public RpcConnection select(long routeKey) {
        return ring(connections, routeKey);
    }

    /** 登记一条连接（写时复制追加）。 */
    public synchronized void add(RpcConnection connection) {
        RpcConnection[] old = connections;
        RpcConnection[] next = Arrays.copyOf(old, old.length + 1);
        next[old.length] = connection;
        connections = next;
    }

    /** 摘除一条连接（写时复制压缩），不在组内返回 false。 */
    public synchronized boolean remove(RpcConnection connection) {
        RpcConnection[] old = connections;
        int index = -1;
        for (int i = 0; i < old.length; i++) {
            if (old[i] == connection) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return false;
        }
        RpcConnection[] next = new RpcConnection[old.length - 1];
        System.arraycopy(old, 0, next, 0, index);
        System.arraycopy(old, index + 1, next, index, old.length - index - 1);
        connections = next;
        return true;
    }

    /** 当前连接快照（只读，禁止修改），遍历期间不保证强一致。 */
    public RpcConnection[] snapshot() {
        return connections;
    }

    public int count() {
        return connections.length;
    }

    public boolean isEmpty() {
        return connections.length == 0;
    }
}
