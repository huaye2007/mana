package cn.managame.runtime.context;

/** Decoded RPC ingress with logical origin and correlation, without a physical connection. */
public interface RpcHandlerContext extends HandlerContext {
    int sourceNodeId();
    int sourceSlotId();
    int command();
    /** Zero for Notify; nonzero for Call, including negative int wire representations. */
    int requestId();
}
