import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link, useLocation } from 'react-router-dom'
import Icon from './Icon'
import {
  ANALYSIS_GROUP,
  REPORT_GROUP,
  SETTINGS_PATHS,
  type NavGroup,
  type NavLeaf,
} from './navConfig'
import logoUrl from '../assets/logo.svg'
import { logout } from '../api/auth'
import { useAuth } from '../lib/authContext'
import { useMenuAccess } from '../lib/useMenuAccess'

/**
 * 현재 경로에 해당하는 하위 항목 **하나**를 고른다 (시안 `er()` 과 동일한 규칙).
 *
 * `/reports` 와 `/reports/management` 처럼 한쪽이 다른 쪽의 접두사인 항목이 함께 있을 때
 * 단순 `startsWith` 로는 둘 다 활성이 된다. 정확 일치이거나 `{to}/` 로 시작하는 항목만
 * 후보로 잡고, 후보가 여럿이면 **`to` 가 가장 긴 것**(가장 구체적인 경로)만 남긴다.
 *
 * @returns 활성 항목. 이 그룹 아래가 아니면 `null` (= 그룹을 접는다)
 */
function findActiveItem(items: NavLeaf[], pathname: string): NavLeaf | null {
  let best: NavLeaf | null = null
  for (const item of items) {
    const match = pathname === item.to || pathname.startsWith(`${item.to}/`)
    if (match && (!best || item.to.length > best.to.length)) best = item
  }
  return best
}

/**
 * 그룹 메뉴. 하위 경로에 들어와 있을 때만 펼쳐지고,
 * 그 외에는 첫 하위 화면으로 가는 단일 항목으로 접힌다.
 */
function NavGroupItem({ group, pathname }: { group: NavGroup; pathname: string }) {
  const { canAccessPath } = useMenuAccess()

  // 🔴 그룹 정의(navConfig)는 그대로 두고 **렌더 시점에** 거른다.
  //    아래 세 곳(접힘 폴백 링크 · 활성 판정 · 펼침 목록)이 전부 visibleItems 를 봐야 한다 —
  //    한 곳이라도 group.items 로 남으면 "숨겼는데 펼치면 보인다" 가 된다.
  const visibleItems = group.items.filter((i) => canAccessPath(i.to))
  if (visibleItems.length === 0) return null

  // 펼침 판정과 활성 판정을 같은 결과로 묶는다 — 둘이 갈리면 펼쳤는데 아무것도 활성이 아닌 상태가 난다.
  const activeItem = findActiveItem(visibleItems, pathname)

  if (!activeItem) {
    return (
      <Link to={visibleItems[0].to} className="nav__item">
        <Icon name={group.icon} size={22} />
        <span>{group.label}</span>
      </Link>
    )
  }

  return (
    <div className="nav__group">
      <div className="nav__item nav__item--parent">
        <Icon name={group.icon} size={22} />
        <span>{group.label}</span>
      </div>
      <div className="nav__sub">
        {visibleItems.map((item) => {
          const active = item === activeItem
          return (
            <Link
              key={item.to}
              to={item.to}
              className={`nav__subitem${active ? ' nav__subitem--active' : ''}`}
            >
              {item.label}
              {active && <span className="nav__dot" />}
            </Link>
          )
        })}
      </div>
    </div>
  )
}

export default function Sidebar() {
  const user = useAuth()
  const { canAccessPath, firstAllowed, landingPath } = useMenuAccess()
  const { pathname } = useLocation()
  const isComplaints = pathname.startsWith('/complaints')
  const isAssistant = pathname.startsWith('/ai-assistant')
  // ✅ 활성 판정은 접두 매칭 그대로 둔다 — SETTINGS_PATHS 두 경로가 모두 `/settings` 접두를 가진다.
  //    🔴 다만 접근 판정에는 startsWith 를 쓰지 않는다 — 아래 firstAllowed 가 정확 일치로 본다.
  const isSettings = pathname.startsWith('/settings')

  // 🔴 설정은 경로 없는 묶음이라 하위 경로 명시 목록에서 첫 허용 경로를 링크 목적지로 쓴다.
  const settingsTo = firstAllowed(SETTINGS_PATHS)
  // ⚠️ `/`(M01) 이 없는 롤이면 로고 클릭이 곧바로 RequireMenu 에 튕긴다 — 랜딩으로 보낸다.
  const brandTo = landingPath() ?? '/no-access'

  // 하단 사용자 카드의 로그아웃 확인 팝오버
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const userRef = useRef<HTMLDivElement>(null)
  const popRef = useRef<HTMLDivElement>(null)
  // 🔴 팝오버는 body 로 포털해 fixed 로 띄운다. `.sidebar` 가 sticky 라 자체 stacking context 를
  //    만들어서, 안에 두면 z-index 를 아무리 올려도 본문의 지도(Leaflet pane 400 · 오버레이 1000)에
  //    가려진다. 사이드바 자체의 z-index 를 올리면 본문 모달(.modal)이 사이드바를 덮지 못하게 된다.
  const [popPos, setPopPos] = useState<{ left: number; bottom: number } | null>(null)

  const openLogoutPop = () => {
    const rect = userRef.current?.getBoundingClientRect()
    if (!rect) return
    // 시안 위치 그대로 — 카드 오른쪽 12px 바깥, 아래끝 정렬
    setPopPos({ left: rect.right + 12, bottom: window.innerHeight - rect.bottom })
    setLogoutOpen(true)
  }

  // 열려 있을 때만 바깥 클릭·Escape·창 크기 변경으로 닫는다.
  // ⚠️ 아이콘 버튼은 stopPropagation 한다 — 안 하면 여는 클릭이 이 리스너에 닿아 열자마자 닫힌다.
  // ⚠️ 팝오버는 포털이라 카드(userRef) 밖이다 — 바깥 판정에 popRef 를 함께 본다.
  useEffect(() => {
    if (!logoutOpen) return
    const onClick = (e: MouseEvent) => {
      const t = e.target as Node
      if (userRef.current?.contains(t) || popRef.current?.contains(t)) return
      setLogoutOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setLogoutOpen(false)
    }
    const onResize = () => setLogoutOpen(false) // 계산해 둔 fixed 좌표가 어긋난다
    document.addEventListener('click', onClick)
    document.addEventListener('keydown', onKeyDown)
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('click', onClick)
      document.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('resize', onResize)
    }
  }, [logoutOpen])

  // 라우트가 바뀌면(설정 링크 클릭 등) 팝오버를 남기지 않는다.
  useEffect(() => {
    setLogoutOpen(false)
  }, [pathname])

  const handleLogout = async () => {
    setLoggingOut(true) // 중복 클릭 방지 — 성공·실패 모두 전체 새로고침이라 되돌리지 않는다
    try {
      const { idpLogoutUrl } = await logout() // 서버 세션은 이미 끊겼다
      // 🔴 IdP 세션까지 끊는다. 서버 세션만 끊으면 재로그인 시 IdP 가 자동
      //    재인증해 "로그아웃했는데 바로 다시 들어가진다" 가 된다.
      //    mock 세션이면 idpLogoutUrl 이 null 이라 /login 으로 폴백한다.
      // 🔴 전체 새로고침이다 — SPA 라우팅만 하면 메모리의 사용자 컨텍스트가 그대로 산다.
      //    replace 라 로그아웃 직전 화면이 히스토리에 남지 않는다.
      window.location.replace(idpLogoutUrl ?? '/login')
    } catch {
      // 🔴 세션이 이미 만료됐으면 logout() 이 401 로 reject 된다(인터셉터).
      //    catch 가 없으면 unhandled rejection 이 나고 아무 데도 못 간다.
      window.location.replace('/login')
    }
  }

  // 두 번째 줄은 부서명, 없으면(부서 미투영·무소속) 권한명으로 폴백한다.
  const userSub = user ? user.deptNm || user.authrtNm : ''

  return (
    <aside className="sidebar">
      <div className="sidebar__brand">
        <Link to={brandTo} className="brand__logo" aria-label="대시보드로 이동">
          <img className="brand__img" src={logoUrl} alt="Jeju" />
          <div className="brand__text">주차민원분석 솔루션</div>
        </Link>
      </div>

      <nav className="sidebar__nav">
        {canAccessPath('/') && (
          <Link to="/" className={`nav__item${pathname === '/' ? ' nav__item--active' : ''}`}>
            <Icon name="home" size={22} />
            <span>대시보드</span>
            {pathname === '/' && <span className="nav__dot" />}
          </Link>
        )}

        {canAccessPath('/complaints') && (
          <Link to="/complaints" className={`nav__item${isComplaints ? ' nav__item--active' : ''}`}>
            <Icon name="location" size={22} />
            <span>민원현황</span>
            {isComplaints && <span className="nav__dot" />}
          </Link>
        )}

        <NavGroupItem group={ANALYSIS_GROUP} pathname={pathname} />
        <NavGroupItem group={REPORT_GROUP} pathname={pathname} />

        {canAccessPath('/ai-assistant') && (
          <Link to="/ai-assistant" className={`nav__item${isAssistant ? ' nav__item--active' : ''}`}>
            <Icon name="sparkle" size={22} />
            <span>AI 어시스턴트</span>
            {isAssistant && <span className="nav__dot" />}
          </Link>
        )}
      </nav>

      <div className="sidebar__foot">
        {settingsTo && (
          <Link to={settingsTo} className={`nav__item${isSettings ? ' nav__item--active' : ''}`}>
            <Icon name="setting" size={22} />
            <span>설정</span>
          </Link>
        )}
        {user && (
          <div className="sb-user" ref={userRef}>
            <span className="sb-user__avatar">
              <Icon name="user" size={20} />
            </span>
            <div className="sb-user__info">
              <div className="sb-user__name">{user.userNm}</div>
              <div className="sb-user__dept">{userSub}</div>
            </div>
            <button
              type="button"
              className="sb-user__logout"
              aria-label="로그아웃"
              title="로그아웃"
              aria-haspopup="dialog"
              aria-expanded={logoutOpen}
              onClick={(e) => {
                e.stopPropagation()
                if (logoutOpen) setLogoutOpen(false)
                else openLogoutPop()
              }}
            >
              <Icon name="logout" size={18} />
            </button>
            {logoutOpen &&
              popPos &&
              createPortal(
                <div
                  ref={popRef}
                  className="sb-logout-pop"
                  role="dialog"
                  aria-label="로그아웃 확인"
                  style={{ left: popPos.left, bottom: popPos.bottom }}
                >
                  <div className="sb-logout-pop__title">로그아웃 하시겠습니까?</div>
                  <p className="sb-logout-pop__sub">
                    {user.userNm} · {userSub}
                  </p>
                  <div className="sb-logout-pop__actions">
                    <button
                      type="button"
                      className="sb-logout-pop__btn"
                      onClick={() => setLogoutOpen(false)}
                    >
                      취소
                    </button>
                    <button
                      type="button"
                      className="sb-logout-pop__btn sb-logout-pop__btn--danger"
                      disabled={loggingOut}
                      onClick={handleLogout}
                    >
                      로그아웃
                    </button>
                  </div>
                </div>,
                document.body,
              )}
          </div>
        )}
      </div>
    </aside>
  )
}
