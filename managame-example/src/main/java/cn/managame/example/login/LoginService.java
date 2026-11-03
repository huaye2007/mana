package cn.managame.example.login;

import cn.managame.example.DomainConstant;
import cn.managame.example.proto.Login;
import cn.managame.runtime.Context;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    @Autowired
    private UserRepository userRepository;


    public void login(Login.LoginReq loginReq){
        User user = userRepository.get(loginReq.getUserId());
        if(user == null){
            user = new User();
            user.setUserId(loginReq.getUserId());
            userRepository.insert(user);
        }
        Long roleId = user.getRoleIdMap().get(loginReq.getServerId());
        if(roleId == null){
            roleId = System.currentTimeMillis();
            user.getRoleIdMap().put(loginReq.getServerId(),roleId);
            userRepository.update(user);
        }
        Login.LoginRes.Builder builder = Login.LoginRes.newBuilder();
        builder.setRoleId(roleId);
        HandleContext handleContext = (HandleContext) Context.current();
        handleContext.reply(builder.build());
    }
}
