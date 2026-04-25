import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Notification } from '../types'

export const NOTIFICATIONS_KEY = {
  all: ['notifications'] as const,
  list: ['notifications', 'list'] as const,
  unreadCount: ['notifications', 'unread-count'] as const,
}

const fetchNotifications = async () => {
  const res = await axiosInstance.get<ApiResponse<Notification[]>>('/notifications')
  return res.data.data
}

const fetchUnreadCount = async () => {
  const res = await axiosInstance.get<ApiResponse<number>>('/notifications/unread-count')
  return res.data.data
}

const markAsRead = async (id: number) => {
  await axiosInstance.patch(`/notifications/${id}/read`)
}

const markAllAsRead = async () => {
  await axiosInstance.patch('/notifications/read-all')
}

export function useNotifications() {
  return useQuery({
    queryKey: NOTIFICATIONS_KEY.list,
    queryFn: fetchNotifications,
  })
}

export function useUnreadCount() {
  return useQuery({
    queryKey: NOTIFICATIONS_KEY.unreadCount,
    queryFn: fetchUnreadCount,
    refetchInterval: 30 * 1000, // 30초마다 자동 갱신
  })
}

export function useMarkAsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markAsRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY.all })
    },
  })
}

export function useMarkAllAsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markAllAsRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: NOTIFICATIONS_KEY.all })
    },
  })
}
