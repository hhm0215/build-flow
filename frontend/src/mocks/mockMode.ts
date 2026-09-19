declare const __BUILDFLOW_MSW_ENABLED__: boolean

export function isMockModeEnabled(isDevelopment: boolean, mswEnabled: boolean) {
  return isDevelopment && mswEnabled
}

export const isMockMode = isMockModeEnabled(import.meta.env.DEV, __BUILDFLOW_MSW_ENABLED__)

type MockWorker = {
  start: (options: { onUnhandledRequest: 'bypass' }) => Promise<unknown>
}

/** 실제 API 모드에서는 browser 모듈도 로드하지 않아 worker가 요청을 가로채지 않는다. */
export async function enableMocking(
  enabled: boolean,
  loadWorker: () => Promise<{ worker: MockWorker }> = () => import('./browser'),
) {
  if (!enabled) return
  const { worker } = await loadWorker()
  await worker.start({ onUnhandledRequest: 'bypass' })
}
