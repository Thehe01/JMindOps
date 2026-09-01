package com.kama.jmindops.governance;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ToolGovernanceAspect {
    private final ToolGovernanceService toolGovernanceService;
    public ToolGovernanceAspect(ToolGovernanceService toolGovernanceService) {
        this.toolGovernanceService = toolGovernanceService;
    }


    @Around("@annotation(toolAnnotation)")
    public Object govern(ProceedingJoinPoint joinPoint, Tool toolAnnotation) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RequiresToolApproval approval = signature.getMethod().getAnnotation(RequiresToolApproval.class);
        String toolName = toolAnnotation.name().isBlank() ? signature.getMethod().getName() : toolAnnotation.name();
        String riskLevel = approval == null ? "LOW" : approval.riskLevel();
        long startedAt = System.currentTimeMillis();

        if (approval != null) {
            ToolGovernanceService.ApprovalDecision decision = toolGovernanceService.requireApproval(toolName, joinPoint.getArgs(), riskLevel);
            if (!decision.approved()) {
                String response = "操作需要人工审批，审批编号：" + decision.approvalId() + "。请在界面批准后让用户确认继续。";
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
}
