import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Estimate, EstimateCreateRequest } from '../types'

export const ESTIMATES_KEY = {
  all: ['estimates'] as const,
  list: (params?: Record<string, string>) => ['estimates', 'list', params] as const,
  detail: (id: number) => ['estimates', 'detail', id] as const,
}

const fetchEstimates = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<Estimate[]>>('/estimates', { params })
  return res.data.data
}

const fetchEstimate = async (id: number) => {
  const res = await axiosInstance.get<ApiResponse<Estimate>>(`/estimates/${id}`)
  return res.data.data
}

const createEstimate = async (body: EstimateCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<Estimate>>('/estimates', body)
  return res.data.data
}

const updateEstimate = async ({ id, ...body }: EstimateCreateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<Estimate>>(`/estimates/${id}`, body)
  return res.data.data
}

const deleteEstimate = async (id: number) => {
  await axiosInstance.delete(`/estimates/${id}`)
}

const confirmEstimate = async (id: number) => {
  const res = await axiosInstance.patch<ApiResponse<Estimate>>(`/estimates/${id}/confirm`)
  return res.data.data
}

export function useEstimates(params?: Record<string, string>) {
  return useQuery({
    queryKey: ESTIMATES_KEY.list(params),
    queryFn: () => fetchEstimates(params),
  })
}

export function useEstimate(id: number) {
  return useQuery({
    queryKey: ESTIMATES_KEY.detail(id),
    queryFn: () => fetchEstimate(id),
    enabled: !!id,
  })
}

export function useCreateEstimate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createEstimate,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ESTIMATES_KEY.all }),
  })
}

export function useUpdateEstimate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateEstimate,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ESTIMATES_KEY.all }),
  })
}

export function useDeleteEstimate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteEstimate,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ESTIMATES_KEY.all }),
  })
}

export function useConfirmEstimate() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: confirmEstimate,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ESTIMATES_KEY.all }),
  })
}
