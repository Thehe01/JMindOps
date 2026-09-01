import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Button,
  Card,
  message as antdMessage,
  Select,
  Space,
  Typography,
} from "antd";
import {
  BulbOutlined,
  DownOutlined,
  MessageOutlined,
  RobotOutlined,
} from "@ant-design/icons";
import { Sender } from "@ant-design/x";
import { useNavigate } from "react-router-dom";
import { createChatSession, type AgentVO } from "../../../api/api.ts";
import { useChatSessions } from "../../../hooks/useChatSessions.ts";
import { getAgentEmoji } from "../../../utils";

const { Title, Text } = Typography;

interface EmptyAgentChatViewProps {
  agents: AgentVO[];
  agentsLoading?: boolean;
  agentsError?: Error | null;
  onRetryAgents?: () => void;
  initialAgentId?: string;
}

const EmptyAgentChatView = ({
  agents,
  agentsLoading = false,
  agentsError,
  onRetryAgents,
  initialAgentId,
}: EmptyAgentChatViewProps) => {
  const [input, setInput] = useState("");
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(
    initialAgentId || null,
  );
  const [creatingSession, setCreatingSession] = useState(false);
  const navigate = useNavigate();
  const { refreshChatSessions } = useChatSessions();

  const agentsWithEmoji = useMemo(
    () => agents.map((agent) => ({ ...agent, emoji: getAgentEmoji(agent.id) })),
    [agents],
  );

  useEffect(() => {
    if (initialAgentId) setSelectedAgentId(initialAgentId);
  }, [initialAgentId]);

  const effectiveAgentId = agents.some((agent) => agent.id === selectedAgentId)
    ? selectedAgentId
    : agents[0]?.id || null;

  const startChat = async () => {
    const initialMessage = input.trim();
    if (!initialMessage) {
      antdMessage.warning("请输入想咨询的问题");
      return;
    }
    if (!effectiveAgentId) {
      antdMessage.warning("请先创建或选择一个智能体助手");
      return;
    }

    setCreatingSession(true);
    try {
      const response = await createChatSession({
        agentId: effectiveAgentId,
        title: initialMessage.slice(0, 20),
      });
      await refreshChatSessions();
      setInput("");
      navigate(`/chat/${response.chatSessionId}`, {
        state: { initialMessage, agentId: effectiveAgentId },
      });
    } catch (error) {
      console.error("创建聊天会话失败:", error);
      antdMessage.error("创建会话失败，请重试");
    } finally {
      setCreatingSession(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      {agents.length > 0 && (
        <div className="border-b border-gray-200 bg-white px-4 py-3">
          <Select
            value={effectiveAgentId}
            onChange={setSelectedAgentId}
            style={{ width: 200 }}
            className="agent-selector"
            suffixIcon={<DownOutlined className="text-gray-400" />}
            placeholder="选择智能体助手"
            optionRender={(option) => (
              <div className="flex items-center gap-2">
                <span className="text-lg">
                  {
                    agentsWithEmoji.find((agent) => agent.id === option.value)
                      ?.emoji
                  }
                </span>
                <span className="text-sm">{option.label}</span>
              </div>
            )}
            options={agentsWithEmoji.map((agent) => ({
              value: agent.id,
              label: agent.name,
            }))}
          />
        </div>
      )}
      {agentsError && (
        <Alert
          banner
          showIcon
          type="error"
          message="智能体加载失败"
          description={agentsError.message}
          action={
            <Button size="small" onClick={onRetryAgents}>
              重试
            </Button>
          }
        />
      )}
      <div className="flex flex-1 items-center justify-center p-6">
        <div className="w-full max-w-2xl space-y-6">
          <div className="mb-8 text-center">
            <Title level={2} className="mb-2">
              开始新的对话
            </Title>
            <Text type="secondary" className="text-base">
              选择一个智能体助手开始聊天，或直接发送消息创建新会话
            </Text>
          </div>
          <Space orientation="vertical" size="large" className="w-full">
            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-blue-400 to-purple-400">
                  <RobotOutlined className="text-xl text-white" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    智能对话
                  </Title>
                  <Text type="secondary">
                    与 AI 助手进行智能对话，获取帮助和建议
                  </Text>
                </div>
              </Space>
            </Card>
            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-green-400 to-teal-400">
                  <BulbOutlined className="text-xl text-white" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    知识问答
                  </Title>
                  <Text type="secondary">
                    基于知识库进行问答，获取准确的信息
                  </Text>
                </div>
              </Space>
            </Card>
            <Card
              hoverable
              className="cursor-pointer transition-all hover:shadow-lg"
            >
              <Space size="middle">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-gradient-to-br from-orange-400 to-red-400">
                  <MessageOutlined className="text-xl text-white" />
                </div>
                <div>
                  <Title level={5} className="mb-1">
                    快速开始
                  </Title>
                  <Text type="secondary">
                    在下方输入框输入消息，立即开始对话
                  </Text>
                </div>
              </Space>
            </Card>
          </Space>
        </div>
      </div>
      <div className="border-t border-gray-200 bg-white">
        <div className="px-4 pb-4 pt-4">
          <Sender
            onSubmit={() => void startChat()}
            value={input}
            loading={creatingSession}
            disabled={agentsLoading || agents.length === 0}
            placeholder="输入消息开始对话..."
            onChange={setInput}
          />
        </div>
      </div>
    </div>
  );
};

export default EmptyAgentChatView;
