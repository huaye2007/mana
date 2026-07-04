package cn.managame.jpa.demo.repository;

import cn.managame.jpa.demo.domain.PlayerProfile;
import cn.managame.jpa.docdb.repository.DocRepository;

public interface PlayerProfileRepository extends DocRepository<PlayerProfile, Long> {
}
