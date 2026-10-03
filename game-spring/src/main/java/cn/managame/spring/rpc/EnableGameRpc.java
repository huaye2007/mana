package cn.managame.spring.rpc;

import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

/** Requires GameRuntime and GameRpcCodec Beans; applications explicitly depend on game-rpc. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(RpcConfiguration.class)
public @interface EnableGameRpc {}
