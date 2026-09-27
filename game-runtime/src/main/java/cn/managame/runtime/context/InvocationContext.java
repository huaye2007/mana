package cn.managame.runtime.context;

import cn.managame.core.Metadata;
public interface InvocationContext extends Context { int businessIdType(); long businessId(); Metadata metadata(); }
