import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import type { ApiResponse, Client, ClientCreateRequest, ClientUpdateRequest } from '../types'
import { SITES_KEY } from './sites.api'

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

export function useUpdateClient() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ id, ...body }: ClientUpdateRequest & { id: number }) => {
      const response = await axiosInstance.put<ApiResponse<Client>>(`/clients/${id}`, body)
      return response.data.data
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: CLIENTS_KEY })
      await queryClient.invalidateQueries({ queryKey: SITES_KEY.all })
    },
  })
}
