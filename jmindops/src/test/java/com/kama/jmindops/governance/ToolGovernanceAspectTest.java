package com.kama.jmindops.governance;

import com.kama.jmindops.agent.tools.DataBaseTools;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolGovernanceAspectTest {

    @Test
    void resolvesApprovalAnnotationFromMostSpecificMethodBehindClassProxy() throws Exception {
        DataBaseTools target = new DataBaseTools(mock(JdbcTemplate.class));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        DataBaseTools proxy = (DataBaseTools) proxyFactory.getProxy();
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(proxy.getClass().getMethod("query", String.class));
        ToolGovernanceAspect aspect = new ToolGovernanceAspect(mock(ToolGovernanceService.class));

        RequiresToolApproval approval = aspect.resolveApprovalAnnotation(signature, proxy);

        assertThat(approval).isNotNull();
        assertThat(approval.riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void resolvesToolAnnotationFromMostSpecificMethodBehindClassProxy() throws Exception {
        DataBaseTools target = new DataBaseTools(mock(JdbcTemplate.class));
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        DataBaseTools proxy = (DataBaseTools) proxyFactory.getProxy();
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(proxy.getClass().getMethod("query", String.class));
        ToolGovernanceAspect aspect = new ToolGovernanceAspect(mock(ToolGovernanceService.class));

        Tool tool = aspect.resolveToolAnnotation(signature, proxy);

        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo("databaseQuery");
    }

    @Test
    void interceptsApprovalProtectedToolBehindExistingClassProxy() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataBaseTools target = new DataBaseTools(jdbcTemplate);
        ProxyFactory transactionalProxyFactory = new ProxyFactory(target);
        transactionalProxyFactory.setProxyTargetClass(true);
        DataBaseTools transactionalProxy = (DataBaseTools) transactionalProxyFactory.getProxy();

        ToolGovernanceService governanceService = mock(ToolGovernanceService.class);
        when(governanceService.requireApproval(anyString(), any(Object[].class), anyString()))
                .thenReturn(new ToolGovernanceService.ApprovalDecision(false, "approval-123"));
        AspectJProxyFactory governedProxyFactory = new AspectJProxyFactory(transactionalProxy);
        governedProxyFactory.setProxyTargetClass(true);
        governedProxyFactory.addAspect(new ToolGovernanceAspect(governanceService));
        DataBaseTools governedProxy = governedProxyFactory.getProxy();

        String result = governedProxy.query("SELECT id FROM knowledge_base");

        assertThat(ToolApprovalSignal.isWaitingResponse(result)).isTrue();
        verify(governanceService).requireApproval(
                org.mockito.ArgumentMatchers.eq("databaseQuery"),
                any(Object[].class),
                org.mockito.ArgumentMatchers.eq("HIGH"));
        verifyNoInteractions(jdbcTemplate);
    }

}
