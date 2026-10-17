import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { setupServer } from 'msw/node'
import type { ApiResponse, Warranty, WarrantyAuditEvent } from '../../types'
import { warrantiesHandlers } from './warranties.handlers'

const server = setupServer(...warrantiesHandlers)
const url = new URL('/api/v1/warranties', document.baseURI)
const headers = { 'Content-Type': 'application/json' }

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

describe('보증보험 MSW 수동 보정 계약', () => {
  it('종료일 없는 OCR 실패 건은 만료 임박 목록에서 제외한다', async () => {
    const response = await fetch(new URL('/api/v1/warranties/expiring?days=36500', document.baseURI))
    const expiring = (await response.json() as ApiResponse<Warranty[]>).data
    expect(expiring.some((warranty) => warranty.id === 7)).toBe(false)
  })

  it('선택 필드의 null 삭제와 누락 유지, OCR 상태 전환을 반영한다', async () => {
    const created = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({
        siteId: 1, insuranceCompany: '기존 보험사', policyNumber: 'POL-1',
        coverageAmount: 100000, startDate: '2026-09-01', endDate: '2027-09-01', memo: '기존 메모',
      }),
    })
    const warranty = (await created.json() as ApiResponse<Warranty>).data
    const itemUrl = new URL(`/api/v1/warranties/${warranty.id}`, document.baseURI)
    const updated = await fetch(itemUrl, {
      method: 'PUT', headers,
      body: JSON.stringify({
        insuranceCompany: '수정 보험사', startDate: '2026-10-01', endDate: '2027-10-01',
        policyNumber: null, coverageAmount: null,
      }),
    })
    expect(updated.status).toBe(200)
    const result = (await updated.json() as ApiResponse<Warranty>).data
    expect(result).toMatchObject({
      siteId: 1, insuranceCompany: '수정 보험사', policyNumber: null,
      coverageAmount: null, memo: '기존 메모', ocrStatus: 'MANUAL',
    })
    const listed = await fetch(url)
    expect((await listed.json() as ApiResponse<Warranty[]>).data).toContainEqual(result)
  })

  it('필수값 누락·날짜 역전 400, 없는 ID 404를 반환한다', async () => {
    const body = JSON.stringify({ insuranceCompany: '보험사', startDate: '2026-01-01', endDate: '2027-01-01' })
    expect((await fetch(new URL('/api/v1/warranties/1', document.baseURI), {
      method: 'PUT', headers, body: JSON.stringify({ insuranceCompany: '', endDate: '2027-01-01' }),
    })).status).toBe(400)
    expect((await fetch(new URL('/api/v1/warranties/1', document.baseURI), {
      method: 'PUT', headers, body: JSON.stringify({ insuranceCompany: '보험사', startDate: '2027-01-01', endDate: '2026-01-01' }),
    })).status).toBe(400)
    expect((await fetch(new URL('/api/v1/warranties/999999', document.baseURI), {
      method: 'PUT', headers, body,
    })).status).toBe(404)
  })

  it('등록·수정·삭제가 감사 이력으로 남고 삭제 뒤에도 조회된다', async () => {
    const created = await fetch(url, {
      method: 'POST', headers,
      body: JSON.stringify({ siteId: 1, insuranceCompany: '[QA] 서울보증보험', startDate: '2026-10-17', endDate: '2028-10-16' }),
    })
    const warranty = (await created.json() as ApiResponse<Warranty>).data
    await fetch(new URL(`/api/v1/warranties/${warranty.id}`, document.baseURI), {
      method: 'PUT', headers,
      body: JSON.stringify({ insuranceCompany: '[QA] 삼성화재', startDate: '2026-10-17', endDate: '2028-10-31' }),
    })
    await fetch(new URL(`/api/v1/warranties/${warranty.id}`, document.baseURI), { method: 'DELETE' })

    const history = (await (await fetch(new URL(`/api/v1/warranties/${warranty.id}/history`, document.baseURI))).json() as ApiResponse<WarrantyAuditEvent[]>).data
    expect(history.map((e) => e.action)).toEqual(['CREATED', 'MANUAL_UPDATED', 'DELETED'])
    expect(history[1].insuranceCompany).toBe('[QA] 삼성화재')
  })
})
