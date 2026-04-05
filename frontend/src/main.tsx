import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, theme } from 'antd'
import koKR from 'antd/locale/ko_KR'
import dayjs from 'dayjs'
import 'dayjs/locale/ko'
import App from './App'
import './index.css'

dayjs.locale('ko')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 1000 * 60 * 5,
    },
  },
})

// ─────────────────────────────────────────────
// MSW는 개발 환경에서만 활성화.
// import.meta.env.DEV = vite dev 실행 시 true
// 빌드(production)에서는 실제 API로 요청이 감.
//
// worker.start()는 Promise를 반환하므로
// Service Worker 등록이 완료된 뒤에 앱을 마운트.
// ─────────────────────────────────────────────
async function enableMocking() {
  if (!import.meta.env.DEV) return
  const { worker } = await import('./mocks/browser')
  return worker.start({
    onUnhandledRequest: 'bypass', // 핸들러 없는 요청은 실제 네트워크로 통과
  })
}

enableMocking().then(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <ConfigProvider
            locale={koKR}
            theme={{
              algorithm: theme.darkAlgorithm,
              token: {
                colorPrimary: '#3b82f6',
                colorBgBase: '#09090b',
                colorTextBase: '#fafafa',
                borderRadius: 8,
                fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
                fontSize: 14,
              },
              components: {
                Button: { colorPrimary: '#3b82f6', algorithm: true },
              },
            }}
          >
            <App />
          </ConfigProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </React.StrictMode>,
  )
})
