import { http, HttpResponse } from 'msw'

export const chatHandlers = [
  http.post('/api/v1/chat/stream', async ({ request }) => {
    const body = await request.json() as { sessionId?: string; message?: string }
    const sessionId = body.sessionId || crypto.randomUUID()
    const answer = body.message?.includes('미수금')
      ? '현재 전체 미수금은 8,000,000원입니다.'
      : '현재 등록된 현장 데이터를 기준으로 확인했습니다. 현장명과 확인할 항목을 함께 말씀해주세요.'
    const frames = [
      `event: session\ndata: ${JSON.stringify({ sessionId })}\n\n`,
      `event: status\ndata: ${JSON.stringify({ phase: 'thinking' })}\n\n`,
      `event: status\ndata: ${JSON.stringify({ phase: 'answering' })}\n\n`,
      ...answer.split(' ').map((word) => `event: token\ndata: ${JSON.stringify({ content: `${word} ` })}\n\n`),
      `event: done\ndata: ${JSON.stringify({ sessionId })}\n\n`,
    ]
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        frames.forEach((frame) => controller.enqueue(encoder.encode(frame)))
        controller.close()
      },
    })
    return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
  }),
]
