import type { HotspotView } from '../../api/complaints'

/*
 * 민원현황(GIS) §4 — "레이어 표시" 카드 (TSK-3880 §4).
 *
 * 가이드 §4 결정: 화면 좌측 상단 floating 카드 안에 분석레이어 라디오 3종 + 토글 2종.
 * §5 범례는 본 라운드 보류.
 *
 * 🔴 라벨은 화면설계서 원문 그대로:
 *   - "민원 다발 지역" / "단속 집중 지역" / "수요 부족 지역" (공백 포함, 띄어쓰기 컨벤션 준수)
 *   - 가이드 §0 의 `분석레이어(...)` 표기는 가이드 내부 표기이며 화면 라벨이 아니다.
 *
 * 2026-09-04 갱신: "수요 부족 지역" 라디오 복원. 사용자 결정("폐기" 가 아니라 "옛 정의 적용 안 함"
 * + Jenks 통계 기법 DB 즉시 계산)에 따라 새 정의(Jenks k=3, "심각" 등급 zone 노출)로 부활.
 * queryValue = 'shortage', 화면 라벨은 "수요 부족 지역" 유지.
 *
 * 🔁 **2026-10-02 TSK-4197(D14) — 위 "Jenks k=3, 심각 등급 zone 노출" 은 폐기됐다.** 라디오·라벨·
 * queryValue 는 그대로이고 바뀐 것은 응답 모집단이다 — 이제 **주차민원 > 0 인 전 zone** 을 받아
 * 세 레이어가 모두 같은 `pickRiskGrade` 백분위로 등급을 매긴다.
 *
 * 🆕 **2026-10-28 TSK-4295 — 선택된 라디오 바로 밑에 "읍면동 / 히트맵" 표시 방식 세그먼트.**
 * 상태(`displayMode`)는 `ComplaintsPage` 가 들고, 이 카드는 그리기만 한다. 수요 부족 지역은
 * 히트맵 대상이 아니라(Q4 — 동 단위 비율이라 점으로 그릴 것이 없다) 선택돼도 버튼을 띄우지 않는다.
 * 레이어를 바꾸면 `읍면동` 으로 돌아가는 것(Q1)은 이 카드가 아니라 페이지의 `onViewChange` 래핑이 한다.
 */

export type LayerView = HotspotView

/**
 * §4 지도 표시 방식 (TSK-4295). 🔴 **정본은 여기 한 곳** — ComplaintsPage·MinwonMap·LegendCard 는
 * 이 타입을 import 한다(리터럴 `'emd' | 'heatmap'` 을 다시 쓰지 않는다).
 *   - `emd`     : 읍면동 polygon·라벨(종전 동작, 기본값)
 *   - `heatmap` : 100m 칸 좌표 히트맵(`GET /api/hotspots/heatmap`)
 */
export type DisplayMode = 'emd' | 'heatmap'

/**
 * 히트맵을 지원하는 분석레이어 — 🔴 **목록 정본은 이 상수 한 곳**(Q4 — `shortage` 제외).
 * 세그먼트 노출(이 카드)과 히트맵 조회 여부(ComplaintsPage)가 같은 목록을 본다 —
 * 갈리면 버튼은 있는데 조회가 400(`view=shortage`) 이거나, 조회는 되는데 버튼이 없다.
 */
export const HEATMAP_VIEWS = ['most-complaint', 'enforcement'] as const satisfies readonly LayerView[]

/** 히트맵 조회가 가능한 view — `fetchHotspotHeatmap` 호출부가 이 타입으로 좁혀 부른다. */
export type HeatmapView = (typeof HEATMAP_VIEWS)[number]

export interface LayerToggles {
  publicParking: boolean
  adminBoundary: boolean
}

export interface LayerCardProps {
  /** 현재 활성 분석레이어 view. */
  view: LayerView
  onViewChange: (next: LayerView) => void
  toggles: LayerToggles
  onTogglePublicParking: (next: boolean) => void
  onToggleAdminBoundary: (next: boolean) => void
  /** 지도 표시 방식 (TSK-4295). 미지정이면 `emd`. */
  displayMode?: DisplayMode
  /** 표시 방식 세그먼트 클릭 핸들러. 미지정이면 버튼을 눌러도 무시한다(읽기 전용). */
  onDisplayModeChange?: (next: DisplayMode) => void
}

/** 표시 방식 세그먼트 라벨 (TSK-4295 §3-2). */
const DISPLAY_MODES: { key: DisplayMode; label: string }[] = [
  { key: 'emd', label: '읍면동' },
  { key: 'heatmap', label: '히트맵' },
]

/** 가이드 §4 화면 라벨 — 시안과 같이 공백 포함 풀 표기. */
const VIEWS: { key: LayerView; label: string }[] = [
  { key: 'most-complaint', label: '민원 다발 지역' },
  { key: 'enforcement', label: '단속 집중 지역' },
  { key: 'shortage', label: '수요 부족 지역' },
]

export default function LayerCard({
  view,
  onViewChange,
  toggles,
  onTogglePublicParking,
  onToggleAdminBoundary,
  displayMode = 'emd',
  onDisplayModeChange,
}: LayerCardProps) {
  /* 세그먼트를 붙일 라디오의 위치. `shortage` 등 미지원 view 면 -1 → 버튼을 렌더하지 않는다(Q4). */
  const modeAt = (HEATMAP_VIEWS as readonly LayerView[]).includes(view)
    ? VIEWS.findIndex((v) => v.key === view)
    : -1

  return (
    <aside className="minwon-card" aria-label="레이어 표시">
      {/* ── §4 레이어 표시 ── */}
      <section className="minwon-card__section">
        <h2 className="minwon-card__title">레이어 표시</h2>

        {/* 분석레이어 라디오 — 풀 라벨, 세로 배치
            + 선택된 라디오 바로 밑 "읍면동 / 히트맵" 세그먼트 (TSK-4295).

            🔴 세그먼트를 **radiogroup 안에 넣지 않는다** — radiogroup 의 접근성 자식은 radio 여야 하고
               (ARIA `radiogroup` 의 소유 요소 = `radio`), 그 안에 button 묶음이 끼면 "분석레이어" 3개 중
               하나로 읽히거나 axe `aria-required-children` 에 걸린다. 그래서 DOM 은
                 [plain flex 컨테이너] > [radiogroup(라디오 3개만)] + [group "표시 방식"(버튼 2개)]
               형제로 두고, radiogroup 을 `display: contents` 로 풀어 두 형제의 자식이 한 flex 열에 서게 한 뒤
               CSS `order` 로 세그먼트를 선택된 라디오 바로 밑에 끼운다(라벨 order = 2i, 세그먼트 = 2i+1).
            검토한 대안과 기각 이유:
              - radiogroup 안 label 사이에 직접 삽입 — 위 문제 그대로.
              - `aria-owns` 로 세그먼트를 바깥으로 재부모화 — Safari/VoiceOver 지원이 불안정하고,
                소유된 노드가 접근성 트리 **끝**(토글 뒤)으로 가서 읽기 순서가 화면과 어긋난다.
              - 바깥을 `fieldset`(role=group)으로 교체 — 종전 radiogroup 의미를 바꾸는 변경이라 범위 밖.
            지금 방식의 읽기·탭 순서는 DOM 순서(라디오 그룹 → 표시 방식 → 토글)라 화면과 맞는다.
            ⚠️ `display: contents` 가 role 을 잃던 옛 브라우저 버그가 있다 — 그래도 라디오는 같은 `name`
               으로 네이티브 그룹이 유지돼 화살표 이동·"3개 중 n번째" 안내는 남는다(라벨 "분석레이어" 만 빠진다). */}
        <div className="minwon-card__group">
          <div className="minwon-card__radios" role="radiogroup" aria-label="분석레이어">
            {VIEWS.map((v, i) => (
              <label
                key={v.key}
                className={`minwon-card__radio${view === v.key ? ' is-active' : ''}`}
                style={{ order: i * 2 }}
              >
                <input
                  type="radio"
                  name="analysisLayer"
                  value={v.key}
                  checked={view === v.key}
                  onChange={() => onViewChange(v.key)}
                />
                <span className="minwon-card__radio__dot" aria-hidden="true" />
                <span className="minwon-card__radio__label">{v.label}</span>
              </label>
            ))}
          </div>

          {modeAt >= 0 && (
            /* 버튼 관례는 코드베이스 세그먼트와 같다 — `button type="button"` + `aria-pressed`
               (`HotspotSettingPanel`·`ZoneRcmdtnSettingPanel`). 🔴 radiogroup 을 또 만들지 않는다. */
            <div
              className="segment minwon-card__mode"
              role="group"
              aria-label="표시 방식"
              style={{ order: modeAt * 2 + 1 }}
            >
              {DISPLAY_MODES.map((m) => (
                <button
                  key={m.key}
                  type="button"
                  aria-pressed={displayMode === m.key}
                  className={`segment__btn${displayMode === m.key ? ' segment__btn--active' : ''}`}
                  onClick={() => onDisplayModeChange?.(m.key)}
                >
                  {m.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <hr className="minwon-card__sep" />

        {/* 토글 2개 */}
        <div className="minwon-card__group">
          <label className="minwon-card__toggle">
            <input
              type="checkbox"
              aria-label="공영주차장 토글"
              checked={toggles.publicParking}
              onChange={(e) => onTogglePublicParking(e.target.checked)}
            />
            <span className="minwon-card__toggle__track">
              <span className="minwon-card__toggle__thumb" />
            </span>
            <span className="minwon-card__toggle__label">공영주차장</span>
          </label>
          <label className="minwon-card__toggle">
            <input
              type="checkbox"
              aria-label="행정동 경계 토글"
              checked={toggles.adminBoundary}
              onChange={(e) => onToggleAdminBoundary(e.target.checked)}
            />
            <span className="minwon-card__toggle__track">
              <span className="minwon-card__toggle__thumb" />
            </span>
            <span className="minwon-card__toggle__label">행정동 경계</span>
          </label>
        </div>
      </section>
    </aside>
  )
}
