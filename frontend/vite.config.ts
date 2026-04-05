import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// MSW_DISABLED=true bun run dev → 실제 백엔드로 연결
// 기본값(개발) → MSW가 요청을 가로채므로 proxy 불필요
const useMock = process.env.MSW_DISABLED !== 'true'

export default defineConfig({
  plugins: [react()],
  optimizeDeps: {
    include: ['react', 'react-dom', 'react/jsx-runtime', 'motion/react'],
  },
  server: {
    port: 3000,
    ...(useMock
      ? {} // Mock 모드: proxy 없음 (MSW가 처리)
      : {
          proxy: {
            '/api': {
              target: 'http://localhost:8080',
              changeOrigin: true,
            },
          },
        }),
  },
})
