import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// MSW_DISABLED=true bun run dev → 실제 백엔드로 연결
// 기본값(개발) → MSW가 요청을 가로채므로 proxy 불필요
const useMock = process.env.MSW_DISABLED !== 'true'

export default defineConfig({
  plugins: [react()],
  define: {
    __BUILDFLOW_MSW_ENABLED__: JSON.stringify(useMock),
  },
  build: {
    // antd는 단일 라이브러리라 더 쪼갤 수 없어 약 720 kB(gzip 약 230 kB). 앱 코드는 페이지별로 분리돼 있다
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        // 자주 바뀌지 않는 큰 라이브러리를 앱 코드와 분리해 캐시 재사용과 첫 로드 크기를 개선
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/[\/]node_modules[\/](react|react-dom|react-router|react-router-dom|scheduler)[\/]/.test(id)) return 'vendor-react'
          if (/[\/]node_modules[\/](antd|@ant-design|rc-[^\/]+|@rc-component)[\/]/.test(id)) return 'vendor-antd'
          if (/[\/]node_modules[\/](react-markdown|remark-[^\/]+|rehype-[^\/]+|unified|mdast-[^\/]+|micromark[^\/]*|hast-[^\/]+)[\/]/.test(id)) return 'vendor-markdown'
          // 나머지(msw 등)는 Rollup에 맡긴다. 묶어버리면 개발 전용 msw가 첫 화면 공용 청크로 끌려온다
          return undefined
        },
      },
    },
  },
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
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
  },
})
