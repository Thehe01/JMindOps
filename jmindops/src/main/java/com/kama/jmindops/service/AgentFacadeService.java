package com.kama.jmindops.service;

import com.kama.jmindops.model.request.CreateAgentRequest;
import com.kama.jmindops.model.request.UpdateAgentRequest;
import com.kama.jmindops.model.response.CreateAgentResponse;
import com.kama.jmindops.model.response.GetAgentsResponse;

public interface AgentFacadeService {
    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}
