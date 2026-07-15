import { consumeSseStream } from './chat.api'
import { describe, expect, it } from 'vitest'

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
