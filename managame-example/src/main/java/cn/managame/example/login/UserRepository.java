package cn.managame.example.login;

import cn.managame.data.SingleRepository;

/** GameData 创建它（见 DataConfig），不能由 Spring 直接 new。 */
public class UserRepository extends SingleRepository<Long,User> {
}
