package cn.managame.example.ai;

import cn.managame.ai.AiParam;
import cn.managame.ai.AiTool;
import cn.managame.example.DomainConstant;
import cn.managame.example.login.User;
import cn.managame.example.login.UserRepository;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.HandleMethod;
import cn.managame.runtime.dispatch.HandleResult;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 暴露给外部 AI 服务的游戏服工具：与普通业务处理器一样运行在 {@link cn.managame.runtime.GameRuntime} 路由上，
 * 直接读写游戏的 {@link UserRepository}。同一个类里通过 {@code audience} 精确隔离玩家侧与运维侧工具。
 */
@Domain(DomainConstant.LOGIN)
public class PlayerAiTools {
    public static final String AUDIENCE_OPS = "ops";
    public static final String AUDIENCE_PLAYER = "player";

    public record QueryUserRoles(@AiParam("Account user id") long userId) {}
    public record ServerRole(int serverId, long roleId) {}
    public record UserRolesView(long userId, List<ServerRole> roles) {}

    public record GrantReward(
            @AiParam("Stable UUID idempotency key; reuse on retries") UUID operationId,
            @AiParam("Target player role id") long roleId,
            @AiParam("Reward coins (positive)") int coins) {
        public GrantReward {
            Objects.requireNonNull(operationId, "operationId");
            if (roleId <= 0 || coins <= 0) throw new IllegalArgumentException("Positive roleId and coins required");
        }
    }

    @Autowired
    private UserRepository userRepository;

    @AiTool(name = "queryUserRoles", description = "Query all server roles owned by an account",
            audience = {AUDIENCE_OPS, AUDIENCE_PLAYER}, output = UserRolesView.class, readOnly = true, idempotent = true)
    @HandleMethod(routeKey = "userId")
    public HandleResult queryUserRoles(QueryUserRoles request) {
        User user = userRepository.get(request.userId());
        if (user == null) return HandleResult.fail(1, "User not found");
        var roles = user.getRoleIdMap().entrySet().stream()
                .map(e -> new ServerRole(e.getKey(), e.getValue())).toList();
        return HandleResult.ok(new UserRolesView(user.getUserId(), roles));
    }

    /** 仅对 ops 受众可见且必须审批：AI 调用时由 AiTools 强制转入 ProposalSink，不会直接进入此方法。 */
    @AiTool(name = "grantReward", description = "Propose granting compensation coins to a player role",
            audience = AUDIENCE_OPS, output = GrantReward.class, requireApproval = true, idempotent = true)
    @HandleMethod(routeKey = "roleId")
    public HandleResult applyApprovedReward(GrantReward request) {
        return HandleResult.ok(request);
    }
}
