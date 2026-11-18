import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import {
  fetchAnalysisWeights,
  fetchAnalysisWeightHistories,
  saveAnalysisWeights,
  type AnalysisTypeWeight,
  type AnalysisWeight,
  type AnalysisWeightHistory,
} from '../../api/analysisWeight'
import SrLabel from '../common/SrLabel'

/*
 * 설정 > 분석 지표 가중치 설정 탭 (TSK-4020 Step 9 · TSK-4140 Step 7 에서 변수 모델로 확장).
 *
 * 레이아웃·상호작용은 시안(https://innopamsolution.github.io/jeju-datahub/)의 DOM 을 그대로 옮겼고,
 * 데이터만 시안의 하드코딩 대신 실 API(`src/api/analysisWeight.ts`)에서 받는다.
 * 상태 관리 규약은 `MenuAuthorityTab.tsx` 와 동일하다 —
 *   draft 로컬 편집 → dirty 일 때만 저장 활성 → 저장 성공 시 `저장되었습니다` 2초 →
 *   저장 실패해도 draft 유지(사용자 입력을 날리지 않는다).
 *
 * ── TSK-4140 D8 : 한 탭 안에서 **가중치(WGVL) / 계수(CFC) 2섹션**으로 나눈다 ──────────
 *   - 가중치 섹션은 `vrblSeCd === 'WGVL'` 행 **전부**를 그린다. `useYn === 'N'` 은 지금까지처럼
 *     `미사용` 태그로 보여 준다. 🔴 **`useYn === 'Y'` 를 렌더 필터로 쓰지 않는다** —
 *     가중치가 전부 `use_yn='N'` 인 묶음(RECOMMEND 4행)이 통째로 비는 회귀가 된다.
 *   - 합계·저장잠금·`준비 중` 판정 대상은 `WGVL` 이면서 `useYn === 'Y'` 인 행뿐이다
 *     (백엔드 `AnalysisWeightServiceImpl` 의 합계 검증·`stngVlSum` 과 같은 기준, D2).
 *   - 🔴 가중치 섹션을 숨기는 조건은 *"WGVL·Y 0건"* 이 아니라 *"WGVL·Y 0건 **이면서 계수가 있다**"*
 *     (= 생활권)다. 그 밖의 묶음(RECOMMEND 등)은 지금처럼 미사용 가중치를 계속 보여 준다.
 *   - 🔴 분석유형 세그먼트는 **두 섹션 바깥**에 둔다. 가중치 섹션 안에 두면 생활권 탭에서 섹션과
 *     함께 사라져 **다른 분석유형으로 돌아갈 방법이 없어진다.**
 *   - 🔴 화면에 `k` 라는 글자를 쓰지 않는다 — 라벨은 `vrblNm`, 단위는 `unitNm` 이 진다.
 *   - 🔴 범위·단위·구분을 프론트 상수로 박지 않는다 (D3) — `minVl`·`maxVl`·`unitNm`·`vrblSeCd`
 *     전부 응답에서 온다. clamp 도 `<input min max>` 도 이 값만 읽는다.
 *
 * 시안과 의도적으로 다른 부분
 *   - 세그먼트 순서·라벨은 **서버 정렬**(TB_COM_CD.sort_seq · cd_nm)을 그대로 쓴다 (D15).
 *     시안 순서(집중구역 분석 · 구역 추천 · 투자·보강 우선순위 · 생활권 시뮬레이션)로 재정렬하지 않는다.
 *   - 초기 선택은 서버가 준 첫 분석유형(HOTSPOT) — 시안은 `invest` 로 시작한다.
 *   - `변경 사유` 입력란을 이력 표 바로 위에 추가했다 (D16 — 시안에 없는 추가).
 *   - 가중치가 전부 `useYn === 'N'` 인 분석유형에는 준비 중 안내를 붙인다 (TSK-4020 D8).
 *     편집·저장은 막지 않는다. 🔴 이 안내는 **가중치 섹션 안에서만** 렌더한다 — 섹션이 숨는
 *     생활권에서 배너만 남으면 *"편집 가능한 계수 위에 준비 중이 뜨는"* 모순이 된다.
 *
 * 시안에 없어 이번에 추가한 부분 (시안은 정적 데이터라 실패 경로가 없다)
 *   - 조회 실패 → 안내 문구 + `다시 시도` 버튼
 *   - 저장 실패 → 오류 문구만 띄우고 draft 는 유지한다
 *
 * ── TSK-4140 Step 7-1 : 설정 변경 이력은 **선택한 분석유형 것만** (사용자 요청) ────────────
 *   - 🔴 거르는 일은 **서버(SQL)** 가 한다 — 받은 배열을 프론트에서 거르면 `LIMIT` 이 먼저 걸린
 *     뒤라 한 유형을 20번 저장했을 때 다른 탭 이력이 있는데도 0건으로 보인다.
 *   - 🔴 탭 전환·저장 성공 모두 **현재 탭 기준으로 재조회**한다(이력 useEffect 의 의존성
 *     `[activeCd, reloadKey]`). 늦게 도착한 이전 탭 응답은 cleanup 의 `cancelled` 가 버린다.
 *   - 🔴 이력 조회 실패가 변수 목록을 막지 않는다 — 종전 `Promise.all` 을 두 effect 로 갈랐다.
 */

/** `저장되었습니다` 노출 시간(ms). `MenuAuthorityTab` 과 동일. */
const SAVED_NOTICE_MS = 2000

/** 이력 조회 건수. 서버 기본값과 같은 20 이며 상한은 100 이다(초과하면 400). */
const HISTORY_SIZE = 20

/** 가중치가 전부 미사용인 분석유형(= 화면 미구현 모델)에 붙는 안내. TSK-4020 D8. */
const NOT_READY_NOTICE =
  '이 분석 화면은 아직 준비 중입니다. 저장한 값은 화면이 구현될 때 그대로 적용됩니다.'

/** 분석유형 1건의 변수 목록 → `{ vrblCd: 설정값 }` draft 맵. */
const toDraft = (type: AnalysisTypeWeight | null, pick: 'stngVl' | 'bscStngVl') => {
  const next: Record<string, number> = {}
  if (type) {
    for (const w of type.weights) next[w.vrblCd] = w[pick]
  }
  return next
}

/**
 * 설정값 표기. 가중치는 정수지만 계수는 `NUMERIC(12,4)` 라 소수가 올 수 있다 —
 * 자릿수를 가정하지 않고 꼬리 0 만 떨군다(`0.1000` → `0.1`, `100.0000` → `100`).
 */
const formatVl = (v: number) => v.toLocaleString('ko-KR', { maximumFractionDigits: 4 })

/**
 * 행별 허용 범위로 clamp 한다. 🔴 상한을 100 으로 굳히지 않는다 — 계수의 상한은 행마다 다르다.
 * 반올림은 `WGVL` 에만 적용한다(계수에 걸면 `0.5` 가 `1` 로 뭉개진다, D8).
 */
const clampVl = (w: AnalysisWeight, v: number) => {
  const rounded = w.vrblSeCd === 'WGVL' ? Math.round(v) : v
  return Math.max(w.minVl, Math.min(w.maxVl, rounded))
}

/** 'YYYY-MM-DDTHH:mm:ss' → 'YYYY-MM-DD HH:mm' (T 제거 · 초 절삭). 형식이 다르면 원문 그대로. */
const formatChgDt = (chgDt: string) => {
  const m = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(chgDt)
  return m ? `${m[1]} ${m[2]}` : chgDt
}

export default function AnalysisWeightTab() {
  /** 서버 원본 — 저장 성공 시 응답으로 이 목록의 해당 1건을 교체한다. */
  const [types, setTypes] = useState<AnalysisTypeWeight[]>([])
  const [histories, setHistories] = useState<AnalysisWeightHistory[]>([])
  const [activeCd, setActiveCd] = useState<string | null>(null)
  /** 편집 중인 설정값(선택 분석유형만). vrblCd → stngVl. 저장 전까지 서버에 반영되지 않는다. */
  const [draft, setDraft] = useState<Record<string, number>>({})
  /**
   * 계수 입력의 **화면 문자열 버퍼** (vrblCd → 입력 중 원문).
   *
   * 🔴 계수는 소수를 받는데 `<input type="number">` 의 value 를 draft(number) 로 직접 묶으면
   * `2.` 같은 중간 상태가 즉시 `2` 로 되돌려져 **소수를 입력할 수 없다**(controlled number input
   * 의 알려진 동작). 그래서 입력 중에는 원문을 그대로 보여 주고, 포커스가 빠질 때
   * `minVl`~`maxVl` 로 clamp 해 draft 에 굳힌다. 가중치(정수 슬라이더)는 이 버퍼를 쓰지 않는다.
   */
  const [cfcText, setCfcText] = useState<Record<string, string>>({})
  /** 변경 사유(선택 입력, 최대 300자 — 컬럼이 VARCHAR(300)). D16. */
  const [chgRsn, setChgRsn] = useState('')

  const [loading, setLoading] = useState(true)
  /** 초기 조회 실패 — 화면 전체를 재시도 안내로 대체한다. (변수 목록 조회만 해당) */
  const [loadError, setLoadError] = useState(false)
  /** 이력 조회 중(탭 전환 포함). 변수 목록의 `loading` 과 분리한다 — 화면을 덮지 않는다. */
  const [historyLoading, setHistoryLoading] = useState(false)
  /**
   * 이력 조회 실패. 🔴 **변수 목록과 분리한다** — 종전 `Promise.all` 은 이력 조회 하나가 실패하면
   * 변수 목록까지 함께 실패해 화면 전체가 `다시 시도` 로 덮였다. 이력은 보조 정보라 편집·저장을
   * 막을 이유가 없다. 실패는 이력 표 안에서만 알린다.
   */
  const [historyError, setHistoryError] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  /** 저장 실패 메시지. 실패해도 draft 는 유지한다. */
  const [saveError, setSaveError] = useState<string | null>(null)
  /** 초기 조회를 다시 돌리는 트리거 — `다시 시도` 버튼과 저장 성공 후 이력 재조회가 함께 쓴다. */
  const [reloadKey, setReloadKey] = useState(0)

  const savedTimerRef = useRef<number | null>(null)

  /** `저장되었습니다` 타이머 해제 — 재저장·언마운트 시 이전 타이머가 남지 않게 한다. */
  const clearSavedTimer = () => {
    if (savedTimerRef.current !== null) {
      window.clearTimeout(savedTimerRef.current)
      savedTimerRef.current = null
    }
  }

  /*
   * 마운트(및 재시도·저장 성공) 시 변수 목록을 조회한다.
   *
   * 🔴 종전의 `Promise.all([변수, 이력])` 을 **두 useEffect 로 분리했다** (TSK-4140 Step 7-1).
   *   ① 이력이 분석유형에 종속돼 `activeCd` 가 정해진 뒤에 불러야 한다 — 마운트 1회로는 탭 전환을
   *      따라가지 못한다.
   *   ② `Promise.all` 은 이력 조회 하나가 실패하면 변수 목록까지 실패시켜 화면 전체를 덮었다.
   *      이력은 보조 정보라 편집·저장을 막을 이유가 없다.
   */
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)

    fetchAnalysisWeights()
      .then((typeList) => {
        if (cancelled) return
        setTypes(typeList)
        setActiveCd((prev) => {
          // 저장 성공으로 재조회한 경우엔 보고 있던 분석유형을 유지한다.
          if (prev && typeList.some((t) => t.anlsTypeCd === prev)) return prev
          // 초기 선택 = 서버가 준 첫 분석유형(HOTSPOT). 서버 정렬을 그대로 따른다.
          return typeList.length > 0 ? typeList[0].anlsTypeCd : null
        })
      })
      .catch(() => {
        if (cancelled) return
        setTypes([])
        setActiveCd(null)
        setDraft({})
        setCfcText({})
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [reloadKey])

  /*
   * 선택한 분석유형의 변경 이력 (TSK-4140 Step 7-1 — 사용자 요청).
   *
   * 🔴 **거르는 일은 서버가 한다** — 받은 배열을 `anlsTypeCd` 로 거르면 `LIMIT` 이 먼저 걸린 뒤라
   *    한 유형을 20번 저장하면 다른 탭 이력이 실제로는 있는데도 0건으로 보인다.
   * 🔴 의존성에 `activeCd` 를 둬 **탭을 바꿀 때마다 재조회**하고, `reloadKey` 를 함께 둬
   *    **저장 성공 후에도 현재 탭 기준**으로 다시 읽는다.
   * ⚠️ 경쟁 조건 — 탭을 빠르게 오가면 이전 요청이 나중에 도착할 수 있다. cleanup 이
   *    `cancelled` 를 세워 **직전 effect 의 응답을 통째로 무시**하므로 마지막 탭의 결과만 남는다
   *    (이 파일의 기존 조회와 같은 관례다).
   */
  useEffect(() => {
    if (!activeCd) {
      setHistories([])
      setHistoryError(false)
      setHistoryLoading(false)
      return
    }

    let cancelled = false
    // 이전 탭의 이력을 즉시 비운다 — 남겨 두면 응답 도착 전까지 '남의 이력' 이 보인다.
    setHistories([])
    setHistoryError(false)
    setHistoryLoading(true)

    fetchAnalysisWeightHistories(HISTORY_SIZE, activeCd)
      .then((historyList) => {
        if (!cancelled) setHistories(historyList)
      })
      .catch(() => {
        if (cancelled) return
        setHistories([])
        setHistoryError(true)
      })
      .finally(() => {
        if (!cancelled) setHistoryLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [activeCd, reloadKey])

  // 언마운트 시 `저장되었습니다` 타이머 정리.
  useEffect(() => clearSavedTimer, [])

  const active = useMemo(
    () => types.find((t) => t.anlsTypeCd === activeCd) ?? null,
    [types, activeCd],
  )

  // 서버 원본이 바뀌면(최초 로드·저장 성공) draft 를 그 값으로 맞춘다 → 직후 dirty=false.
  // 입력 버퍼도 함께 비운다 — 서버값으로 되돌아갔는데 옛 원문이 남아 있으면 안 된다.
  useEffect(() => {
    setDraft(toDraft(active, 'stngVl'))
    setCfcText({})
  }, [active])

  /* ── 하위 유형 묶음 (TSK-4217) ───────────────────────────────
   * 대메뉴 탭 안을 `anlsTrgtCd` 묶음으로 가른다. 합계 100 검사·재정규화가 이 단위라(백엔드 Step 4)
   * 배지·저장잠금도 묶음마다 판정한다.
   *   - 🔴 **묶음 순서는 서버가 정한다**(D1=A — 공통 묶음 먼저 → 공통코드 sort_seq 순). 프론트는
   *     `trgtSums` 순서를 그대로 따르고 재정렬하지 않는다. `trgtSums` 에 없는 묶음(키 없는 옛 응답 등)은
   *     `weights` 에 처음 나온 순서로 뒤에 붙인다 — 행이 화면에서 사라지지 않게 하는 방어다.
   *   - 공통 묶음(`anlsTrgtCd` null)은 **제목을 달지 않는다** — 하위 유형이 없는 대메뉴는 종전 화면 그대로다.
   *   - 🔴 서버 `trgtSums[].stngVlSum` 은 **저장된 값의 합**이라 편집 중 합계로 쓰지 않는다 —
   *     합계는 아래에서 draft 로 다시 계산한다.
   */
  const groups = useMemo(() => {
    if (!active) return []
    const keyOf = (cd: string | null | undefined) => cd ?? ''
    const order: { cd: string | null; nm: string | null }[] = (active.trgtSums ?? []).map((t) => ({
      cd: t.anlsTrgtCd,
      nm: t.anlsTrgtNm,
    }))
    for (const w of active.weights) {
      if (!order.some((g) => keyOf(g.cd) === keyOf(w.anlsTrgtCd))) {
        order.push({ cd: w.anlsTrgtCd ?? null, nm: w.anlsTrgtNm ?? null })
      }
    }
    return order
      .map((g) => {
        /*
         * 🔴 숨김 행(`expsrYn !== 'Y'`, TSK-4346)은 그리지 않는다 — 렌더·합계 판정 모두 노출 행만 본다.
         * 저장 페이로드는 이 목록이 아니라 `active.weights` 전체라 숨김 행도 현재값(draft = stngVl)으로 실린다.
         * 행이 0개가 된 묶음은 아래 `.filter` 가 뺀다.
         */
        const rows = active.weights.filter(
          (w) => keyOf(w.anlsTrgtCd) === keyOf(g.cd) && w.expsrYn === 'Y',
        )
        /* ── 섹션 분리 (D8) — 묶음 안에서 ─────────────────────
         * `wgvl`       : 가중치 섹션의 **렌더 대상** (미사용 행 포함 — 지금처럼 `미사용` 태그로 보인다)
         * `wgvlActive` : 합계·저장잠금·`준비 중` 의 **판정 대상** (백엔드 검증 기준과 동일)
         * `cfc`        : 계수 섹션의 렌더 대상
         */
        const wgvl = rows.filter((w) => w.vrblSeCd === 'WGVL')
        const wgvlActive = wgvl.filter((w) => w.useYn === 'Y')
        const cfc = rows.filter((w) => w.vrblSeCd === 'CFC')
        return {
          key: keyOf(g.cd),
          /** 묶음 제목. 공통 묶음은 null(제목 없음). 이름이 미등재면 코드로 대신한다. */
          title: g.cd === null ? null : g.nm ?? g.cd,
          wgvl,
          wgvlActive,
          cfc,
          /**
           * 가중치 섹션 표시 여부.
           * 🔴 *"WGVL·Y 0건"* 만으로 숨기면 가중치가 전부 `use_yn='N'` 인 묶음(RECOMMEND)까지 빈 화면이 된다.
           * 숨기는 것은 **계수가 그 자리를 대신하는 묶음**(생활권 혼잡도)뿐이다 — D8 의 근거가
           * *"안 쓰이는 숫자를 계수 옆에 두면 계수의 신뢰까지 깎인다"* 이므로 계수가 없는 묶음엔 해당하지 않는다.
           */
          showWgvl: wgvl.length > 0 && !(wgvlActive.length === 0 && cfc.length > 0),
          /**
           * 묶음의 가중치가 전부 미사용이면 준비 중 모델이다. 가중치 0건은 안내 대상이 아니다.
           * 🔴 안내 문구는 **가중치 섹션 안에서만** 렌더한다 (D8) — 계수 위에 "준비 중" 이 뜨면 모순이다.
           */
          notReady: wgvl.length > 0 && wgvlActive.length === 0,
        }
      })
      .filter((g) => g.wgvl.length > 0 || g.cfc.length > 0)
  }, [active])

  /**
   * 묶음별 **편집 중 가중치 합계** (key → 합). 100 이 아니면 그 묶음 배지가 붉어지고 저장이 잠긴다 (D12 1차 방어).
   * 🔴 합산 대상은 `WGVL`·`useYn='Y'` 뿐이다 — 계수를 섞으면 집중구역 저장이 곧바로 400 이 된다.
   */
  const sums = useMemo(() => {
    const next: Record<string, number> = {}
    for (const g of groups) {
      next[g.key] = g.wgvlActive.reduce((acc, w) => acc + (draft[w.vrblCd] ?? 0), 0)
    }
    return next
  }, [groups, draft])

  /**
   * 묶음의 합계 조건 충족 여부. 🔴 **합산 대상이 0건이면 조건 자체를 건너뛴다** —
   * 그러지 않으면 가중치가 하나도 없는 묶음(생활권 혼잡도)에서 `설정 저장` 이 영구 비활성이 된다.
   */
  const sumOkOf = (g: (typeof groups)[number]) => g.wgvlActive.length === 0 || sums[g.key] === 100

  /** 저장 가능 조건 — WGVL·Y 행이 있는 **모든 묶음**의 합이 100 (백엔드 Step 4-1 과 같은 단위). */
  const sumOk = groups.every(sumOkOf)

  /** draft 를 선택 분석유형의 서버값과 변수별로 비교한다. */
  const dirty = useMemo(() => {
    if (!active) return false
    return active.weights.some((w) => draft[w.vrblCd] !== w.stngVl)
  }, [active, draft])

  /**
   * 모든 값이 행별 허용 범위 안인지. 저장 전 1차 방어다 —
   * 범위를 벗어난 값은 서버가 400 으로 막지만, 버튼을 먼저 잠가 왕복을 줄인다.
   */
  const inRange = useMemo(() => {
    if (!active) return true
    return active.weights.every((w) => {
      const v = draft[w.vrblCd]
      return v === undefined || (v >= w.minVl && v <= w.maxVl)
    })
  }, [active, draft])

  const canSave = dirty && sumOk && inRange && !saving

  /**
   * draft 가 기본값(`bscStngVl`)과 같은지. `기본값으로 초기화` 비활성 판단에만 쓴다.
   * ⚠️ `&& !dirty` 를 붙이지 않는다 — 저장된 값이 기본값과 다른 상태에서 초기화를 누르면
   * `isDefault=true` 인데 `dirty=true` 라 버튼이 활성으로 남아 "죽은 버튼" 이 된다.
   */
  const isDefault = useMemo(() => {
    if (!active) return true
    // 숨김 행(TSK-4346)은 화면에서 못 바꾸는 값이라 판정에서 뺀다 — 노출 행만 본다.
    return active.weights
      .filter((w) => w.expsrYn === 'Y')
      .every((w) => draft[w.vrblCd] === w.bscStngVl)
  }, [active, draft])

  /** 직전 저장 결과(성공·실패 문구)는 새 편집이 시작되면 의미가 없다. */
  const clearSaveNotice = () => {
    setSaved(false)
    setSaveError(null)
    clearSavedTimer()
  }

  /**
   * 분석유형 전환. `MenuAuthorityTab` 과 동일하게 **누를 때마다** draft 를 그 유형의 서버값으로
   * 되돌린다(편집분 버림). 변경 사유도 함께 비운다 — 사유는 분석유형 1건의 저장 1회에 딸린 값이라
   * 다른 유형으로 넘어가면 뜻이 달라진다.
   */
  const selectType = (t: AnalysisTypeWeight) => {
    setActiveCd(t.anlsTypeCd)
    setDraft(toDraft(t, 'stngVl'))
    setCfcText({})
    setChgRsn('')
    clearSaveNotice()
  }

  /**
   * 가중치 슬라이더·숫자 입력 공통. 행의 `minVl`~`maxVl` 로 clamp 한다
   * (`Number('') → 0` 이라 지우면 하한으로 붙는다).
   */
  const setWeight = (w: AnalysisWeight, v: string | number) => {
    const n = clampVl(w, Number(v) || 0)
    setDraft((prev) => ({ ...prev, [w.vrblCd]: n }))
    clearSaveNotice()
  }

  /** 계수 입력 — 타이핑 중에는 원문을 그대로 두고, 파싱되는 값만 draft 에 반영한다. */
  const typeCoefficient = (w: AnalysisWeight, raw: string) => {
    setCfcText((prev) => ({ ...prev, [w.vrblCd]: raw }))
    const n = Number(raw)
    if (raw.trim() !== '' && Number.isFinite(n)) {
      setDraft((prev) => ({ ...prev, [w.vrblCd]: n }))
    }
    clearSaveNotice()
  }

  /** 계수 입력 확정(blur) — 여기서 `minVl`~`maxVl` 로 clamp 하고 버퍼를 비운다. */
  const commitCoefficient = (w: AnalysisWeight) => {
    const raw = cfcText[w.vrblCd]
    if (raw === undefined) return
    const parsed = Number(raw)
    // 빈 칸·해석 불가면 마지막으로 유효했던 값(없으면 기본값)으로 되돌린다.
    const base =
      raw.trim() === '' || !Number.isFinite(parsed) ? draft[w.vrblCd] ?? w.bscStngVl : parsed
    const n = clampVl(w, base)
    setDraft((prev) => ({ ...prev, [w.vrblCd]: n }))
    setCfcText((prev) => {
      const next = { ...prev }
      delete next[w.vrblCd]
      return next
    })
  }

  /**
   * 기본값으로 초기화 — 로컬 draft 만 되돌린다. **저장하지 않는다** (D4).
   * 🔴 노출 행만 기본값으로 — 숨김 행(TSK-4346)은 서버 현재값(`stngVl`)을 그대로 둔다
   * (서버도 숨김 행 요청값을 버리지만 요청부터 현재값으로 보낸다).
   */
  const resetToDefault = () => {
    if (!active || isDefault) return
    const next: Record<string, number> = {}
    for (const w of active.weights) next[w.vrblCd] = w.expsrYn === 'Y' ? w.bscStngVl : w.stngVl
    setDraft(next)
    setCfcText({})
    clearSaveNotice()
  }

  const handleSave = async () => {
    if (!active || !canSave) return
    setSaving(true)
    setSaveError(null)
    try {
      const updated = await saveAnalysisWeights(active.anlsTypeCd, {
        // 🔴 그 분석유형의 변수 집합 **전체**(가중치 + 계수)를 보낸다 — 빠지면 집합 불일치로 400.
        weights: active.weights.map((w) => ({ vrblCd: w.vrblCd, stngVl: draft[w.vrblCd] ?? 0 })),
        // 빈 문자열은 보내지 않는다 — 서버가 `chg_rsn = null` 로 기록한다.
        chgRsn: chgRsn.trim() || undefined,
      })
      // 서버 응답을 단일 진실로 삼아 목록을 교체한다 → draft 동기화 useEffect 가 dirty 를 푼다.
      setTypes((prev) => prev.map((t) => (t.anlsTypeCd === updated.anlsTypeCd ? updated : t)))
      setChgRsn('')
      // 이력은 서버가 새로 쌓으므로 재조회한다 (같은 useEffect 가 목록도 함께 갱신).
      setReloadKey((k) => k + 1)
      setSaved(true)
      clearSavedTimer()
      savedTimerRef.current = window.setTimeout(() => {
        setSaved(false)
        savedTimerRef.current = null
      }, SAVED_NOTICE_MS)
    } catch (e) {
      /*
       * ⚠️ `strict: true` 라 catch 변수는 `unknown` 이다 — 바로 `e.response` 로 파고들면 TS18046.
       * 이 저장소 관례(알림·보고서 화면)대로 `axios.isAxiosError` 로 좁힌다.
       * 서버 `message` 는 `server.error.include-message` 설정에 따라 없을 수 있어 기본 문구로 받는다.
       */
      const msg = axios.isAxiosError(e)
        ? (e.response?.data as { message?: string } | undefined)?.message
        : undefined
      // 실패해도 draft 는 그대로 둔다 — 사용자가 고른 값을 날리지 않는다.
      setSaveError(msg ?? '저장에 실패했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setSaving(false)
    }
  }

  /*
   * 최초 로드에서만 화면 전체를 대기 문구로 덮는다.
   * 저장 성공 후의 재조회(`reloadKey`)는 이미 그린 화면 위에서 조용히 도는 백그라운드 갱신이라,
   * 여기서 덮어버리면 `저장되었습니다` 문구가 순간 사라졌다 돌아온다.
   */
  if (loading && types.length === 0) {
    return <p className="st-hint">불러오는 중...</p>
  }

  if (loadError) {
    return (
      <>
        <p className="st-hint">분석 지표 가중치 정보를 불러오지 못했습니다.</p>
        <div className="st-actions">
          <button type="button" className="btn-save" onClick={() => setReloadKey((k) => k + 1)}>
            다시 시도
          </button>
        </div>
      </>
    )
  }

  if (types.length === 0) {
    return <p className="st-hint">표시할 분석 모델이 없습니다.</p>
  }

  return (
    <>
      <p className="st-hint">
        분석 모델별 종합점수 산출에 사용되는 가중치와 산식 계수를 지역 여건에 맞게 조정할 수 있습니다.
        변경 내역은 이력으로 기록됩니다.
      </p>

      {/*
        분석유형 세그먼트 — 서버 정렬 순서 그대로 (D15, 프론트 재정렬 금지).
        🔴 두 섹션 **바깥**에 둔다 — 가중치 섹션 안에 두면 그 섹션이 숨는 생활권 탭에서
        세그먼트까지 사라져 다른 분석유형으로 돌아갈 수 없다.
      */}
      <div className="segment">
        {types.map((t) => (
          <button
            key={t.anlsTypeCd}
            type="button"
            aria-pressed={t.anlsTypeCd === activeCd}
            className={`segment__btn${t.anlsTypeCd === activeCd ? ' segment__btn--active' : ''}`}
            onClick={() => selectType(t)}
          >
            {t.anlsTypeNm}
          </button>
        ))}
      </div>

      {/*
        하위 유형 묶음 (TSK-4217) — 서버가 준 묶음 순서 그대로. 공통 묶음은 제목 없이 종전 화면과 같다.
        묶음마다 아래 두 섹션(가중치 / 계수)과 합계 배지가 따로 붙는다.
      */}
      {groups.map((g) => {
        const SecH = g.title ? 'h3' : 'h2'
        return (
          <Fragment key={g.key}>
            {g.title && <h2 className="st-sec__title">{g.title}</h2>}

            {/* ── 1) 종합점수 가중치 (WGVL) ───────────────────────── */}
            {g.showWgvl && (
              <div className="st-sec">
                <div>
                  <SecH className="st-sec__title">종합점수 가중치</SecH>
                  <p className="st-sec__sub">
                    각 지표가 종합점수에 반영되는 비율을 입력합니다. 합계가 100%가 아니면 저장되지 않으며,
                    원천 데이터가 없는 지표는 자동 비활성 후 나머지 지표로 재정규화됩니다.
                  </p>
                </div>

                {/* 🔴 준비 중 안내는 이 섹션 안에서만 — 계수 위에 뜨면 모순이다 (D8). */}
                {g.notReady && <p className="st-sec__sub">{NOT_READY_NOTICE}</p>}

                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-10)' }}>
                  {g.wgvl.map((w) => {
                    const v = draft[w.vrblCd] ?? 0
                    // 슬라이더 채움 비율도 행의 범위에서 계산한다 — 0~100 을 가정하지 않는다.
                    const span = w.maxVl - w.minVl
                    const pct = span > 0 ? ((v - w.minVl) / span) * 100 : 0
                    return (
                      <div key={w.vrblCd} className="st-w-row">
                        <div className="st-w-info">
                          <div className="st-w-name">
                            {w.vrblNm}
                            {w.useYn === 'N' && (
                              <span className="st-tag" style={{ marginLeft: 'var(--space-8)' }}>
                                미사용
                              </span>
                            )}
                          </div>
                          {w.vrblExpln && <div className="st-w-sub">{w.vrblExpln}</div>}
                        </div>

                        <SrLabel text={`${w.vrblNm} 가중치`}>
                          <input
                            type="range"
                            className="st-range"
                            min={w.minVl}
                            max={w.maxVl}
                            step={5}
                            value={v}
                            aria-label={`${w.vrblNm} 가중치`}
                            /* ⚠️ 캐스팅 필수 — @types/react 18 의 CSSProperties 에 인덱스 시그니처가 없어
                               캐스팅 없이 커스텀 프로퍼티를 넣으면 TS2353 이 난다. */
                            style={{ '--pct': `${pct}%` } as React.CSSProperties}
                            onChange={(e) => setWeight(w, e.target.value)}
                          />
                        </SrLabel>

                        <span className="st-num">
                          <SrLabel text={`${w.vrblNm} 가중치 입력`}>
                            <input
                              type="text"
                              inputMode="numeric"
                              value={v}
                              aria-label={`${w.vrblNm} 가중치 입력`}
                              onChange={(e) => setWeight(w, e.target.value)}
                            />
                          </SrLabel>
                          {w.unitNm && <span>{w.unitNm}</span>}
                        </span>
                      </div>
                    )
                  })}
                </div>

                {/*
                  합계 표시는 가중치 섹션 전용이다 — 계수에는 합계 개념이 없다 (D2 · D8).
                  🔴 합산 대상이 0건(RECOMMEND — 전부 미사용)이면 줄 자체를 감춘다.
                  `가중치 합계 0%` 를 띄우면 40/30/20/10 이 보이는 화면에서 숫자가 서로를 부정한다.
                */}
                {g.wgvlActive.length > 0 && (
                  <div className="st-sum">
                    <span className="st-w-name">가중치 합계</span>
                    <span className={`st-sum__badge${sumOkOf(g) ? '' : ' st-sum__badge--err'}`}>{sums[g.key]}%</span>
                  </div>
                )}
              </div>
            )}

            {/* ── 2) 분석 계수 (CFC) ──────────────────────────────── */}
            {g.cfc.length > 0 && (
              <div className="st-sec">
                <div>
                  <SecH className="st-sec__title">분석 계수</SecH>
                  <p className="st-sec__sub">
                    가중합이 아니라 산식에 직접 들어가는 값입니다. 합계 조건이 없고, 각 계수는 아래 표시된
                    허용 범위 안에서만 저장됩니다.
                  </p>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-10)' }}>
                  {g.cfc.map((w) => {
                    // 입력 중에는 원문 버퍼를, 아니면 draft 값을 보여 준다.
                    const text = cfcText[w.vrblCd] ?? String(draft[w.vrblCd] ?? w.bscStngVl)
                    return (
                      <div key={w.vrblCd} className="st-w-row">
                        <div className="st-w-info">
                          <div className="st-w-name">
                            {w.vrblNm}
                            {w.useYn === 'N' && (
                              <span className="st-tag" style={{ marginLeft: 'var(--space-8)' }}>
                                미사용
                              </span>
                            )}
                          </div>
                          {w.vrblExpln && <div className="st-w-sub">{w.vrblExpln}</div>}
                          {/* 범위·기본값은 전부 응답값이다 — 화면에 상수를 박지 않는다 (D3). */}
                          <div className="st-w-sub">
                            범위 {formatVl(w.minVl)} ~ {formatVl(w.maxVl)} · 기본값{' '}
                            {formatVl(w.bscStngVl)}
                          </div>
                        </div>

                        <span className="st-num">
                          <SrLabel text={`${w.vrblNm} 입력`}>
                            <input
                              type="number"
                              min={w.minVl}
                              max={w.maxVl}
                              /* 🔴 증감 폭을 상수로 고르지 않는다 — 계수는 소수를 받으므로 `any` 로 연다. */
                              step="any"
                              value={text}
                              aria-label={`${w.vrblNm} 입력`}
                              /* `.st-num input` 은 0~100 정수 기준 30px 이라 소수가 잘린다. */
                              style={{ width: 64 }}
                              onChange={(e) => typeCoefficient(w, e.target.value)}
                              onBlur={() => commitCoefficient(w)}
                            />
                          </SrLabel>
                          {w.unitNm && <span>{w.unitNm}</span>}
                        </span>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
          </Fragment>
        )
      })}

      {/* ── 변경 사유 (시안에 없는 추가 — D16). 이력 표 바로 위. ─── */}
      <div className="st-rsn">
        <label htmlFor="chg-rsn">
          변경 사유 <span>(선택)</span>
        </label>
        <input
          id="chg-rsn"
          type="text"
          value={chgRsn}
          maxLength={300}
          placeholder="예: 관광성수기 반영"
          onChange={(e) => setChgRsn(e.target.value)}
        />
      </div>

      {/* ── 설정 변경 이력 ─────────────────────────────────── */}
      <div className="st-sec">
        <div>
          <h2 className="st-sec__title">설정 변경 이력</h2>
          <p className="st-sec__sub">
            선택한 분석 모델의 변경 이력만 표시됩니다. 설정값 변경 시 변경자와 사유가 기록되며, 분석
            결과에는 산출 당시의 설정 스냅샷이 함께 저장됩니다.
          </p>
        </div>

        <div className="st-table-wrap">
          <table className="st-table">
            <caption className="sr-only">분석 지표 가중치 변경 이력</caption>
            <thead>
              <tr>
                <th scope="col" style={{ width: 150 }}>변경 일시</th>
                <th scope="col" style={{ width: 130 }}>변경자</th>
                <th scope="col" style={{ width: 180 }}>변경 항목</th>
                <th scope="col">변경 내용</th>
              </tr>
            </thead>
            <tbody>
              {/*
                🔴 이력 실패는 이 표 안에서만 알린다 — 화면 전체를 `다시 시도` 로 덮지 않는다
                (변수 편집·저장은 이력 없이도 성립한다, Step 7-1).
              */}
              {historyLoading ? (
                <tr>
                  <td colSpan={4} className="st-td-dim" style={{ textAlign: 'center' }}>
                    불러오는 중...
                  </td>
                </tr>
              ) : historyError ? (
                <tr>
                  <td colSpan={4} className="st-td-dim" style={{ textAlign: 'center' }}>
                    변경 이력을 불러오지 못했습니다.
                  </td>
                </tr>
              ) : histories.length === 0 ? (
                <tr>
                  <td colSpan={4} className="st-td-dim" style={{ textAlign: 'center' }}>
                    변경 이력이 없습니다.
                  </td>
                </tr>
              ) : (
                histories.map((h) => (
                  <tr key={h.chgHstrySn}>
                    <td className="st-td-dim">{formatChgDt(h.chgDt)}</td>
                    <td>{h.chnrgNm}</td>
                    <td className="st-td-name">{h.chgArtclNm}</td>
                    {/* `.st-table td` 는 nowrap 이라 긴 변경 내용만 인라인으로 푼다(시안 동일). */}
                    <td className="st-td-dim" style={{ whiteSpace: 'normal' }}>
                      {h.chgRsn ? `${h.chgCn} (${h.chgRsn})` : h.chgCn}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── 하단 액션 ───────────────────────────────────────── */}
      <div className="st-actions">
        {saveError ? (
          /* 실패 문구 전용 클래스가 없어 `.st-dirty` 타이포를 재사용하고 색만 인라인으로 뒤집는다
             (`MenuAuthorityTab` 과 동일한 처리). */
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

        <button type="button" className="btn" disabled={isDefault} onClick={resetToDefault}>
          기본값으로 초기화
        </button>
        <button
          type="button"
          className="btn-save"
          disabled={!canSave}
          onClick={() => {
            void handleSave()
          }}
        >
          설정 저장
        </button>
      </div>
    </>
  )
}
