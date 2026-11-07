package cn.managame.example;

public class DomainConstant {

    public final static int PLAYER  = 1;
    /** 登录：按账号 userId 路由，同一个账号的登录依次执行，不同账号并行。 */
    public final static int LOGIN  = 2;
    /** 世界：只有一条路由，所有消息依次执行。 */
    public final static int WORLD  = 3;
}
