package com.github.huaye2007.mana.config.api;

/**
 * 配置变更类型。
 */
public enum ChangeType {
    /** 新增 key */
    ADDED,
    /** 已有 key 值变更 */
    UPDATED,
    /** key 被删除 */
    DELETED
}
