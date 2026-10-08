import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { message } from 'antd'
import { describe, expect, it, vi } from 'vitest'
import UploadParseModal from './UploadParseModal'

const mutate = vi.hoisted(() => vi.fn())

vi.mock('../../api/estimates.api', () => ({
  useParseEstimateFile: () => ({ mutate, reset: vi.fn(), isPending: false }),
}))

describe('UploadParseModal', () => {
  it('백엔드가 지원하는 .xlsx만 업로드 대상으로 안내한다', () => {
    render(
      <UploadParseModal open onClose={vi.fn()} onConfirm={vi.fn()} />,
    )

    expect(screen.getByText(/지원 형식: \.xlsx · 최대 10MB/)).toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).toHaveAttribute('accept', '.xlsx')
  })

  it('.xls 파일은 파싱 API로 보내지 않는다', async () => {
    mutate.mockClear()
    const error = vi.spyOn(message, 'error').mockImplementation(() => ({}) as ReturnType<typeof message.error>)
    render(<UploadParseModal open onClose={vi.fn()} onConfirm={vi.fn()} />)

    const input = document.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['test'], 'old.xls')] } })

    await waitFor(() => expect(error).toHaveBeenCalledWith('엑셀 파일(.xlsx)만 업로드 가능합니다.'))
    expect(mutate).not.toHaveBeenCalled()
    error.mockRestore()
  })
})
