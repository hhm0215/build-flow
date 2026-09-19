import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import type { ApiResponse, Client, ClientCreateRequest } from '../types'

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

export function useCreateClient() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: ClientCreateRequest) => {
      const response = await axiosInstance.post<ApiResponse<Client>>('/clients', body)
      return response.data.data
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CLIENTS_KEY }),
  })
}
