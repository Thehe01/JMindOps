import { fetchWithAuth, HttpError } from "./http.ts";

export interface ParsedSseEvent {
  event: string;
  data: string;
  id?: string;
  retry?: number;
}

interface StreamSseOptions {
  signal: AbortSignal;
  onOpen?: () => void | Promise<void>;
  onEvent: (event: ParsedSseEvent) => void | Promise<void>;
}

function parseEventBlock(block: string): ParsedSseEvent | null {
  let event = "message";
  let id: string | undefined;
  let retry: number | undefined;
  const data: string[] = [];

  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(":")) continue;
    const separatorIndex = line.indexOf(":");
    const field = separatorIndex === -1 ? line : line.slice(0, separatorIndex);
    let value = separatorIndex === -1 ? "" : line.slice(separatorIndex + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "event") event = value;
    if (field === "data") data.push(value);
    if (field === "id") id = value;
    if (field === "retry") {
      const parsedRetry = Number(value);
      if (Number.isFinite(parsedRetry)) retry = parsedRetry;
    }
  }

  if (data.length === 0 && event === "message") return null;
  return { event, data: data.join("\n"), id, retry };
}

/**
 * 使用 fetch 读取标准 SSE 数据，因此 JWT 可以放在 Authorization Header 中，
 * 不会出现在 URL、反向代理访问日志或监控链路的查询参数里。
 */
export async function streamSse(
  url: string,
  { signal, onOpen, onEvent }: StreamSseOptions,
): Promise<void> {
  const response = await fetchWithAuth(url, {
    method: "GET",
    headers: { Accept: "text/event-stream" },
    cache: "no-store",
    signal,
  });

  if (!response.ok) {
    throw new HttpError(
      `实时连接失败（HTTP ${response.status}）`,
      response.status,
    );
  }
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.toLowerCase().includes("text/event-stream")) {
    throw new HttpError("实时连接返回了非 SSE 响应", response.status);
  }
  if (!response.body) {
    throw new Error("当前浏览器不支持流式响应");
  }

  await onOpen?.();

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      let boundary = buffer.search(/\r?\n\r?\n/);
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary);
        const separator =
          buffer.slice(boundary).match(/^\r?\n\r?\n/)?.[0] || "\n\n";
        buffer = buffer.slice(boundary + separator.length);
        const parsedEvent = parseEventBlock(block);
        if (parsedEvent) await onEvent(parsedEvent);
        boundary = buffer.search(/\r?\n\r?\n/);
      }
    }

    buffer += decoder.decode();
    const trailingEvent = parseEventBlock(buffer);
    if (trailingEvent && !signal.aborted) await onEvent(trailingEvent);
  } finally {
    reader.releaseLock();
  }
}

export function waitForReconnect(
  delayMs: number,
  signal: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }

    const timer = window.setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, delayMs);

    const onAbort = () => {
      window.clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
