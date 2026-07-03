package com.github.huaye2007.mana.runtime.executor;

/**
 * 标准执行器组划分。注解里 group 不指定时默认落 {@link #PLAYER}；
 * 这四个组之外的业务组由注解显式指定 group 和对应的路由键。
 */
public final class ExecutorGroups {

    /**
     * 登陆线程池组：登陆洪峰隔离，不影响在线玩家。适合虚拟线程。
     */
    public static final byte LOGIN = 1;

    /**
     * 玩家线程池组（默认组）：玩家自己触发、与他人关系不大的业务逻辑，
     * 按玩家 id 做路由键。适合虚拟线程。
     */
    public static final byte PLAYER = 2;

    /**
     * 场景线程池组：场景同步、房间类业务，不能阻塞或卡顿。用平台线程。
     */
    public static final byte SCENE = 3;

    /**
     * 公共线程池组：排行榜、公会等全局业务，可按参数对应的值做路由键
     * （routerKeyMethod 提取）。适合虚拟线程。
     */
    public static final byte COMMON = 4;

    private ExecutorGroups() {
    }
}
