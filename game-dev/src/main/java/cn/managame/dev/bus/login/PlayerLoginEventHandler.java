package cn.managame.dev.bus.login;

import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.executor.ExecutorGroups;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登陆事件监听样例：展示 EventBus 接线方式——@EventHandler 由 Spring 扫描托管，
 * 启动期 AnnotationScanner 注册进 EventBus；事件按 routerKey 投递到本组执行器，
 * 与该玩家的命令任务串行。登陆后的旁路逻辑（好友上线通知、日志上报等）都挂这里，
 * 不塞进 LoginController 主流程。
 */
@EventHandler(group = ExecutorGroups.PLAYER)
public class PlayerLoginEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(PlayerLoginEventHandler.class);

    @EventMethod
    public void onPlayerLogin(PlayerLoginEvent event) {
        logger.info("player login done, userId={}, roleId={}, serverId={}",
                event.getUserId(), event.getRoleId(), event.getServerId());
    }
}
