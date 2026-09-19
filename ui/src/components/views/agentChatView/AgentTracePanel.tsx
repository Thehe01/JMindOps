import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Descriptions, Drawer, Empty, Space, Spin, Tag, Timeline } from "antd";
import {
  getAgentTrace,
  type AgentStepStatus,
  type AgentTraceResponse,
  type ToolInvocationStatus,
} from "../../../api/api.ts";

interface AgentTracePanelProps {
  generationId: string | null;
  open: boolean;
  onClose: () => void;
}

const statusColor: Record<AgentStepStatus | ToolInvocationStatus, string> = {
  THINKING: "processing",
  EXECUTING_TOOLS: "processing",
  COMPLETED: "success",
  PREPARED: "default",
  RUNNING: "processing",
  SUCCEEDED: "success",
  WAITING_APPROVAL: "warning",
  FAILED: "error",
  UNKNOWN: "error",
};

const shortHash = (hash?: string) => (hash ? `${hash.slice(0, 10)}…` : "-");

const AgentTracePanel = ({ generationId, open, onClose }: AgentTracePanelProps) => {
  const [trace, setTrace] = useState<AgentTraceResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadTrace = useCallback(async () => {
    if (!generationId) return;
    setLoading(true);
    setError(null);
    try {
      setTrace(await getAgentTrace(generationId));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "执行轨迹加载失败");
    } finally {
      setLoading(false);
    }
  }, [generationId]);

  useEffect(() => {
    if (open) void loadTrace();
  }, [loadTrace, open]);

  return (
    <Drawer
      title="Agent 执行轨迹"
      width={560}
      open={open}
      onClose={onClose}
      extra={<Button onClick={() => void loadTrace()} loading={loading}>刷新</Button>}
    >
      {loading && !trace ? (
        <div className="flex justify-center py-16"><Spin /></div>
      ) : error ? (
        <Alert type="error" showIcon message={error} />
      ) : !trace ? (
        <Empty description="暂无执行轨迹" />
      ) : (
        <Space direction="vertical" size="large" className="w-full">
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="路由">
              <Tag color="blue">{trace.routingDecision || "待判定"}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="当前步骤">{trace.currentStep}</Descriptions.Item>
            <Descriptions.Item label="累计 Token">{trace.cumulativeTokens}</Descriptions.Item>
            <Descriptions.Item label="检查点版本">{trace.checkpointVersion}</Descriptions.Item>
          </Descriptions>

          {trace.steps.length === 0 ? (
            <Empty description="Agent 尚未进入推理步骤" />
          ) : (
            <Timeline
              items={trace.steps.map((step) => ({
                color: statusColor[step.status],
                children: (
                  <div className="pb-3">
                    <div className="mb-2 flex flex-wrap items-center gap-2">
                      <strong>步骤 {step.stepNo}</strong>
                      <Tag color={statusColor[step.status]}>{step.status}</Tag>
                      {step.modelName && <span className="text-xs text-gray-500">{step.modelName}</span>}
                    </div>
                    <div className="mb-2 text-xs text-gray-500">
                      Token {step.totalTokens}（输入 {step.promptTokens} / 输出 {step.completionTokens}）
                      {step.modelLatencyMs !== undefined ? ` · 模型 ${step.modelLatencyMs}ms` : ""}
                      {` · 输出摘要 ${shortHash(step.outputHash)}`}
                    </div>
                    {step.lastError && <Alert className="mb-2" type="error" showIcon message={step.lastError} />}
                    {step.toolInvocations.map((tool) => (
                      <div key={tool.invocationId} className="mb-2 rounded border border-gray-200 bg-gray-50 p-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <strong>{tool.toolName}</strong>
                          <Tag color={statusColor[tool.status]}>{tool.status}</Tag>
                          {tool.latencyMs !== undefined && <span className="text-xs text-gray-500">{tool.latencyMs}ms</span>}
                        </div>
                        <div className="mt-1 text-xs text-gray-500">
                          参数 {tool.argumentsLength} 字符 / {shortHash(tool.argumentsHash)}
                          {tool.resultLength !== undefined ? ` · 结果 ${tool.resultLength} 字符 / ${shortHash(tool.resultHash)}` : ""}
                        </div>
                        {tool.lastError && <div className="mt-1 text-xs text-red-600">{tool.lastError}</div>}
                      </div>
                    ))}
                  </div>
                ),
              }))}
            />
          )}
        </Space>
      )}
    </Drawer>
  );
};

export default AgentTracePanel;
