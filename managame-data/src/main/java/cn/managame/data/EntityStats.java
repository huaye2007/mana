package cn.managame.data;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * One entity repository as {@link GameData#stats()} read it. {@code cached} is how many entities are in memory, or groups
 * for a GroupRepository, as Caffeine estimates it. {@code waiting} is how many changes wait to be written, those of the
 * round in progress included: a number that keeps growing means the database falls behind. Since GameData was built,
 * {@code written} changes were written, {@code failures} writes failed (a batch, or a single change once a batch is
 * split, each counting once, whether tried again or dropped) and {@code dropped} changes were handed to the
 * DataErrorHandler. {@code cache} is the cache's own count of hits, misses, loads, failed loads, load time and
 * evictions; {@code CacheStats.minus} gives what changed between two reads.
 */
public record EntityStats(Class<?> entityType, long cached, int waiting, long written, long failures, long dropped,
        CacheStats cache) {}
