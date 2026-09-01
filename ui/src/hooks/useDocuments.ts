import { useEffect, useState, useCallback, useRef } from "react";
import {
  type DocumentVO,
  getDocumentsByKbId,
  deleteDocument,
} from "../api/api.ts";

export function useDocuments(kbId: string | undefined) {
  const [documents, setDocuments] = useState<DocumentVO[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const latestRequestRef = useRef(0);

  const fetchDocuments = useCallback(
    async (signal?: AbortSignal) => {
      const requestId = ++latestRequestRef.current;
      if (!kbId) {
        setDocuments([]);
        setError(null);
        setLoading(false);
        return;
      }

      setLoading(true);
      setError(null);
      try {
        const resp = await getDocumentsByKbId(kbId, signal);
        if (!signal?.aborted && requestId === latestRequestRef.current) {
          setDocuments(resp.documents);
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
            : new Error("加载文档失败");
        if (requestId === latestRequestRef.current) setError(normalizedError);
        throw normalizedError;
      } finally {
        if (!signal?.aborted && requestId === latestRequestRef.current) {
          setLoading(false);
        }
      }
    },
    [kbId],
  );

  useEffect(() => {
    const controller = new AbortController();
    void fetchDocuments(controller.signal).catch(() => undefined);
    return () => controller.abort();
  }, [fetchDocuments]);

  const deleteDocumentHandle = async (documentId: string) => {
    await deleteDocument(documentId);
    setDocuments((current) =>
      current.filter((document) => document.id !== documentId),
    );
    void fetchDocuments().catch(() => undefined);
  };

  return {
    documents,
    loading,
    error,
    refreshDocuments: fetchDocuments,
    deleteDocument: deleteDocumentHandle,
  };
}
