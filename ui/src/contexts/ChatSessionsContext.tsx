import React, { useEffect, useState, useCallback, useRef } from "react";
import {
  type ChatSessionVO,
  getChatSessions,
  deleteChatSession,
} from "../api/api.ts";
import { ChatSessionsContext } from "./chatSessions.ts";

export function ChatSessionsProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [chatSessions, setChatSessions] = useState<ChatSessionVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const latestRequestRef = useRef(0);

  const fetchChatSessions = useCallback(async (signal?: AbortSignal) => {
    const requestId = ++latestRequestRef.current;
    setLoading(true);
    setError(null);
    try {
      const resp = await getChatSessions(signal);
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setChatSessions(resp.chatSessions);
      }
    } catch (requestError) {
      if (
        requestError instanceof DOMException &&
        requestError.name === "AbortError"
      ) {
        return;
      }
      const normalizedError =
        requestError instanceof Error
          ? requestError
          : new Error("加载会话失败");
      if (requestId === latestRequestRef.current) setError(normalizedError);
      throw normalizedError;
    } finally {
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void fetchChatSessions(controller.signal).catch(() => undefined);
    return () => controller.abort();
  }, [fetchChatSessions]);

  const deleteChatSessionHandle = useCallback(
    async (chatSessionId: string) => {
      await deleteChatSession(chatSessionId);
      setChatSessions((current) =>
        current.filter((session) => session.id !== chatSessionId),
      );
      void fetchChatSessions().catch(() => undefined);
    },
    [fetchChatSessions],
  );

  return (
    <ChatSessionsContext.Provider
      value={{
        chatSessions,
        loading,
        error,
        refreshChatSessions: fetchChatSessions,
        deleteChatSession: deleteChatSessionHandle,
      }}
    >
      {children}
    </ChatSessionsContext.Provider>
  );
}
