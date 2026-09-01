package com.kama.jmindops.service.impl;

import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.agent.tools.ToolType;
import com.kama.jmindops.service.ToolFacadeService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;
    public ToolFacadeServiceImpl(List<Tool> tools) {
        this.tools = tools;
    }


    @Override
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .toList();
    }
}
