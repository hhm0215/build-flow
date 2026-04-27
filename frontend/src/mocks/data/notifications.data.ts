import { Notification } from '../../types/domain.types'

export const mockNotifications: Notification[] = [
  {
    id: 1,
    type: 'ESTIMATE_PARSED',
    message: '[강남 오피스텔 신축] 공내역서 AI 파싱이 완료되었습니다. 견적서를 확인해주세요.',
    siteId: 1,
    read: false,
    createdAt: '2026-04-25T14:30:00',
  },
  {
    id: 2,
    type: 'PURCHASE_REGISTERED',
    message: '[판교 물류센터] 철근 자재 매입이 등록되었습니다. (포스코스틸, ₩8,500,000)',
    siteId: 2,
    read: false,
    createdAt: '2026-04-25T11:15:00',
  },
  {
    id: 3,
    type: 'WARRANTY_EXPIRING',
    message: '[수원 아파트 리모델링] 하자보증보험 만료 30일 전입니다. 갱신을 검토해주세요.',
    siteId: 3,
    read: false,
    createdAt: '2026-04-24T09:00:00',
  },
  {
    id: 4,
    type: 'PURCHASE_REGISTERED',
    message: '[강남 오피스텔 신축] 레미콘 매입이 등록되었습니다. (삼표산업, ₩3,200,000)',
    siteId: 1,
    read: true,
    createdAt: '2026-04-23T16:45:00',
  },
  {
    id: 5,
    type: 'ESTIMATE_PARSED',
    message: '[판교 물류센터] 추가공사 견적서 파싱이 완료되었습니다.',
    siteId: 2,
    read: true,
    createdAt: '2026-04-22T10:20:00',
  },
  {
    id: 6,
    type: 'WARRANTY_EXPIRING',
    message: '[송도 상가 인테리어] 하자보증보험이 7일 후 만료됩니다. 즉시 확인이 필요합니다.',
    siteId: null,
    read: true,
    createdAt: '2026-04-21T08:00:00',
  },
]
