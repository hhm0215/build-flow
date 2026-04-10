import { http, HttpResponse } from 'msw'
import { ApiResponse, LoginResponse } from '../../types'

// ─────────────────────────────────────────────
// MSW 핸들러 기본 구조:
//   http.post(URL, resolver)
//   resolver: ({ request }) => HttpResponse.json(데이터)
//
// 브라우저가 이 URL로 요청을 보내는 순간
// Service Worker가 가로채서 resolver를 실행함.
// 실제 네트워크 요청은 일어나지 않음.
// ─────────────────────────────────────────────

export const authHandlers = [
  http.post<never, { loginId: string; password: string }>(
    '/api/v1/auth/login',
    async ({ request }) => {
      const body = await request.json()

      // 개발 환경 Mock: 관리자 아이디/비밀번호 아무거나 입력해도 로그인 됨
      // 빈 값만 막음
      if (!body.loginId || !body.password) {
        return HttpResponse.json<ApiResponse<null>>(
          { success: false, data: null, error: '관리자 아이디와 비밀번호를 입력하세요.' },
          { status: 400 },
        )
      }

      return HttpResponse.json<ApiResponse<LoginResponse>>({
        success: true,
        data: {
          accessToken: 'mock-jwt-token-buildflow',
          tokenType: 'Bearer',
          expiresIn: 3600,
        },
        error: null,
      })
    },
  ),

  http.post('/api/v1/auth/logout', () => {
    return HttpResponse.json<ApiResponse<null>>({ success: true, data: null, error: null })
  }),
]
