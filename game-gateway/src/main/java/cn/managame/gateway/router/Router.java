package cn.managame.gateway.router;

import cn.managame.registry.api.ServiceInstance;

import java.util.List;

/**
 * 后端实例选择策略。{@link #refresh} 在实例上下线时被调用（注册中心 watch 线程），
 * {@link #select} 在网络 IO 线程高频调用——实现必须做到 select 无锁/低开销，
 * refresh 与 select 并发安全（典型做法：不可变快照 + volatile 替换）。
 */
public interface Router {

    /** 实例集变化时整体重建内部结构（哈希环/轮询表）。 */
    void refresh(List<ServiceInstance> instances);

    /** 按路由键选一个实例；当前无实例返回 null。 */
    ServiceInstance select(long routeKey);
}
