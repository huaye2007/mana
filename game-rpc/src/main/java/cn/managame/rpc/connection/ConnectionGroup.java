package cn.managame.rpc.connection;

import java.util.Arrays;


public final class ConnectionGroup {

    static final RpcConnection[] EMPTY = new RpcConnection[0];

    private volatile RpcConnection[] connections = EMPTY;
    private volatile boolean fixedSlots;

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
        if (fixedSlots) {
            throw new IllegalStateException("fixed-slot connection group requires setSlot()");
        }
        RpcConnection[] old = connections;
        RpcConnection[] next = Arrays.copyOf(old, old.length + 1);
        next[old.length] = connection;
        connections = next;
    }

    /**
     * 把连接组切换为固定槽位模式。客户端连接组使用该模式，使同一个 routeKey 在断线、重连后
     * 仍然优先命中相同槽位；服务端动态入站连接继续使用追加模式。
     */
    public synchronized void configureFixedSlots(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("fixed slot size must be > 0, got " + size);
        }
        if (fixedSlots) {
            if (connections.length != size) {
                throw new IllegalStateException("fixed slot size already configured as " + connections.length);
            }
            return;
        }
        if (connections.length != 0) {
            throw new IllegalStateException("cannot configure fixed slots after connections were added");
        }
        fixedSlots = true;
        connections = new RpcConnection[size];
    }

    /** 固定槽位模式下登记连接；不会改变数组顺序或容量。 */
    public synchronized void setSlot(int index, RpcConnection connection) {
        if (!fixedSlots) {
            throw new IllegalStateException("connection group is not in fixed-slot mode");
        }
        if (index < 0 || index >= connections.length) {
            throw new IndexOutOfBoundsException("slot index " + index + " out of [0, " + connections.length + ")");
        }
        RpcConnection[] next = connections.clone();
        RpcConnection old = next[index];
        if (old != null && old != connection) {
            throw new IllegalStateException("slot " + index + " is already occupied");
        }
        next[index] = connection;
        connections = next;
    }

    /** 摘除一条连接；固定槽位模式置空该槽，动态模式写时复制压缩；不在组内返回 false。 */
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
        if (fixedSlots) {
            RpcConnection[] next = old.clone();
            next[index] = null;
            connections = next;
            return true;
        }
        RpcConnection[] next = new RpcConnection[old.length - 1];
        System.arraycopy(old, 0, next, 0, index);
        System.arraycopy(old, index + 1, next, index, old.length - index - 1);
        connections = next;
        return true;
    }

    /** 当前非空连接快照，遍历期间不保证强一致。 */
    public RpcConnection[] snapshot() {
        RpcConnection[] current = connections;
        if (!fixedSlots) {
            return current;
        }
        int count = nonNullCount(current);
        RpcConnection[] result = new RpcConnection[count];
        int index = 0;
        for (RpcConnection connection : current) {
            if (connection != null) {
                result[index++] = connection;
            }
        }
        return result;
    }

    public int count() {
        RpcConnection[] current = connections;
        return fixedSlots ? nonNullCount(current) : current.length;
    }

    public boolean isEmpty() {
        return count() == 0;
    }

    int capacity() {
        return connections.length;
    }

    private static int nonNullCount(RpcConnection[] connections) {
        int count = 0;
        for (RpcConnection connection : connections) {
            if (connection != null) {
                count++;
            }
        }
        return count;
    }
}
