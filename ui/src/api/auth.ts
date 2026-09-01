import { get, post } from "./http.ts";

export interface AuthResponse {
  accessToken: string;
  userId: string;
  username: string;
  role: "USER" | "ADMIN";
}

interface Credentials {
  username: string;
  password: string;
}

export function login(credentials: Credentials) {
  return post<AuthResponse>("/auth/login", credentials);
}

export function register(credentials: Credentials) {
  return post<AuthResponse>("/auth/register", credentials);
}

export function getCurrentUser() {
  return get<Pick<AuthResponse, "userId" | "username" | "role">>("/auth/me");
}
