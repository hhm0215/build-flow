import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import axiosInstance from './axiosInstance'
import { ApiResponse, Warranty, WarrantyCreateRequest } from '../types'

export const WARRANTIES_KEY = {
  all: ['warranties'] as const,
  list: (params?: Record<string, string>) => ['warranties', 'list', params] as const,
  expiring: (days: number) => ['warranties', 'expiring', days] as const,
}

const fetchWarranties = async (params?: Record<string, string>) => {
  const res = await axiosInstance.get<ApiResponse<Warranty[]>>('/warranties', { params })
  return res.data.data
}

const uploadWarranty = async ({ file, siteId }: { file: File; siteId: number }) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('siteId', String(siteId))
  const res = await axiosInstance.post<ApiResponse<Warranty>>('/warranties/upload', formData, {
    timeout: 30_000,
  })
  return res.data.data
}

const createWarranty = async (body: WarrantyCreateRequest) => {
  const res = await axiosInstance.post<ApiResponse<Warranty>>('/warranties', body)
  return res.data.data
}

const updateWarranty = async ({ id, ...body }: WarrantyCreateRequest & { id: number }) => {
  const res = await axiosInstance.put<ApiResponse<Warranty>>(`/warranties/${id}`, body)
  return res.data.data
}

const deleteWarranty = async (id: number) => {
  await axiosInstance.delete(`/warranties/${id}`)
}

const fetchExpiring = async (days: number) => {
  const res = await axiosInstance.get<ApiResponse<Warranty[]>>(`/warranties/expiring`, { params: { days } })
  return res.data.data
}

type RefetchIntervalOption =
  | number
  | false
  | ((query: { state: { data?: Warranty[] } }) => number | false)

export function useWarranties(params?: Record<string, string>, refetchInterval?: RefetchIntervalOption) {
  return useQuery({
    queryKey: WARRANTIES_KEY.list(params),
    queryFn: () => fetchWarranties(params),
    refetchInterval: refetchInterval as never,
  })
}

export function useUploadWarranty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: uploadWarranty,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: WARRANTIES_KEY.all }),
  })
}

export function useCreateWarranty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createWarranty,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: WARRANTIES_KEY.all }),
  })
}

export function useUpdateWarranty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: updateWarranty,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: WARRANTIES_KEY.all }),
  })
}

export function useDeleteWarranty() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: deleteWarranty,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: WARRANTIES_KEY.all }),
  })
}

export function useExpiringWarranties(days: number = 30) {
  return useQuery({
    queryKey: WARRANTIES_KEY.expiring(days),
    queryFn: () => fetchExpiring(days),
  })
}
