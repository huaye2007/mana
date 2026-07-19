package cn.managame.dev.server;

import cn.managame.dev.protocol.GameErrorCode;
import cn.managame.dev.protocol.GamePacket;
import cn.managame.dev.protocol.GamePacketConstant;
import cn.managame.runtime.command.CommandMeta;
import cn.managame.runtime.command.CommandRegistry;
import cn.managame.runtime.context.GameCommandTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.executor.ExecutorGroups;
import cn.managame.runtime.executor.TaskSubmissionResult;
import cn.managame.runtime.runnable.GameCommandTaskRunnable;
import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 外网消息路由 + 回包/推送门面。
 *
 * <p>入站：按 command 找到 {@code CommandMeta}，做登陆校验后封装成 COMMAND 任务
 * 投递到对应执行器组（同 routerKey 串行）。出站：业务在 handler 线程里调
 * {@link #reply}/{@link #replyError}（回当前请求）或 {@link #push}/{@link #broadcast}
 * （服务端主动推送，seq=0）；写失败由网络层丢弃，不重试不缓冲。</p>
 */
@Service
public class GameRouterManager {

    private static final Logger logger = LoggerFactory.getLogger(GameRouterManager.class);

    private static final ISerializer SERIALIZER =
            SerializerManager.getInstance().getISerializer(GamePacketConstant.BODY_SERIAL_TYPE);

    private final PlayerSessionManager playerSessionManager;

    public GameRouterManager(PlayerSessionManager playerSessionManager) {
        this.playerSessionManager = playerSessionManager;
    }

    /** 网络 IO 线程入口：路由校验要轻量，重活都在执行器组里做。 */
    public void handleGameMsg(PlayerSession playerSession, GamePacket gamePacket) {
        CommandMeta meta = CommandRegistry.getInstance().getCommandMeta(gamePacket.getCommand());
        if (meta == null) {
            // decoder 已按未知 command 回过错误帧，这里只可能是注册表运行期被改（不应发生）
            logger.error("unknown command={}, msg dropped", gamePacket.getCommand());
            return;
        }

        // 登陆 gate：LOGIN 组之外的命令要求 session 已绑定 roleId，未登陆直接回错误码
        if (meta.getGroup() != ExecutorGroups.LOGIN && playerSession.getRoleId() == 0) {
            playerSession.writeMsg(GamePacket.of(gamePacket.getCommand(), gamePacket.getSeq(),
                    GameErrorCode.NOT_LOGGED_IN, GamePacketConstant.EMPTY_BODY));
            return;
        }

        Object body = gamePacket.getBody();
        byte busType;
        long busId;
        long routerKey;
        try {
            switch (meta.getGroup()) {
            case ExecutorGroups.LOGIN -> {
                // 未登陆、无 roleId：按消息内的键（如 userId）打散，避免登陆全部串行到单 worker
                busType = BusTypeConstant.DEFAULT;
                busId = 0;
                routerKey = meta.extractRouterKey(body, 0L);
            }
            case ExecutorGroups.PLAYER -> {
                busType = BusTypeConstant.PLAYER;
                busId = playerSession.getRoleId();
                routerKey = meta.extractRouterKey(body, busId);
            }
            default -> {
                logger.error("unsupported executor group={}, command={}", meta.getGroup(), meta.getCommand());
                playerSession.writeMsg(GamePacket.of(gamePacket.getCommand(), gamePacket.getSeq(),
                        GameErrorCode.INTERNAL_ERROR, GamePacketConstant.EMPTY_BODY));
                return;
            }
            }
        } catch (IllegalArgumentException e) {
            logger.warn("invalid router key, command={}, msg dropped", meta.getCommand(), e);
            playerSession.writeMsg(GamePacket.of(gamePacket.getCommand(), gamePacket.getSeq(),
                    GameErrorCode.BAD_REQUEST, GamePacketConstant.EMPTY_BODY));
            return;
        }

        GameCommandTaskRunnable runnable = new GameCommandTaskRunnable(
                meta, routerKey, busType, busId, gamePacket.getSeq(), null, body, playerSession);
        TaskSubmissionResult result = ExecutorGroupRegistry.getInstance().tryExecute(runnable);
        if (!result.isAccepted()) {
            int errorCode = result == TaskSubmissionResult.REJECTED_OVERLOADED
                    ? GameErrorCode.SERVER_BUSY
                    : GameErrorCode.INTERNAL_ERROR;
            playerSession.writeMsg(GamePacket.of(gamePacket.getCommand(), gamePacket.getSeq(),
                    errorCode, GamePacketConstant.EMPTY_BODY));
        }
    }

    /** 回当前请求：command/seq 取当前 COMMAND 任务上下文，code=OK。只能在 handler 线程里调。 */
    public void reply(Object msg) {
        GameCommandTaskContext context = currentCommandContext();
        writeTo((PlayerSession) context.getSession(),
                context.getCommand(), context.getSeq(), GameErrorCode.OK, msg);
    }

    /** 给当前请求回错误码（空 body）。业务上更推荐直接抛 {@link GameBusinessException}。 */
    public void replyError(int code) {
        GameCommandTaskContext context = currentCommandContext();
        ((PlayerSession) context.getSession()).writeMsg(GamePacket.of(
                context.getCommand(), context.getSeq(), code, GamePacketConstant.EMPTY_BODY));
    }

    /** 服务端主动推送：seq=0；目标离线时丢弃（转发失败不重试）。任意线程可调。 */
    public void push(long roleId, int command, Object msg) {
        PlayerSession session = playerSessionManager.get(roleId);
        if (session == null) {
            logger.debug("push dropped, role offline, roleId={}, command={}", roleId, command);
            return;
        }
        writeTo(session, command, 0, GameErrorCode.OK, msg);
    }

    /** 全服在线广播：body 只序列化一次。任意线程可调。 */
    public void broadcast(int command, Object msg) {
        byte[] bodyBytes = SERIALIZER.serialize(msg);
        playerSessionManager.forEachOnline(session ->
                session.writeMsg(GamePacket.of(command, 0, GameErrorCode.OK, bodyBytes)));
    }

    private static void writeTo(PlayerSession session, int command, int seq, int code, Object msg) {
        session.writeMsg(GamePacket.of(command, seq, code, SERIALIZER.serialize(msg)));
    }

    private static GameCommandTaskContext currentCommandContext() {
        if (GameTaskContextHolder.current() instanceof GameCommandTaskContext context) {
            return context;
        }
        throw new IllegalStateException("reply 只能在 COMMAND 任务上下文中调用（handler 线程）");
    }
}
