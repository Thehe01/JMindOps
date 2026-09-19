package com.kama.jmindops.governance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class ToolGovernanceAspect {
    private final ToolGovernanceService toolGovernanceService;
    public ToolGovernanceAspect(ToolGovernanceService toolGovernanceService) {
        this.toolGovernanceService = toolGovernanceService;
    }


    @Around("execution(public * com.kama.jmindops.agent.tools..*(..))")
    public Object govern(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ToolGovernanceInvocationContext.isCallbackGoverned()) {
            return joinPoint.proceed();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Tool toolAnnotation = resolveToolAnnotation(signature, joinPoint.getTarget());
        if (toolAnnotation == null) {
            return joinPoint.proceed();
        }
        RequiresToolApproval approval = resolveApprovalAnnotation(signature, joinPoint.getTarget());
        String toolName = toolAnnotation.name().isBlank() ? signature.getMethod().getName() : toolAnnotation.name();
        String riskLevel = approval == null ? "LOW" : approval.riskLevel();
        long startedAt = System.currentTimeMillis();

        if (approval != null) {
            ToolGovernanceService.ApprovalDecision decision = toolGovernanceService.requireApproval(toolName, joinPoint.getArgs(), riskLevel);
            if (!decision.approved()) {
                String response = ToolApprovalSignal.waitingMessage(decision.approvalId());
                toolGovernanceService.audit(toolName, riskLevel, "PENDING_APPROVAL", joinPoint.getArgs(), response, System.currentTimeMillis() - startedAt);
                return response;
            }
        }

        try {
            Object result = joinPoint.proceed();
            toolGovernanceService.audit(toolName, riskLevel, "SUCCEEDED", joinPoint.getArgs(), result, System.currentTimeMillis() - startedAt);
            return result;
        } catch (Throwable throwable) {
            toolGovernanceService.audit(toolName, riskLevel, "FAILED", joinPoint.getArgs(), throwable.getMessage(), System.currentTimeMillis() - startedAt);
            throw throwable;
        }
    }

    RequiresToolApproval resolveApprovalAnnotation(MethodSignature signature, Object target) {
        Method targetMethod = resolveTargetMethod(signature, target);
        return AnnotatedElementUtils.findMergedAnnotation(targetMethod, RequiresToolApproval.class);
    }

    Tool resolveToolAnnotation(MethodSignature signature, Object target) {
        Method targetMethod = resolveTargetMethod(signature, target);
        return AnnotatedElementUtils.findMergedAnnotation(targetMethod, Tool.class);
    }

    private Method resolveTargetMethod(MethodSignature signature, Object target) {
        Class<?> targetClass = AopUtils.getTargetClass(target);
        return AopUtils.getMostSpecificMethod(signature.getMethod(), targetClass);
    }
}
