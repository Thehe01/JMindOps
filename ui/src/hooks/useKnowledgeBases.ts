import { useContext } from "react";
import { KnowledgeBasesContext } from "../contexts/appData.ts";

export function useKnowledgeBases() {
  const context = useContext(KnowledgeBasesContext);
  if (!context) {
    throw new Error("useKnowledgeBases must be used within AppDataProvider");
  }
  return context;
}
