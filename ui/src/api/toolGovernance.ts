import { get, post } from "./http.ts";

export interface ToolApproval {
  id: string;
  sessionId?: string;
  toolName: string;
  arguments: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CONSUMED";
  createdAt: string;
  expiresAt: string;
}

export function getPendingToolApprovals(signal?: AbortSignal) {
  return get<ToolApproval[]>(
    "/tool-approvals",
    { status: "PENDING" },
    { signal },
  );
}

export function decideToolApproval(approvalId: string, approved: boolean) {
  return post<void>(
    `/tool-approvals/${approvalId}/${approved ? "approve" : "reject"}`,
  );
}
