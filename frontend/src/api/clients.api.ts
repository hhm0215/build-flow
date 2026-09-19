import { useQuery } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import type { ApiResponse, Client } from '../types'

export const CLIENTS_KEY = ['clients'] as const

export function useClients(enabled = true) {
  return useQuery({
    queryKey: CLIENTS_KEY,
    enabled,
    queryFn: async () => {
      const response = await axiosInstance.get<ApiResponse<Client[]>>('/clients')
      return response.data.data
    },
  })
}
