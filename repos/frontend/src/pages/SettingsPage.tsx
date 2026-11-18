import { Link, Outlet, useLocation } from 'react-router-dom'
import { SETTINGS_PATHS } from '../components/navConfig'
import { useMenuAccess } from '../lib/useMenuAccess'

/*
 * 설정 페이지 셸 (TSK-4019 Step 7-1).
 * 시안(https://innopamsolution.github.io/jeju-datahub/)의 설정 화면 구조를 옮기되,
 * topbar(제목·부제) + card.st-body 안의 segment 탭 구성만 가져온다.
 *
 * 시안 대비 의도적 차이 2가지
 *  1) topbar 우측 액션(`AI 대화 시작하기` 버튼 · 알림 벨)을 생략했다.
 *     마스코트 SVG·벨 마크업이 DashboardPage.tsx 안에 인라인으로 박혀 있어 공유 컴포넌트가 없고,
 *     공통 컴포넌트 추출은 대시보드 회귀 위험이 있어 이 TSK(메뉴 권한 관리) 범위 밖이다.
 *     → topbar 액션 공통화는 후속 TSK 후보.
 *  2) 시안의 `사용자 정보` 탭을 노출하지 않는다 (2026-08-10 사용자 확정).
 *     사용자 계정 테이블(TB_USER 성격)이 아직 스키마에 없어 채울 내용이 없다.
 *     해당 탭이 구현되는 후속 TSK 에서 `SETTINGS_PATHS` 맨 앞에 경로를 넣고
 *     아래 `TAB_LABELS` 에 라벨을 더하면 탭이 되돌아온다.
 *     ※ 2026-08-28(TSK-4077) 메뉴 트리(TB_MENU M0601)에서도 제거했다 —
 *       탭·메뉴 권한 목록 어디에도 `사용자 정보` 가 남지 않는다.
 */

/**
 * 탭 정의 — 탭 선택이 곧 라우팅이다 (TSK-4115 Step 6).
 * 🔴 경로는 navConfig 의 `SETTINGS_PATHS` 를 그대로 파생시킨다 — 여기서 다시 적으면
 *    App.tsx(중첩 라우트)·사이드바와 세 곳이 갈린다.
 *    순서(= 기본 탭 순서)도 그 배열이 정한다: 시드 sort_seq(M0602=1 · M0603=2)와 같다.
 */
const TAB_LABELS: Record<(typeof SETTINGS_PATHS)[number], string> = {
  '/settings/menu-authority': '메뉴 권한 관리',
  '/settings/analysis-weight': '분석 지표 가중치 설정',
}
const TABS = SETTINGS_PATHS.map((to) => ({ to, label: TAB_LABELS[to] }))

export default function SettingsPage() {
  /*
   * 🔴 `canAccessPath` 는 훅 반환값이라 컴포넌트 본문에서만 쓸 수 있다 —
   *    `visible` 을 모듈 스코프로 올리면 컴파일이 깨진다.
   * 탭이 0개면 `SettingsIndexRedirect`(App.tsx)가 이미 랜딩으로 보내 여기 도달하지 않는다.
   */
  const { canAccessPath } = useMenuAccess()
  const visible = TABS.filter((t) => canAccessPath(t.to))

  /**
   * 🔴 활성 판정은 로컬 state 인덱스가 아니라 현재 URL 이다 — 탭 전환이 히스토리에 남아
   *    브라우저 뒤로가기로 직전 탭에 돌아간다.
   */
  const { pathname } = useLocation()

  return (
    <>
      <header className="topbar">
        <div>
          <h1 className="page-title">설정</h1>
          <p className="page-sub">메뉴 권한 · 분석 지표 가중치 관리</p>
        </div>
      </header>

      <div className="content">
        <div className="card st-body">
          {/* 탭이 1개뿐이면 고를 것이 없으므로 탭 바를 숨긴다. */}
          {visible.length > 1 && (
            <div className="segment">
              {visible.map((t) => {
                const on = pathname === t.to
                return (
                  <Link
                    key={t.to}
                    to={t.to}
                    aria-current={on ? 'page' : undefined}
                    className={`segment__btn${on ? ' segment__btn--active' : ''}`}
                    /*
                     * `.segment__btn` 은 dashboard.css 소유이고 `<button>` 기준이라
                     * 앵커에서는 높이 안 세로중앙·밑줄이 남는다. 그 파일은 이 범위에서
                     * 건드리지 않으므로(settings.css 주석의 소유 규칙) 두 줄만 인라인으로 맞춘다.
                     */
                    style={{ display: 'inline-flex', alignItems: 'center', textDecoration: 'none' }}
                  >
                    {t.label}
                  </Link>
                )
              })}
            </div>
          )}

          {/*
            🔴 렌더 주체는 자식 라우트다 — `<Outlet />` 이 없으면 react-router 6 이 자식 element 를
            아예 실행하지 않아 인덱스 리다이렉트·자식 가드가 통째로 죽는다.
            (준비 중 분기는 걷어냈다 — `사용자 정보` 탭이 들어오는 후속 TSK 가 자식 라우트로 만든다.)
          */}
          <Outlet />
        </div>
      </div>
    </>
  )
}
