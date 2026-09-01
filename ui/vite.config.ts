import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import Inspector from "vite-plugin-react-inspector"; // 👉 1. 引入 Inspector 插件

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const backendTarget = env.VITE_BACKEND_TARGET || "http://localhost:8080";

  return {
    plugins: [react(), tailwindcss(), Inspector()],
    server: {
      proxy: {
        "/api": {
          target: backendTarget,
          changeOrigin: true,
        },
        "/sse": {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
