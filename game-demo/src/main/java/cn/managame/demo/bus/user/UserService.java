package cn.managame.demo.bus.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;


    public User getAndCreateUser(Long roleId){
        User user = userRepository.get(roleId);
        if(user == null){
            user = new User();
            user.setUserId(System.nanoTime());
            userRepository.insert(user);
        }
        return user;
    }
}
