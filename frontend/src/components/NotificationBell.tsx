import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { Bell, FileText, ShoppingCart, ShieldAlert, ChevronRight } from 'lucide-react'
import {
  useNotifications,
  useUnreadCount,
  useMarkAsRead,
} from '../api/notifications.api'
import type { Notification } from '../types'

const TYPE_CONFIG: Record<string, { color: string; bg: string; border: string; icon: typeof Bell }> = {
  ESTIMATE_PARSED: {
    color: '#3b82f6',
    bg: 'rgba(59,130,246,0.1)',
    border: 'rgba(59,130,246,0.2)',
    icon: FileText,
  },
  PURCHASE_REGISTERED: {
    color: '#22c55e',
    bg: 'rgba(34,197,94,0.1)',
    border: 'rgba(34,197,94,0.2)',
    icon: ShoppingCart,
  },
  WARRANTY_EXPIRING: {
    color: '#f59e0b',
    bg: 'rgba(245,158,11,0.1)',
    border: 'rgba(245,158,11,0.2)',
    icon: ShieldAlert,
  },
}

function formatRelativeTime(dateStr: string): string {
  const now = new Date()
  const date = new Date(dateStr)
  const diffMs = now.getTime() - date.getTime()
  const diffMin = Math.floor(diffMs / 60_000)
  const diffHour = Math.floor(diffMs / 3_600_000)
  const diffDay = Math.floor(diffMs / 86_400_000)

  if (diffMin < 1) return '방금 전'
  if (diffMin < 60) return `${diffMin}분 전`
  if (diffHour < 24) return `${diffHour}시간 전`
  if (diffDay < 7) return `${diffDay}일 전`
  return date.toLocaleDateString('ko-KR')
}

export default function NotificationBell() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const wrapperRef = useRef<HTMLDivElement>(null)

  const { data: unreadCount } = useUnreadCount()
  const { data: notifications } = useNotifications()
  const { mutate: markAsRead } = useMarkAsRead()

  const count = unreadCount ?? 0
  const recent = (notifications ?? []).slice(0, 5)

  useEffect(() => {
    if (!open) return
    const clickHandler = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    const keyHandler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', clickHandler)
    document.addEventListener('keydown', keyHandler)
    return () => {
      document.removeEventListener('mousedown', clickHandler)
      document.removeEventListener('keydown', keyHandler)
    }
  }, [open])

  const handleItemClick = (n: Notification) => {
    if (!n.read) markAsRead(n.id)
    setOpen(false)
    if (n.siteId) {
      navigate(`/sites/${n.siteId}`)
    } else {
      navigate('/notifications')
    }
  }

  const handleViewAll = () => {
    setOpen(false)
    navigate('/notifications')
  }

  return (
    <div ref={wrapperRef} style={{ position: 'relative' }}>
      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setOpen((v) => !v)}
        aria-label="알림"
        style={{
          position: 'relative',
          width: 34,
          height: 34,
          borderRadius: 9,
          border: '1px solid var(--border)',
          background: open ? 'rgba(59,130,246,0.12)' : 'transparent',
          color: open ? 'var(--accent)' : 'var(--text-secondary)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
          transition: 'background 0.15s, color 0.15s',
        }}
      >
        <Bell size={16} strokeWidth={1.8} />
        {count > 0 && (
          <span
            style={{
              position: 'absolute',
              top: -4,
              right: -4,
              minWidth: 16,
              height: 16,
              padding: '0 4px',
              borderRadius: 8,
              background: '#ef4444',
              color: 'white',
              fontSize: 10,
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 0 8px rgba(239,68,68,0.5)',
            }}
          >
            {count > 99 ? '99+' : count}
          </span>
        )}
      </motion.button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -6, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.98 }}
            transition={{ duration: 0.15, ease: 'easeOut' }}
            style={{
              position: 'absolute',
              top: 'calc(100% + 8px)',
              right: 0,
              width: 360,
              background: 'var(--bg-elevated)',
              border: '1px solid var(--border)',
              borderRadius: 12,
              boxShadow: '0 12px 32px rgba(0,0,0,0.5)',
              overflow: 'hidden',
              zIndex: 100,
            }}
          >
            {/* 헤더 */}
            <div
              style={{
                padding: '14px 16px',
                borderBottom: '1px solid var(--border)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>
                알림
              </span>
              {count > 0 && (
                <span
                  style={{
                    fontSize: 11,
                    fontWeight: 600,
                    color: '#ef4444',
                    background: 'rgba(239,68,68,0.1)',
                    padding: '2px 8px',
                    borderRadius: 20,
                  }}
                >
                  미읽음 {count}건
                </span>
              )}
            </div>

            {/* 목록 */}
            {recent.length === 0 ? (
              <div
                style={{
                  padding: '32px 16px',
                  textAlign: 'center',
                  fontSize: 13,
                  color: 'var(--text-muted)',
                }}
              >
                알림이 없습니다.
              </div>
            ) : (
              <div style={{ maxHeight: 360, overflowY: 'auto' }}>
                {recent.map((n) => {
                  const config = TYPE_CONFIG[n.type] ?? {
                    color: 'var(--text-muted)',
                    bg: 'rgba(255,255,255,0.04)',
                    border: 'var(--border)',
                    icon: Bell,
                  }
                  const TypeIcon = config.icon

                  return (
                    <button
                      key={n.id}
                      onClick={() => handleItemClick(n)}
                      style={{
                        width: '100%',
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: 10,
                        padding: '12px 16px',
                        border: 'none',
                        borderBottom: '1px solid var(--border)',
                        background: n.read ? 'transparent' : 'rgba(59,130,246,0.05)',
                        color: 'inherit',
                        cursor: 'pointer',
                        textAlign: 'left',
                        transition: 'background 0.15s ease',
                      }}
                      onMouseEnter={(e) => {
                        (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,0.04)'
                      }}
                      onMouseLeave={(e) => {
                        (e.currentTarget as HTMLButtonElement).style.background = n.read
                          ? 'transparent'
                          : 'rgba(59,130,246,0.05)'
                      }}
                    >
                      <div
                        style={{
                          flexShrink: 0,
                          width: 28,
                          height: 28,
                          borderRadius: 7,
                          background: config.bg,
                          border: `1px solid ${config.border}`,
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                        }}
                      >
                        <TypeIcon size={13} color={config.color} strokeWidth={2.5} />
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <p
                          style={{
                            margin: 0,
                            fontSize: 13,
                            color: n.read ? 'var(--text-secondary)' : 'var(--text-primary)',
                            fontWeight: n.read ? 400 : 500,
                            lineHeight: 1.4,
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                          }}
                        >
                          {n.message}
                        </p>
                        <span
                          style={{
                            fontSize: 11,
                            color: 'var(--text-muted)',
                            marginTop: 4,
                            display: 'block',
                          }}
                        >
                          {formatRelativeTime(n.createdAt)}
                        </span>
                      </div>
                      {!n.read && (
                        <div
                          style={{
                            flexShrink: 0,
                            width: 6,
                            height: 6,
                            borderRadius: '50%',
                            background: '#3b82f6',
                            marginTop: 8,
                            boxShadow: '0 0 6px rgba(59,130,246,0.4)',
                          }}
                        />
                      )}
                    </button>
                  )
                })}
              </div>
            )}

            {/* 푸터 */}
            <button
              onClick={handleViewAll}
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 4,
                padding: '12px 16px',
                border: 'none',
                background: 'transparent',
                color: 'var(--accent)',
                fontSize: 12,
                fontWeight: 600,
                cursor: 'pointer',
                transition: 'background 0.15s ease',
              }}
              onMouseEnter={(e) => {
                (e.currentTarget as HTMLButtonElement).style.background = 'rgba(59,130,246,0.06)'
              }}
              onMouseLeave={(e) => {
                (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
              }}
            >
              전체 보기
              <ChevronRight size={13} strokeWidth={2.5} />
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
