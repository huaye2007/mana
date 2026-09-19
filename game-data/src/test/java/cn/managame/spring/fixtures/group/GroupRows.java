package cn.managame.spring.fixtures.group;

import cn.managame.core.repository.GroupRepository;
import cn.managame.support.DataTestSupport.Row;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupRows extends GroupRepository<Row, Long> { }
