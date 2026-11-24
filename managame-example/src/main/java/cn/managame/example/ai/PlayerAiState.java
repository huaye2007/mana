package cn.managame.example.ai;

import cn.managame.data.annotation.Id;
import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.mysql.annotation.Table;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家维度的 AI 状态，交由 {@link cn.managame.data.GameData} 管理：
 * <ul>
 *   <li>离线后由 Caffeine {@code expireAfterAccess} 自动淘汰出内存，不会无限增长</li>
 *   <li>每个 NPC 的会话代号（{@code npcEpochs}）落库保存，停服重启不会回退到 1 串上旧记忆</li>
 *   <li>按 {@code GameTime} 自然日统计调用次数（{@code quotaDay} + {@code dailyCalls}），控制单玩家每日额度</li>
 * </ul>
 */
@Table(name = "player_ai_state")
public class PlayerAiState {

    @Id
    @Column(name = "role_id")
    private long roleId;

    @Column(name = "npc_epochs")
    private Map<Long, Integer> npcEpochs = new ConcurrentHashMap<>();

    @Column(name = "quota_day")
    private long quotaDay;

    @Column(name = "daily_calls")
    private int dailyCalls;

    public long getRoleId() { return roleId; }

    public void setRoleId(long roleId) { this.roleId = roleId; }

    public Map<Long, Integer> getNpcEpochs() { return npcEpochs; }

    public void setNpcEpochs(Map<Long, Integer> npcEpochs) {
        this.npcEpochs = npcEpochs != null ? new ConcurrentHashMap<>(npcEpochs) : new ConcurrentHashMap<>();
    }

    public long getQuotaDay() { return quotaDay; }

    public void setQuotaDay(long quotaDay) { this.quotaDay = quotaDay; }

    public int getDailyCalls() { return dailyCalls; }

    public void setDailyCalls(int dailyCalls) { this.dailyCalls = dailyCalls; }
}
