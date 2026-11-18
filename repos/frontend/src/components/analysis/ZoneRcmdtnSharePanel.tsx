import { useEffect, useRef, type CSSProperties, type RefObject } from 'react'
import type { CommonCodeItem } from '../../api/commonCodes'
import type { VrblVls } from '../../api/analysisWeight'
import type {
  ZoneRcmdtnShareCrtrVls,
  ZoneRcmdtnShareResult,
  ZoneRcmdtnShareZone,
} from '../../api/zoneRcmdtn'
import InfoTip from '../common/InfoTip'
import type { ZoneRcmdtnShareSelection } from './zoneRcmdtnSelection'
import {
  NOTE_TREND_WINDOW_SHORT,
  SHARE_NOTE_SCOPE,
  SHARE_NOTE_SCR,
  SHARE_SEC_TITLE,
  SHARE_WARN_NO_DATA,
  UNDECIDABLE_BADGE,
  noteTrendBfrFallback,
  shareEmptyCandText,
  shareGrdNm,
  shareZoneLvl,
  shareLvlColor,
  shareNoDataText,
  shareRowSub,
  shareRowVal,
  shareScrDesc,
  shareTrendDesc,
  shareUnableNote,
  stripNoteMark,
} from './zoneRcmdtnView'

/*
 * 구역추천 — 결과 패널의 `공유주차제 적용` 본문.
 *
 * 껍데기(`.sim-result` · 헤더 · 스크롤 컨테이너)는 `ZoneRcmdtnResultPanel` 이 들고 있고, 이 컴포넌트는
 * 그 `.sim-result__body` 안에 들어가는 **섹션들만** 그린다. 마크업은 4207 의 순위 리스트와 같은
 * `.sim-sec > .rl > .rl__row` 이고 CSS 클래스를 새로 만들지 않는다(`analysis.css` · `dashboard.css`).
 *
 * 🔴 **이 화면이 답하는 질문은 "주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"**
 * 하나다. 공유할 부설주차장이 있는지 · 어느 시간대인지 · 민원이 얼마나 줄어드는지 · 읍면동 안의
 * 어디인지는 **답하지 못한다** — 그래서 범위 한정 문장(`SHARE_NOTE_SCOPE`)을 패널 하단에 **항상** 띄운다
 * (후보 0곳·자료 없는 기간 포함. 🔁 패널은 실행 결과가 있을 때만 뜬다).
 * 🔁 2026-10-13 사용자 요청 — 시간대 · 공한지 제외 수 · 요금제 `SHARE` 표기 세 문장을 걷었다.
 * 남은 세 문장 중 번호(`SHARE_NOTE_RNK`) · 지수(`SHARE_NOTE_SCR`)는 제목 줄 InfoTip
 * 으로 옮기고, 부설주차장 미확인(`SHARE_NOTE_SCOPE`) 한 줄만 화면에 남긴다.
 *
 * 🔁 **2026-10-13 — 목록이 하나다.** 종전에는 후보 목록 + 접힌 구역 2개(`CVLCPT_ONLY` · `UNABLE`)였다.
 * 이제 두 조건을 **검토 지수**(`rvwScr`) 하나로 보여 주고, 필터에 걸린 읍면동 전부를 서버 순서로
 * **한 `.rl` 에** 그린다 — 등급은 구역이 아니라 행의 색 점이다. 순서는 **우선도**(추세 반영 지수)
 * 내림차순 하나라(종전의 "후보 먼저" 삭제), 후보가 아닌 행이 후보 사이에 끼고 색 점이 섞일 수 있다.
 *   - 사용자 요청 — 기준선(검토 지수 1) 표시와 "더 보기" 접기를 걷었다. 처음부터 **전 행을
 *     서버 순서 그대로 쭉** 그린다(최대 74행). `높음` 과 `보통` 의 경계는 행의 색 점이 말한다.
 *   - 지수의 **산출 방법**(`shareScrDesc`)은 제목 줄 InfoTip 에 있다 — 기준값은 응답 `crtrVls` 가 준다
 *     (종전에는 제목 밑 부제였다).
 *
 * 🔁 **2026-10-13 사용자 요청 — 표시 등급을 지수 3단계로 바꾸고, 행의 등급·주의 배지와 시군구명을 걷었다.**
 *   - 등급은 `최우선`(지수 `coreMltp` 이상) · `우선`(1 이상) · `후순위`(1 미만 — 이름 변경,
 *     종전 `매우 높음`·`높음`·`보통`)이며 `shareLvl` 이
 *     **검토 지수로** 정한다. 라벨·색의 정본은 `zoneRcmdtnView.SHARE_LVLS` 다.
 *   - 행에서 뺀 것: 등급 배지(공통코드 5종의 이름) · 주의 배지(`cutnCds` 2종) · 시군구명(`sggNm`).
 *     행에 남은 배지는 **지수가 없는 행의 사유**(공통코드 `UNABLE` 라벨) 하나다(🔁 2026-10-27 `공한지를 빼면 후보` 삭제).
 *
 * 🔴 **번호(`rnk`)는 표시 순서이며 우선순위가 아니다.** 서버가 **조회 결과(지역 필터 적용 후) 안에서**
 * 1 부터 매긴다 — 서귀포시만 보면 첫 행 강정동이 `1` 이다(등급·검토 지수는 도 전체로 볼 때와 같다).
 * 🔴 프론트는 번호를 다시 계산하지 않는다 — 서버 값을 그대로 그린다.
 * 정렬도 서버 순서 그대로다(프론트 재정렬 금지). 🔁 번호는 이제 **후보 밖에도** 있다 —
 * 후보인지는 `rnk` 가 아니라 `rvwTrgtYn` 으로 묻는다.
 *
 * 🔴 **공통코드 `SHRN_RVW_GRD_CD` 는 `UNABLE` 의 라벨에만 쓴다** — 그 행의 배지 · 더 보기 버튼 ·
 * 목록 아래 안내를 `shareGrdNm` 이 고른다. 이 파일에 그 낱말을 적지 않는다.
 * (🔁 종전에는 행의 등급 배지와 산출 설명의 `CORE` 도 공통코드 라벨이었다.)
 *
 * 🔁 요약 줄과 "도 전체 기준" 안내는 걷었다(2026-10-13). 판정은 시군구를 골라도 같고 번호는 고른
 * 시군구 안에서 매긴다는 점은 설정 패널의 시군구 칸 안내가 말한다.
 *
 * ⚠️ 본문은 넷 중 하나다 — 실행 전(안내) · 실행 중 · 결과 · (실패는 설정 패널이 문구를 띄우고
 * 여기는 직전 상태를 유지한다). `/share` 는 호출마다 실행 이력이 남아 **자동 조회가 없다.**
 */

/** 스크롤로 행을 끌어올릴 때 컨테이너 위쪽에 남기는 여백(px) — `ZoneRcmdtnResultPanel` 과 같다. */
const SCROLL_MARGIN = 8

/** 결론·안내 문장 상자 — `ZoneRcmdtnResultPanel.CONCLUSION` 과 같은 값이다(리스트 자리에 문장을 둔다). */
const CONCLUSION: CSSProperties = {
  margin: 'var(--space-16) 0 0',
  padding: 'var(--space-14) var(--space-12)',
  borderRadius: 'var(--radius-10)',
  background: 'var(--fill-alternative)',
  fontSize: 'var(--body2-size)',
  lineHeight: 1.6,
  color: 'var(--text-neutral)',
}

/**
 * 범위 한정 문장 — 🔴 강조색으로 눈에 들어와야 한다(그 오독이 가장 비싸다).
 * 종전 3줄 묶음(`NOTES`·`NOTE_LINE`)의 글자 크기·줄높이를 이 한 줄이 물려받는다.
 */
const NOTE_LINE_STRONG: CSSProperties = {
  margin: 0,
  fontSize: 'var(--label2-size)',
  lineHeight: 1.5,
  color: 'var(--status-cautionary)',
}

/** 행의 큰 값 — `.rl__cnt`(18px)에 `종합지수 5.05` 를 그대로 넣으면 이름을 밀어내 한 치수 줄인다. */
const ROW_VAL: CSSProperties = { fontSize: 'var(--body1-size)' }

/** 배지 줄 — 보조줄 아래. 좁은 패널(360px)이라 줄바꿈을 허용한다. */
const BADGES: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'var(--space-6)',
  marginTop: 'var(--space-6)',
}

/** 목록 아래 안내(`UNABLE` 의 `—` 가 왜 비었는가). */
const LIST_NOTE: CSSProperties = {
  margin: 'var(--space-10) 0 0',
  fontSize: 'var(--label2-size)',
  lineHeight: 1.5,
  color: 'var(--text-alternative)',
}

/** 이 결과를 만든 조건 — 🔴 설정 패널의 **지금 입력이 아니다**. */
export interface ZoneRcmdtnShareApplied {
  bgngYmd: string
  endYmd: string
  /** 필터로 건 시군구명. 걸지 않았으면 `null`(= 도 전체). 🔁 읍면동 필터는 화면에서 걷었다. */
  sggNm: string | null
  /** 실행 시점의 기간 라벨 — 부제 `분석 기간 : …`. */
  periodLabel: string
  /**
   * 이 결과를 만든 실행의 공유주차제 계수 일시 조정. 설정값으로 돌렸으면 `undefined`.
   * 보고서 저장(`shareExportCriteria`)이 이 값을 그대로 싣는다 — 🔴 필수 칸이다(빠뜨리면 보고서만 설정값으로 계산된다).
   */
  vrblVls: VrblVls | undefined
}

export interface ZoneRcmdtnSharePanelProps {
  /**
   * `/share` 응답. 🔁 **이 패널은 실행 결과가 있을 때만 뜬다**(2026-10-13 사용자 요청 — 요금제 보기와 같게).
   * 실행 전·실행 중에는 부모가 패널 자체를 그리지 않으므로 `null` 이 오지 않는다.
   */
  share: ZoneRcmdtnShareResult
  /** `share` 를 만든 조건. `share` 가 있으면 함께 있다. */
  applied: ZoneRcmdtnShareApplied | null
  /** 공통코드 `SHRN_RVW_GRD_CD` — 🔁 `UNABLE` 라벨에만 쓴다. 못 받았으면 코드값이 보인다. */
  grdCodes: CommonCodeItem[]
  /** 현재 선택(행 ↔ 깃발). 주인은 `RecommendationPage` 하나다. */
  selection: ZoneRcmdtnShareSelection | null
  /**
   * 행 클릭 — 후보면 해당 깃발을 강조·이동시킨다. 🔴 후보가 아닌 행은 깃발이 없어 **행 강조만** 된다
   * (지도는 그 선택을 조용히 흘려보낸다 — `ZoneRcmdtnMap` 의 "목록 → 지도").
   */
  onSelectZone: (emdCd: string) => void
  /** 스크롤 컨테이너(`.sim-result__body`) — 깃발에서 고른 행을 화면 안으로 끌어오는 기준이다. */
  containerRef: RefObject<HTMLDivElement>
}

/**
 * 행 본문(이름줄 + 보조줄 + 배지줄).
 *
 * 🔁 등급 배지를 걷었다 — 등급은 **색 점**이 말한다. 서버 등급 `CVLCPT_ONLY`·`NONE` 의 이름도 함께
 * 걷었다(새 3단계에서는 둘 다 `보통` 이라 가를 것이 없다). 주의 배지(`cutnCds`)와 시군구명도 그리지 않는다.
 * 🔴 `UNABLE` 행의 배지만은 남긴다 — 지수·번호가 `—` 인 **이유**라서다(`unableNm` = 공통코드 라벨).
 */
function RowMain({
  zone,
  crtrVls,
  unableNm,
}: {
  zone: ZoneRcmdtnShareZone
  crtrVls: ZoneRcmdtnShareCrtrVls
  unableNm: string
}) {
  const unable = zone.rvwGrdCd === 'UNABLE'
  return (
    <div className="rl__main">
      <div className="rl__top">
        <span className="rl__name">
          {/*
           * 🔴 색은 검토 등급이다(지수 3단계 · 지수가 없으면 회색) — DOM `style` 은 `var()` 를 받는다.
           * 🔁 2026-10-27 사용자 요청 — 등급은 큰 값(종합지수)으로 정한다(`shareZoneLvl`).
           */}
          <span
            className="dot"
            style={{ background: shareLvlColor(shareZoneLvl(zone, crtrVls)) }}
          />
          {zone.emdNm}
        </span>
        <span className="rl__val">
          {/*
           * 큰 값은 **종합지수**(구 `추세 반영 지수`·`우선도`)다(목록 순서의 기준). 🔁 2026-10-27 — 보조줄은 `100면당 민원 · 민원 증가율` 이다.
           * 🔴 `null`(공영 면수 0)·`undefined`(배포 전 응답)는 `—` 다 — 0 으로 쓰지 않는다.
           */}
          <span className="rl__cnt" style={ROW_VAL}>
            {shareRowVal(zone.incrsRfltRvwScr)}
          </span>
        </span>
      </div>
      <span className="rl__sub">{shareRowSub(zone)}</span>
      {/* 배지가 없는 행(대부분)에는 줄 자체를 두지 않는다 — 빈 줄의 여백만 남는다. */}
      {unable && (
        <div style={BADGES}>
          <span className={UNDECIDABLE_BADGE}>{unableNm}</span>
        </div>
      )}
    </div>
  )
}

/**
 * 범위 한정 문장 — 🔴 **패널 하단에 항상 보인다**(후보 0곳·자료 없는 기간 포함. 🔁 패널은 실행 결과가 있을 때만 뜬다).
 * 번호(`SHARE_NOTE_RNK`) · 지수(`SHARE_NOTE_SCR`) 두 줄은 제목 줄 InfoTip 으로 옮겼다.
 * 🔴 이 한 줄만은 팝업에 숨기지 않는다(2026-10-26 사용자 결정) — **오독 방지라 상시 노출**이다.
 * 팝업 뒤로 가면 목록이 "공유할 주차장이 있는 곳" 으로 읽힌다(이 화면은 부설주차장을 보지 않았다).
 */
function ShareNotes() {
  return (
    <div className="sim-sec">
      <p style={NOTE_LINE_STRONG}>{SHARE_NOTE_SCOPE}</p>
    </div>
  )
}

export default function ZoneRcmdtnSharePanel({
  share,
  applied,
  grdCodes,
  selection,
  onSelectZone,
  containerRef,
}: ZoneRcmdtnSharePanelProps) {
  /** `emdCd` → 행 DOM(그려진 행만). */
  const rowRefs = useRef(new Map<string, HTMLDivElement>())

  /**
   * 깃발에서 고른 행을 화면 안으로 끌어온다 — `ZoneRcmdtnResultPanel` 의 주차장 행과 같은 계산이다.
   * 🔴 의존성은 `selection` **객체 전체**다(부모가 선택마다 `seq` 를 올린다 — 같은 깃발을 다시 눌러도 돈다).
   */
  useEffect(() => {
    if (!selection) return
    const container = containerRef.current
    const row = rowRefs.current.get(selection.emdCd)
    if (!container || !row) return
    const top = row.offsetTop - container.offsetTop
    const above = top < container.scrollTop
    const below = top + row.offsetHeight > container.scrollTop + container.clientHeight
    if (above || below) container.scrollTo({ top: top - SCROLL_MARGIN, behavior: 'smooth' })
  }, [selection, containerRef])

  const { crtrVls, zones } = share
  const sggNm = applied?.sggNm ?? null
  /** 후보 수(보이는 목록 기준). 🔴 후보인지는 `rnk` 가 아니라 `rvwTrgtYn` 이다 — `rnk` 는 이제 후보 밖에도 있다. */
  const candCnt = zones.filter((z) => z.rvwTrgtYn === 'Y').length
  const unableCnt = zones.filter((z) => z.rvwGrdCd === 'UNABLE').length
  const unableNm = shareGrdNm(grdCodes, 'UNABLE')
  /** 조회 기간에 민원이 0건 — 🔴 후보 0곳과 **다른 사유**다(조치가 다르다: 기간을 옮긴다). */
  const noData = share.warnings.includes(SHARE_WARN_NO_DATA)
  /**
   * 증가율 경고 2종. 🔴 문장은 요금제 보기의 것을 **그대로** 쓴다(같은 경고 코드에 다른 문장을
   * 쓰지 않는다). 증가율을 계산하지 않으면 계수가 1 이라 "추세 미반영" 은 따로 적지 않는다.
   */
  const trendWindowShort = share.warnings.includes('TREND_WINDOW_SHORT')
  const trendBfrFallback = share.warnings.includes('TREND_BFR_FALLBACK')
  /** 목록이 그려질 때만 경고를 싣는다 — 목록이 없는데 증가율 안내만 뜨면 설명할 대상이 없다. */
  const listShown = !noData && zones.length > 0

  /** 목록의 한 행. 🔴 후보든 아니든 같은 마크업이다 — 한 목록이다. */
  const renderRow = (z: ZoneRcmdtnShareZone) => {
    const active = selection?.emdCd === z.emdCd
    return (
      <div
        key={z.emdCd}
        className={`rl__row${active ? ' is-active' : ''}`}
        role="button"
        tabIndex={0}
        aria-current={active ? true : undefined}
        ref={(el) => {
          // 언마운트되는 행은 지운다 — 남기면 이전 결과·접힌 행의 DOM 을 스크롤 대상으로 잡는다.
          if (el) rowRefs.current.set(z.emdCd, el)
          else rowRefs.current.delete(z.emdCd)
        }}
        onClick={() => onSelectZone(z.emdCd)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            onSelectZone(z.emdCd)
          }
        }}
      >
        {/*
         * 🔴 표시 순서다 — 우선순위가 아니다(아래 안내 문구). 후보 행이면 깃발의 숫자와 같다.
         * `UNABLE`(지수 없음)과 배포 전 응답의 비후보는 번호가 없어 `—` 다.
         */}
        <span className="rl__rank">{z.rnk ?? '—'}</span>
        <RowMain zone={z} crtrVls={crtrVls} unableNm={unableNm} />
      </div>
    )
  }

  return (
    <>
      <div className="sim-sec">
        {/*
         * 제목 줄 오른쪽 끝에 **검토 지수 산정 기준 InfoTip**. 배치는 요금제 순위의
         * `순위 산정 기준` 과 같다(`.sim-sec__head` · `placement="right"` — 제목 바로 옆이면 320px 팝업이
         * 360px 패널에서 잘린다). `marginBottom: 0` 은 종전 간격(제목 → 결론 상자 16 · 목록 20)을 지킨다.
         * 출처: 1~3 은 `shareScrDesc(crtrVls)`(🔴 기준값은 응답 `crtrVls` — 설정을 바꾸면 숫자가 따라간다),
         * 4 는 `SHARE_NOTE_SCR`(보고서와 공유 — 화면에서만 `※` 를 뗀다. 번호 안내 `SHARE_NOTE_RNK` 는 걷었다),
         * (🔁 2026-10-27 — `공한지를 빼면 후보` 배지를 걷으며 그 설명 줄도 걷었다.)
         * 종전 제목 밑 산출 설명(`ScrDesc`)과 하단 안내 2줄을 이리로 옮겼다.
         * 지수 정의 뒤에 **순서의 정의**(`shareTrendDesc` — 계수 범위는 응답 `crtrVls` 로 만든다)와
         * 증가율 경고 2종(요금제 순위 InfoTip 과 같은 자리·같은 문장 — 앞 `※` 만 뗀다)을 싣는다.
         */}
        <div className="sim-sec__head" style={{ marginBottom: 0 }}>
          <h3 className="sim-sec__title">{SHARE_SEC_TITLE}</h3>
          <InfoTip
            placement="right"
            label="검토 지수 산정 기준 안내"
            title="검토 지수 산정 기준"
            items={[
              ...shareScrDesc(crtrVls),
              ...shareTrendDesc(crtrVls),
              listShown &&
                trendBfrFallback &&
                stripNoteMark(noteTrendBfrFallback(crtrVls.trendDayCnt ?? null)),
              listShown && trendWindowShort && stripNoteMark(NOTE_TREND_WINDOW_SHORT),
              stripNoteMark(SHARE_NOTE_SCR),
            ]}
          />
        </div>
        {applied && <p className="sim-sec__sub">분석 기간 : {applied.periodLabel}</p>}

        {/*
         * 🔁 요약 줄(후보 수·공한지 제외 수) · 민원 비중 · 적용 조건 · "도 전체 기준" 안내 네 줄을 걷었다
         * (사용자 요청 2026-10-13). 후보 수는 목록(행의 색 점)이 말한다. 공한지를 뺐을 때의 변화는 행의 배지로만 남는다.
         */}
        {noData && (
          /* 🔴 빈 화면 대신 자료 구간을 알려 준다(이 API 응답의 `dataBgngYmd`·`dataEndYmd`). */
          <p style={CONCLUSION} role="status">
            {shareNoDataText(share.dataBgngYmd, share.dataEndYmd)}
          </p>
        )}

        {/* ⚠️ 후보 0곳은 빈 리스트가 아니라 **결론**이다 — 문장을 먼저 두고, 아래 목록(더 보기)은 그대로 둔다. */}
        {!noData && candCnt === 0 && (
          <p style={CONCLUSION}>{shareEmptyCandText(sggNm)}</p>
        )}

        {/*
         * ── 하나의 목록 ── 전 행을 쭉 그린다(기준선·더 보기 접기를 걷었다). 🔴 서버 순서 그대로다.
         * 자료 없는 기간에는 목록을 걷는다 — `UNABLE` 은 주차장 유무로 정해져 그때도 9곳이 나오는데,
         * "민원 자료가 없다" 는 안내 아래에 판정 목록이 있으면 판정한 것처럼 보인다.
         */}
        {listShown && <div className="rl">{zones.map(renderRow)}</div>}
        {/* 🔴 `UNABLE` 행의 `—` 가 고장으로 읽히지 않게 이유를 적는다(그 행이 있을 때만). */}
        {!noData && unableCnt > 0 && (
          <p style={LIST_NOTE}>{shareUnableNote(unableNm, unableCnt)}</p>
        )}
      </div>

      <ShareNotes />
    </>
  )
}
