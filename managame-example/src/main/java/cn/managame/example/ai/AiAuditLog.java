package cn.managame.example.ai;

import cn.managame.data.mysql.annotation.Column;
import cn.managame.data.mysql.annotation.Table;

/**
 * AI 与 MCP 审计/用量日志（通过 {@link AiAuditLogRepository} 异步批量写入 {@code ai_audit_log}，不阻塞玩家路由）：
 * <ul>
 *   <li>{@code MCP_WRITE}：记录谁（{@code caller}）经哪条链路（{@code traceId}）对哪个玩家执行了什么写工具及结果码</li>
 *   <li>{@code NPC_CHAT} / {@code GM_ASK}：记录每次 AI 对话的 Token 消耗与审计摘要，供成本归因与合规留存</li>
 * </ul>
 */
@Table(name = "ai_audit_log")
public class AiAuditLog {

    private long id;
    private String kind;
    private String caller;

    @Column(name = "role_id")
    private long roleId;

    private String name;

    @Column(name = "trace_id")
    private String traceId;

    private String detail;
    private int code;
    private int tokens;

    @Column(name = "created_at")
    private long createdAt;

    public AiAuditLog() {}

    public AiAuditLog(long id, String kind, String caller, long roleId, String name,
            String traceId, String detail, int code, int tokens, long createdAt) {
        this.id = id;
        this.kind = kind != null ? kind : "";
        this.caller = caller != null ? caller : "";
        this.roleId = roleId;
        this.name = name != null ? name : "";
        this.traceId = traceId != null ? traceId : "";
        this.detail = detail != null ? detail : "";
        this.code = code;
        this.tokens = tokens;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public String getKind() { return kind; }
    public String getCaller() { return caller; }
    public long getRoleId() { return roleId; }
    public String getName() { return name; }
    public String getTraceId() { return traceId; }
    public String getDetail() { return detail; }
    public int getCode() { return code; }
    public int getTokens() { return tokens; }
    public long getCreatedAt() { return createdAt; }
}
