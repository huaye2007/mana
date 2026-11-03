package cn.managame.example.login;

import cn.managame.example.DomainConstant;
import cn.managame.example.proto.Login;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import org.springframework.beans.factory.annotation.Autowired;

@Domain(DomainConstant.LOGIN)
public class LoginHandle {
    @Autowired
    private LoginService loginService;

    @HandleMethod(routeKey = "userId")
    public void login(Login.LoginReq loginReq){
        loginService.login(loginReq);
    }
}
