import { motion } from 'motion/react'
import { Bell, CheckCheck, FileText, ShoppingCart, ShieldAlert } from 'lucide-react'
import PageHeader from '../../components/PageHeader'
import { useNotifications, useUnreadCount, useMarkAsRead, useMarkAllAsRead } from '../../api/notifications.api'

const TYPE_CONFIG: Record<string, { label: string; color: string; bg: string; border: string; icon: typeof Bell }> = {
  ESTIMATE_PARSED: {
    label: '견적',
    color: '#3b82f6',
    bg: 'rgba(59,130,246,0.1)',
    border: 'rgba(59,130,246,0.2)',
    icon: FileText,
  },
  PURCHASE_REGISTERED: {
    label: '매입',
    color: '#22c55e',
    bg: 'rgba(34,197,94,0.1)',
    border: 'rgba(34,197,94,0.2)',
    icon: ShoppingCart,
  },
  WARRANTY_EXPIRING: {
    label: '보증',
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

export default function NotificationListPage() {
  const { data, isLoading } = useNotifications()
  const { data: unreadCount } = useUnreadCount()
  const { mutate: markAsRead } = useMarkAsRead()
  const { mutate: markAllAsRead } = useMarkAllAsRead()
  const notifications = data ?? []
  const hasUnread = (unreadCount ?? 0) > 0

  return (
    <div>
      <PageHeader
        icon={Bell}
        title="알림"
        description="시스템 알림 및 이벤트"
        action={
          <motion.button
            whileHover={hasUnread ? { scale: 1.02 } : {}}
            whileTap={hasUnread ? { scale: 0.98 } : {}}
            onClick={() => { if (hasUnread) markAllAsRead() }}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '8px 16px',
              background: 'var(--accent-gradient)',
              border: 'none', borderRadius: 'var(--radius-sm)',
              color: 'white', fontSize: 13, fontWeight: 600,
              cursor: hasUnread ? 'pointer' : 'not-allowed',
              boxShadow: '0 0 16px rgba(59,130,246,0.2)',
              opacity: hasUnread ? 1 : 0.4,
              pointerEvents: hasUnread ? 'auto' : 'none',
            }}
          >
            <CheckCheck size={14} strokeWidth={2.5} />
            전체 읽음
          </motion.button>
        }
      />

      {/* 미읽음 건수 배너 */}
      {!isLoading && unreadCount != null && unreadCount > 0 && (
        <motion.div
          initial={{ opacity: 0, y: -8 }}
          animate={{ opacity: 1, y: 0 }}
          style={{
            background: 'rgba(239,68,68,0.06)',
            border: '1px solid rgba(239,68,68,0.2)',
            borderRadius: 10,
            padding: '12px 20px',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
            읽지 않은 알림
          </span>
          <span style={{ fontSize: 18, fontWeight: 700, color: '#ef4444', letterSpacing: '-0.03em' }}>
            {unreadCount}건
          </span>
        </motion.div>
      )}

      {/* 로딩 스켈레톤 */}
      {isLoading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="shimmer" style={{ height: 72, borderRadius: 10 }} />
          ))}
        </div>
      ) : notifications.length === 0 ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          style={{
            textAlign: 'center',
            padding: '48px 0',
            color: 'var(--text-muted)',
            fontSize: 14,
          }}
        >
          알림이 없습니다.
        </motion.div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          {notifications.map((n, i) => {
            const config = TYPE_CONFIG[n.type] ?? {
              label: n.type,
              color: 'var(--text-muted)',
              bg: 'rgba(255,255,255,0.04)',
              border: 'var(--border)',
              icon: Bell,
            }
            const TypeIcon = config.icon

            return (
              <motion.div
                key={n.id}
                initial={{ opacity: 0, x: -8 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.04 }}
                onClick={() => { if (!n.read) markAsRead(n.id) }}
                style={{
                  position: 'relative',
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 14,
                  padding: '14px 20px',
                  background: n.read ? 'var(--bg-card)' : 'rgba(59,130,246,0.04)',
                  border: `1px solid ${n.read ? 'var(--border)' : 'rgba(59,130,246,0.15)'}`,
                  borderRadius: 10,
                  cursor: n.read ? 'default' : 'pointer',
                  transition: 'background 0.15s ease',
                }}
                onMouseEnter={(e) => {
                  if (!n.read) (e.currentTarget as HTMLElement).style.background = 'rgba(59,130,246,0.07)'
                }}
                onMouseLeave={(e) => {
                  (e.currentTarget as HTMLElement).style.background = n.read
                    ? 'var(--bg-card)'
                    : 'rgba(59,130,246,0.04)'
                }}
              >
                {/* 미읽음 파란 점 */}
                {!n.read && (
                  <div
                    style={{
                      position: 'absolute',
                      left: 8,
                      top: '50%',
                      transform: 'translateY(-50%)',
                      width: 6,
                      height: 6,
                      borderRadius: '50%',
                      background: '#3b82f6',
                      boxShadow: '0 0 6px rgba(59,130,246,0.4)',
                    }}
                  />
                )}

                {/* 타입 아이콘 */}
                <div
                  style={{
                    flexShrink: 0,
                    width: 34,
                    height: 34,
                    borderRadius: 8,
                    background: config.bg,
                    border: `1px solid ${config.border}`,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginLeft: !n.read ? 6 : 0,
                  }}
                >
                  <TypeIcon size={15} color={config.color} strokeWidth={2.5} />
                </div>

                {/* 콘텐츠 */}
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                    <span
                      style={{
                        fontSize: 11,
                        fontWeight: 600,
                        padding: '2px 8px',
                        borderRadius: 20,
                        background: config.bg,
                        color: config.color,
                        border: `1px solid ${config.border}`,
                      }}
                    >
                      {config.label}
                    </span>
                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                      {formatRelativeTime(n.createdAt)}
                    </span>
                  </div>
                  <p
                    style={{
                      margin: 0,
                      fontSize: 13,
                      color: n.read ? 'var(--text-secondary)' : 'var(--text-primary)',
                      fontWeight: n.read ? 400 : 500,
                      lineHeight: 1.5,
                    }}
                  >
                    {n.message}
                  </p>
                </div>
              </motion.div>
            )
          })}
        </div>
      )}
    </div>
  )
}
