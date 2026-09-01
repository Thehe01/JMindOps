import { useState } from "react";
import { Button, Card, Input, message, Space, Typography } from "antd";
import { login, register } from "../../api/auth.ts";
import { setAccessToken } from "../../api/http.ts";

const { Title, Text } = Typography;
interface LoginViewProps {
  onAuthenticated: () => void;
}

export default function LoginView({ onAuthenticated }: LoginViewProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [registerMode, setRegisterMode] = useState(false);
  const [loading, setLoading] = useState(false);

  const submit = async () => {
    setLoading(true);
    try {
      const result = registerMode
        ? await register({ username, password })
        : await login({ username, password });
      setAccessToken(result.accessToken);
      onAuthenticated();
    } catch (error) {
      console.error("认证失败:", error);
      message.error(
        registerMode ? "注册失败，请检查输入" : "登录失败，请检查用户名和密码",
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <Card className="w-full max-w-md shadow-sm">
        <Space direction="vertical" size="large" className="w-full">
          <div>
            <Title level={2} className="mb-1">
              JMindOps
            </Title>
            <Text type="secondary">智能运维与知识协同 Agent 平台</Text>
          </div>
          <Input
            placeholder="用户名"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
          <Input.Password
            placeholder="密码（至少 8 位）"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            onPressEnter={() => void submit()}
          />
          <Button
            type="primary"
            block
            loading={loading}
            onClick={() => void submit()}
          >
            {registerMode ? "创建账号" : "登录"}
          </Button>
          <Button
            type="link"
            block
            onClick={() => setRegisterMode((current) => !current)}
          >
            {registerMode ? "已有账号？去登录" : "没有账号？创建一个"}
          </Button>
        </Space>
      </Card>
    </div>
  );
}
