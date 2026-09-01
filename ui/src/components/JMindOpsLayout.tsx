import { lazy, Suspense } from "react";
import { Navigate, Routes, Route, useParams } from "react-router-dom";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";

const AgentChatView = lazy(() => import("./views/AgentChatView.tsx"));
const KnowledgeBaseView = lazy(() => import("./views/KnowledgeBaseView.tsx"));

function AgentChatRoute() {
  const { chatSessionId } = useParams<{ chatSessionId?: string }>();
  return <AgentChatView key={chatSessionId || "new"} />;
}

export default function JMindOpsLayout() {
  return (
    <Layout>
      <Sidebar>
        <SideMenu />
      </Sidebar>
      <Content>
        <Suspense
          fallback={
            <div className="flex h-full items-center justify-center text-gray-400">
              正在加载页面...
            </div>
          }
        >
          <Routes>
            <Route path="/" element={<AgentChatRoute />} />
            <Route path="/agent" element={<AgentChatRoute />} />
            <Route path="/chat" element={<AgentChatRoute />} />
            <Route path="/chat/:chatSessionId" element={<AgentChatRoute />} />
            <Route path="/knowledge-base" element={<KnowledgeBaseView />} />
            <Route
              path="/knowledge-base/:knowledgeBaseId"
              element={<KnowledgeBaseView />}
            />
            <Route path="*" element={<Navigate replace to="/" />} />
          </Routes>
        </Suspense>
      </Content>
    </Layout>
  );
}
