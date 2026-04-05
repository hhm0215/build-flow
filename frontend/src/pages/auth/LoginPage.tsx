import { useState } from 'react'
import { Form, Input, Button } from 'antd'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { Zap, Mail, Lock, ArrowRight, AlertCircle, FlaskConical } from 'lucide-react'
import { useAuthStore } from '../../stores/authStore'
import axiosInstance from '../../api/axiosInstance'

interface LoginForm {
  email: string
  password: string
}

export default function LoginPage() {
  const navigate = useNavigate()
  const setTokens = useAuthStore((s) => s.setTokens)
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const onFinish = async (values: LoginForm) => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const response = await axiosInstance.post('/auth/login', values)
      setTokens(response.data.data.accessToken)
      navigate('/dashboard')
    } catch (err: unknown) {
      const msg =
        (err as { response?: { data?: { error?: string } } })?.response?.data?.error
        ?? '로그인에 실패했습니다. 다시 시도해주세요.'
      setErrorMsg(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: '100vh',
      background: 'var(--bg-base)',
      position: 'relative',
      overflow: 'hidden',
    }}>
      {/* Background glow blobs */}
      <div style={{
        position: 'absolute', top: '20%', left: '30%',
        width: 400, height: 400,
        background: 'rgba(59,130,246,0.06)', borderRadius: '50%',
        filter: 'blur(80px)', pointerEvents: 'none',
      }} />
      <div style={{
        position: 'absolute', bottom: '20%', right: '30%',
        width: 300, height: 300,
        background: 'rgba(139,92,246,0.06)', borderRadius: '50%',
        filter: 'blur(80px)', pointerEvents: 'none',
      }} />
      <div style={{
        position: 'absolute', inset: 0,
        backgroundImage: `linear-gradient(rgba(255,255,255,0.015) 1px, transparent 1px),
                          linear-gradient(90deg, rgba(255,255,255,0.015) 1px, transparent 1px)`,
        backgroundSize: '40px 40px', pointerEvents: 'none',
      }} />

      {/* Card */}
      <motion.div
        initial={{ opacity: 0, y: 24, scale: 0.97 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.45, ease: [0.25, 0.46, 0.45, 0.94] }}
        style={{
          width: 400,
          background: 'rgba(255,255,255,0.04)',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 16,
          padding: 36,
          backdropFilter: 'blur(20px)',
          boxShadow: '0 24px 80px rgba(0,0,0,0.5)',
          position: 'relative',
          zIndex: 1,
        }}
      >
        {/* Logo */}
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
          style={{ textAlign: 'center', marginBottom: 32 }}
        >
          <div style={{
            width: 48, height: 48,
            background: 'var(--accent-gradient)',
            borderRadius: 12,
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            marginBottom: 16,
            boxShadow: '0 0 32px rgba(59,130,246,0.4)',
          }}>
            <Zap size={24} color="white" fill="white" />
          </div>
          <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>
            BuildFlow
          </div>
          <div style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 4 }}>
            현장 관리 플랫폼에 오신 것을 환영합니다
          </div>
        </motion.div>

        {/* 개발 환경 힌트 */}
        {import.meta.env.DEV && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            transition={{ delay: 0.3 }}
            style={{
              background: 'rgba(139,92,246,0.08)',
              border: '1px solid rgba(139,92,246,0.2)',
              borderRadius: 8,
              padding: '10px 14px',
              marginBottom: 20,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <FlaskConical size={13} color="#8b5cf6" strokeWidth={2} style={{ flexShrink: 0 }} />
            <span style={{ fontSize: 12, color: '#a78bfa' }}>
              <strong>Mock 모드</strong> — 이메일·비밀번호 아무거나 입력하면 로그인됩니다
            </span>
          </motion.div>
        )}

        {/* 에러 메시지 */}
        <AnimatePresence>
          {errorMsg && (
            <motion.div
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -6 }}
              style={{
                background: 'rgba(239,68,68,0.08)',
                border: '1px solid rgba(239,68,68,0.2)',
                borderRadius: 8,
                padding: '10px 14px',
                marginBottom: 16,
                display: 'flex',
                alignItems: 'center',
                gap: 8,
              }}
            >
              <AlertCircle size={13} color="#ef4444" strokeWidth={2} style={{ flexShrink: 0 }} />
              <span style={{ fontSize: 12, color: '#fca5a5' }}>{errorMsg}</span>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Form */}
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.2 }}>
          <Form layout="vertical" onFinish={onFinish} size="large">
            <Form.Item
              name="email"
              label="이메일"
              rules={[
                { required: true, message: '이메일을 입력하세요' },
                { type: 'email', message: '올바른 이메일 형식이 아닙니다' },
              ]}
              style={{ marginBottom: 16 }}
            >
              <Input
                prefix={<Mail size={15} color="var(--text-muted)" strokeWidth={1.8} style={{ marginRight: 4 }} />}
                placeholder="name@company.com"
              />
            </Form.Item>

            <Form.Item
              name="password"
              label="비밀번호"
              rules={[{ required: true, message: '비밀번호를 입력하세요' }]}
              style={{ marginBottom: 24 }}
            >
              <Input.Password
                prefix={<Lock size={15} color="var(--text-muted)" strokeWidth={1.8} style={{ marginRight: 4 }} />}
                placeholder="비밀번호 입력"
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <motion.div whileHover={{ scale: 1.01 }} whileTap={{ scale: 0.99 }}>
                <Button
                  type="primary"
                  htmlType="submit"
                  block
                  loading={loading}
                  style={{
                    height: 44,
                    background: 'var(--accent-gradient)',
                    border: 'none',
                    borderRadius: 'var(--radius-sm)',
                    fontSize: 14,
                    fontWeight: 600,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: 8,
                    boxShadow: '0 0 24px rgba(59,130,246,0.25)',
                  }}
                >
                  {!loading && '로그인'}
                  {!loading && <ArrowRight size={15} strokeWidth={2.5} />}
                </Button>
              </motion.div>
            </Form.Item>
          </Form>
        </motion.div>
      </motion.div>
    </div>
  )
}
