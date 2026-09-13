package cn.managame.rpc.core;

import cn.managame.network.Connection;

@FunctionalInterface
public interface ConnectionSelector {
    /** Receives the directly connected target Peer selected by nodeId. */
    Connection select(RpcPeer peer, long routeKey);

    /** Stable SplitMix64 finalizer over all fixed slots; zero uses writable round robin. */
    ConnectionSelector DEFAULT =
            (peer, key) -> {
                if (key != 0) {
                    long hash = key;
                    hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
                    hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
                    hash ^= hash >>> 31;
                    return peer.connection(Math.floorMod(hash, peer.connectionCount()));
                }
                int start = peer.nextRoundRobin();
                boolean active = false;
                for (int i = 0; i < peer.connectionCount(); i++) {
                    var c = peer.connection((int) (((long) start + i) % peer.connectionCount()));
                    if (c != null && c.isActive()) {
                        active = true;
                        if (c.isWritable()) return c;
                    }
                }
                throw (active ? RpcException.OVERLOADED : RpcException.UNAVAILABLE);
            };
}
