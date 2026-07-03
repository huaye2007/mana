package com.github.huaye2007.mana.jpa.demo.repository;

import com.github.huaye2007.mana.jpa.demo.domain.PlayerAccount;
import com.github.huaye2007.mana.jpa.rdb.query.RdbQuerySpec;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepository;

import java.util.List;

public interface PlayerAccountRepository extends RdbRepository<PlayerAccount, Long> {

    List<PlayerAccount> findBySpec(RdbQuerySpec spec, Object routingKey);
}
