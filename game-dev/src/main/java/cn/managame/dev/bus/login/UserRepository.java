package cn.managame.dev.bus.login;

import cn.managame.jpa.core.repository.GameRepository;
import cn.managame.jpa.rdb.cache.IRdbUniqueCacheRepository;

@GameRepository
public interface UserRepository extends IRdbUniqueCacheRepository<User,Long> {
}
