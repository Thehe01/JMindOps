import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  onSend: (message: string) => Promise<void>;
  loading?: boolean;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({
  onSend,
  loading = false,
}) => {
  const [message, setMessage] = useState("");

  return (
    <Sender
      onSubmit={async () => {
        const content = message.trim();
        if (!content || loading) return;

        try {
          await onSend(content);
          setMessage("");
        } catch {
          // 错误由父组件统一提示，保留输入内容便于用户重试。
        }
      }}
      placeholder="输入消息..."
      value={message}
      onChange={setMessage}
      loading={loading}
    />
  );
};

export default AgentChatInput;
