package cn.managame.jpa.demo.repository;

import cn.managame.jpa.demo.domain.PlayerAccount;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import cn.managame.jpa.rdb.repository.RdbRepository;

import java.util.List;

public interface PlayerAccountRepository extends RdbRepository<PlayerAccount, Long> {

    List<PlayerAccount> findBySpec(RdbQuerySpec spec, Object routingKey);
}
