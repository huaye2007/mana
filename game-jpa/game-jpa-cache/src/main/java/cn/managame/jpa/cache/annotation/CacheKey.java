package cn.managame.jpa.cache.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记实体字段为组合缓存键的一部分。
 * 用于 {@link cn.managame.jpa.cache.MultiCacheRepository} 场景。
 * <p>
 * 框架启动时按 order 排序，自动提取标注字段的值组成缓存key。
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "mail")
 * public class Mail {
 *     @Id
 *     private long id;
 *
 *     @CacheKey(order = 1)
 *     private long roleId;
 *
 *     private String content;
 * }
 *
 * @Entity
 * @Table(name = "activity_data")
 * public class ActivityData {
 *     @Id
 *     private long id;
 *
 *     @CacheKey(order = 1)
 *     private long roleId;
 *
 *     @CacheKey(order = 2)
 *     private int activityId;
 *
 *     private String data;
 * }
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheKey {

    /**
     * 组合键中的排序，从1开始。多个 @CacheKey 字段按 order 升序排列组成缓存key。
     */
    int order();
}
