package cn.managame.spring.fixtures.collision;

import cn.managame.core.repository.SingleRepository;
import cn.managame.support.DataTestSupport.Row;
import org.springframework.stereotype.Repository;

public final class Repositories {
    @Repository("collision") public interface First extends SingleRepository<Row, Long> { }
    @Repository("collision") public interface Second extends SingleRepository<Row, Long> { }
}
