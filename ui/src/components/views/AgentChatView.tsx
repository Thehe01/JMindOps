import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, message as antdMessage } from "antd";
import AgentChatHistory from "./agentChatView/AgentChatHistory.tsx";
import AgentChatInput from "./agentChatView/AgentChatInput.tsx";
import {
  createChatMessage,
  getChatMessagesBySessionId,
  getChatSession,
  retryGenerationTask,
} from "../../api/api.ts";
import { SSE_BASE_URL } from "../../api/http.ts";
import { streamSse, waitForReconnect } from "../../api/sse.ts";
import {
  decideToolApproval,
  getPendingToolApprovals,
  type ToolApproval,
} from "../../api/toolGovernance.ts";
import EmptyAgentChatView from "./agentChatView/EmptyAgentChatView.tsx";
import { useAgents } from "../../hooks/useAgents.ts";
import type { ChatMessageVO, SseMessage, SseMessageType } from "../../types";
import ToolApprovalPanel from "./agentChatView/ToolApprovalPanel.tsx";
import AgentTracePanel from "./agentChatView/AgentTracePanel.tsx";

interface ChatRouteState {
  initialMessage?: string;
  agentId?: string;
  selectedAgentId?: string;
}

type ConnectionStatus = "connecting" | "connected" | "reconnecting";

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

function mergeServerMessages(
  currentMessages: ChatMessageVO[],
  serverMessages: ChatMessageVO[],
  activeGenerationId: string | null,
): ChatMessageVO[] {
  const persistedIds = new Set(serverMessages.map((message) => message.id));

  const inFlightMessages = currentMessages.filter((message) => {
    if (!message.id?.startsWith("stream:")) return false;
    if (persistedIds.has(message.id)) return false;

    // 如果这个临时消息的 ID 不等于当前活跃的 activeGenerationId，说明它是历史残留，直接丢弃
    const genId = message.id.substring(7);
    if (activeGenerationId && genId !== activeGenerationId) {
      return false;
    }
    // 如果没有活跃的 activeGenerationId（说明后端已经完成），也丢弃临时消息
    if (!activeGenerationId) {
      return false;
    }
    return true;
  });

  return [...serverMessages, ...inFlightMessages];
}

const AgentChatView = () => {
  const { chatSessionId } = useParams<{ chatSessionId: string }>();
  const {
    agents,
    loading: agentsLoading,
    error: agentsError,
    refreshAgents,
  } = useAgents();
  const location = useLocation();
  const navigate = useNavigate();
  const routeState = location.state as ChatRouteState | null;

  const [messages, setMessages] = useState<ChatMessageVO[]>([]);
  const [agentId, setAgentId] = useState("");
  const agentIdRef = useRef("");
  const [sessionLoading, setSessionLoading] = useState(Boolean(chatSessionId));
  const [sessionError, setSessionError] = useState<Error | null>(null);
  const [sessionLoadNonce, setSessionLoadNonce] = useState(0);
  const [sending, setSending] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [pendingApprovals, setPendingApprovals] = useState<ToolApproval[]>([]);
  const [decidingApprovalId, setDecidingApprovalId] = useState<string | null>(
    null,
  );
  const [displayAgentStatus, setDisplayAgentStatus] = useState(false);
  const [agentStatusText, setAgentStatusText] = useState("");
  const [agentStatusType, setAgentStatusType] = useState<
    SseMessageType | undefined
  >(undefined);
  const [connectionStatus, setConnectionStatus] =
    useState<ConnectionStatus>("connecting");
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [failedGenerationId, setFailedGenerationId] = useState<string | null>(null);
  const [retryingGeneration, setRetryingGeneration] = useState(false);
  const [traceGenerationId, setTraceGenerationId] = useState<string | null>(null);
  const [traceOpen, setTraceOpen] = useState(false);
  const [reconnectNonce, setReconnectNonce] = useState(0);

  // AgentChatView 由路由层按 sessionId 设置 key。每个会话实例只提交自己的首条消息。
  const pendingInitialMessage = useRef(
    routeState?.initialMessage?.trim() || "",
  );
  const pendingInitialAgentId = useRef(routeState?.agentId || "");
  const initialMessageSubmitted = useRef(false);
  const connectedOnce = useRef(false);
  const activeGenerationIdRef = useRef<string | null>(null);
  const lastTerminalGenerationIdRef = useRef<string | null>(null);
  const pendingSendRef = useRef<{ key: string; requestId: string } | null>(null);

  const refreshChatMessages = useCallback(
    async (signal?: AbortSignal) => {
      if (!chatSessionId) return;
      const response = await getChatMessagesBySessionId(chatSessionId, signal);
      setMessages((current) =>
        mergeServerMessages(current, response.chatMessages, activeGenerationIdRef.current),
      );

      const lastMessage = response.chatMessages.at(-1);
      if (
        lastMessage?.role === "assistant" &&
        !lastMessage.metadata?.toolCalls?.length
      ) {
        setGenerating(false);
      }
    },
    [chatSessionId],
  );

  const refreshPendingApprovals = useCallback(
    async (signal?: AbortSignal) => {
      if (!chatSessionId) {
        setPendingApprovals([]);
        return;
      }
      try {
        const approvals = await getPendingToolApprovals(signal);
        const currentApprovals = approvals.filter(
          (approval) => approval.sessionId === chatSessionId,
        );
        setPendingApprovals(currentApprovals);
        if (currentApprovals.length > 0) setGenerating(false);
      } catch (error) {
        if (!isAbortError(error)) {
          console.error("加载工具审批失败:", error);
        }
      }
    },
    [chatSessionId],
  );

  useEffect(() => {
    if (!chatSessionId) return;
    const controller = new AbortController();

    const loadChat = async () => {
      setSessionLoading(true);
      setSessionError(null);
      try {
        const [messagesResponse, sessionResponse] = await Promise.all([
          getChatMessagesBySessionId(chatSessionId, controller.signal),
          getChatSession(chatSessionId, controller.signal),
        ]);
        if (controller.signal.aborted) return;
        setMessages(messagesResponse.chatMessages);
        agentIdRef.current = sessionResponse.chatSession.agentId;
        setAgentId(sessionResponse.chatSession.agentId);
      } catch (error) {
        if (isAbortError(error)) return;
        console.error("加载聊天会话失败:", error);
        setSessionError(
          error instanceof Error ? error : new Error("加载聊天会话失败"),
        );
      } finally {
        if (!controller.signal.aborted) setSessionLoading(false);
      }
    };

    void loadChat();
    return () => controller.abort();
  }, [chatSessionId, sessionLoadNonce]);

  useEffect(() => {
    if (!chatSessionId) return;
    const controller = new AbortController();
    void refreshPendingApprovals(controller.signal);
    const timer = window.setInterval(
      () => void refreshPendingApprovals(controller.signal),
      5000,
    );
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [chatSessionId, refreshPendingApprovals]);

  const sendMessage = useCallback(
    async (content: string, requestedAgentId?: string) => {
      if (!chatSessionId) throw new Error("会话尚未创建");

      const targetAgentId = requestedAgentId || agentIdRef.current;
      if (!targetAgentId) {
        const error = new Error("会话尚未初始化");
        antdMessage.warning("会话正在初始化，请稍后再发送");
        throw error;
      }

      setSending(true);
      setGenerating(true);
      setStreamError(null);
      const sendKey = JSON.stringify([targetAgentId, chatSessionId, content]);
      const requestId =
        pendingSendRef.current?.key === sendKey
          ? pendingSendRef.current.requestId
          : crypto.randomUUID();
      pendingSendRef.current = { key: sendKey, requestId };
      try {
        const response = await createChatMessage({
          requestId,
          agentId: targetAgentId,
          sessionId: chatSessionId,
          content,
        });
        pendingSendRef.current = null;
        if (
          response.generationId &&
          (response.status === "PENDING" || response.status === "RUNNING") &&
          response.generationId !== lastTerminalGenerationIdRef.current
        ) {
          setTraceGenerationId(response.generationId);
          activeGenerationIdRef.current = response.generationId;
          setFailedGenerationId(null);
        } else if (
          response.status === "SUCCEEDED" ||
          response.status === "FAILED"
        ) {
          activeGenerationIdRef.current = null;
          setGenerating(false);
          if (response.status === "FAILED" && response.generationId) {
            setFailedGenerationId(response.generationId);
          }
        }
        try {
          await refreshChatMessages();
        } catch (refreshError) {
          // 消息已经被后端接受，刷新失败不应把正在运行的生成任务标记为发送失败。
          console.error("刷新聊天消息失败:", refreshError);
        }
      } catch (error) {
        activeGenerationIdRef.current = null;
        setGenerating(false);
        console.error("发送消息失败:", error);
        antdMessage.error("发送失败，请重试");
        throw error;
      } finally {
        setSending(false);
      }
    },
    [chatSessionId, refreshChatMessages],
  );

  const submitInitialMessage = useCallback(async () => {
    const initialMessage = pendingInitialMessage.current;
    if (!initialMessage || initialMessageSubmitted.current || !chatSessionId) {
      return;
    }

    initialMessageSubmitted.current = true;
    try {
      await sendMessage(initialMessage, pendingInitialAgentId.current);
      navigate(`/chat/${chatSessionId}`, { replace: true, state: null });
    } catch {
      initialMessageSubmitted.current = false;
      setStreamError("首条消息发送失败，可点击“重新连接”后重试");
    }
  }, [chatSessionId, navigate, sendMessage]);

  const handleSseMessage = useCallback(
    (sseMessage: SseMessage) => {
      const payloadMessage = sseMessage.payload?.message;
      const generationId = sseMessage.metadata?.generationId;
      const temporaryMessageId = `stream:${generationId || chatSessionId}`;

      if (generationId) {
        setTraceGenerationId(generationId);
        const activeGenerationId = activeGenerationIdRef.current;
        if (activeGenerationId && generationId !== activeGenerationId) {
          return;
        }
        if (!activeGenerationId) {
          activeGenerationIdRef.current = generationId;
        }
      }

      if (sseMessage.type === "AI_GENERATED_CONTENT_CHUNK") {
        if (!payloadMessage || !chatSessionId) return;
        setGenerating(true);
        setDisplayAgentStatus(false);
        setMessages((previousMessages) => {
          const nextMessages = [...previousMessages];
          const temporaryIndex = nextMessages.findIndex(
            (message) => message.id === temporaryMessageId,
          );
          if (temporaryIndex >= 0) {
            const currentMessage = nextMessages[temporaryIndex];
            nextMessages[temporaryIndex] = {
              ...currentMessage,
              content: currentMessage.content + payloadMessage.content,
            };
          } else {
            nextMessages.push({
              ...payloadMessage,
              id: temporaryMessageId,
              sessionId: payloadMessage.sessionId || chatSessionId,
              role: "assistant",
            });
          }
          return nextMessages;
        });
        return;
      }

      if (sseMessage.type === "AI_GENERATED_CONTENT") {
        if (!payloadMessage) return;
        setMessages((previousMessages) => {
          const existingIndex = previousMessages.findIndex(
            (message) => message.id === payloadMessage.id,
          );
          const temporaryIndex = previousMessages.findIndex(
            (message) => message.id === temporaryMessageId,
          );
          const replaceIndex =
            existingIndex >= 0 ? existingIndex : temporaryIndex;
          if (replaceIndex < 0) return [...previousMessages, payloadMessage];
          const nextMessages = [...previousMessages];
          nextMessages[replaceIndex] = payloadMessage;
          return nextMessages;
        });

        if (
          payloadMessage.role === "assistant" &&
          !payloadMessage.metadata?.toolCalls?.length
        ) {
          activeGenerationIdRef.current = null;
          setGenerating(false);
          setDisplayAgentStatus(false);
          setAgentStatusText("");
          setAgentStatusType(undefined);
        }
        void refreshPendingApprovals();
        return;
      }

      if (
        sseMessage.type === "AI_PLANNING" ||
        sseMessage.type === "AI_THINKING" ||
        sseMessage.type === "AI_EXECUTING"
      ) {
        setGenerating(true);
        setDisplayAgentStatus(true);
        setAgentStatusText(sseMessage.payload?.statusText || "处理中");
        setAgentStatusType(sseMessage.type);
        return;
      }

      if (sseMessage.type === "AI_DONE") {
        lastTerminalGenerationIdRef.current = generationId || null;
        activeGenerationIdRef.current = null;
        setGenerating(false);
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
        setFailedGenerationId(null);
        void refreshChatMessages();
        void refreshPendingApprovals();
        return;
      }

      if (sseMessage.type === "AI_ERROR") {
        lastTerminalGenerationIdRef.current = generationId || null;
        activeGenerationIdRef.current = null;
        setGenerating(false);
        setDisplayAgentStatus(false);
        setAgentStatusText("");
        setAgentStatusType(undefined);
        setFailedGenerationId(generationId || null);
        setStreamError(
          sseMessage.payload?.error ||
            sseMessage.payload?.statusText ||
            "Agent 执行失败，请重试",
        );
      }
    },
    [chatSessionId, refreshChatMessages, refreshPendingApprovals],
  );

  const retryFailedGeneration = useCallback(async () => {
    if (!failedGenerationId) {
      setReconnectNonce((value) => value + 1);
      return;
    }
    setRetryingGeneration(true);
    try {
      const response = await retryGenerationTask(failedGenerationId);
      if (
        response.generationId &&
        (response.status === "PENDING" || response.status === "RUNNING")
      ) {
        activeGenerationIdRef.current = response.generationId;
        setGenerating(true);
        setFailedGenerationId(null);
        setStreamError(null);
      }
    } catch (error) {
      setStreamError(error instanceof Error ? error.message : "任务重试失败");
    } finally {
      setRetryingGeneration(false);
    }
  }, [failedGenerationId]);

  useEffect(() => {
    if (!chatSessionId) return;
    const controller = new AbortController();

    const connect = async () => {
      let attempt = 0;
      while (!controller.signal.aborted) {
        setConnectionStatus(attempt === 0 ? "connecting" : "reconnecting");
        try {
          await streamSse(`${SSE_BASE_URL}/connect/${chatSessionId}`, {
            signal: controller.signal,
            onEvent: async (event) => {
              if (event.event === "init") {
                const isReconnect = connectedOnce.current;
                connectedOnce.current = true;
                attempt = 0;
                setConnectionStatus("connected");
                setConnectionError(null);
                if (isReconnect) {
                  await Promise.all([
                    refreshChatMessages(controller.signal),
                    refreshPendingApprovals(controller.signal),
                  ]);
                }
                await submitInitialMessage();
                return;
              }

              if (event.event === "message") {
                try {
                  handleSseMessage(JSON.parse(event.data) as SseMessage);
                } catch (error) {
                  console.error("解析 SSE 消息失败:", error);
                  setStreamError("收到无法解析的实时消息");
                }
                return;
              }

              if (event.event === "error") {
                setGenerating(false);
                setStreamError(event.data || "Agent 执行失败，请重试");
              }
            },
          });

          if (!controller.signal.aborted) {
            throw new Error("实时连接已关闭");
          }
        } catch (error) {
          if (isAbortError(error) || controller.signal.aborted) return;
          attempt += 1;
          const errorText =
            error instanceof Error ? error.message : "实时连接异常";
          setConnectionStatus("reconnecting");
          setConnectionError(errorText);
          try {
            await waitForReconnect(
              Math.min(1000 * 2 ** Math.min(attempt - 1, 4), 15_000),
              controller.signal,
            );
          } catch (waitError) {
            if (isAbortError(waitError)) return;
            throw waitError;
          }
        }
      }
    };

    void connect();
    return () => controller.abort();
  }, [
    chatSessionId,
    handleSseMessage,
    reconnectNonce,
    refreshChatMessages,
    refreshPendingApprovals,
    submitInitialMessage,
  ]);

  if (!chatSessionId) {
    return (
      <EmptyAgentChatView
        agents={agents}
        agentsLoading={agentsLoading}
        agentsError={agentsError}
        onRetryAgents={() => void refreshAgents()}
        initialAgentId={routeState?.selectedAgentId}
      />
    );
  }

  const connectionMessage =
    connectionStatus === "connecting"
      ? "正在建立实时连接..."
      : "实时连接已中断，正在自动重连...";

  return (
    <div className="flex h-full min-w-0 flex-col">
      {connectionStatus !== "connected" && (
        <Alert
          banner
          showIcon
          type={connectionError ? "warning" : "info"}
          message={connectionMessage}
          description={connectionError || undefined}
          action={
            <Button
              size="small"
              onClick={() => setReconnectNonce((value) => value + 1)}
            >
              重新连接
            </Button>
          }
        />
      )}
      {streamError && (
        <Alert
          banner
          showIcon
          closable
          type="error"
          message={streamError}
          onClose={() => setStreamError(null)}
          action={
            <Button
              size="small"
              loading={retryingGeneration}
              onClick={() => void retryFailedGeneration()}
            >
              {failedGenerationId ? "重试任务" : "重新连接"}
            </Button>
          }
        />
      )}
      {sessionError && (
        <Alert
          banner
          showIcon
          type="error"
          message="聊天会话加载失败"
          description={sessionError.message}
          action={
            <Button
              size="small"
              onClick={() => setSessionLoadNonce((value) => value + 1)}
            >
              重试加载
            </Button>
          }
        />
      )}
      {traceGenerationId && (
        <div className="flex justify-end border-b border-gray-100 bg-white px-4 py-2">
          <Button size="small" onClick={() => setTraceOpen(true)}>
            查看执行轨迹
          </Button>
        </div>
      )}
      {sessionLoading && messages.length === 0 ? (
        <div className="flex flex-1 items-center justify-center text-gray-400">
          正在加载聊天记录...
        </div>
      ) : (
        <AgentChatHistory
          messages={messages}
          displayAgentStatus={displayAgentStatus}
          agentStatusText={agentStatusText}
          agentStatusType={agentStatusType}
        />
      )}
      <ToolApprovalPanel
        approvals={pendingApprovals}
        decidingApprovalId={decidingApprovalId}
        onDecide={async (approvalId, approved) => {
          setDecidingApprovalId(approvalId);
          try {
            await decideToolApproval(approvalId, approved);
            await refreshPendingApprovals();
            antdMessage.success(
              approved ? "已批准操作，Agent 正在自动恢复执行..." : "已拒绝该操作，Agent 将调整计划",
            );
          } catch (error) {
            antdMessage.error(
              error instanceof Error ? error.message : "审批失败，请重试",
            );
            throw error;
          } finally {
            setDecidingApprovalId(null);
          }
        }}
      />
      <AgentTracePanel
        generationId={traceGenerationId}
        open={traceOpen}
        onClose={() => setTraceOpen(false)}
      />
      <div className="border-t border-gray-200 bg-white p-4">
        <AgentChatInput
          onSend={sendMessage}
          loading={
            sessionLoading ||
            Boolean(sessionError) ||
            sending ||
            generating ||
            !agentId ||
            connectionStatus !== "connected"
          }
        />
      </div>
    </div>
  );
};

export default AgentChatView;
