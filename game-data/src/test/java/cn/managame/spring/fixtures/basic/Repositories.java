package cn.managame.spring.fixtures.basic;

import cn.managame.core.repository.SingleRepository;
import cn.managame.support.DataTestSupport.Row;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

public final class Repositories {
    @Repository("walletRepository") @Primary
    public interface WalletRows extends SingleRepository<Row, Long> { }
    @Repository("eventRepository")
    public interface EventRepository extends cn.managame.support.DataTestSupport.Events { }
    @Repository("otherRepository")
    public interface OtherRows extends SingleRepository<Row, Long> { }
    public interface UnmarkedRows extends SingleRepository<Row, Long> { }
    @Repository public static class OrdinaryRepository { }
}
