-- 游戏的表：MysqlEntityMapper 默认自动补齐缺失实体表；MysqlLogWriter 的日志基表在此定义。
CREATE TABLE IF NOT EXISTS `user` (
    `user_id`     BIGINT NOT NULL,
    `role_id_map` JSON   NOT NULL,   -- Map<Integer, Long>：区服 id → 角色 id
    PRIMARY KEY (`user_id`)
);

CREATE TABLE IF NOT EXISTS `player_ai_state` (
    `role_id`     BIGINT NOT NULL,
    `npc_epochs`  JSON   NOT NULL,   -- Map<Long, Integer>：npcId → 当前对话代号
    `quota_day`   BIGINT NOT NULL,   -- 按 GameTime 时区计的 epochDay
    `daily_calls` INT    NOT NULL,   -- 当日已发起 AI 对话次数
    PRIMARY KEY (`role_id`)
);

CREATE TABLE IF NOT EXISTS `ai_audit_log` (
    `id`         BIGINT       NOT NULL,
    `kind`       VARCHAR(32)  NOT NULL,
    `caller`     VARCHAR(32)  NOT NULL,
    `role_id`    BIGINT       NOT NULL,
    `name`       VARCHAR(64)  NOT NULL,
    `trace_id`   VARCHAR(128) NOT NULL,
    `detail`     TEXT         NOT NULL,
    `code`       INT          NOT NULL,
    `tokens`     INT          NOT NULL,
    `created_at` BIGINT       NOT NULL,
    PRIMARY KEY (`id`)
);
