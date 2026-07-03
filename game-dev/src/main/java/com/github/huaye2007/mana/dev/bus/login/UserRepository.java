package com.github.huaye2007.mana.dev.bus.login;

import com.github.huaye2007.mana.jpa.core.repository.GameRepository;
import com.github.huaye2007.mana.jpa.rdb.cache.IRdbUniqueCacheRepository;

@GameRepository
public interface UserRepository extends IRdbUniqueCacheRepository<User,Long> {
}
