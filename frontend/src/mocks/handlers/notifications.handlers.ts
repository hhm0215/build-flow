import { http, HttpResponse } from 'msw'
import { mockNotifications } from '../data/notifications.data'
import { ApiResponse, Notification } from '../../types'

let notifications = [...mockNotifications]

export const notificationsHandlers = [
  // 전체 알림 목록
  http.get('/api/v1/notifications', () => {
    const sorted = [...notifications].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    )
    return HttpResponse.json<ApiResponse<Notification[]>>({
      success: true,
      data: sorted,
      error: null,
    })
  }),

  // 미읽음 건수
  http.get('/api/v1/notifications/unread-count', () => {
    const count = notifications.filter((n) => !n.read).length
    return HttpResponse.json<ApiResponse<number>>({
      success: true,
      data: count,
      error: null,
    })
  }),

  // 개별 읽음 처리
  http.patch<{ id: string }>('/api/v1/notifications/:id/read', ({ params }) => {
    const index = notifications.findIndex((n) => n.id === Number(params.id))
    if (index === -1) {
      return HttpResponse.json<ApiResponse<null>>(
        { success: false, data: null, error: '알림을 찾을 수 없습니다.' },
        { status: 404 },
      )
    }
    notifications[index] = { ...notifications[index], read: true }
    return HttpResponse.json<ApiResponse<Notification>>({
      success: true,
      data: notifications[index],
      error: null,
    })
  }),

  // 전체 읽음 처리
  http.patch('/api/v1/notifications/read-all', () => {
    notifications = notifications.map((n) => ({ ...n, read: true }))
    return HttpResponse.json<ApiResponse<null>>({
      success: true,
      data: null,
      error: null,
    })
  }),
]
