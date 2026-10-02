package cn.managame.demo.bus.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;


    public User getAndCreateUser(Long userId){
        User user = userRepository.get(userId);
        if(user == null){
            user = new User();
            user.setUserId(userId);
            userRepository.insert(user);
        }
        return user;
    }
}
