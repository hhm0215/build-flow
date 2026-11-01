import { consumeSseStream, streamChat } from './chat.api'
import axiosInstance from './axiosInstance'
import { useAuthStore } from '../stores/authStore'
import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'

describe('streamChat 인증 갱신', () => {
  const originalAdapter = axiosInstance.defaults.adapter
  afterEach(() => {
    axiosInstance.defaults.adapter = originalAdapter
    useAuthStore.getState().logout()
    vi.unstubAllGlobals()
  })

  it('스트림 401이면 refresh 후 새 access로 한 번 재시도한다', async () => {
    useAuthStore.getState().setTokens('expired-access', 'refresh-0')
    axiosInstance.defaults.adapter = vi.fn<AxiosAdapter>(async (config) => ({
      config, status: 200, statusText: 'OK', headers: {},
      data: { success: true, data: { accessToken: 'access-1', refreshToken: 'refresh-1' }, error: null },
    }))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(streamChunks(['event: done\ndata: {"sessionId":"s1"}\n\n']), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const events: string[] = []

    await streamChat('안녕', null, new AbortController().signal, ({ event }) => events.push(event))

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer expired-access')
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer access-1')
    expect(events).toEqual(['done'])
    expect(useAuthStore.getState().refreshToken).toBe('refresh-1')
  })
})

function streamChunks(chunks: string[]) {
  const encoder = new TextEncoder()
  return new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
}

describe('consumeSseStream', () => {
  it('청크 경계로 나뉜 한글과 여러 이벤트를 순서대로 파싱한다', async () => {
    const events: string[] = []
    await consumeSseStream(
      streamChunks([
        'event: session\ndata: {"sessionId":"s1"}\n\nevent: token\ndata: {"content":"안',
        '녕"}\n\nevent: done\ndata: {"sessionId":"s1"}\n\n',
      ]),
      ({ event, data }) => events.push(`${event}:${data.content ?? data.sessionId}`),
    )
    expect(events).toEqual(['session:s1', 'token:안녕', 'done:s1'])
  })

  it('마지막 빈 줄이 없는 이벤트도 처리한다', async () => {
    const events: string[] = []
    await consumeSseStream(streamChunks(['event: token\ndata: {"content":"끝"}']), ({ event }) => events.push(event))
    expect(events).toEqual(['token'])
  })

  it('onEvent가 던지면 리더를 취소해 연결을 정리한다', async () => {
    let cancelled = false
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('event: error\ndata: {"message":"실패"}\n\n'))
      },
      cancel() {
        cancelled = true
      },
    })

    await expect(
      consumeSseStream(stream, () => {
        throw new Error('server error event')
      }),
    ).rejects.toThrow('server error event')
    expect(cancelled).toBe(true)
  })
})
