package cn.managame.dev.server;

import cn.managame.dev.protocol.GameErrorCode;
import cn.managame.dev.protocol.GamePacket;
import cn.managame.dev.protocol.GamePacketConstant;
import cn.managame.runtime.context.GameCommandTaskContext;
import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.exception.GameTaskExceptionHandler;
import cn.managame.runtime.monitor.GameTaskMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务失败 → 客户端错误回包的桥接，宿主启动期注册到
 * {@code GameTaskExceptionHandlers}/{@code GameTaskMonitors}：
 * <ul>
 *   <li>handler 抛 {@link GameBusinessException}：回其自带 code，不打堆栈；</li>
 *   <li>handler 抛其它异常：记错误日志，回 {@link GameErrorCode#INTERNAL_ERROR}；</li>
 *   <li>worker 队列满任务被丢弃：回 {@link GameErrorCode#SERVER_BUSY}（丢弃即终态）。</li>
 * </ul>
 *
 * <p>只有 COMMAND 任务有请求方可回；EVENT/TIMER/CALLBACK 任务失败仅记日志。
 * 回包直接写 session（连接已断时由网络层静默丢弃），不经过执行器组。</p>
 */
public class GameTaskFailureReplier implements GameTaskExceptionHandler, GameTaskMonitor {

    private static final Logger logger = LoggerFactory.getLogger(GameTaskFailureReplier.class);

    private final long slowTaskThresholdMs;

    public GameTaskFailureReplier() {
        this(1_000);
    }

    public GameTaskFailureReplier(long slowTaskThresholdMs) {
        this.slowTaskThresholdMs = slowTaskThresholdMs;
    }

    @Override
    public void handle(GameTaskContext context, Throwable cause) {
        if (cause instanceof GameBusinessException business) {
            logger.info("business reject, taskType={}, group={}, routerKey={}, code={}, msg={}",
                    context.getTaskType(), context.getGroup(), context.getRouterKey(),
                    business.getCode(), business.getMessage());
            replyError(context, business.getCode());
            return;
        }
        logger.error("game task failed, taskType={}, group={}, routerKey={}",
                context.getTaskType(), context.getGroup(), context.getRouterKey(), cause);
        replyError(context, GameErrorCode.INTERNAL_ERROR);
    }

    @Override
    public void onTaskComplete(GameTaskContext context, long queueDelayMs, long execMs) {
        if (execMs >= slowTaskThresholdMs) {
            logger.warn("slow task, taskType={}, group={}, routerKey={}, queueDelayMs={}, execMs={}",
                    context.getTaskType(), context.getGroup(), context.getRouterKey(), queueDelayMs, execMs);
        }
    }

    @Override
    public void onTaskDropped(GameTaskContext context) {
        logger.error("worker queue full, task dropped, taskType={}, group={}, routerKey={}",
                context.getTaskType(), context.getGroup(), context.getRouterKey());
        replyError(context, GameErrorCode.SERVER_BUSY);
    }

    private static void replyError(GameTaskContext context, int code) {
        if (context instanceof GameCommandTaskContext command
                && command.getSession() instanceof PlayerSession session) {
            session.writeMsg(GamePacket.of(command.getCommand(), command.getSeq(),
                    code, GamePacketConstant.EMPTY_BODY));
        }
    }
}
