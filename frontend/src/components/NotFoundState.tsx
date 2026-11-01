import { Button, Result } from 'antd'
import { useNavigate } from 'react-router-dom'

interface NotFoundStateProps {
  title?: string
  subTitle?: string
  actionLabel?: string
  to?: string
}

/** 없는 주소·없는 리소스 안내. 레이아웃 안에서 렌더해 메뉴로 바로 돌아갈 수 있게 한다 */
export default function NotFoundState({
  title = '페이지를 찾을 수 없습니다',
  subTitle = '주소가 바뀌었거나 없는 페이지입니다. 메뉴에서 다시 선택해 주세요.',
  actionLabel = '대시보드로',
  to = '/dashboard',
}: NotFoundStateProps) {
  const navigate = useNavigate()
  return (
    <Result
      status="404"
      title={title}
      subTitle={subTitle}
      extra={
        <Button type="primary" onClick={() => navigate(to)}>
          {actionLabel}
        </Button>
      }
    />
  )
}
