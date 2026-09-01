import { lazy, Suspense, useState } from "react";
import { BrowserRouter } from "react-router-dom";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";
import LoginView from "./components/views/LoginView.tsx";
import { getAccessToken } from "./api/http.ts";
import { AppDataProvider } from "./contexts/AppDataProvider.tsx";

const JMindOpsLayout = lazy(() => import("./components/JMindOpsLayout.tsx"));

function App() {
  const [authenticated, setAuthenticated] = useState(() =>
    Boolean(getAccessToken()),
  );

  if (!authenticated) {
    return <LoginView onAuthenticated={() => setAuthenticated(true)} />;
  }

  return (
    <BrowserRouter>
      <AppDataProvider>
        <ChatSessionsProvider>
          <Suspense
            fallback={
              <div className="flex h-screen items-center justify-center text-gray-500">
                正在加载应用...
              </div>
            }
          >
            <JMindOpsLayout />
          </Suspense>
        </ChatSessionsProvider>
      </AppDataProvider>
    </BrowserRouter>
  );
}

export default App;
