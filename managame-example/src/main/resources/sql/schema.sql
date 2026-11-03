-- 游戏的表由应用自己建、自己迁移；GameData 启动时只检查每一列都在。
CREATE TABLE IF NOT EXISTS `user` (
    `user_id`     BIGINT NOT NULL,
    `role_id_map` JSON   NOT NULL,   -- Map<Integer, Long>：区服 id → 角色 id
    PRIMARY KEY (`user_id`)
);
