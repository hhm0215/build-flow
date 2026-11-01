import { isAxiosError } from 'axios'

/** 서버가 리소스 없음(HTTP 404)으로 답한 요청 오류인지 */
export function isNotFoundError(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 404
}

/** 404는 다시 시도해도 같으므로 재시도하지 않고, 그 밖의 오류는 1회만 재시도한다 */
export function retryUnlessNotFound(failureCount: number, error: unknown): boolean {
  return !isNotFoundError(error) && failureCount < 1
}
