import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import AppLayout from './layouts/AppLayout'
import OpsLayout from './layouts/OpsLayout'
import OidcCallbackGate from './components/auth/OidcCallbackGate'
import RequireAuth from './components/auth/RequireAuth'
import RequireMenu, { LandingRedirect } from './components/auth/RequireMenu'
import { SETTINGS_PATHS } from './components/navConfig'
import { useMenuAccess } from './lib/useMenuAccess'
import LoginPage from './pages/LoginPage'
import NoAccessPage from './pages/NoAccessPage'
import DashboardPage from './pages/DashboardPage'
import ComplaintsPage from './pages/ComplaintsPage'
import HotspotPage from './pages/analysis/HotspotPage'
import PolicySimulationPage from './pages/analysis/PolicySimulationPage'
import LifestyleSimulationPage from './pages/analysis/LifestyleSimulationPage'
import RecommendationPage from './pages/analysis/RecommendationPage'
import InvestmentPriorityPage from './pages/analysis/InvestmentPriorityPage'
import ReportsPage from './pages/ReportsPage'
import ReportManagementPage from './pages/reports/ReportManagementPage'
import AlertInquiryPage from './pages/alerts/AlertInquiryPage'
import AlertManagementPage from './pages/alerts/AlertManagementPage'
import AiAssistantPage from './pages/AiAssistantPage'
import SettingsPage from './pages/SettingsPage'
import MenuAuthorityTab from './components/settings/MenuAuthorityTab'
import AnalysisWeightTab from './components/settings/AnalysisWeightTab'
import BatchOpsPage from './pages/ops/BatchOpsPage'

/**
 * `/settings` 로 직접 들어온 사람을 첫 허용 탭으로 보낸다 (TSK-4115 Step 5, D13).
 * 둘 다 없으면 랜딩으로 — `LandingRedirect` 와 같은 판정을 쓴다.
 *
 * ⚠️ `SETTINGS_PATHS` 는 `navConfig.ts` 것을 그대로 쓴다 — 여기서 다시 배열을 적으면 두 곳이 갈린다.
 */
function SettingsIndexRedirect() {
  const { firstAllowed, landingPath, hasAnyMenu } = useMenuAccess()
  const to = firstAllowed(SETTINGS_PATHS)
  if (to) return <Navigate to={to} replace />
  if (!hasAnyMenu) return <Navigate to="/no-access" replace />
  return <Navigate to={landingPath() ?? '/no-access'} replace />
}

/** 경로는 화면 시안(https://innopamsolution.github.io/jeju-datahub/)과 동일하게 맞춘다. */
export default function App() {
  return (
    <OidcCallbackGate>
      <BrowserRouter>
        <Routes>
          {/* 🔴 /login 은 AppLayout·RequireAuth 그룹 **밖**이다. 그룹 안에 두면 가드가 먼저 돌아
              미인증 → /login → 가드 → … 무한 리다이렉트가 된다.
              (순서가 아니라 그룹이 이유다 — react-router 6 은 specificity 랭킹으로 매칭하므로
               정적 세그먼트 /login 은 splat * 을 어디에 적든 이긴다.) */}
          <Route path="/login" element={<LoginPage />} />
          {/* 🔴 /no-access 도 그룹 **밖**이다 — 메뉴가 0개인 사용자에게 빈 사이드바(AppLayout)를
              보여줄 이유가 없고, 그룹 안에 두면 RequireMenu/LandingRedirect 와 얽힌다.
              (무한 리다이렉트가 이유인 /login 과는 근거가 다르다 — RequireAuth 는 미인증만
               /login 으로 보내므로 인증된 0메뉴 사용자에게는 루프가 생기지 않는다.) */}
          <Route path="/no-access" element={<NoAccessPage />} />
          {/* 배치 운영 (TSK-4337 · 숨김 메뉴 M0604) — 🔴 navConfig·Sidebar 에는 넣지 않는다(D1). 주소로만 들어온다.
              사이드바 없는 운영 셸(OpsLayout)을 쓴다 — 상단 바는 화면이 그린다. 인증·메뉴 인가는 다른 화면과 같다. */}
          <Route element={<RequireAuth><OpsLayout /></RequireAuth>}>
            <Route path="ops/batch" element={<RequireMenu path="/ops/batch"><BatchOpsPage /></RequireMenu>} />
          </Route>
          <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
            {/* 🔴 RequireMenu 의 path 는 **절대경로**다 — 라우트의 상대경로를 그대로 넘기면 전부 false 다.
                대시보드는 index 라 path 자체가 없어 "/" 로 감싼다. */}
            <Route index element={<RequireMenu path="/"><DashboardPage /></RequireMenu>} />
            <Route path="complaints" element={<RequireMenu path="/complaints"><ComplaintsPage /></RequireMenu>} />
            <Route path="analysis/hotspot" element={<RequireMenu path="/analysis/hotspot"><HotspotPage /></RequireMenu>} />
            <Route path="analysis/policy-simulation" element={<RequireMenu path="/analysis/policy-simulation"><PolicySimulationPage /></RequireMenu>} />
            <Route path="analysis/lifestyle-simulation" element={<RequireMenu path="/analysis/lifestyle-simulation"><LifestyleSimulationPage /></RequireMenu>} />
            <Route path="analysis/recommendation" element={<RequireMenu path="/analysis/recommendation"><RecommendationPage /></RequireMenu>} />
            <Route path="analysis/investment-priority" element={<RequireMenu path="/analysis/investment-priority"><InvestmentPriorityPage /></RequireMenu>} />
            <Route path="reports" element={<RequireMenu path="/reports"><ReportsPage /></RequireMenu>} />
            <Route path="reports/management" element={<RequireMenu path="/reports/management"><ReportManagementPage /></RequireMenu>} />
            <Route path="alerts/inquiry" element={<RequireMenu path="/alerts/inquiry"><AlertInquiryPage /></RequireMenu>} />
            <Route path="alerts/management" element={<RequireMenu path="/alerts/management"><AlertManagementPage /></RequireMenu>} />
            <Route path="ai-assistant" element={<RequireMenu path="/ai-assistant"><AiAssistantPage /></RequireMenu>} />
            {/* 🔴 부모(/settings)에는 가드를 걸지 않는다 — /settings 는 어느 메뉴의 경로도 아니라
                감싸면 항상 튕긴다. 가드는 **자식에만** 건다(안 걸면 URL 직접 입력으로 뚫린다). */}
            <Route path="settings" element={<SettingsPage />}>
              <Route index element={<SettingsIndexRedirect />} />
              <Route
                path="menu-authority"
                element={<RequireMenu path="/settings/menu-authority"><MenuAuthorityTab /></RequireMenu>}
              />
              <Route
                path="analysis-weight"
                element={<RequireMenu path="/settings/analysis-weight"><AnalysisWeightTab /></RequireMenu>}
              />
            </Route>
            {/* 🔴 <Navigate to="/" /> 를 쓰지 않는다 — `/` 가 없는 롤이면 무한 리다이렉트다(D5) */}
            <Route path="*" element={<LandingRedirect />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </OidcCallbackGate>
  )
}
