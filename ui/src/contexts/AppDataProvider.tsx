import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  createAgent,
  createKnowledgeBase,
  deleteAgent,
  getAgents,
  getKnowledgeBases,
  updateAgent,
  type AgentVO,
  type CreateAgentRequest,
  type CreateKnowledgeBaseRequest,
  type UpdateAgentRequest,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import { AgentsContext, KnowledgeBasesContext } from "./appData.ts";

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error("请求失败");
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

function AgentsProvider({ children }: { children: ReactNode }) {
  const [agents, setAgents] = useState<AgentVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const latestRequestRef = useRef(0);

  const refreshAgents = useCallback(async (signal?: AbortSignal) => {
    const requestId = ++latestRequestRef.current;
    setLoading(true);
    setError(null);
    try {
      const response = await getAgents(signal);
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setAgents(response.agents);
      }
    } catch (requestError) {
      if (isAbortError(requestError)) return;
      const normalizedError = toError(requestError);
      if (requestId === latestRequestRef.current) {
        setError(normalizedError);
      }
      throw normalizedError;
    } finally {
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void refreshAgents(controller.signal).catch(() => undefined);
    return () => controller.abort();
  }, [refreshAgents]);

  const createAgentHandle = useCallback(
    async (request: CreateAgentRequest) => {
      const response = await createAgent(request);
      setAgents((current) => [
        ...current,
        {
          id: response.agentId,
          ...request,
        },
      ]);
      setError(null);
      void refreshAgents().catch(() => undefined);
    },
    [refreshAgents],
  );

  const deleteAgentHandle = useCallback(
    async (agentId: string) => {
      await deleteAgent(agentId);
      setAgents((current) => current.filter((agent) => agent.id !== agentId));
      setError(null);
      void refreshAgents().catch(() => undefined);
    },
    [refreshAgents],
  );

  const updateAgentHandle = useCallback(
    async (agentId: string, request: UpdateAgentRequest) => {
      await updateAgent(agentId, request);
      setAgents((current) =>
        current.map((agent) =>
          agent.id === agentId ? { ...agent, ...request } : agent,
        ),
      );
      setError(null);
      void refreshAgents().catch(() => undefined);
    },
    [refreshAgents],
  );

  const value = useMemo(
    () => ({
      agents,
      loading,
      error,
      refreshAgents,
      createAgentHandle,
      deleteAgentHandle,
      updateAgentHandle,
    }),
    [
      agents,
      loading,
      error,
      refreshAgents,
      createAgentHandle,
      deleteAgentHandle,
      updateAgentHandle,
    ],
  );

  return (
    <AgentsContext.Provider value={value}>{children}</AgentsContext.Provider>
  );
}

function KnowledgeBasesProvider({ children }: { children: ReactNode }) {
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const latestRequestRef = useRef(0);

  const refreshKnowledgeBases = useCallback(async (signal?: AbortSignal) => {
    const requestId = ++latestRequestRef.current;
    setLoading(true);
    setError(null);
    try {
      const response = await getKnowledgeBases(signal);
      const converted = response.knowledgeBases.map((knowledgeBase) => ({
        knowledgeBaseId: knowledgeBase.id,
        name: knowledgeBase.name,
        description: knowledgeBase.description || "",
      }));
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setKnowledgeBases(converted);
      }
    } catch (requestError) {
      if (isAbortError(requestError)) return;
      const normalizedError = toError(requestError);
      if (requestId === latestRequestRef.current) {
        setError(normalizedError);
      }
      throw normalizedError;
    } finally {
      if (!signal?.aborted && requestId === latestRequestRef.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void refreshKnowledgeBases(controller.signal).catch(() => undefined);
    return () => controller.abort();
  }, [refreshKnowledgeBases]);

  const createKnowledgeBaseHandle = useCallback(
    async (request: CreateKnowledgeBaseRequest) => {
      const response = await createKnowledgeBase(request);
      setKnowledgeBases((current) => [
        ...current,
        {
          knowledgeBaseId: response.knowledgeBaseId,
          name: request.name,
          description: request.description || "",
        },
      ]);
      setError(null);
      void refreshKnowledgeBases().catch(() => undefined);
    },
    [refreshKnowledgeBases],
  );

  const value = useMemo(
    () => ({
      knowledgeBases,
      loading,
      error,
      refreshKnowledgeBases,
      createKnowledgeBaseHandle,
    }),
    [
      knowledgeBases,
      loading,
      error,
      refreshKnowledgeBases,
      createKnowledgeBaseHandle,
    ],
  );

  return (
    <KnowledgeBasesContext.Provider value={value}>
      {children}
    </KnowledgeBasesContext.Provider>
  );
}

export function AppDataProvider({ children }: { children: ReactNode }) {
  return (
    <AgentsProvider>
      <KnowledgeBasesProvider>{children}</KnowledgeBasesProvider>
    </AgentsProvider>
  );
}
