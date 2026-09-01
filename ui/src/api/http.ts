import { message } from "antd";

// API 响应类型定义，匹配后端 ApiResponse 结构
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

// 请求配置选项
export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | boolean | null | undefined>;
}

/**
 * API 使用同源相对路径。开发环境由 Vite 代理到后端，容器/生产环境由
 * Nginx 代理，避免把浏览器错误地指向访问者自己的 localhost。
 */
export const BASE_URL = import.meta.env.VITE_API_BASE_URL || "/api";

/** SSE 服务地址，与 REST API 保持同源，支持通过环境变量覆盖。 */
export const SSE_BASE_URL = import.meta.env.VITE_SSE_BASE_URL || "/sse";
export const ACCESS_TOKEN_KEY = "jmindops_access_token";

export class HttpError extends Error {
  status: number;

  constructor(messageText: string, status: number) {
    super(messageText);
    this.name = "HttpError";
    this.status = status;
  }
}

export function getAccessToken(): string | null {
  const sessionToken = sessionStorage.getItem(ACCESS_TOKEN_KEY);
  if (sessionToken) return sessionToken;

  // 兼容旧版本持久化的令牌，并在首次读取时迁移到会话级存储。
  const legacyToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (legacyToken) {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, legacyToken);
    localStorage.removeItem(ACCESS_TOKEN_KEY);
  }
  return legacyToken;
}

export function setAccessToken(token: string): void {
  sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
  localStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function clearAccessToken(): void {
  sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(ACCESS_TOKEN_KEY);
}

function redirectToLogin(): void {
  clearAccessToken();
  window.location.assign("/");
}

/**
 * 发起带认证信息的原始请求。JSON、multipart 与 SSE 都通过这里读取令牌，
 * 避免各功能自行拼接 token 或遗漏 401 处理。
 */
export async function fetchWithAuth(
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> {
  const requestHeaders = new Headers(init.headers);
  const accessToken = getAccessToken();
  if (accessToken && !requestHeaders.has("Authorization")) {
    requestHeaders.set("Authorization", `Bearer ${accessToken}`);
  }

  const response = await fetch(input, {
    ...init,
    headers: requestHeaders,
  });

  if (response.status === 401) {
    redirectToLogin();
  }

  return response;
}

/**
 * 构建完整的 URL（包含查询参数）
 */
function buildUrl(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
): string {
  const fullUrl = `${BASE_URL}${url}`;

  if (!params || Object.keys(params).length === 0) {
    return fullUrl;
  }

  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined) {
      searchParams.append(key, String(value));
    }
  });

  const queryString = searchParams.toString();
  return queryString ? `${fullUrl}?${queryString}` : fullUrl;
}

/**
 * 处理响应
 */
export async function handleResponse<T>(
  response: Response,
): Promise<ApiResponse<T>> {
  let data: ApiResponse<T> | undefined;
  try {
    data = (await response.json()) as ApiResponse<T>;
  } catch {
    if (response.ok) {
      throw new HttpError("服务端返回了无法解析的响应", response.status);
    }
  }

  if (!response.ok) {
    throw new HttpError(
      data?.message || `请求失败（HTTP ${response.status}）`,
      response.status,
    );
  }

  if (!data) {
    throw new HttpError("服务端返回了空响应", response.status);
  }

  // 检查业务状态码
  if (data.code !== 200) {
    message.error(data.message || "请求失败");
    throw new Error(data.message || "请求失败");
  }

  return data;
}

/**
 * 封装的 fetch 请求函数
 */
async function request<T = unknown>(
  url: string,
  options: RequestOptions = {},
): Promise<T> {
  const { params, headers, ...restOptions } = options;

  // 构建完整 URL
  const fullUrl = buildUrl(url, params);

  // 设置默认请求头
  const defaultHeaders = new Headers(headers);
  if (
    !(restOptions.body instanceof FormData) &&
    !defaultHeaders.has("Content-Type")
  ) {
    defaultHeaders.set("Content-Type", "application/json");
  }

  try {
    const response = await fetchWithAuth(fullUrl, {
      ...restOptions,
      headers: defaultHeaders,
    });

    const apiResponse = await handleResponse<T>(response);
    return apiResponse.data;
  } catch (error) {
    // 统一错误处理
    if (error instanceof Error) {
      throw error;
    }
    throw new Error("网络请求失败");
  }
}

/**
 * GET 请求
 */
export function get<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "GET",
    params,
  });
}

/**
 * POST 请求
 */
export function post<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "POST",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PUT 请求
 */
export function put<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PUT",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PATCH 请求
 */
export function patch<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PATCH",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * DELETE 请求
 */
export function del<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">,
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "DELETE",
    params,
  });
}

// 导出默认对象，方便使用
export default {
  get,
  post,
  put,
  patch,
  delete: del,
};
