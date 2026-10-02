package com.kama.jmindops.agent.tools.test;

import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.agent.tools.ToolType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTool implements Tool {
    @Override
    public String getName() {
        return "dateTool";
    }

    @Override
    public String getDescription() {
        return "获取当前的日期";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @dev.langchain4j.agent.tool.Tool(name = "getDate", value = "获取当前的日期")
    public String getDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
