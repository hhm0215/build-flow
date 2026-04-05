// ─────────────────────────────────────────────
// 모든 API 응답의 공통 래퍼
// 백엔드 GlobalExceptionHandler가 항상 이 형태로 내려줌
// ─────────────────────────────────────────────
export interface ApiResponse<T> {
  success: boolean
  data: T
  error: string | null
}

// 페이지네이션 응답 (Spring Pageable)
export interface PagedData<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number   // 현재 페이지 (0-based)
  size: number
}
