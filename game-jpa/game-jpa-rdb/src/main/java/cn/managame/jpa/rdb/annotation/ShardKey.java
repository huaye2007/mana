package cn.managame.jpa.rdb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记字段为分片路由键。
 * <p>
 * 框架在执行数据库操作时，会提取该字段的值作为路由键传递给 {@link cn.managame.jpa.core.routing.RoutingStrategy}，
 * 由路由策略决定目标数据源和物理表名。
 * <p>
 * 每个实体最多标记一个 @ShardKey 字段。
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "player_item")
 * public class PlayerItem {
 *     @Id
 *     private long id;
 *
 *     @ShardKey
 *     private long roleId;
 *
 *     private int itemId;
 *     private int count;
 * }
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ShardKey {
}
