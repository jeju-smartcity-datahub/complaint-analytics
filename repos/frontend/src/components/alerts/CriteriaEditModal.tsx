import { useState, type CSSProperties } from 'react'
import axios from 'axios'
import Modal from '../common/Modal'
import RiskBadge, { RISK_LEVELS } from '../common/RiskBadge'
import { saveRiskCriteria, type RiskCriteriaItem, type RiskCriteriaSaveItem } from '../../api/alerts'
import SrLabel from '../common/SrLabel'

/*
 * 알림 기준 설정 모달 (알림 관리 화면의 `설정`).
 *
 * DOM·문구는 시안의 `.modal__dialog--sm` 을 옮겼고,
 * 셸(overlay 클릭·Escape·body 스크롤 잠금)은 공통 `components/common/Modal` 이 맡는다.
 *
 * 시안과 의도적으로 다른 부분
 *   - 값이 로컬 상태가 아니라 **`PUT /api/risk-criteria` 3건 일괄 UPSERT** 다.
 *     3단계를 모두 보내야 하므로 **부분 저장이 없다** — 한 칸이라도 비면 저장을 막는다
 *   - 단계 목록·순서는 **호출부가 넘긴 서버 응답 순서**(`sort_seq` = 심각 → 경고 → 주의)를 그대로 쓴다.
 *     시안의 하드코딩 3종 배열을 옮기지 않는다
 *   - 저장 실패 문구 (시안은 로컬 상태라 실패 경로가 없다)
 *   - **최소 건수는 전 단계 공통 입력 1칸**이다. 시안대로 단계 목록의
 *     4번째 행(`badge--neutral` 배지)으로 두되, 저장할 때 그 한 값을 3건에 복사한다 —
 *     화면이 값을 하나만 받으면 단계별로 갈리는 사고가 구조적으로 불가능하다.
 *   - 시안에 없는 단계별 값 불일치 안내(목록 아래)를 남겼다. `기본값(N)으로` 버튼은
 *     시안에 맞춰 뺐다
 *
 * ⚠️ 기준을 바꿔도 **과거 알림 이력의 `incrsRtThrsh`(판정 당시 스냅샷)는 변하지 않는다**.
 */

/** 오류 문구 스타일. 시안엔 실패 경로가 없어 전용 클래스도 없다(다른 화면과 같은 토큰을 쓴다). */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/** 푸터 왼쪽(취소 버튼) 옆에 오류 문구를 함께 두기 위한 묶음. */
const FOOT_LEFT: CSSProperties = { display: 'flex', alignItems: 'center', gap: 'var(--space-12)' }

/** 단계별로 값이 갈려 있을 때의 안내 — 저장하면 통일된다는 사실을 미리 알린다. */
const SPLIT_NOTE: CSSProperties = {
  margin: '0 0 var(--space-8)',
  fontSize: 'var(--label1-size)',
  color: 'var(--status-caution, var(--text-neutral))',
}

/** 서버 검증과 같은 범위 — `0 < incrsRt <= 100`. 넘으면 400 이라 버튼에서 먼저 막는다. */
function isValidRate(raw: string): boolean {
  const value = Number(raw)
  return raw.trim() !== '' && Number.isFinite(value) && value > 0 && value <= 100
}

/** 서버 검증과 같은 범위 — 0 <= x <= 1000 정수. 🔴 0 은 '하한 없음'이라 유효하다. */
function isValidMinCnt(raw: string): boolean {
  const value = Number(raw)
  return raw.trim() !== '' && Number.isInteger(value) && value >= 0 && value <= 1000
}

/** 단계 라벨(심각/경고/주의)은 어떤 응답에도 없다 — 코드값에서 만든다. */
function riskLabel(code: string): string {
  return RISK_LEVELS.find((l) => l.code === code)?.label ?? code
}

interface CriteriaEditModalProps {
  /** 현재 기준 3건 (`GET /api/risk-criteria` 결과 — 순서를 그대로 쓴다). */
  criteria: RiskCriteriaItem[]
  /** 닫기 요청 (overlay 클릭 · Escape · 취소 · 닫기 버튼). */
  onClose: () => void
  /** 저장 성공 — 인자는 서버가 재조회한 3건이다. 카드 갱신은 호출부 책임. */
  onSaved: (saved: RiskCriteriaItem[]) => void
}

export default function CriteriaEditModal({ criteria, onClose, onSaved }: CriteriaEditModalProps) {
  /** 단계별 증가율. `number` 로 두면 지우는 순간 NaN 이 되므로 문자열로 들고 저장 직전에 변환한다. */
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(criteria.map((c) => [c.riskLvlCd, String(c.incrsRt)])),
  )

  /**
   * 최소 민원수 — **전 단계 공통 값 하나**다.
   * 🔴 조회한 3건의 minCvlcptCnt 가 다르면(= SQL 로 직접 갈라 둔 예외 상황) **가장 낮은 값**을 쓴다.
   *    그것이 현재 실효 하한이기 때문이다. 저장하면 3건이 그 값으로 통일된다.
   */
  const [minCnt, setMinCnt] = useState<string>(() =>
    String(Math.min(...criteria.map((c) => c.minCvlcptCnt))),
  )

  /** 단계별로 갈려 있는 상태인지 — 안내 문구 노출 판단에만 쓴다. */
  const isSplit = new Set(criteria.map((c) => c.minCvlcptCnt)).size > 1

  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /** 증가율 3칸 + 최소 건수 1칸이 **모두** 유효해야 저장할 수 있다(부분 저장 없음). */
  const submittable =
    !saving &&
    criteria.length > 0 &&
    criteria.every((c) => isValidRate(values[c.riskLvlCd] ?? '')) &&
    isValidMinCnt(minCnt)

  const handleSave = async () => {
    if (!submittable) return
    setSaving(true)
    setError(null)

    /* 🔴 한 값을 3건에 그대로 복사한다 — API 계약(3건 일괄)은 바뀌지 않는다.
     * `criteria` 원소를 spread 하지 않는다 — `bscMinCvlcptCnt`(조회 전용)가 섞여 들어간다. */
    const body: RiskCriteriaSaveItem[] = criteria.map((c) => ({
      riskLvlCd: c.riskLvlCd,
      incrsRt: Number(values[c.riskLvlCd]),
      minCvlcptCnt: Number(minCnt), // ← 3건 모두 같은 값
    }))

    try {
      onSaved(await saveRiskCriteria(body))
    } catch (e) {
      // 서버에 전역 예외 핸들러가 없어 400 사유가 본문에 실려 오지 않는다 — 상태코드로만 갈라 안내한다.
      setError(
        axios.isAxiosError(e) && e.response?.status === 400
          ? '기준값을 확인해 주세요. (증가율 0 초과 100 이하 소수 2자리, 최소 건수 0 이상 1000 이하 정수)'
          : '알림 기준 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      title="알림 기준 설정"
      variant="sm"
      onClose={onClose}
      footSplit
      footer={
        <>
          <span style={FOOT_LEFT}>
            <button className="btn" type="button" style={{ height: 44 }} onClick={onClose}>
              취소
            </button>
            {error && (
              <span style={ERROR_TEXT} role="alert">
                {error}
              </span>
            )}
          </span>
          <button
            className="btn-save"
            type="button"
            disabled={!submittable}
            onClick={() => {
              void handleSave()
            }}
          >
            저장
          </button>
        </>
      }
    >
      <p className="crit-card__sub" style={{ marginTop: 0 }}>
        <b>민원 증가율</b>이 아래 기준 이상일 때 해당 단계 알림이 발생합니다.
      </p>
      <p className="crit-card__sub" style={{ marginTop: 0, marginBottom: 16 }}>
        알림 기준은 서비스에 <b>공통으로 반영</b>되며 <b>다음날부터</b> 적용됩니다.
      </p>

      <div className="crit-list">
        {criteria.map((c) => (
          <div className="crit-row" key={c.thrshId}>
            {/* 단계 라벨·색은 `RiskBadge` 한 곳에서 온다(응답에 라벨이 없다). */}
            <RiskBadge code={c.riskLvlCd} className="crit-row__tag" />
            <SrLabel text={`${riskLabel(c.riskLvlCd)} 기준 증가율`}>
              <input
                type="number"
                className="crit-inp"
                aria-label={`${riskLabel(c.riskLvlCd)} 기준 증가율`}
                min={0.01}
                max={100}
                step={0.01}
                value={values[c.riskLvlCd] ?? ''}
                onChange={(e) =>
                  setValues((prev) => ({ ...prev, [c.riskLvlCd]: e.target.value }))
                }
              />
            </SrLabel>
            <span className="crit-row__unit">% 이상</span>
          </div>
        ))}

        {/* ── 공통(단계 무관) 최소 건수 — 칸이 1개다 ── */}
        {criteria.length > 0 && (
          <div className="crit-row">
            <span className="badge badge--neutral crit-row__tag">최소 건수</span>
            <SrLabel text="최소 민원 건수">
              <input
                type="number"
                className="crit-inp"
                aria-label="최소 민원 건수"
                min={0}
                max={1000}
                step={1}
                value={minCnt}
                onChange={(e) => setMinCnt(e.target.value)}
              />
            </SrLabel>
            <span className="crit-row__unit">건 이상</span>
          </div>
        )}
      </div>

      {isSplit && (
        <p style={SPLIT_NOTE} role="alert">
          단계별로 다르게 설정돼 있습니다(
          {criteria.map((c) => `${riskLabel(c.riskLvlCd)} ${c.minCvlcptCnt}`).join(' · ')}
          ). 저장하면 모든 단계가 같은 값이 됩니다.
        </p>
      )}

      <p className="crit-card__sub" style={{ marginTop: 16, marginBottom: 0 }}>
        당일 또는 전일 민원 건수가 최소 건수 미만이면 증가율 기준을 충족해도 알림이 발생하지 않습니다.
      </p>
    </Modal>
  )
}
