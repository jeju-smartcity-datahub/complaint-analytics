import { useEffect, useState } from 'react'
import axios from 'axios'
import { fetchAuthorizeUrl, loginWithPassword } from '../api/auth'

/**
 * 로그인 화면.
 *
 * `AppLayout`(사이드바) 밖이고 `RequireAuth` 그룹 밖이다 — 그룹 안에 두면 가드가 먼저 돌아
 * 미인증 → /login → 가드 → … 무한 리다이렉트가 된다.
 *
 * 🔴 **통합 인증(OIDC)이 기본이고, 이메일 로그인은 보조다**. 표준 경로가
 *    MFA·SSO 를 유지하므로 시각적으로도 그쪽이 먼저 오게 배치한다.
 */
export default function LoginPage() {
  const [busy, setBusy] = useState(false)
  // 콜백 게이트가 실패 시 /login?error 로 보낸다
  const failed = new URLSearchParams(window.location.search).has('error')

  // ── 이메일·비밀번호 로그인 ────────────────────────────────────────
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [pwBusy, setPwBusy] = useState(false)
  const [pwError, setPwError] = useState<string | null>(null)

  // 🔴 CSRF 토큰 선확보 — permitAll 이어도 POST 는 `X-XSRF-TOKEN` 이 없으면 **403** 이다.
  //    `/login` 은 `RequireAuth` 밖이라 이 화면에서는 `GET /api/auth/me` 가 돌지 않는다.
  //    즉 첫 진입에서 `XSRF-TOKEN` 쿠키가 없을 수 있고, 그대로 폼을 제출하면 403 이 난다.
  //    (백엔드 `CsrfCookieFilter` 가 아무 요청에서나 토큰을 실체화해 쿠키를 내려 주므로
  //     permitAll GET 한 번이면 충분하다.) 이미 쿠키가 있으면 부르지 않는다.
  useEffect(() => {
    if (document.cookie.split('; ').some((c) => c.startsWith('XSRF-TOKEN='))) return
    // 실패해도 화면을 막지 않는다 — OIDC 버튼은 어차피 같은 API 를 다시 부른다.
    void fetchAuthorizeUrl().catch(() => undefined)
  }, [])

  const onLogin = async () => {
    setBusy(true)
    try {
      const { authorizeUrl } = await fetchAuthorizeUrl() // 세션에 state·verifier 가 심긴다
      // 🔴 navigate() 를 쓰면 안 된다 — react-router 의 클라이언트 라우팅은 SPA 안에서만 움직여
      //    외부 IdP 로 못 간다. 전체 페이지 이동이어야 한다.
      window.location.href = authorizeUrl
    } catch {
      setBusy(false)
    }
  }

  const onPasswordLogin = async (e: React.FormEvent<HTMLFormElement>) => {
    // 🔴 <form onSubmit> 이라 Enter 로도 제출된다. 기본 동작(전체 새로고침)만 막는다.
    e.preventDefault()
    setPwError(null)
    setPwBusy(true)
    try {
      await loginWithPassword({ email, password })
      // 🔴 navigate() 가 아니다 — `RequireAuth` 는 이미 마운트돼 있어 SPA 전환으로는
      //    `GET /api/auth/me` 를 다시 부르지 않는다. 전체 페이지 이동이어야 세션이 반영된다.
      window.location.assign('/')
    } catch (err) {
      // 🔴 **백엔드가 준 고정 문구만** 띄운다. 백엔드는 비번오류·없는계정·정지계정을
      //    구분해 주지 않으며, 시도 제한(429)도 401 과 **같은 문구**를 준다 — 상태코드로
      //    문구를 갈면 "몇 번 남았는지·계정이 존재하는지" 를 프론트가 대신 흘리게 된다.
      //    본문이 없을 때(네트워크 오류·403 등)만 화면이 가진 일반 문구로 떨어진다.
      //    🔴 err 를 console 로 찍지 않는다(비밀번호가 섞인 요청이 콘솔에 남는다).
      const msg = axios.isAxiosError(err)
        ? (err.response?.data as { message?: string } | undefined)?.message
        : undefined
      setPwError(msg ?? '로그인에 실패했습니다. 다시 시도해 주세요.')
      // 🔴 실패하면 비밀번호를 비운다 — 성공 경로는 풀 페이지 이동이라 state 가 함께 사라지지만,
      //    실패 경로는 화면이 그대로 남아 입력값이 메모리·React DevTools 에 계속 노출된다.
      setPassword('')
      setPwBusy(false)
    }
  }

  const inputStyle: React.CSSProperties = {
    width: '100%',
    boxSizing: 'border-box',
    padding: '10px 12px',
    fontSize: 14,
    borderRadius: 6,
    border: '1px solid var(--line-normal-normal, #70737c38)',
    background: '#fff',
  }

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 32,
        padding: 24,
      }}
    >
      <h1 style={{ margin: 0, textAlign: 'center', fontSize: 28, lineHeight: 1.4 }}>
        제주 스마트시티
        <br />
        데이터 허브
      </h1>

      {failed && (
        <p role="alert" style={{ margin: 0, color: '#c0392b' }}>
          로그인에 실패했습니다. 다시 시도해 주세요.
        </p>
      )}

      <button
        type="button"
        onClick={onLogin}
        disabled={busy}
        style={{
          padding: '14px 28px',
          fontSize: 16,
          cursor: busy ? 'default' : 'pointer',
          borderRadius: 6,
          border: '1px solid #1f6feb',
          background: '#1f6feb',
          color: '#fff',
          minWidth: 240,
        }}
      >
        {busy ? '이동 중…' : '통합 인증으로 로그인'}
      </button>

      {/* ── 또는 ─────────────────────────────────────────────────────────────── */}
      <div
        aria-hidden="true"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          width: 280,
          color: 'var(--label-alternative, #37383c9c)',
          fontSize: 13,
        }}
      >
        <span style={{ flex: 1, height: 1, background: 'var(--line-normal-neutral, #70737c29)' }} />
        또는
        <span style={{ flex: 1, height: 1, background: 'var(--line-normal-neutral, #70737c29)' }} />
      </div>

      <form
        onSubmit={onPasswordLogin}
        aria-describedby="pw-login-notice"
        style={{ display: 'flex', flexDirection: 'column', gap: 12, width: 280 }}
      >
        <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 }}>
          이메일
          <input
            type="email"
            name="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={pwBusy}
            style={inputStyle}
          />
        </label>

        <label style={{ display: 'flex', flexDirection: 'column', gap: 6, fontSize: 13 }}>
          비밀번호
          {/* 🔴 type="password" + name + autoComplete 는 비밀번호 관리자 호환을 위한 것이다. */}
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={pwBusy}
            style={inputStyle}
          />
        </label>

        {pwError && (
          <p role="alert" style={{ margin: 0, color: '#c0392b', fontSize: 13 }}>
            {pwError}
          </p>
        )}

        <button
          type="submit"
          disabled={pwBusy}
          style={{
            padding: '12px 20px',
            fontSize: 15,
            cursor: pwBusy ? 'default' : 'pointer',
            borderRadius: 6,
            border: '1px solid var(--line-normal-strong, #70737c85)',
            background: '#fff',
            color: 'var(--label-normal, #171717)',
          }}
        >
          {pwBusy ? '로그인 중…' : '로그인'}
        </button>

        {/* 🔴 고지 — 이 경로는 MFA 를 조용히 건너뛰고 IdP SSO 세션을
            만들지 않는다. 사용자가 그 차이를 모르는 채 쓰지 않도록 폼에 붙여 둔다. */}
        <p
          id="pw-login-notice"
          style={{
            margin: 0,
            fontSize: 12,
            lineHeight: 1.5,
            color: 'var(--label-alternative, #37383c9c)',
          }}
        >
          이메일 로그인에는 <strong>SSO·2단계 인증이 적용되지 않습니다.</strong> 다른 DHub
          서비스와 로그인이 연동되지 않으니, 가능하면 위의 통합 인증을 이용해 주세요.
        </p>
      </form>
    </main>
  )
}
