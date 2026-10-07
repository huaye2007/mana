package cn.managame.router.route;

/** Service-local identity. All long binding keys, including zero, are usable. */
public record BindingKey(int serviceId, long bindingKey) {
    public BindingKey {
        if (serviceId <= 0) throw new IllegalArgumentException("serviceId must be positive");
    }
}
