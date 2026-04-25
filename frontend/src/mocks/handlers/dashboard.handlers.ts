import { http, HttpResponse } from 'msw'
import { ApiResponse, DashboardStats, DashboardSummary } from '../../types'
import { mockSites } from '../data/sites.data'

export const dashboardHandlers = [
  http.get('/api/v1/dashboard/stats', () => {
    const sitesByStatus: Record<string, number> = {}
    mockSites.forEach((s) => {
      sitesByStatus[s.status] = (sitesByStatus[s.status] || 0) + 1
    })

    const stats: DashboardStats = {
      totalSites: mockSites.length,
      sitesByStatus,
      totalEstimateAmount: 115_000_000,
      totalPurchaseAmount: 53_600_000,
      totalMargin: 61_400_000,
      averageMarginRate: 53.39,
      siteProfits: mockSites.map((s) => ({
        siteId: s.id,
        siteName: s.siteName,
        status: s.status,
        estimateAmount: 25_000_000,
        purchaseAmount: 10_000_000,
        margin: 15_000_000,
        marginRate: 60,
      })),
    }

    return HttpResponse.json<ApiResponse<DashboardStats>>({
      success: true,
      data: stats,
      error: null,
    })
  }),

  http.get('/api/v1/dashboard/summary', () => {
    const summary: DashboardSummary = {
      summary: '## 현장 종합 요약\n\n현재 5개 현장 운영 중이며, 전체 마진율 53.4%로 양호합니다.\n\n### 주의 현장\n- 없음\n\n### 액션 아이템\n1. 정산 중인 현장 미수금 확인 필요',
      generatedAt: new Date().toISOString(),
    }

    return HttpResponse.json<ApiResponse<DashboardSummary>>({
      success: true,
      data: summary,
      error: null,
    })
  }),
]
