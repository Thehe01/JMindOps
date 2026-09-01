import { Button, Card, Space, Tag, Typography } from "antd";
import type { ToolApproval } from "../../../api/toolGovernance.ts";

const { Text } = Typography;

interface ToolApprovalPanelProps {
  approvals: ToolApproval[];
  onDecide: (approvalId: string, approved: boolean) => Promise<void>;
  decidingApprovalId?: string | null;
}

export default function ToolApprovalPanel({
  approvals,
  onDecide,
  decidingApprovalId,
}: ToolApprovalPanelProps) {
  if (approvals.length === 0) return null;

  return (
    <div className="border-b border-amber-200 bg-amber-50 p-3">
      <Space direction="vertical" className="w-full">
        {approvals.map((approval) => (
          <Card key={approval.id} size="small" className="border-amber-300">
            <Space direction="vertical" className="w-full" size="small">
              <div className="flex items-center justify-between gap-3">
                <Text strong>Agent 请求执行 {approval.toolName}</Text>
                <Tag color="orange">需要确认</Tag>
              </div>
              <Text type="secondary" className="break-all">
                参数：{approval.arguments}
              </Text>
              <Space>
                <Button
                  type="primary"
                  size="small"
                  loading={decidingApprovalId === approval.id}
                  disabled={Boolean(decidingApprovalId)}
                  onClick={() => void onDecide(approval.id, true)}
                >
                  批准
                </Button>
                <Button
                  danger
                  size="small"
                  loading={decidingApprovalId === approval.id}
                  disabled={Boolean(decidingApprovalId)}
                  onClick={() => void onDecide(approval.id, false)}
                >
                  拒绝
                </Button>
              </Space>
            </Space>
          </Card>
        ))}
      </Space>
    </div>
  );
}
