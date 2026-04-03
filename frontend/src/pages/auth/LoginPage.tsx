import { Form, Input, Button, Card, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../../stores/authStore'
import axiosInstance from '../../api/axiosInstance'

const { Title } = Typography

interface LoginForm {
  email: string
  password: string
}

export default function LoginPage() {
  const navigate = useNavigate()
  const setTokens = useAuthStore((s) => s.setTokens)

  const onFinish = async (values: LoginForm) => {
    const response = await axiosInstance.post('/auth/login', values)
    setTokens(response.data.data.accessToken)
    navigate('/dashboard')
  }

  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Title level={3} style={{ textAlign: 'center', marginBottom: 24 }}>
          BuildFlow
        </Title>
        <Form layout="vertical" onFinish={onFinish}>
          <Form.Item name="email" label="이메일" rules={[{ required: true, type: 'email' }]}>
            <Input size="large" placeholder="이메일을 입력하세요" />
          </Form.Item>
          <Form.Item name="password" label="비밀번호" rules={[{ required: true }]}>
            <Input.Password size="large" placeholder="비밀번호를 입력하세요" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" size="large" block>
              로그인
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  )
}
