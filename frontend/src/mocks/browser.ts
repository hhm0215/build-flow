import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

// ─────────────────────────────────────────────
// setupWorker: Service Worker를 등록하고
// 지정한 handlers 목록을 기반으로
// 네트워크 요청을 가로채도록 설정함.
//
// worker.start()를 호출하면:
//   1. public/mockServiceWorker.js가 브라우저에 등록됨
//   2. 이후 모든 fetch/xhr 요청을 Service Worker가 감시
//   3. handlers에 매칭되는 요청 → mock 응답 반환
//   4. 매칭 안 되는 요청 → 실제 네트워크로 통과 (passthrough)
// ─────────────────────────────────────────────
export const worker = setupWorker(...handlers)
