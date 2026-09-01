package com.kama.jmindops.aspect;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;


@Aspect
@Component
public class LLMTraceAspect {
    private static final Logger log = LoggerFactory.getLogger(LLMTraceAspect.class);


    // 拦截实现了 ChatModel 接口的 call 方法
    @Around("execution(* org.springframework.ai.chat.model.ChatModel.call(org.springframework.ai.chat.prompt.Prompt))")
    public Object traceLLMCall(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof Prompt) {
            Prompt prompt = (Prompt) args[0];
            // Prompt 可能包含用户隐私、知识库正文或工具结果，生产日志只记录结构信息。
            log.info("LLM request started: model={}, messageCount={}",
                    joinPoint.getTarget().getClass().getSimpleName(),
                    prompt.getInstructions().size());
        }

        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception e) {
            log.error("LLM call failed: exceptionType={}", e.getClass().getSimpleName());
            log.debug("LLM call failure details", e);
            throw e;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            if (result instanceof ChatResponse chatResponse) {
                log.info("LLM response completed: durationMs={}", costTime);
                if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                    log.info("LLM token usage: {}", chatResponse.getMetadata().getUsage());
                }
            }
        }
    }
}
