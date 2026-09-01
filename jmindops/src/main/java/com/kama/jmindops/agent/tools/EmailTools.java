package com.kama.jmindops.agent.tools;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.service.EmailService;
import com.kama.jmindops.governance.RequiresToolApproval;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "app.tools.email.enabled", havingValue = "true")
public class EmailTools implements Tool {
    private static final Logger log = LoggerFactory.getLogger(EmailTools.class);


    private final EmailService emailService;

    public EmailTools(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public String getName() {
        return "emailTool";
    }

    @Override
    public String getDescription() {
        return "一个用于发送邮件的工具，可以通过QQ邮箱发送邮件给指定的收件人。邮件发送采用异步方式，不会阻塞工具调用。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    /**
     * 发送邮件（异步执行）
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param content 邮件内容
     * @return 发送结果信息
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "sendEmail",
            description = "发送邮件到指定的收件人。参数包括：to（收件人邮箱地址，必填）、subject（邮件主题，必填）、content（邮件正文内容，必填）。邮件采用异步方式发送，工具调用会立即返回，实际发送在后台执行。"
    )
    @RequiresToolApproval
    public String sendEmail(String to, String subject, String content) {
        // 验证参数
        if (to == null || to.trim().isEmpty()) {
            return "错误：收件人邮箱地址不能为空";
        }
        if (subject == null || subject.trim().isEmpty()) {
            return "错误：邮件主题不能为空";
        }
        if (content == null || content.trim().isEmpty()) {
            return "错误：邮件内容不能为空";
        }

        // 验证邮箱格式（简单验证）
        if (!to.contains("@")) {
            return "错误：收件人邮箱地址格式不正确";
        }

        // 异步发送邮件
        emailService.sendEmailAsync(to.trim(), subject.trim(), content.trim());

        log.info("邮件已提交异步发送: recipientDomain={}, subjectLength={}, contentLength={}",
                to.substring(to.lastIndexOf('@') + 1), subject.length(), content.length());
        return String.format("邮件已提交发送！\n收件人: %s\n主题: %s\n邮件正在后台异步发送中...", to, subject);
    }
}
