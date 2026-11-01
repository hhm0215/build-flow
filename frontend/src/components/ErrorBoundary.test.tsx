import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

const SECRET = 'Bearer eyJhbGciOiJIUzI1NiJ9.secret-token'

let shouldThrow = true
function Bomb() {
  if (shouldThrow) throw new Error(`실패 ${SECRET}`)
  return <p>정상 화면</p>
}

describe('ErrorBoundary', () => {
  afterEach(() => {
    shouldThrow = true
    vi.restoreAllMocks()
  })

  it('자식 오류 시 안내를 보여 주고 오류 원문(토큰)을 노출하지 않는다', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary area="page"><Bomb /></ErrorBoundary>)

    expect(screen.getByText('문제가 생겼습니다. 다시 시도해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('secret-token')
    const boundaryLogs = consoleError.mock.calls.filter((args) => String(args[0]).startsWith('[ErrorBoundary:page]'))
    expect(boundaryLogs).toHaveLength(1)
    expect(String(boundaryLogs[0][0])).not.toContain('secret-token')
  })

  it('다시 시도 버튼으로 리셋하면 자식을 다시 렌더한다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary area="page"><Bomb /></ErrorBoundary>)
    expect(screen.queryByText('정상 화면')).not.toBeInTheDocument()

    shouldThrow = false
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(screen.getByText('정상 화면')).toBeInTheDocument()
  })

  it('resetKey가 바뀌면(라우트 이동) 오류 상태를 해제한다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Harness() {
      const [path, setPath] = useState('/a')
      return (
        <>
          <button onClick={() => { shouldThrow = false; setPath('/b') }}>이동</button>
          <ErrorBoundary area="page" resetKey={path}><Bomb /></ErrorBoundary>
        </>
      )
    }
    render(<Harness />)
    expect(screen.getByText('문제가 생겼습니다. 다시 시도해 주세요.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '이동' }))
    expect(screen.getByText('정상 화면')).toBeInTheDocument()
  })

  it('한 영역의 오류가 형제 영역을 지우지 않는다', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <>
        <ErrorBoundary area="page"><p>페이지 본문</p></ErrorBoundary>
        <ErrorBoundary area="chat" compact fallbackClassName="chat-panel"><Bomb /></ErrorBoundary>
      </>,
    )
    expect(screen.getByText('페이지 본문')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveClass('chat-panel')
    expect(screen.getByText('문제가 생겼습니다. 다시 시도해 주세요.')).toBeInTheDocument()
  })
})
