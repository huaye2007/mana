package com.github.huaye2007.mana.jpa.core.repository;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class RepositoryTypesTest {

    @Test
    public void resolvesEntityTypeThroughIntermediateGenericInterfaces() {
        assertEquals(Player.class,
                RepositoryTypes.resolveEntityType(PlayerRepository.class, BaseRepository.class));
    }

    @Test
    public void resolvesEntityTypeWhenIntermediateInterfaceReordersGenericArguments() {
        assertEquals(Player.class,
                RepositoryTypes.resolveEntityType(ReorderedPlayerRepository.class, BaseRepository.class));
    }

    @Test
    public void failsFastWhenEntityTypeCannotBeResolved() {
        assertThrows(IllegalArgumentException.class, () -> {
            RepositoryTypes.resolveEntityType(RawRepository.class, BaseRepository.class);
        });
    }

    interface BaseRepository<T, ID> {
    }

    interface CachedRepository<T, ID> extends BaseRepository<T, ID> {
    }

    interface ReorderedRepository<ID, T> extends BaseRepository<T, ID> {
    }

    interface PlayerRepository extends CachedRepository<Player, Long> {
    }

    interface ReorderedPlayerRepository extends ReorderedRepository<Long, Player> {
    }

    @SuppressWarnings("rawtypes")
    interface RawRepository extends CachedRepository {
    }

    static class Player {
    }
}
