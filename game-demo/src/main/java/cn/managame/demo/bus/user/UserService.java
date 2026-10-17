package cn.managame.demo.bus.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;


    public User getAndCreateUser(Long userId){
        return userRepository.getOrCreate(userId, id -> {
            User user = new User();
            user.setUserId(id);
            return user;
        });
    }
}
