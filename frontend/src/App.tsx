import { Suspense, lazy } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import MainLayout from './layouts/MainLayout'
import NotFoundState from './components/NotFoundState'
import LoginPage from './pages/auth/LoginPage'
import { useAuthStore } from './stores/authStore'

// 로그인·레이아웃은 첫 화면이라 즉시 로드하고, 업무 페이지는 이동할 때 청크를 받는다
const DashboardPage = lazy(() => import('./pages/dashboard/DashboardPage'))
const SiteListPage = lazy(() => import('./pages/site/SiteListPage'))
const SiteDetailPage = lazy(() => import('./pages/site/SiteDetailPage'))
const ClientListPage = lazy(() => import('./pages/client/ClientListPage'))
const EstimateListPage = lazy(() => import('./pages/estimate/EstimateListPage'))
const PurchaseListPage = lazy(() => import('./pages/purchase/PurchaseListPage'))
const TaxListPage = lazy(() => import('./pages/tax/TaxListPage'))
const NotificationListPage = lazy(() => import('./pages/notification/NotificationListPage'))
const WarrantyListPage = lazy(() => import('./pages/warranty/WarrantyListPage'))

function PageFallback() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}>
      <Spin />
    </div>
  )
}

function Page({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<PageFallback />}>{children}</Suspense>
}

function PrivateRoute({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/"
        element={
          <PrivateRoute>
            <MainLayout />
          </PrivateRoute>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<Page><DashboardPage /></Page>} />
        <Route path="sites" element={<Page><SiteListPage /></Page>} />
        <Route path="sites/:id" element={<Page><SiteDetailPage /></Page>} />
        <Route path="clients" element={<Page><ClientListPage /></Page>} />
        <Route path="estimates" element={<Page><EstimateListPage /></Page>} />
        <Route path="purchases" element={<Page><PurchaseListPage /></Page>} />
        <Route path="taxes" element={<Page><TaxListPage /></Page>} />
        <Route path="notifications" element={<Page><NotificationListPage /></Page>} />
        <Route path="warranties" element={<Page><WarrantyListPage /></Page>} />
        {/* 없는 주소는 레이아웃 안에서 404 안내 — 비로그인이면 위 PrivateRoute가 먼저 /login으로 보낸다 */}
        <Route path="*" element={<NotFoundState />} />
      </Route>
    </Routes>
  )
}
