import { Routes, Route, Navigate } from 'react-router-dom'
import MainLayout from './layouts/MainLayout'
import LoginPage from './pages/auth/LoginPage'
import DashboardPage from './pages/dashboard/DashboardPage'
import SiteListPage from './pages/site/SiteListPage'
import EstimateListPage from './pages/estimate/EstimateListPage'
import PurchaseListPage from './pages/purchase/PurchaseListPage'
import TaxListPage from './pages/tax/TaxListPage'
import { useAuthStore } from './stores/authStore'

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
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="sites" element={<SiteListPage />} />
        <Route path="estimates" element={<EstimateListPage />} />
        <Route path="purchases" element={<PurchaseListPage />} />
        <Route path="taxes" element={<TaxListPage />} />
      </Route>
    </Routes>
  )
}
