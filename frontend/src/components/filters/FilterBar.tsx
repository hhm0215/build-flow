import { ReactNode } from 'react'
import { motion } from 'motion/react'
import { Filter, X } from 'lucide-react'

interface FilterBarProps {
  children: ReactNode
  activeCount?: number
  onReset?: () => void
}

export default function FilterBar({ children, activeCount = 0, onReset }: FilterBarProps) {
  return (
    <motion.div
      initial={{ opacity: 0, y: -4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.2 }}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        flexWrap: 'wrap',
        padding: '12px 16px',
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 10,
        marginBottom: 16,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          color: 'var(--text-muted)',
          fontSize: 12,
          fontWeight: 600,
          padding: '0 4px',
          letterSpacing: '0.04em',
          textTransform: 'uppercase',
        }}
      >
        <Filter size={13} strokeWidth={2.2} />
        필터
        {activeCount > 0 && (
          <span
            style={{
              fontSize: 11,
              padding: '1px 7px',
              borderRadius: 20,
              background: 'rgba(59,130,246,0.15)',
              color: 'var(--accent)',
              border: '1px solid rgba(59,130,246,0.3)',
              marginLeft: 2,
            }}
          >
            {activeCount}
          </span>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', flex: 1 }}>
        {children}
      </div>

      {activeCount > 0 && onReset && (
        <motion.button
          whileHover={{ scale: 1.03 }}
          whileTap={{ scale: 0.97 }}
          onClick={onReset}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 4,
            padding: '6px 10px',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-sm)',
            background: 'transparent',
            color: 'var(--text-secondary)',
            fontSize: 12,
            fontWeight: 500,
            cursor: 'pointer',
            transition: 'all 0.15s',
          }}
          onMouseEnter={(e) => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--danger)'
            ;(e.currentTarget as HTMLElement).style.color = 'var(--danger)'
          }}
          onMouseLeave={(e) => {
            (e.currentTarget as HTMLElement).style.borderColor = 'var(--border)'
            ;(e.currentTarget as HTMLElement).style.color = 'var(--text-secondary)'
          }}
        >
          <X size={12} strokeWidth={2.5} />
          전체 초기화
        </motion.button>
      )}
    </motion.div>
  )
}
