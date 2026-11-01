import { useState } from 'react'
import { Outlet, useNavigate, useLocation, matchPath } from 'react-router-dom'
import { motion, AnimatePresence } from 'motion/react'
import { Drawer } from 'antd'
import {
  LayoutDashboard,
  HardHat,
  FileText,
  ShoppingBag,
  Receipt,
  Bell,
  ShieldCheck,
  Zap,
  ChevronRight,
  Menu,
} from 'lucide-react'
import { useSite } from '../api/sites.api'
import NotificationBell from '../components/NotificationBell'
import ChatPanel from '../components/chat/ChatPanel'
import LogoutButton from '../components/LogoutButton'
import { useViewport } from '../hooks/useViewport'

const menuItems = [
  { key: '/dashboard', icon: LayoutDashboard, label: '대시보드' },
  { key: '/sites', icon: HardHat, label: '현장 관리' },
  { key: '/estimates', icon: FileText, label: '견적서' },
  { key: '/purchases', icon: ShoppingBag, label: '매입 관리' },
  { key: '/taxes', icon: Receipt, label: '세금계산서' },
  { key: '/notifications', icon: Bell, label: '알림' },
  { key: '/warranties', icon: ShieldCheck, label: '보증보험' },
]

/** 로고·주 메뉴·로그아웃. 데스크톱 사이드바와 모바일/태블릿 Drawer가 같은 내용을 공유한다. */
function SidebarContent({ pathname, onNavigate }: { pathname: string; onNavigate: (key: string) => void }) {
  return (
    <>
      {/* Logo */}
      <div style={{ padding: '8px 10px 28px', display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          width: 34,
          height: 34,
          background: 'var(--accent-gradient)',
          borderRadius: 9,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          boxShadow: '0 0 20px rgba(59,130,246,0.3)',
          flexShrink: 0,
        }}>
          <Zap size={17} color="white" fill="white" />
        </div>
        <div>
          <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--text-primary)', letterSpacing: '-0.03em' }}>
            BuildFlow
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 1 }}>현장 관리 플랫폼</div>
        </div>
      </div>

      {/* Nav label */}
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', padding: '0 10px 8px', letterSpacing: '0.06em', textTransform: 'uppercase' }}>
        메뉴
      </div>

      {/* Nav items */}
      <nav aria-label="주 메뉴" style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 2 }}>
        {menuItems.map(({ key, icon: Icon, label }) => {
          const isActive = pathname === key
          return (
            <motion.button
              key={key}
              whileHover={{ x: 2 }}
              whileTap={{ scale: 0.97 }}
              onClick={() => onNavigate(key)}
              aria-label={label}
              aria-current={isActive ? 'page' : undefined}
              style={{
                position: 'relative',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '9px 10px',
                minHeight: 40,
                borderRadius: 'var(--radius-sm)',
                border: 'none',
                cursor: 'pointer',
                background: isActive ? 'rgba(59,130,246,0.12)' : 'transparent',
                color: isActive ? 'var(--accent)' : 'var(--text-secondary)',
                fontSize: 14,
                fontWeight: isActive ? 600 : 400,
                textAlign: 'left',
                width: '100%',
                transition: 'background 0.15s, color 0.15s',
                overflow: 'hidden',
              }}
              onMouseEnter={(e) => {
                if (!isActive) {
                  (e.currentTarget as HTMLButtonElement).style.background = 'rgba(255,255,255,0.04)'
                  ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--text-primary)'
                }
              }}
              onMouseLeave={(e) => {
                if (!isActive) {
                  (e.currentTarget as HTMLButtonElement).style.background = 'transparent'
                  ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--text-secondary)'
                }
              }}
            >
              {isActive && (
                <motion.div
                  layoutId="activeIndicator"
                  style={{
                    position: 'absolute',
                    left: 0,
                    top: '20%',
                    bottom: '20%',
                    width: 3,
                    background: 'var(--accent-gradient)',
                    borderRadius: '0 3px 3px 0',
                  }}
                  transition={{ type: 'spring', stiffness: 400, damping: 30 }}
                />
              )}
              <Icon size={15} strokeWidth={isActive ? 2.5 : 1.8} />
              <span style={{ flex: 1 }}>{label}</span>
              {isActive && <ChevronRight size={13} strokeWidth={2.5} style={{ opacity: 0.5 }} />}
            </motion.button>
          )
        })}
      </nav>

      {/* Divider */}
      <div style={{ height: 1, background: 'var(--border)', margin: '12px 4px' }} />

      {/* Logout */}
      <LogoutButton />
    </>
  )
}

export default function MainLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const { isMobile, isCompact } = useViewport()
  const [drawerOpen, setDrawerOpen] = useState(false)

  const currentPage = menuItems.find((m) => m.key === location.pathname)

  const siteDetailMatch = matchPath('/sites/:id', location.pathname)
  const parsedSiteId = siteDetailMatch?.params?.id ? Number(siteDetailMatch.params.id) : NaN
  const siteDetailId = Number.isFinite(parsedSiteId) && parsedSiteId > 0 ? parsedSiteId : 0
  const { data: siteForBreadcrumb } = useSite(siteDetailId)

  const breadcrumbItems: string[] = currentPage
    ? [currentPage.label]
    : siteDetailId > 0
      ? ['현장 관리', siteForBreadcrumb?.siteName ?? `#${siteDetailId}`]
      : []

  const handleNavigate = (key: string) => {
    setDrawerOpen(false)
    navigate(key)
  }

  return (
    <div style={{ display: 'flex', height: '100vh', overflow: 'hidden', background: 'var(--bg-base)' }}>
      {/* Sidebar — 데스크톱(≥1024px)만 고정 표시. 그 아래는 헤더 메뉴 버튼 + Drawer */}
      {isCompact ? (
        <Drawer
          open={drawerOpen}
          onClose={() => setDrawerOpen(false)}
          placement="left"
          width={260}
          closable={false}
          title={null}
          rootClassName="bf-nav-drawer"
          styles={{
            body: {
              padding: '16px 10px',
              background: 'var(--bg-elevated)',
              display: 'flex',
              flexDirection: 'column',
            },
          }}
        >
          <SidebarContent pathname={location.pathname} onNavigate={handleNavigate} />
        </Drawer>
      ) : (
        <motion.aside
          initial={{ x: -240, opacity: 0 }}
          animate={{ x: 0, opacity: 1 }}
          transition={{ duration: 0.35, ease: [0.25, 0.46, 0.45, 0.94] }}
          style={{
            width: 'var(--sidebar-width)',
            background: 'var(--bg-elevated)',
            borderRight: '1px solid var(--border)',
            display: 'flex',
            flexDirection: 'column',
            padding: '16px 10px',
            flexShrink: 0,
          }}
        >
          <SidebarContent pathname={location.pathname} onNavigate={handleNavigate} />
        </motion.aside>
      )}

      {/* Main */}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        {/* Header */}
        <motion.header
          initial={{ y: -10, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          transition={{ duration: 0.3, delay: 0.1 }}
          style={{
            height: 'var(--header-height)',
            borderBottom: '1px solid var(--border)',
            display: 'flex',
            alignItems: 'center',
            padding: isCompact ? '0 12px' : '0 24px',
            background: 'rgba(9,9,11,0.8)',
            backdropFilter: 'blur(12px)',
            gap: 8,
            flexShrink: 0,
          }}
        >
          {isCompact && (
            <button
              type="button"
              onClick={() => setDrawerOpen(true)}
              aria-label="메뉴 열기"
              aria-expanded={drawerOpen}
              style={{
                width: 40,
                height: 40,
                flexShrink: 0,
                display: 'grid',
                placeItems: 'center',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius-sm)',
                background: 'transparent',
                color: 'var(--text-secondary)',
                cursor: 'pointer',
              }}
            >
              <Menu size={18} strokeWidth={2} />
            </button>
          )}
          {!isMobile && <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>BuildFlow</span>}
          {breadcrumbItems.map((label, idx) => (
            <span
              key={`${label}-${idx}`}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 8, minWidth: 0 }}
            >
              {(!isMobile || idx > 0) && <span style={{ color: 'var(--text-muted)', fontSize: 13 }}>/</span>}
              <span
                style={{
                  color: idx === breadcrumbItems.length - 1 ? 'var(--text-secondary)' : 'var(--text-muted)',
                  fontSize: 13,
                  fontWeight: idx === breadcrumbItems.length - 1 ? 500 : 400,
                  maxWidth: isMobile ? 140 : 240,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {label}
              </span>
            </span>
          ))}
          <div style={{ flex: 1 }} />
          <NotificationBell />
        </motion.header>

        {/* Content */}
        <main className="bf-main" style={{ flex: 1, overflow: 'auto' }}>
          <AnimatePresence mode="wait">
            <motion.div
              key={location.pathname}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.2, ease: 'easeOut' }}
              style={{ minHeight: '100%' }}
            >
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>
      <ChatPanel />
    </div>
  )
}
