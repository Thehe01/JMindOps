package com.kama.jmindops.agent.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记工具是否具备幂等执行语义或支持幂等键。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentTool {
    /** 是否为幂等工具（多次执行相同参数无额外副作用） */
    boolean value() default true;

    /** 是否支持传递 tool_call_id 作为 idempotency key */
    boolean supportsIdempotencyKey() default false;
}
