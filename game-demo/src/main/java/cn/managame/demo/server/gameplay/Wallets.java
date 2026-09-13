package cn.managame.demo.server.gameplay;

import java.util.concurrent.ConcurrentHashMap;

/** Shared local state. Both handlers access each wallet on the same player route. */
public final class Wallets {
    static final class Wallet { int gold = 100; }
    private final ConcurrentHashMap<Long, Wallet> wallets = new ConcurrentHashMap<>();

    Wallet get(long playerId) { return wallets.computeIfAbsent(playerId, ignored -> new Wallet()); }
}
