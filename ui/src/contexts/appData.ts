import { createContext } from "react";
import type {
  AgentVO,
  CreateAgentRequest,
  UpdateAgentRequest,
} from "../api/api.ts";
import type { KnowledgeBase } from "../types";
import type { CreateKnowledgeBaseRequest } from "../api/api.ts";

export interface AgentsContextType {
  agents: AgentVO[];
  loading: boolean;
  error: Error | null;
  refreshAgents: (signal?: AbortSignal) => Promise<void>;
  createAgentHandle: (request: CreateAgentRequest) => Promise<void>;
  deleteAgentHandle: (agentId: string) => Promise<void>;
  updateAgentHandle: (
    agentId: string,
    request: UpdateAgentRequest,
  ) => Promise<void>;
}

export interface KnowledgeBasesContextType {
  knowledgeBases: KnowledgeBase[];
  loading: boolean;
  error: Error | null;
  refreshKnowledgeBases: (signal?: AbortSignal) => Promise<void>;
  createKnowledgeBaseHandle: (
    request: CreateKnowledgeBaseRequest,
  ) => Promise<void>;
}

export const AgentsContext = createContext<AgentsContextType | undefined>(
  undefined,
);

export const KnowledgeBasesContext = createContext<
  KnowledgeBasesContextType | undefined
>(undefined);
