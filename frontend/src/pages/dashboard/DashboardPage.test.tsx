import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import DashboardPage from './DashboardPage'

vi.mock('../../api/dashboard.api', () => ({
  useDashboardStats: () => ({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() }),
  useDashboardSummary: () => ({ data: undefined, isLoading: true }),
}))

describe('DashboardPage navigation', () => {
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
})
