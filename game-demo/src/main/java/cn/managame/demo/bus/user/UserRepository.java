package cn.managame.demo.bus.user;

import cn.managame.data.SingleRepository;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository extends SingleRepository<Long,User> {
}
