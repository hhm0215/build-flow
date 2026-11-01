import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button, Result } from 'antd'

interface ErrorBoundaryProps {
  children: ReactNode
  /** 콘솔 기록에 붙일 영역 이름 (예: 'page', 'chat') */
  area: string
  /** 값이 바뀌면 오류 상태를 자동으로 해제한다 (예: 라우트 경로) */
  resetKey?: unknown
  /** 좁은 영역(채팅 패널 등)에서 작게 보여 줄 때 */
  compact?: boolean
  /** 안내를 감쌀 요소의 className (채팅 패널처럼 고정 위치 영역 유지용) */
  fallbackClassName?: string
}

interface ErrorBoundaryState {
  hasError: boolean
}

/**
 * 렌더/effect 오류가 앱 전체를 비우지 않도록 영역별로 감싼다.
 * 오류 메시지·스택에는 토큰 등 민감 정보가 섞일 수 있어 화면에 노출하지 않는다.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: Error, _info: ErrorInfo) {
    // 원문 메시지는 남기지 않고 오류 종류만 기록한다
    console.error(`[ErrorBoundary:${this.props.area}] ${error?.name ?? 'Error'}`)
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps) {
    if (this.state.hasError && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false })
    }
  }

  reset = () => this.setState({ hasError: false })

  render() {
    if (!this.state.hasError) return this.props.children
    const result = (
      <Result
        status="error"
        title="문제가 생겼습니다. 다시 시도해 주세요."
        subTitle={this.props.compact ? undefined : '화면을 표시하는 중 오류가 발생했습니다.'}
        style={this.props.compact ? { padding: '24px 12px' } : undefined}
        extra={<Button type="primary" onClick={this.reset}>다시 시도</Button>}
      />
    )
    if (!this.props.fallbackClassName) return result
    return (
      <div className={this.props.fallbackClassName} role="alert" style={{ height: 'auto' }}>
        {result}
      </div>
    )
  }
}
