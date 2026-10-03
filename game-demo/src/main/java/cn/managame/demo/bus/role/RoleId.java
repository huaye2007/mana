package cn.managame.demo.bus.role;

import cn.managame.runtime.context.HandlerContext;

/** Demo-owned identity category and value, independent of protocol and Route keys. */
public record RoleId(long value) {
    public static final int TYPE = 1;

    public static RoleId from(HandlerContext context) {
        if (context.businessIdType() != TYPE) throw new IllegalArgumentException("Expected role business identity");
        return new RoleId(context.businessId());
    }
}
