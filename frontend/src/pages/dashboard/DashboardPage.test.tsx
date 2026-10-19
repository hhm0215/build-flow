import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardPage from './DashboardPage'

const { summaryRefetch, summaryQuery } = vi.hoisted(() => ({
  summaryRefetch: vi.fn(),
  summaryQuery: vi.fn(),
}))

vi.mock('../../api/dashboard.api', () => ({
  useDashboardStats: () => ({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() }),
  useDashboardSummary: summaryQuery,
}))

beforeEach(() => {
  summaryRefetch.mockReset()
  summaryQuery.mockReset()
  summaryQuery.mockReturnValue({ data: undefined, isLoading: true, isError: false, refetch: summaryRefetch })
})

describe('DashboardPage', () => {
  it('현장별 손익의 전체 보기를 누르면 현장 목록으로 이동한다', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/sites" element={<div>현장 목록 도착</div>} />
        </Routes>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: '전체 보기' }))
    expect(screen.getByText('현장 목록 도착')).toBeInTheDocument()
  })

  it('AI 요약 실패 시 빈 결과 대신 오류와 재시도를 표시한다', () => {
    summaryQuery.mockReturnValue({ data: undefined, isLoading: false, isError: true, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('AI 요약을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('요약 데이터가 없습니다')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(summaryRefetch).toHaveBeenCalledOnce()
  })

  it('캐시된 오래된 요약이 있어도 최신 요청이 실패하면 오류를 표시한다', () => {
    summaryQuery.mockReturnValue({ data: { summary: '오래된 요약' }, isLoading: false, isError: true, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('AI 요약을 불러오지 못했습니다.')).toBeInTheDocument()
    expect(screen.queryByText('오래된 요약')).not.toBeInTheDocument()
  })

  it('정상적인 빈 요약은 빈 결과로 안내한다', () => {
    summaryQuery.mockReturnValue({ data: { summary: '' }, isLoading: false, isError: false, refetch: summaryRefetch })
    render(<MemoryRouter><DashboardPage /></MemoryRouter>)

    expect(screen.getByText('요약 데이터가 없습니다')).toBeInTheDocument()
    expect(screen.queryByText('AI 요약을 불러오지 못했습니다.')).not.toBeInTheDocument()
  })
})
