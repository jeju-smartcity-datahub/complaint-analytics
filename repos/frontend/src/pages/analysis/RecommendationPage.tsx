import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import axios from 'axios'
import Breadcrumb from '../../components/common/Breadcrumb'
import TopbarActions from '../../components/common/TopbarActions'
import ZoneRcmdtnSettingPanel from '../../components/analysis/ZoneRcmdtnSettingPanel'
import ZoneRcmdtnResultPanel from '../../components/analysis/ZoneRcmdtnResultPanel'
import ZoneRcmdtnMap from '../../components/analysis/ZoneRcmdtnMap'
import ZoneRcmdtnLegend, { ZoneRcmdtnShareLegend } from '../../components/analysis/ZoneRcmdtnLegend'
import type { ZoneRcmdtnShareApplied } from '../../components/analysis/ZoneRcmdtnSharePanel'
import type {
  ZoneRcmdtnFeeExportCriteria,
  ZoneRcmdtnShareExportCriteria,
} from '../../lib/analysis/zoneRcmdtnExport'
import type {
  ZoneRcmdtnSelection,
  ZoneRcmdtnShareSelection,
} from '../../components/analysis/zoneRcmdtnSelection'
import {
  DEFAULT_TAB,
  DEFAULT_TRGT_TYPE_SE,
  SHARE_GRD_GROUP,
  SHARE_WARN_NO_DATA,
  drawableExclLots,
  type ZoneRcmdtnPeriodKey,
  type ZoneRcmdtnTab,
} from '../../components/analysis/zoneRcmdtnView'
import { fetchCommonCodes, type CommonCodeItem } from '../../api/commonCodes'
import {
  fetchZoneRcmdtn,
  fetchZoneRcmdtnShare,
  fetchZoneRcmdtnZones,
  type ZoneRcmdtnResult,
  type ZoneRcmdtnShareResult,
  type ZoneRcmdtnTrgtTypeSe,
  type ZoneRcmdtnZone,
} from '../../api/zoneRcmdtn'
import { formatPeriodLabel, toYmd, validateRange, type RangeError } from '../../lib/dateRange'
import type { VrblVls } from '../../api/analysisWeight'
import {
  useAnalysisCoefficient,
  type CoeffRequest,
} from '../../lib/analysis/useAnalysisCoefficient'

/*
 * 구역추천 분석 `/analysis/recommendation`
 * (요금제 적용 · 공유주차제 적용).
 *
 * 🔴 **보기(`분석 내용`)가 둘이고 답하는 질문이 다르다.**
 *   - `요금제 적용`(기본) — *"이 읍면동에서 유료화한다면 어느 주차장부터 해야 하나"*. 읍면동을
 *     추천하는 보기가 아니다. **시군구 또는 읍면동이 입력**이고(시군구는 필수),
 *     결과는 그 안의 주차장 순위다(읍면동을 비우면 시군구 안 전 읍면동의 주차장을 한 목록으로).
 *   - `공유주차제 적용` — *"주차 민원 기준으로, 공유주차제를 검토해 볼 만한 읍면동은 어디인가"*.
 *     기간만 주면 **읍면동 목록**이 나오고 지역은 입력이 아니라 **필터**다(시군구 하나).
 * 아래 서술은 따로 적지 않는 한 `요금제 적용` 보기의 것이다.
 *
 * 화면 구성(**시안 레이아웃 전면 정렬**)
 *   `content content--analysis > section.sim-stage`
 *     ├ `ZoneRcmdtnMap`          🔴 지도는 스테이지의 **직계 자식**(아래 주석)
 *     ├ `ZoneRcmdtnSettingPanel` 좌측 오버레이 — 분석 내용 · 지역 · 유형 · 기간 · 실행
 *     ├ `ZoneRcmdtnResultPanel`  우측 — 요약(적합도 배지 · 필수 문구 ③④) + `rl` 순위 리스트
 *     └ `ZoneRcmdtnLegend`       우하단(실제 자리는 좌하단) — 🔁 축은 새 방식(`anlsMthd === 'RVW_SCR'`)이면
 *                                **검토 등급**(추세 반영 전 점수 80/60/40), 구 응답이면 **우선순위**다(종전 증가율)
 *
 * 🔁 **상단 탭 2개 · 표 · 3개월 차트가 사라졌다.** 번들을 다시 뜯어 보니 시안의 5열 표와
 * 차트는 화면이 아니라 **내보내기 PDF/Word 의 `sections` 정의**였고, 화면에는 지도 전면 위에
 * 좌측 설정 카드와 우측 `rl` 리스트만 있다. 탭은 설정 패널의 `분석 내용` 셀렉트로 들어갔다.
 * 🔁 그 문서는 결과 패널의 `내보내기 > PDF/Word` 가 만든다 — 이 프로젝트에서 내보내기는
 * **"내보내기 1회 = 보고서 1건 저장"**이고, 조건은 **조회에 적용된 값**을 이 페이지가 넘긴다
 * (`feeExportCriteria`·`shareExportCriteria`).
 *
 * 🔁 **공유주차제 적용 보기** — 종전(4207)에는 `/zones` 응답에서 요금제 부적합
 * (`rcmdtnTypeCd='SHARE'`) 동을 번호·핀 없이 나열했다. 이제 **`/share` 응답**으로 공유주차 검토 후보
 * 읍면동을 목록·깃발과 함께 그린다.
 *   - 🔁 **2026-10-13 사용자 요청 — 표시 등급을 지수 3단계로, 행의 등급·주의 배지와 시군구명을 걷었다.**
 *     등급은 `매우 높음`(지수 `crtrVls.coreMltp` 이상) · `높음`(1 이상) · `보통`(1 미만)이고 **검토 지수로**
 *     정한다(`zoneRcmdtnView.shareLvl`). 목록의 색 점 · 깃발 색 · 범례 3줄 · 팝업 배지가 같은 표를 읽는다.
 *     공통코드 `SHRN_RVW_GRD_CD` 는 이제 **`UNABLE` 라벨 하나**에만 쓴다(지수가 없는 행의 사유).
 *   - 🔴 **번호는 표시 순서이며 우선순위가 아니다** — 서버가 조회 결과(시군구 필터 적용 후) 안에서
 *     1 부터 매기고, 화면은 그 값을 그대로 그린다(다시 세지 않는다). 화면도 "순위"·"추천" 이라고 쓰지 않는다(결과 이름은 "검토 후보" 다).
 *   - 🔴 **공유주차제의 효과는 여전히 산출하지 않는다** — 부설주차장 개방 물량 데이터·산식이 없다.
 *     공유할 부설주차장이 있는지·어느 시간대인지·읍면동 안의 어디인지도 답하지 못하며, 그 사실은
 *     결과 패널의 필수 문구 5개가 화면에 적는다.
 *   - 🔴 **`/share` 는 `실행` 버튼을 눌렀을 때만 부른다**(`runShare`). 호출마다 실행 이력이 남아
 *     마운트·보기 전환·조건 변경으로는 요청이 나가지 않는다 — effect 에서 부르지 않는다.
 *   - 🔁 **공유주차제 보기의 읍면동 칸을 걷었다**(2026-10-13) — 결과가 읍면동 **목록**이라 읍면동
 *     하나로 좁히면 1행짜리 목록이 된다. 그 보기는 **시군구만** 고르고 `runShare` 는 `sggNm` 만 보낸다.
 *     🔴 `emdCd` 상태는 **요금제 보기 전용**이다 — `runShare` 가 읽지 않는다.
 *
 * 🔴 **분석 내용을 바꾸면 화면을 그 보기의 첫 진입 상태로 되돌린다**(2026-10-13 · `changeTab`).
 * 두 보기는 입력(읍면동 하나 ↔ 시군구 필터)과 결과 단위(주차장 ↔ 읍면동)가 달라, 한쪽의 조건·결과가
 * 다른 쪽에 남으면 *"이 조건으로 이 보기를 실행한 결과"* 로 오독된다. **전환 = 초기화**이므로 보기
 * 사이로 넘어가는 조건이 없다.
 *   - 비우는 것: 지역 선택 · 기간(세그먼트·직접설정 날짜) · 두 보기의 결과·적용 조건·오류·실행 중
 *     표시 · 선택 · 패널 닫힘 상태 · 지도의 핀·경계·깃발·팝업·시야.
 *   - 유지하는 것: 공통코드(라벨)와 읍면동 참고 목록 — 보기와 무관하게 받아 둔 마스터라 다시 받지
 *     않는다. ⚠️ 단 참고 목록은 **적용 조건**을 따라가므로, 요금제를 실행한 적이 있으면 그 조건이
 *     초기값으로 돌아가며 `/zones` 가 **한 번** 다시 나간다(이 창구는 실행 이력을 남기지 않는다).
 *   - 🔴 **전환은 조회를 하지 않는다** — `/share` 와 요금제 상세는 여전히 `실행` 버튼에서만 나간다.
 *   - 🔴 `useEffect(…, [tab])` 로 뒤늦게 지우지 않는다 — 한 프레임 동안 옛 결과가 새 보기에 비친다.
 *
 * 🔴 **분석 내용(`tab`)은 보기 모드이고 `rcmdtnTypeCd` 는 판정 결과다.** 요금제 쪽에서 `SHARE` 동도
 * 고를 수 있어야 하므로(그때 순위는 **참고용**) 보기 선택이 읍면동 선택을 제한하지 않는다.
 * ⚠️ 그 `SHARE` 배지(요금제로는 자리가 모자람)와 공유주차제 보기의 검토 등급은 **다른 기준**이다.
 *
 * 🔴 **선택 상태(`selection`·`shareSelection`)의 주인은 이 페이지 하나다.** 리스트와 지도가 각자
 * 상태를 들면 서로 다른 대상을 가리킨다. 두 보기는 단위가 달라(주차장 ↔ 읍면동) 선택도 따로 든다.
 *
 * 🔴 **효과(민원이 몇 건 줄어드는지)를 어디에도 표시하지 않는다**(요약·리스트·팝업 전부). E0 가
 * 읍면동 단위라 주차장 단위 효과 산식이 없고, `SHARE` 는 *"요금제로 효과를 내기 어렵다"* 는 뜻일 뿐
 * 공유주차제의 효과를 분석한 결과가 아니다. (범례는 `우선순위` 로 바뀌어 `감소` 라벨이 없다.)
 *
 * 🆕 **분석 계수 일시 조정** — 설정 패널 제목 줄의 버튼. 보기마다 스코프가 다르다:
 *   - 요금제: `RECOMMEND_FEE` 10개 → `/zones`·상세·요금제 보고서. 🔴 `/zones` 는 `applied` effect 로 다시
 *     받으므로 `vrblVls` 를 `AppliedCriteria` 에 싣고 `buildQuery` 한 곳이 두 창구에 같은 값을 얹는다.
 *   - 공유주차제: `RECOMMEND_SHARE` 4개 → `/share`·공유주차제 보고서(`shareApplied.vrblVls`).
 *   - `적용`·`초기화` 는 그 보기에 결과가 있으면 재분석한다. 보고서는 마지막으로 성공한 실행의 값이다.
 *   - 🔴 분석 내용을 바꾸면 계수 상태(초안·적용·마지막 실행값)도 기본값으로 돌아간다(`changeTab` → `resetAll`).
 *
 * 라우팅·메뉴는 이미 있다 — `navConfig` `/analysis/recommendation` · `TB_MENU` **M0304** ·
 * `RequireMenu` 가 감싸므로 이 파일에 인가 코드가 없다.
 */

/* ── 조회 구간 ────────────────────────────────────────────
 *
 * **민원 창을 화면에서 고른다**. 종전에는 분석 정본 구간으로 **고정**했지만
 * 기간 칸이 생겨 세그먼트(최근 1·3·6개월)와 `직접설정` 을 받는다.
 *
 * **서버의 민원 기간 기준 열이 `dclr_ymd` 에서 `ocrn_dt`(발생일시) 로 바뀌었다.**
 * 그래서 아래 구간이 **+1년 밀렸다**(`ocrn_dt = dclr_ymd + 365일` · 행 집합은 동일). 파라미터
 * 이름·형식(`bgngYmd`=`yyyyMMdd`)은 그대로다.
 *
 * **첫 진입 기본값은 `최근1개월`이다**(집중구역·생활권 화면과 같다). 종전에는 문서의
 * 실측값과 대조하려고 고정 구간(`2026-01-01~2026-08-31`, `직접설정`)으로 열었다.
 *
 * 🔴 **단속 창은 보내지 않는다** — 서버 기본값(20개월 `20250101`~`20260831`)이 분석 정본과 같다.
 * 🔴 민원 창과 단속 창은 **일부러 다른 구간**이다(보유 구간이 다르다) — 민원 창을 단속에 그대로
 * 쓰면 이도이동 단속 0건 → `E0=0` → **전 읍면동이 판정 불가**가 된다. 여기서 한 번 더 적으면
 * 서버 기본값이 바뀔 때 두 곳이 갈린다.
 */
/** 첫 진입 기간 — 위 근거로 `최근1개월`이다. */
const DEFAULT_PERIOD: ZoneRcmdtnPeriodKey = '1'

/** 조회 구간 (yyyy-MM-dd). 서버로 나갈 때 `YYYYMMDD` 로 바꾼다. */
interface DateRange {
  from: string
  to: string
}

/** `yyyy-MM-dd` → `YYYYMMDD`. API 파라미터 형식이며 어긋나면 400 이다. */
function toCompactYmd(ymd: string): string {
  return ymd.replace(/-/g, '')
}

/**
 * 기간 세그먼트 → 실제 조회 구간. 기준 시각은 **오늘**이다(집중구역 화면과 같은 계산).
 *
 * ⚠️ `setMonth(getMonth() - n)` 만 쓰면 **말일에 넘친다** — 3/31 에서 1개월을 빼면 2/31 이 없어
 * 3/3 으로 튕겨 구간이 줄어든다. 대상 월의 말일로 clamp 해서 3/31 → 2/28 이 되게 한다.
 */
function recentMonths(months: number): DateRange {
  const today = new Date()
  const day = today.getDate()
  const start = new Date(today)
  start.setDate(1)
  start.setMonth(start.getMonth() - months)
  const lastDayOfStartMonth = new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate()
  start.setDate(Math.min(day, lastDayOfStartMonth))
  return { from: toYmd(start), to: toYmd(today) }
}

/** 첫 진입 구간. 직접설정 칸과 읍면동 참고 목록(`/zones`)이 같은 값을 읽어 세그먼트와 어긋나지 않는다. */
const DEFAULT_RANGE = recentMonths(Number(DEFAULT_PERIOD))

/**
 * 실패 문구. 서버 `message` 는 `server.error.include-message` 설정에 따라 없을 수 있어 기본 문구로 받는다
 * (집중구역 화면 `HotspotPage.errorText` 와 같은 관례 — `strict: true` 라 `unknown` 을 먼저 좁힌다).
 */
function errorText(e: unknown, fallback: string): string {
  const msg = axios.isAxiosError(e)
    ? (e.response?.data as { message?: string } | undefined)?.message
    : undefined
  return msg ?? fallback
}

/*
 * 머리말 제목·부제 — 🔁 **시안 문구 그대로**다(사용자 요청 2026-10-13).
 * 분석 내용(요금제/공유주차제)에 따라 바꾸지 않는다 — 시안의 머리말은 보기와 무관하게 하나다.
 * 보기별로 "이 화면이 답하는 것·답하지 못하는 것"은 머리말이 아니라 결과 패널의 안내 문구가 진다.
 */
const PAGE_TITLE = '요금제·공유주차제 적용 구역 추천'
const PAGE_SUB = '민원 증가 추세를 분석하여 최적 구역 자동 추천'

/**
 * 시군구를 고르기 전 안내 — 🔴 빈 화면이 아니라 **정상 시작점**이다.
 * 시군구만 골라도 실행된다(읍면동은 선택).
 */
const NOTE_PICK_EMD =
  '분석할 시군구를 선택하고 실행하세요. 읍면동을 고르면 그 동만, 전체면 시군구 안 모든 동의 주차장 순위를 봅니다.'

/**
 * 적용된 조회 조건 — **읍면동 참고 목록(`/zones`)이 읽는 값**이다.
 *
 * 🔴 설정 패널의 **현재 입력이 아니라 마지막으로 실행한 조건**이다. 목록 라벨의
 * `요금제적용 100%` 는 그 창·유형으로 계산된 값이라, 입력을 건드릴 때마다 다시 받으면
 * (날짜 한 글자마다) 호출이 폭발하고 화면의 결과와도 어긋난다.
 */
interface AppliedCriteria {
  bgngYmd: string
  endYmd: string
  trgtTypeSe: ZoneRcmdtnTrgtTypeSe
  /**
   * **조회에 적용된** 읍면동코드. `''` 이면 시군구 모드다.
   * 요청은 `fetchZoneRcmdtn` 이 `emdCd` 우선으로 하나만 싣는다(배타 규칙 — 여기서 고르지 않는다).
   */
  emdCd: string
  /** **조회에 적용된** 시군구명. 요금제 보기의 필수 입력이다. */
  sggNm: string
  /**
   * **조회에 적용된** 요금제 계수 일시 조정(`RECOMMEND_FEE`). `undefined` = 설정값.
   * 🔴 `/zones`·상세가 `buildQuery` 로 같은 값을 싣는다 — 다르면 판정이 갈린다.
   */
  vrblVls: VrblVls | undefined
}

/**
 * 🔴 **첫 진입 상태의 정본**이다 — 첫 렌더(`useState`)와 분석 내용 전환의 초기화(`changeTab`)가
 * **같은 상수**를 읽는다. 두 곳에 따로 적으면 한쪽만 고쳐져 "전환하면 첫 진입과 다른 화면" 이 된다.
 * ⚠️ 여기 없는 것(결과·오류·선택 등)의 첫 상태는 전부 **비어 있음**(`null`·`false`)이다.
 */
const INITIAL = {
  sggNm: '',
  emdCd: '',
  period: DEFAULT_PERIOD,
  // 직접설정 칸은 기본 기간으로 채워 둔다 — 빈 칸으로 열면 실행 버튼이 막힌 채 시작한다.
  customFrom: DEFAULT_RANGE.from,
  customTo: DEFAULT_RANGE.to,
  panelOpen: true,
  /**
   * 첫 진입에는 이 조건으로 `/zones` 를 한 번 받는다.
   * 🔴 **객체 하나를 그대로 쓴다** — 한 번도 실행하지 않은 채 보기를 바꾸면 `applied` 가 같은
   * 참조라 참고 목록 effect 가 다시 돌지 않는다(불필요한 재조회가 없다).
   */
  applied: {
    bgngYmd: toCompactYmd(DEFAULT_RANGE.from),
    endYmd: toCompactYmd(DEFAULT_RANGE.to),
    trgtTypeSe: DEFAULT_TRGT_TYPE_SE,
    emdCd: '',
    sggNm: '',
    vrblVls: undefined,
  } satisfies AppliedCriteria,
}

export default function RecommendationPage() {
  /**
   * 공통코드 `SHRN_RVW_GRD_CD` — 🔁 공유주차제 보기에서 **`UNABLE` 의 라벨**에만 쓴다
   * (화면의 검토 등급 이름은 공통코드가 아니라 `zoneRcmdtnView.SHARE_LVLS` 다).
   */
  const [shareGrdCodes, setShareGrdCodes] = useState<CommonCodeItem[]>([])

  // ── 읍면동 참고 목록 (select 를 채운다) ──
  const [zones, setZones] = useState<ZoneRcmdtnZone[]>([])
  const [zonesLoading, setZonesLoading] = useState(true)
  const [zonesError, setZonesError] = useState<string | null>(null)
  /** `다시 시도` 를 누르면 값이 바뀌어 목록 조회 effect 가 다시 돈다. */
  const [reloadZonesKey, setReloadZonesKey] = useState(0)

  /**
   * 분석 내용 — 🔴 **`요금제 적용` 이 기본**이다(주력이 주차장 순위다).
   * 🔴 **`setTab` 을 직접 부르지 않는다** — 바꾸는 창구는 아래 `changeTab` 하나이고, 그 함수가
   *    화면을 첫 진입 상태로 되돌린다(전환 = 초기화).
   * ⚠️ 바꿔도 조회를 하지 않는다 — 전환이 호출을 유발하면 `TB_ANLS_EXCN` 이 왕복마다 1행씩 늘어난다.
   */
  const [tab, setTab] = useState<ZoneRcmdtnTab>(DEFAULT_TAB)

  // ── 입력 ──
  /**
   * 요금제 보기에서도 시군구가 **요청 파라미터**다. 읍면동을 고르면 `emdCd` 만, 비우면
   * `sggNm` 만 나간다(배타 규칙은 `fetchZoneRcmdtn` 한 곳 · 🔴 시군구는 필수). 읍면동 select 을
   * 좁히는 역할도 그대로다(`/zones` 는 전 시군구를 한 번에 준다).
   * ⚠️ 공유주차제 보기에서는 다르다 — 고른 시군구가 `sggNm` **필터로 나간다**(비우면 도 전체).
   * 🔁 state 는 하나지만 **보기 사이로 값이 넘어가지 않는다** — 분석 내용을 바꾸면 `changeTab` 이
   * 비운다(기간 칸도 같다).
   */
  const [sggNm, setSggNm] = useState(INITIAL.sggNm)
  /** 🔴 **요금제 보기 전용**이다 — 공유주차제 보기에는 읍면동 칸이 없고 `runShare` 도 읽지 않는다. */
  const [emdCd, setEmdCd] = useState(INITIAL.emdCd)
  const [period, setPeriod] = useState<ZoneRcmdtnPeriodKey>(INITIAL.period)
  const [customFrom, setCustomFrom] = useState(INITIAL.customFrom)
  const [customTo, setCustomTo] = useState(INITIAL.customTo)

  // ── 상세 결과 ──
  const [result, setResult] = useState<ZoneRcmdtnResult | null>(null)
  /** `result` 를 만든 실행의 기간 라벨. 결과와 같은 지점에서만 갱신한다(지역 없는 실행은 `result` 를 건드리지 않는다). */
  const [ranPeriodLabel, setRanPeriodLabel] = useState('')
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** 결과 패널 열림 여부. `X` 로 닫아도 **다시 실행하면 열린다**. */
  const [panelOpen, setPanelOpen] = useState(INITIAL.panelOpen)

  /** 마지막으로 실행한 조건. 첫 진입에는 기본값으로 `/zones` 를 한 번 받는다. */
  const [applied, setApplied] = useState<AppliedCriteria>(INITIAL.applied)

  /**
   * 분석 내용을 바꿀 때마다 1씩 오르는 **초기화 신호**다(`selection.seq` 와 같은 장치).
   * 지도는 이 값이 바뀌면 팝업을 닫고 기본 시야로 돌아가고(인스턴스는 다시 만들지 않는다),
   * 결과 패널은 이 값을 `key` 로 받아 제 안의 상태(내보내기 안내·잠금 · 스크롤)까지 새로 시작한다.
   */
  const [resetSeq, setResetSeq] = useState(0)

  /**
   * 🆕 분석 계수 일시 조정. 초안·적용·마지막 실행값은 훅이 든다(React 상태만).
   * 보기별 스코프 — 요금제 `RECOMMEND_FEE` · 공유주차제 `RECOMMEND_SHARE`.
   */
  const coeff = useAnalysisCoefficient('RECOMMEND')
  const coeffScope = tab === 'FEE' ? 'RECOMMEND_FEE' : 'RECOMMEND_SHARE'

  /**
   * 🔴 **시군구를 바꾸면 읍면동 선택을 비운다.** 비우지 않으면 다른 시군구의 동이 고른 채로 남아
   * 셀렉트에 보이지 않는 값으로 실행된다(집중구역 `changeSgg` 와 같은 이유).
   * ⚠️ 공유주차제 보기에서 시군구를 바꿔도 이 함수를 탄다 — 그 보기에서 `emdCd` 는 이미 비어 있어
   * (분석 내용을 바꿀 때 `changeTab` 이 비운다) 두 번째 줄은 아무 일도 하지 않는다.
   * ⚠️ 조회는 하지 않는다 — 조회 방아쇠는 `btn-run` 하나다.
   */
  const changeSgg = useCallback((next: string) => {
    setSggNm(next)
    setEmdCd('')
  }, [])

  /**
   * (사용자 요청 2026-10-26) — **요금제 보기는 첫 시군구를 미리 고른다.** 설정 패널에서
   * `제주도 전체`(= 실행 잠김) 옵션을 뺐으므로 빈 값으로 두면 고를 수 없는 상태가 보인다.
   * 첫 진입(목록 도착)과 보기 전환(`changeTab` 이 비운 뒤) 두 경우를 이 effect 하나가 맡는다.
   * `zones` 는 읍면동코드 순(목록 effect 가 정렬)이라 첫 행의 시군구가 제주시(50110)다. ⚠️ 조회는 하지 않는다 — 방아쇠는 `btn-run` 하나다.
   * 공유주차제 보기는 빈 값이 "도 전체" 라 건드리지 않는다.
   */
  useEffect(() => {
    if (tab === 'FEE' && !sggNm && zones.length > 0) setSggNm(zones[0].sggNm)
  }, [tab, sggNm, zones])

  /** 현재 선택(리스트 ↔ 핀). 출처와 무관하게 선택마다 `seq` 를 올려 "새 요청" 으로 전달한다. */
  const [selection, setSelection] = useState<ZoneRcmdtnSelection | null>(null)
  const selectLot = useCallback((pkltId: number) => {
    setSelection((prev) => ({ pkltId, seq: (prev?.seq ?? 0) + 1 }))
  }, [])

  // ── 공유주차제 적용 보기 ──
  /**
   * `/share` 응답. 🔴 **`runShare`(실행 버튼)만 채운다** — 마운트·보기 전환·조건 변경으로는
   * 채워지지 않는다(호출마다 실행 이력이 남는다). 그 전에는 `null` 이고 결과 패널이 안내를 띄운다.
   */
  const [share, setShare] = useState<ZoneRcmdtnShareResult | null>(null)
  const [shareRunning, setShareRunning] = useState(false)
  const [shareError, setShareError] = useState<string | null>(null)
  /** `share` 를 만든 조건 — 결과 패널이 "어느 기간·지역의 결과인가" 를 적는 데 쓴다. */
  const [shareApplied, setShareApplied] = useState<ZoneRcmdtnShareApplied | null>(null)
  /**
   * 공유주차제 보기의 선택(행 ↔ 깃발). 요금제 보기의 `selection` 과 단위가 달라 따로 든다.
   * 🔁 목록이 하나로 합쳐져 **후보가 아닌 행도 고를 수 있다** — 그 행은 깃발이 없어 행 강조만 된다.
   */
  const [shareSelection, setShareSelection] = useState<ZoneRcmdtnShareSelection | null>(null)
  const selectShareZone = useCallback((code: string) => {
    setShareSelection((prev) => ({ emdCd: code, seq: (prev?.seq ?? 0) + 1 }))
  }, [])

  /*
   * 공통코드는 마운트 때 한 번만 받는다 — 라벨 출처라 실패하면 코드값(`UNABLE`)이 그대로 보인다.
   * ⚠️ 실행 이력과 무관한 조회(`/common-codes`)라 마운트 시 받아도 된다.
   * 추천유형(`ZONE_RCMDTN_TYPE_CD`) 조회는 걷었다(그 라벨을 띄우던 지역 요약이 화면에서 빠졌다).
   */
  useEffect(() => {
    let cancelled = false
    fetchCommonCodes(SHARE_GRD_GROUP)
      .then((list) => {
        if (!cancelled) setShareGrdCodes(list)
      })
      .catch(() => {
        if (!cancelled) setShareGrdCodes([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  /*
   * 읍면동 참고 목록. **적용된 조건이 바뀌면 다시 받는다** — 가능 면수·충족률·판정이 창·유형으로
   * 달라져(이도이동 397면 ↔ 1,128면) select 라벨의 `요금제적용 100%` 가 옛 값으로 남으면 거짓이다.
   *
   * ⚠️ 이 창구는 **실행 이력을 남기지 않는다**(`/zones`) — 그래서 마운트 시 자동 조회해도 된다.
   */
  useEffect(() => {
    let cancelled = false
    setZonesLoading(true)
    setZonesError(null)
    fetchZoneRcmdtnZones(applied)
      .then((list) => {
        if (cancelled) return
        /*
         * (사용자 요청 2026-10-26) — 시군구·읍면동 select 을 **코드 오름차순**으로 보인다.
         * 서버는 이름순(`sgg_nm, emd_nm`)이라 서귀포시가 먼저 온다. 읍면동코드 앞 5자리가 시군구 코드라
         * 이 정렬 하나로 시군구(제주시 50110 → 서귀포시 50130)와 그 안의 읍면동이 함께 코드순이 된다.
         * 🔴 API 응답 순서는 바꾸지 않는다 — 보고서·다른 화면이 같은 창구를 읽을 수 있다.
         */
        setZones([...list].sort((a, b) => a.emdCd.localeCompare(b.emdCd)))
        setZonesLoading(false)
      })
      .catch((e) => {
        if (cancelled) return
        // 조용히 비우지 않는다 — 목록이 비면 "읍면동 설정이 안 된다" 로 보이는데 그 대부분은
        // 백엔드 미기동이라 원인을 알려 줘야 한다(집중구역 화면의 지역 목록과 같은 방침).
        setZones([])
        setZonesLoading(false)
        setZonesError(
          errorText(e, '읍면동 목록을 불러오지 못했습니다. 백엔드가 실행 중인지 확인해 주세요.'),
        )
      })
    return () => {
      cancelled = true
    }
  }, [applied, reloadZonesKey])

  /** 실행 시 서버로 나갈 구간. 세그먼트면 오늘 기준으로 펴고, 직접설정이면 입력값 그대로다. */
  const range = useMemo<DateRange>(
    () => (period === 'custom' ? { from: customFrom, to: customTo } : recentMonths(Number(period))),
    [period, customFrom, customTo],
  )

  /*
   * 서버가 400 을 내는 조건(빈 날짜·역전·최대 구간 초과)은 버튼에서 먼저 끊는다.
   * 규칙은 화면마다 두지 않고 `lib/dateRange` 하나만 쓴다 — 갈리면 화면마다 다르게 막힌다
   * (백엔드 상한은 한 창당 36개월이라 이 검증을 통과하면 그쪽에도 걸리지 않는다).
   */
  const rangeError: RangeError | null = validateRange(range.from, range.to)

  /**
   * 🔴 **요금제 보기에서 상세 조회를 부르는 유일한 경로**다 — 호출 1회가 **실행 이력 1행**
   * (`TB_ANLS_EXCN` + `TB_ANLS_RSLT`)이라 방아쇠를 하나로 묶는다.
   *
   * 공유주차제 보기의 `실행` 은 이 함수가 아니라 아래 `runShare` 로 간다
   * (종전에는 그 보기에서 읍면동 없이 실행하면 여기서 `/zones` 만 다시 받았다).
   *
   * 🔴 늦게 도착한 응답이 최신 실행을 덮지 않게 요청마다 번호를 붙인다.
   *
   * `req` 는 이번 실행의 요금제 계수(기본 = 적용값). `applied.vrblVls` 로 실어 `/zones` 도 같은 값으로
   * 다시 받는다. 성공했을 때만 마지막 실행값으로 남긴다(`markRan` — 실패·늦은 응답은 남기지 않는다).
   * 🔴 패널에는 인자 없이 부르는 화살표 함수로 넘긴다 — 클릭 이벤트가 `req` 로 들어오지 않게.
   */
  const reqSeq = useRef(0)
  const runAnalysis = async (req: CoeffRequest = coeff.appliedRequest('RECOMMEND_FEE')) => {
    if (rangeError) return

    const criteria: AppliedCriteria = {
      bgngYmd: toCompactYmd(range.from),
      endYmd: toCompactYmd(range.to),
      // 🔁 화면에서 고르지 않는다 — `WIDE` 고정(기획 결정 2026-10-12). 상수 주석에 되돌릴 때의 대가가 있다.
      trgtTypeSe: DEFAULT_TRGT_TYPE_SE,
      emdCd,
      sggNm,
      vrblVls: req.vrblVls,
    }
    setApplied(criteria)
    // 시군구가 입력이다(읍면동은 선택 — 비우면 시군구 모드). 도 전체 실행은 열지 않는다.
    if (!sggNm) return

    const periodLabel = formatPeriodLabel(period, range)
    const seq = ++reqSeq.current
    setRunning(true)
    setError(null)
    // 직전 결과를 비운다 — 남겨 두면 조회 중에 이전 조건의 리스트·핀이 그대로 보인다.
    setResult(null)
    /*
     * 선택도 함께 푼다. 유형 범위를 바꾸면 같은 1위가 **다른 주차장**이고, 동을 바꾸면 그 주차장이
     * 아예 없다 — 남겨 두면 새 결과의 엉뚱한 행이 선택된 채로 열린다.
     */
    setSelection(null)

    try {
      // 🔴 `{ emdCd, ...criteria }` 로 펼치지 않는다 — 배타 규칙(`emdCd` 우선)은 `fetchZoneRcmdtn` 한 곳이다.
      const res = await fetchZoneRcmdtn(criteria)
      if (seq !== reqSeq.current) return
      coeff.markRan(req)
      setResult(res)
      setRanPeriodLabel(periodLabel)
      setRunning(false)
      // 닫아 둔 패널을 다시 연다 — 실행했는데 결과가 어디에도 안 보이는 상태를 만들지 않는다.
      setPanelOpen(true)
    } catch (e) {
      if (seq !== reqSeq.current) return
      // 입력값은 그대로 두고 문구만 띄운다 — 사용자가 고른 조건을 날리지 않는다.
      setError(errorText(e, '구역추천 분석에 실패했습니다. 잠시 후 다시 시도해 주세요.'))
      setRunning(false)
    }
  }

  /**
   * 🔴 **`/share` 를 부르는 유일한 경로**다 — 설정 패널 `실행` 버튼의 클릭 핸들러에서만
   * 온다. 호출 1회가 실행 이력 1행이라 effect 로 옮기지 않는다(StrictMode 이중 실행·보기 왕복·
   * 날짜 한 글자마다 이력이 쌓인다).
   *
   * 지역은 **필터**다 — 시군구를 골랐으면 `sggNm` 을 싣고 비면 도 전체다.
   * 🔴 **`emdCd` 를 싣지 않는다**(🔁 읍면동 칸을 걷었다). 이 함수는 `emdCd` 상태를 **읽지 않는다**.
   *
   * 🔴 늦게 도착한 응답이 최신 실행을 덮지 않게 요청마다 번호를 붙인다(위 `reqSeq` 와 따로 센다 —
   * 두 보기의 조회가 서로를 무효로 만들지 않게).
   *
   * `req` 는 이번 실행의 공유주차제 계수(기본 = 적용값). 결과와 같은 지점에서 `shareApplied.vrblVls`
   * 로 굳혀 보고서가 그 값을 쓴다(실패·늦은 응답은 남기지 않는다).
   */
  const shareReqSeq = useRef(0)
  const runShare = async (req: CoeffRequest = coeff.appliedRequest('RECOMMEND_SHARE')) => {
    if (rangeError) return

    const bgngYmd = toCompactYmd(range.from)
    const endYmd = toCompactYmd(range.to)
    // 요청에 실은 값과 결과 패널이 적는 값이 **같은 변수**다 — 둘이 어긋날 수 없다.
    const applied: ZoneRcmdtnShareApplied = {
      bgngYmd,
      endYmd,
      sggNm: sggNm || null,
      periodLabel: formatPeriodLabel(period, range),
      vrblVls: req.vrblVls,
    }

    const seq = ++shareReqSeq.current
    setShareRunning(true)
    setShareError(null)
    // 직전 결과·선택을 비운다 — 남기면 조회 중에 이전 조건의 목록·깃발이 그대로 보인다.
    setShare(null)
    setShareSelection(null)
    // 닫아 둔 패널을 다시 연다 — 실행했는데 진행 상태가 어디에도 안 보이는 일을 만들지 않는다.
    setPanelOpen(true)

    try {
      const res = await fetchZoneRcmdtnShare({
        bgngYmd,
        endYmd,
        ...(sggNm ? { sggNm } : {}),
        vrblVls: req.vrblVls,
      })
      if (seq !== shareReqSeq.current) return
      coeff.markRan(req)
      setShare(res)
      setShareApplied(applied)
      setShareRunning(false)
    } catch (e) {
      if (seq !== shareReqSeq.current) return
      /*
       * 400(형식·역전·36개월 초과·없는 지역)은 서버가 본문에 사유를 싣지 않는다(`message` 없음 ·
       * 2026-10-13 실측). 상태코드로 원인을 단정하지 않고 요금제 보기와 같은 방식으로 받는다 —
       * 서버 문구가 있으면 그것을, 없으면 아래 문장을 띄운다. 입력값은 그대로 둔다.
       */
      setShareError(
        errorText(
          e,
          '공유주차 검토 후보 분석에 실패했습니다. 분석 기간·지역을 확인하고 다시 시도해 주세요.',
        ),
      )
      setShareRunning(false)
    }
  }

  /**
   * 🔴 **분석 내용을 바꾸는 유일한 창구**이고, 바뀌는 순간 화면을 그 보기의 **첫 진입 상태**로
   * 되돌린다(머리 주석 "전환 = 초기화"). 같은 값을 다시 고르면 아무것도 하지 않는다.
   *
   * 🔴 **여기서 한 번에 지운다 — `tab` 을 보는 effect 로 미루지 않는다.** `setTab` 과 같은 배치에
   * 묶여야 새 보기의 **첫 렌더**가 이미 빈 화면이다(effect 는 그 렌더 뒤에 돌아 옛 결과가 한 프레임
   * 비치고, StrictMode 에서는 두 번 돈다).
   *
   * 🔴 **조회를 하지 않는다.** `/share`·요금제 상세는 호출마다 실행 이력이 남아 방아쇠가 `실행`
   * 버튼 하나다. 여기서 나갈 수 있는 요청은 `/zones` 하나뿐이고(적용 조건이 초기값으로 돌아갈 때
   * 참고 목록 effect 가 한 번 다시 받는다 — 이력을 남기지 않는 창구다), 실행한 적이 없으면 그것도 없다.
   *
   * 🔴 **진행 중이던 조회를 버린다** — 두 요청 번호를 올려, 전환 뒤에 도착한 응답이 방금 비운
   * 화면을 다시 채우지 못하게 한다(`runAnalysis`·`runShare` 의 `seq !== …current` 가 걸러 낸다).
   * 그 요청의 `finally` 가 없으므로 실행 중 표시도 여기서 직접 내린다.
   *
   * ⚠️ 공통코드와 읍면동 참고 목록(`zones`·그 로딩/오류)은 건드리지 않는다 — 보기와 무관한 마스터다.
   *
   * **계수 상태(초안·적용·마지막 실행값)도 함께 기본값으로** 되돌린다(`resetAll`). 남기면
   * 결과는 비었는데 배너만 조정 상태로 남고 `/zones` 와도 갈린다. `setApplied(INITIAL.applied)` 는 그대로다 —
   * 그 초기값의 `vrblVls` 가 `undefined` 라 `/zones` 도 설정값으로 돌아간다(실행한 적이 없으면 같은 참조라 재조회 없음).
   */
  const changeTab = (next: ZoneRcmdtnTab) => {
    if (next === tab) return
    reqSeq.current += 1
    shareReqSeq.current += 1

    setTab(next)
    // 입력 조건 — 기간 검증 오류(`rangeError`)는 이 값들에서 계산되므로 함께 사라진다.
    setSggNm(INITIAL.sggNm)
    setEmdCd(INITIAL.emdCd)
    setPeriod(INITIAL.period)
    setCustomFrom(INITIAL.customFrom)
    setCustomTo(INITIAL.customTo)
    // 요금제 보기의 결과 — 핀·읍면동 경계·범례는 `result` 를 따라 걷힌다.
    setResult(null)
    setRunning(false)
    setError(null)
    setSelection(null)
    setApplied(INITIAL.applied)
    // 공유주차제 보기의 결과 — 깃발·범례는 `share` 를 따라 걷힌다.
    setShare(null)
    setShareRunning(false)
    setShareError(null)
    setShareApplied(null)
    setShareSelection(null)
    // 닫아 둔 패널도 첫 상태로 — 다음 실행 결과가 닫힌 채로 오지 않게.
    setPanelOpen(INITIAL.panelOpen)
    // 분석 계수 — 보기 사이로 값이 넘어가지 않는다.
    coeff.resetAll()
    // 지도 시야·팝업과 결과 패널 내부 상태는 이 신호로 되돌린다.
    setResetSeq((n) => n + 1)
  }

  /**
   * 결과 패널을 띄울 조건.
   * - 요금제: 결과가 있어야 연다(진입 직후에는 설정 패널의 안내가 할 말을 다 한다).
   * - 공유주차제: 🔁 **요금제와 같다**(2026-10-13 사용자 요청) — `구역 추천 실행` 을 눌러 결과가 온
   *   뒤에만 연다. 실행 전·실행 중에는 패널이 없다(후보 0곳·자료 없는 기간도 응답이 있으므로 연다).
   */
  /**
   * 계수 `적용` — 그 보기에 결과가 있으면 확정된 요청으로 재분석한다(없으면 값만 남기고 다음 `실행` 이 쓴다).
   * 🔴 `setState` 직후 `appliedRequest` 는 이전 값이라 훅이 돌려준 요청을 그대로 넘긴다.
   * ⚠️ 재분석은 실행 이력 1행이다 — 결과가 없을 때 돌리지 않는 이유이기도 하다.
   */
  const applyCoeff = (req: CoeffRequest) => {
    if (req.scope === 'RECOMMEND_SHARE') {
      if (share) void runShare(req)
    } else if (result) {
      void runAnalysis(req)
    }
  }
  /** 배너 `초기화` — 적용값을 기본값으로 되돌리고, 결과가 있으면 그 값으로 재분석한다. */
  const resetCoeff = () => applyCoeff(coeff.reset(coeffScope))

  const panelVisible =
    panelOpen && (tab === 'SHARE' ? !!share && !shareRunning : !!result && !running)

  /**
   * 범례는 **그릴 핀이 있을 때만** 띄운다 — 색을 설명할 대상이 없으면 범례가 거짓말을 한다.
   * 보기마다 축이 다르다 — 요금제는 검토 등급(새 방식 · 구 응답은 우선순위),
   * 공유주차제는 검토 등급(지수 3단계)이다(둘 중 하나만 뜬다).
   */
  const legendVisible = tab === 'FEE' && !!result && result.lots.length > 0
  /**
   * 범례 `후보 아님` 줄의 근거 = 지도에 **그려지는** 회색 핀 수(지도와 같은 `drawableExclLots`).
   * ⚠️ `legendVisible`(후보 1곳 이상)은 그대로다 — 후보 0개소 동은 범례 없이 회색 핀만 보인다.
   */
  const exclCnt = useMemo(() => drawableExclLots(result?.exclLots).length, [result])
  /**
   * 공유주차제 범례 — 🔁 **목록에 검토 지수가 있는 행이 1곳 이상**일 때 띄운다(종전: 후보 1곳 이상).
   * 범례가 3줄이 되어 깃발뿐 아니라 **목록의 색 점**(`보통` 포함)을 설명한다 — 깃발이 0개여도
   * 색 점이 있으면 띄운다. 지수가 하나도 없으면(전 행 회색) 설명할 색이 없어 걷는다.
   * 🔴 조회 기간에 민원이 0건이면 걷는다 — 그때는 결과 패널이 목록 자체를 걷는다(같은 경고 코드를 본다).
   */
  const shareLegendVisible =
    tab === 'SHARE' &&
    !!share &&
    !share.warnings.includes(SHARE_WARN_NO_DATA) &&
    share.zones.some((z) => z.rvwScr != null)

  /**
   * 내보내기(= 보고서 저장) 조건. 🔴 **조회에 적용된 값**이다 — 설정 패널의 live 입력
   * (`emdCd`·`period`·`customFrom` …)을 쓰면 화면의 결과와 다른 조건으로 저장된다.
   * `null` 이면 패널이 버튼을 잠근다 — 결과가 없거나, 서버가 400 을 낼 0건이다.
   *
   * 요금제: `applied` 의 범위(`emdCd` 또는 `sggNm` — · 시군구 모드엔 결과에 `zone` 이 없다).
   * 배타는 `createZoneRcmdtnFeeReport` 가 `emdCd` 우선으로 지킨다. `runAnalysis` 가 조회 전에 `result` 를
   * 비우므로 결과가 있으면 `applied` 가 곧 그 결과를 만든 조건이다. 단속 창은 조회처럼 싣지 않는다.
   * 계수는 마지막으로 성공한 실행의 값(`ranRequest`)이다(팝오버 초안·미실행 적용값이 아니다).
   */
  const { ranRequest } = coeff
  // 렌더마다 새 객체를 만들지 않게 고정한다 — `feeExportCriteria` 가 매 렌더 바뀌지 않도록.
  const feeRanReq = useMemo(() => ranRequest('RECOMMEND_FEE'), [ranRequest])
  const feeExportCriteria = useMemo<ZoneRcmdtnFeeExportCriteria | null>(() => {
    if (!result || result.lots.length === 0) return null
    return {
      emdCd: applied.emdCd || undefined,
      sggNm: applied.emdCd ? undefined : applied.sggNm || undefined,
      bgngYmd: applied.bgngYmd,
      endYmd: applied.endYmd,
      trgtTypeSe: applied.trgtTypeSe,
      vrblVls: feeRanReq.vrblVls,
    }
  }, [result, applied, feeRanReq])
  /** 공유주차제: `shareApplied`. 후보(`rvwTrgtYn === 'Y'`)가 한 곳도 없으면 잠근다. */
  const shareExportCriteria = useMemo<ZoneRcmdtnShareExportCriteria | null>(() => {
    if (!share || !shareApplied) return null
    if (!share.zones.some((z) => z.rvwTrgtYn === 'Y')) return null
    return {
      bgngYmd: shareApplied.bgngYmd,
      endYmd: shareApplied.endYmd,
      sggNm: shareApplied.sggNm,
      // `share` 를 만든 실행의 계수. `runShare` 가 결과와 같은 지점에서 굳혔다.
      vrblVls: shareApplied.vrblVls,
    }
  }, [share, shareApplied])

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="분석·시뮬레이션" page="구역추천" />
          <h1 className="page-title">{PAGE_TITLE}</h1>
          <p className="page-sub">{PAGE_SUB}</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--analysis">
        <section className="sim-stage">
          {/*
           * 🔴 배경지도는 스테이지의 **직계 자식**이어야 한다 —
           * `analysis.css` 의 `.sim-stage > .leaflet-container { width:100%; height:100% }` 가 그
           * 형태를 전제로 크기를 잡는다(`HotspotPage` 가 같은 함정을 적어 뒀다 · 10-1).
           * 오버레이 패널은 `position:absolute; z-index:1000` 이라 흐름상 뒤에 있어도 지도 위에 얹힌다.
           */}
          {/*
           * 🔴 지도는 **하나**이고 보기에 맞는 층의 데이터만 넘긴다 — 다른 쪽은 비워
           * 그 층의 마커가 걷히게 한다. 요금제 보기에서 넘기는 값은 종전과 같다.
           * 🔁 분석 내용을 바꾸면 두 결과가 모두 비므로(`changeTab`) 아래 `tab` 분기는 **두 번째 잠금**이다 —
           * 한 보기의 결과가 다른 보기의 층에 그려지는 일을 props 에서도 막아 둔다.
           */}
          <ZoneRcmdtnMap
            /*
             * 시군구 모드는 `zone` 이 null 이라 `''` 이 된다 → 경계 없이 핀 `fitBounds` 로
             * 시야를 잡는다(`/zones` 에 시군구 코드가 없어 시군구 경계는 이번 범위 밖).
             */
            emdCd={tab === 'FEE' ? (result?.zone?.emdCd ?? '') : ''}
            lots={tab === 'FEE' ? result?.lots : undefined}
            /* 핀 색·팝업 배지의 방식(검토 등급 / 구 우선순위). `lots` 와 같은 탭 잠금. */
            anlsMthd={tab === 'FEE' ? result?.anlsMthd : undefined}
            /*
             * 후보 밖 회색 `P` 핀. 🔴 요금제 보기에서만 넘긴다 — 공유주차제 보기면 `undefined`
             * 라 회색 핀도 걷힌다(E5). 선택·시야와 무관하다.
             */
            exclLots={tab === 'FEE' ? result?.exclLots : undefined}
            exclMinCpcty={tab === 'FEE' ? result?.crtrVls.swtcTrgtMinCpcty : null}
            selection={selection}
            onSelectLot={selectLot}
            /*
             * 🔁 2026-10-27 — 깃발이 목록의 모든 행에 서므로, 자료 없는 기간(`NO_CVLCPT_IN_RANGE`)에는 목록과 같게
             * 깃발도 걷는다(그때 결과 패널은 목록 대신 안내 문구만 띄운다).
             */
            shareZones={
              tab === 'SHARE'
                ? share?.warnings.includes(SHARE_WARN_NO_DATA)
                  ? []
                  : share?.zones
                : undefined
            }
            shareCrtrVls={tab === 'SHARE' ? share?.crtrVls : null}
            shareSelection={tab === 'SHARE' ? shareSelection : null}
            onSelectShareZone={selectShareZone}
            resetSeq={resetSeq}
          />

          <ZoneRcmdtnSettingPanel
            tab={tab}
            onTabChange={changeTab}
            zones={zones}
            zonesLoading={zonesLoading}
            zonesError={zonesError}
            onRetryZones={() => setReloadZonesKey((k) => k + 1)}
            sggNm={sggNm}
            onSggChange={changeSgg}
            emdCd={emdCd}
            onEmdChange={setEmdCd}
            period={period}
            onPeriodChange={setPeriod}
            customFrom={customFrom}
            customTo={customTo}
            onCustomFromChange={setCustomFrom}
            onCustomToChange={setCustomTo}
            rangeError={rangeError}
            running={tab === 'SHARE' ? shareRunning : running}
            /*
             * 🔴 조회 방아쇠는 이 클릭 하나다. 공유주차제 보기면 `/share`, 요금제 보기면 상세 조회 —
             * 어느 쪽도 effect 에서 부르지 않는다(둘 다 호출마다 실행 이력이 남는다).
             */
            onRun={() => {
              void (tab === 'SHARE' ? runShare() : runAnalysis())
            }}
            coeff={coeff}
            coeffScope={coeffScope}
            onApplyCoeff={applyCoeff}
            onResetCoeff={resetCoeff}
            error={tab === 'SHARE' ? shareError : error}
            /* 공유주차제 보기는 읍면동 칸이 없어 이 안내를 띄우지 않는다(지역 칸 아래에 따로 적는다). */
            resultNote={tab === 'FEE' && !sggNm && !error ? NOTE_PICK_EMD : null}
          />

          {panelVisible && (
            <ZoneRcmdtnResultPanel
              /* 분석 내용을 바꾸면 패널을 새로 세운다 — 내보내기 안내·잠금·스크롤이 다음 보기로 넘어가지 않게. */
              key={resetSeq}
              tab={tab}
              result={result}
              periodLabel={ranPeriodLabel}
              share={share}
              shareApplied={shareApplied}
              shareGrdCodes={shareGrdCodes}
              shareSelection={shareSelection}
              onSelectShareZone={selectShareZone}
              selection={selection}
              onSelectLot={selectLot}
              feeExportCriteria={feeExportCriteria}
              shareExportCriteria={shareExportCriteria}
              onClose={() => setPanelOpen(false)}
            />
          )}

          {/*
           * 범례의 축은 `result.anlsMthd` 가 가른다. 새 방식(`RVW_SCR`)이면 **우선도 단계**
           * (우선도 100/60 · 고정 3줄), 구 응답이면 **우선순위**(배정 민원 등간격 3단계 · 경계는 `lots`).
           */}
          {/* 회색 핀이 있으면 맨 아래 `후보 아님` 줄. */}
          {legendVisible && result && (
            <ZoneRcmdtnLegend
              lots={result.lots}
              exclCnt={exclCnt}
              minCpcty={result.crtrVls.swtcTrgtMinCpcty}
              anlsMthd={result.anlsMthd}
            />
          )}

          {/* 🔴 공유주차제 보기의 범례 — 축은 검토 등급(지수 3단계)이고 경계 숫자는 응답이 준다. */}
          {shareLegendVisible && share && <ZoneRcmdtnShareLegend crtrVls={share.crtrVls} />}
        </section>
      </div>
    </>
  )
}
