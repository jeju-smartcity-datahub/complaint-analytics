import { logout } from '../api/auth'

/**
 * 접근 가능한 메뉴가 하나도 없는 사용자 안내 화면.
 *
 * 🔴 `RequireAuth`·`AppLayout` 그룹 **밖**에 둔다 — 무한 리다이렉트 때문이 아니라
 *    **사이드바가 렌더되면 안 되기 때문**이다. 메뉴가 0개인 사용자에게 빈 사이드바를
 *    보여줄 이유가 없고, 그룹 안에 두면 `RequireMenu`/`LandingRedirect` 와 얽힌다.
 *
 * 🔴 그래서 이 화면은 **`useAuth()` 를 쓰지 않는다** — `AuthProvider` 를 렌더하는 것은
 *    `RequireAuth` 자신이라 그룹 밖에서는 컨텍스트가 없다. 고정 안내 문구 + 로그아웃 버튼뿐이다.
 */
export default function NoAccessPage() {
  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 24,
        padding: 24,
      }}
    >
      <h1 style={{ margin: 0, textAlign: 'center', fontSize: 24, lineHeight: 1.4 }}>
        접근 가능한 메뉴가 없습니다
      </h1>

      <p style={{ margin: 0, textAlign: 'center', lineHeight: 1.6, opacity: 0.8 }}>
        현재 계정에 부여된 메뉴 권한이 없습니다.
        <br />
        시스템 관리자에게 권한 부여를 요청해 주세요.
      </p>

      {/* Sidebar 의 로그아웃과 같은 경로다 — 서버 세션 해제 후 IdP 로그아웃(없으면 /login) 으로 전체 이동. */}
      <button
        type="button"
        onClick={async () => {
          try {
            const { idpLogoutUrl } = await logout()
            // 🔴 전체 새로고침이다 — SPA 라우팅만 하면 메모리의 사용자 컨텍스트가 그대로 산다.
            window.location.assign(idpLogoutUrl ?? '/login')
          } catch {
            // 세션이 이미 만료됐으면 401 로 reject 된다 — catch 가 없으면 아무 데도 못 간다.
            window.location.assign('/login')
          }
        }}
        style={{
          padding: '12px 24px',
          fontSize: 15,
          cursor: 'pointer',
          borderRadius: 6,
          border: '1px solid #1f6feb',
          background: '#1f6feb',
          color: '#fff',
          minWidth: 200,
        }}
      >
        로그아웃
      </button>
    </main>
  )
}
