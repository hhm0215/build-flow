import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion } from 'motion/react'
import { LogOut } from 'lucide-react'
import axiosInstance from '../api/axiosInstance'
import { useAuthStore } from '../stores/authStore'
import type { ApiResponse } from '../types/api.types'

export default function LogoutButton() {
  const navigate = useNavigate()
  const inFlight = useRef(false)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  const handleLogout = async () => {
    if (inFlight.current) return
    inFlight.current = true
    setPending(true)
    setFailed(false)
    try {
      const response = await axiosInstance.post<ApiResponse<null>>('/auth/logout')
      if (response.data?.success !== true) throw new Error('Logout was not confirmed')
      useAuthStore.getState().logout()
      navigate('/login', { replace: true })
    } catch {
      // Keep the token available for a retry if revocation was not confirmed.
      setFailed(true)
    } finally {
      inFlight.current = false
      setPending(false)
    }
  }

  return (
    <>
      {failed && (
        <p role="alert" style={{ color: 'var(--danger)', fontSize: 12, padding: '0 10px' }}>
          서버 로그아웃을 확인하지 못했습니다. 로그인 정보가 아직 유효할 수 있어 이 기기의 로그인 상태를 유지했습니다. 다시 시도해 주세요.
        </p>
      )}
      <motion.button
        whileHover={{ x: 2 }}
        whileTap={{ scale: 0.97 }}
        onClick={handleLogout}
        disabled={pending}
        aria-busy={pending}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          padding: '9px 10px',
          borderRadius: 'var(--radius-sm)',
          border: 'none',
          cursor: pending ? 'wait' : 'pointer',
          background: 'transparent',
          color: 'var(--text-muted)',
          fontSize: 14,
          width: '100%',
          transition: 'color 0.15s, background 0.15s',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.color = 'var(--danger)'
          e.currentTarget.style.background = 'rgba(239,68,68,0.08)'
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.color = 'var(--text-muted)'
          e.currentTarget.style.background = 'transparent'
        }}
      >
        <LogOut size={15} strokeWidth={1.8} />
        {pending ? '로그아웃 중…' : '로그아웃'}
      </motion.button>
    </>
  )
}
