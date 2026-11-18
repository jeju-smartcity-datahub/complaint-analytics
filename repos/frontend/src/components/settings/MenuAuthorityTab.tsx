import { useEffect, useMemo, useRef, useState } from 'react'
import {
  fetchAuthorities,
  fetchMenuAuthorities,
  saveMenuAuthorities,
  type AuthrtItem,
  type MenuAuthrtItem,
} from '../../api/settings'

/*
 * 설정 > 메뉴 권한 관리 탭 (TSK-4019 Step 7-2).
 *
 * 레이아웃·상호작용은 시안(https://innopamsolution.github.io/jeju-datahub/)의 DOM 을 그대로 옮겼고,
 * 데이터만 시안의 하드코딩 대신 실 API(`src/api/settings.ts`)에서 받는다.
 *
 * 시안 로직과 동일한 부분
 *   - 메뉴 행 클릭 → 선택 변경 + 편집 중이던 draft 를 그 메뉴의 서버값으로 리셋(편집분 버림)
 *   - 권한 옵션 클릭 → 로컬 draft 토글 (서버 호출 없음)
 *   - dirty 일 때만 `권한 저장` 활성화, 저장 직후 `저장되었습니다` 2초 노출
 *
 * 시안에 없어 이번에 추가한 부분 (시안은 정적 데이터라 실패 경로가 없다)
 *   - 조회 실패 → 안내 문구 + `다시 시도` 버튼
 *   - 저장 실패 → 오류 문구만 띄우고 draft 는 유지한다 (사용자 입력을 날리지 않는다)
 *
 * 목록 순서는 서버 정렬(menuLvl·sortSeq)을 그대로 따르고 프론트에서 재정렬하지 않는다.
 */

/** 시안과 동일한 체크 아이콘. `.st-chk--on` 일 때만 CSS 로 노출된다. */
const CheckIcon = (
  <svg viewBox="0 0 24 24" fill="none" width="14" height="14">
    <path
      d="M5 12.5 10 17.5 19 7"
      stroke="#fff"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
)

/** `저장되었습니다` 노출 시간(ms). 시안과 동일. */
const SAVED_NOTICE_MS = 2000

/**
 * SUPER 유형 권한의 체크박스를 비활성화하는 사유. TSK-4115 D7 재개정.
 * 🔴 **권한명을 받아 조립한다** — "어느 권한이 해제 불가인지" 를 문구가 스스로 말해야 한다
 *    (권한명 없이 사유만 띄우면 목록 아래 안내가 어느 행을 가리키는지 알 수 없다 — 2026-09-04 사용자 지적).
 * ⚠️ 이름은 **서버 응답의 `authrtNm`** 이다 — 롤 이름을 하드코딩하지 않는다.
 * ⚠️ 조사(은/는)를 붙이지 않는다 — 권한명 받침에 따라 달라지므로 이름 뒤에 구분자를 둔다.
 */
const superReason = (authrtNm: string) =>
  `${authrtNm} — 모든 메뉴에 접근하는 권한이라 해제할 수 없습니다`

/** 전 메뉴 접근이 보장되는 권한 유형 코드. 롤 이름보다 훨씬 안정적인 값이라 상수로 둔다 (D7 재개정). */
const SUPER_AUTHRT_TYPE_CD = 'SUPER'

/** 권한 목록에서 SUPER 유형 권한 코드 집합을 뽑는다 — 판정의 유일한 출처다. */
const superCdsOf = (authorities: AuthrtItem[]): Set<string> =>
  new Set(
    authorities.filter((a) => a.authrtTypeCd === SUPER_AUTHRT_TYPE_CD).map((a) => a.authrtCd),
  )

/**
 * 🔴 draft 에는 SUPER 권한이 **항상 들어간다** (TSK-4115 D7 재개정).
 * 체크박스가 항상 체크·비활성인 것과 저장 본문에 실리는 것이 같은 근거를 갖게 한다 —
 * 서버 자동 보정은 최후 방어선이고, 화면은 그 전에 이미 올바른 상태를 보낸다.
 */
const withSuper = (authrtCds: Iterable<string>, superCds: Set<string>): Set<string> => {
  const next = new Set(authrtCds)
  for (const cd of superCds) next.add(cd)
  return next
}

export default function MenuAuthorityTab() {
  const [authorities, setAuthorities] = useState<AuthrtItem[]>([])
  const [menus, setMenus] = useState<MenuAuthrtItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  /** 편집 중인 권한 코드 집합. 저장 전까지 서버에 반영되지 않는다. */
  const [draft, setDraft] = useState<Set<string>>(() => new Set())

  const [loading, setLoading] = useState(true)
  /** 초기 조회 실패 — 화면 전체를 재시도 안내로 대체한다. */
  const [loadError, setLoadError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  /** 저장 실패 메시지. 실패해도 draft 는 유지한다. */
  const [saveError, setSaveError] = useState<string | null>(null)
  /** `다시 시도` 로 초기 조회를 재실행하기 위한 트리거. */
  const [reloadKey, setReloadKey] = useState(0)

  const savedTimerRef = useRef<number | null>(null)

  /** `저장되었습니다` 타이머 해제 — 재저장·언마운트 시 이전 타이머가 남지 않게 한다. */
  const clearSavedTimer = () => {
    if (savedTimerRef.current !== null) {
      window.clearTimeout(savedTimerRef.current)
      savedTimerRef.current = null
    }
  }

  // 마운트(및 재시도) 시 권한 마스터 + 메뉴 매핑을 각 1회만 병렬 조회한다.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)

    Promise.all([fetchAuthorities(), fetchMenuAuthorities()])
      .then(([authrtList, menuList]) => {
        if (cancelled) return
        setAuthorities(authrtList)
        setMenus(menuList)
        // 초기 선택 = 서버가 준 첫 메뉴.
        const first = menuList.length > 0 ? menuList[0] : null
        setSelectedId(first ? first.menuId : null)
        setDraft(withSuper(first ? first.authrtCds : [], superCdsOf(authrtList)))
      })
      .catch(() => {
        if (cancelled) return
        setAuthorities([])
        setMenus([])
        setSelectedId(null)
        setDraft(new Set())
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [reloadKey])

  // 언마운트 시 `저장되었습니다` 타이머 정리.
  useEffect(() => clearSavedTimer, [])

  const selected = useMemo(
    () => menus.find((m) => m.menuId === selectedId) ?? null,
    [menus, selectedId],
  )

  /**
   * 🔴 전 메뉴 접근이 보장되는 권한 코드 집합 (TSK-4115 D7 재개정).
   * 어떤 권한이 그런지는 **서버 응답의 유형**(`authrtTypeCd`)이 알려 준다 — 롤 이름을 박지 않는다.
   * 권한 이름이 바뀌거나 SUPER 가 늘어나도 화면이 저절로 따라간다.
   */
  const superCds = useMemo(() => superCdsOf(authorities), [authorities])

  /** 목록 아래 안내에 쓸 SUPER 권한 **이름** 목록. 어느 권한이 해제 불가인지 밝힌다. */
  const superNms = useMemo(
    () =>
      authorities
        .filter((a) => a.authrtTypeCd === SUPER_AUTHRT_TYPE_CD)
        .map((a) => a.authrtNm),
    [authorities],
  )

  /** draft 와 선택 메뉴의 서버값을 **집합**으로 비교한다 (순서·중복 무관). */
  const dirty = useMemo(() => {
    if (!selected) return false
    const savedSet = new Set(selected.authrtCds)
    if (savedSet.size !== draft.size) return true
    for (const cd of savedSet) {
      if (!draft.has(cd)) return true
    }
    return false
  }, [selected, draft])

  /**
   * 메뉴 선택. 시안 그대로 **클릭할 때마다** draft 를 그 메뉴의 서버값으로 되돌린다
   * (이미 선택된 행을 다시 눌러도 초기화 — 시안 동작).
   */
  const selectMenu = (menu: MenuAuthrtItem) => {
    setSelectedId(menu.menuId)
    setDraft(withSuper(menu.authrtCds, superCds))
    setSaveError(null)
    setSaved(false)
    clearSavedTimer()
  }

  /** 권한 옵션 토글 — 로컬 draft 만 바꾼다. SUPER 유형은 애초에 끌 수 없다 (D7 재개정). */
  const toggleAuthority = (authrtCd: string) => {
    if (superCds.has(authrtCd)) return
    setDraft((prev) => {
      const next = new Set(prev)
      if (next.has(authrtCd)) next.delete(authrtCd)
      else next.add(authrtCd)
      return next
    })
    // 직전 저장 결과(성공 문구·실패 문구)는 새 편집이 시작되면 의미가 없다.
    setSaved(false)
    setSaveError(null)
    clearSavedTimer()
  }

  const handleSave = async () => {
    if (!selected || !dirty || saving) return
    setSaving(true)
    setSaveError(null)
    try {
      const updated = await saveMenuAuthorities(selected.menuId, Array.from(draft))
      // 서버 응답을 단일 진실로 삼아 목록과 draft 를 함께 맞춘다 → 저장 직후 dirty=false.
      setMenus((prev) => prev.map((m) => (m.menuId === updated.menuId ? updated : m)))
      setDraft(withSuper(updated.authrtCds, superCds))
      setSaved(true)
      clearSavedTimer()
      savedTimerRef.current = window.setTimeout(() => {
        setSaved(false)
        savedTimerRef.current = null
      }, SAVED_NOTICE_MS)
    } catch {
      /*
       * 🔴 실패해도 draft 는 그대로 둔다 — 사용자가 고른 값을 날리지 않는다.
       *    (저장되지 않았다는 사실은 아래 문구 + 여전히 활성인 `권한 저장` 버튼이 알린다.)
       *
       * ✅ 400 코드 분기를 두지 않는다 (TSK-4115 D7 재개정) — SUPER 권한 누락은 서버가 자동 보정하므로
       *    "다시 눌러도 영원히 실패하는 거절" 이 사라졌다. 남은 400 은 잘못된 권한코드·바디 누락처럼
       *    화면이 만들 수 없는 요청뿐이라 한 문구로 안내한다.
       */
      setSaveError('저장에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="st-hint">불러오는 중...</p>
  }

  if (loadError) {
    return (
      <>
        <p className="st-hint">메뉴 권한 정보를 불러오지 못했습니다.</p>
        <div className="st-savewrap">
          <button type="button" className="btn-save" onClick={() => setReloadKey((k) => k + 1)}>
            다시 시도
          </button>
        </div>
      </>
    )
  }

  if (menus.length === 0) {
    return <p className="st-hint">표시할 메뉴가 없습니다.</p>
  }

  return (
    <>
      <p className="st-hint">
        왼쪽 목록에서 메뉴를 선택하면 오른쪽에서 해당 메뉴의 사용 권한 그룹을 지정할 수 있습니다.
      </p>

      <div className="st-perm">
        {/* 좌: 메뉴 목록 (서버 정렬 순서 그대로) */}
        <div className="st-menu-card">
          <div className="st-menu-head">
            <span>메뉴명</span>
            <span>구분</span>
          </div>
          <div className="st-menu-list">
            {menus.map((m) => {
              const on = m.menuId === selectedId
              return (
                <button
                  key={m.menuId}
                  type="button"
                  aria-pressed={on}
                  className={`st-menu-row${on ? ' st-menu-row--on' : ''}`}
                  onClick={() => selectMenu(m)}
                >
                  <span>{m.menuNm}</span>
                  <span className={`st-tag${m.menuSeNm === '관리' ? ' st-tag--manage' : ''}`}>
                    {m.menuSeNm}
                  </span>
                </button>
              )
            })}
          </div>
        </div>

        {/* 우: 선택 메뉴의 접근 권한 */}
        <div className="st-assign">
          <h2 className="st-assign__title">{`'${selected ? selected.menuNm : ''}' 접근 권한`}</h2>
          <p className="st-assign__sub">이 메뉴를 사용할 권한 그룹을 선택하세요.</p>

          <div className="st-opts">
            {authorities.map((a) => {
              /*
               * 🔴 SUPER 유형 권한은 아예 끄지 못하게 한다 (TSK-4115 D7 재개정) — 모든 메뉴에서
               *    항상 체크 + 비활성이다. 어떤 권한이 SUPER 인지는 **서버 응답의 유형**
               *    (`authrtTypeCd`)이 알려 준다. 롤 이름을 프론트에 하드코딩하지 않는다 —
               *    DB 에서 유형을 옮기면 화면이 저절로 따라간다.
               */
              const locked = superCds.has(a.authrtCd)
              // 항상 체크 — draft 에도 항상 들어 있지만(withSuper), 표시도 그 사실에 기대지 않는다
              const on = locked || draft.has(a.authrtCd)
              return (
                <button
                  key={a.authrtCd}
                  type="button"
                  aria-pressed={on}
                  disabled={locked}
                  title={locked ? superReason(a.authrtNm) : undefined}
                  className={`st-opt${on ? ' st-opt--on' : ''}`}
                  /* 비활성 상태 전용 클래스가 settings.css 에 없어(시안에 없던 상태) 인라인으로 낮춘다. */
                  style={locked ? { cursor: 'default', opacity: 0.75 } : undefined}
                  onClick={() => toggleAuthority(a.authrtCd)}
                >
                  <span className={`st-chk${on ? ' st-chk--on' : ''}`}>{CheckIcon}</span>
                  {a.authrtNm}
                  {locked ? (
                    <span style={{ marginLeft: 'auto', color: 'var(--text-alternative)' }}>
                      해제 불가
                    </span>
                  ) : null}
                </button>
              )
            })}
          </div>

          {/* 사유는 툴팁만으로는 보이지 않으므로 목록 아래에도 남긴다 — 반드시 **권한명과 함께**. */}
          {superNms.length > 0 ? (
            <p className="st-hint">{superNms.map(superReason).join(' / ')}</p>
          ) : null}

          <div className="st-savewrap">
            {saveError ? (
              /*
               * 실패 문구는 시안에 없어 전용 클래스(`.st-error`)도 없다. settings.css 는 이번
               * 범위에서 건드리지 않으므로 `.st-dirty` 의 타이포를 재사용하고 색만 인라인으로
               * 뒤집는다. 전용 클래스 신설은 후속 TSK 후보.
               */
              <span className="st-dirty" role="alert" style={{ color: 'var(--status-negative)' }}>
                {saveError}
              </span>
            ) : saved ? (
              <span className="st-saved" role="status">
                저장되었습니다
              </span>
            ) : dirty ? (
              <span className="st-dirty">저장되지 않은 변경사항이 있습니다</span>
            ) : null}

            <button
              type="button"
              className="btn-save"
              disabled={!dirty || saving}
              onClick={() => {
                void handleSave()
              }}
            >
              권한 저장
            </button>
          </div>
        </div>
      </div>
    </>
  )
}
