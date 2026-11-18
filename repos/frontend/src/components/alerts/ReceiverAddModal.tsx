import { useEffect, useMemo, useState, type CSSProperties, type SelectHTMLAttributes } from 'react'
import axios from 'axios'
import Icon from '../Icon'
import Modal from '../common/Modal'
import { RISK_LEVELS } from '../common/RiskBadge'
import {
  createNotiReceiver,
  fetchReceiverCandidates,
  updateReceiver,
  type NotiReceiverCandidate,
  type NotiReceiverItem,
  type NotiReceiverRegionRequest,
} from '../../api/alerts'
import type { RegionItem } from '../../api/regions'
import { useAuth } from '../../lib/authContext'
import SrLabel from '../common/SrLabel'

/*
 * 수신자 추가·수정 모달 (TSK-4028 Step 9 → TSK-4250 Step 9 개편).
 *
 * DOM·문구는 시안(https://innopamsolution.github.io/jeju-datahub/)의 `수신자 추가` 모달을 옮겼고,
 * 셸(overlay 클릭·Escape·body 스크롤 잠금)은 공통 `components/common/Modal` 이 맡는다.
 *
 * 사람 선택 (R8 — 수신자는 `TB_USER` 에서 고른다. 이름·이메일·부서는 서버가 복사한다)
 *   - 기본 **"내 정보로 입력"** — `/api/auth/me` 의 `userId` 로 **후보 API 결과에서 내 행을 찾는다**
 *     (`/me` 에는 부서·`registered` 가 없고 그 계약은 바꾸지 않는다)
 *   - **"사용자 목록 불러오기"** — 소속 select → 사용자 select. 무소속은 소속 select 맨 끝(Q2)
 *   - 🔴 `registered=true` 는 고를 수 없다(R9 — 한 사용자 = 수신자 1행). 바꾸려면 표의 `수정` 이다
 *
 * 지역 (R3·R11)
 *   - 시/도 + 읍면동 select + ＋ 버튼 → 칩 누적, 칩 × 로 삭제
 *   - "전체 지역" 칩은 배타(시안). 시군구 전체 ↔ 그 시군구 읍면동은 공존 불가(R11 — 시안보다 한 단계 더)
 *   - 칩이 0개면 **현재 select 값**으로 저장한다(시안 그대로 — 기본값 "전체" → `regions: [{}]`)
 *
 * 수정 모드 (R10 — 시안 밖): `editing` 을 받으면 사람 영역을 잠그고 지역·단계를 프리필해 `PUT` 한다.
 *
 * ⚠️ 단계 그룹은 저장·배지 표시까지다(D14) — 알림 조회 결과를 거르지 않는다.
 */

/** 오류 문구 스타일. 시안엔 실패 경로가 없어 전용 클래스도 없다(다른 화면과 같은 토큰을 쓴다). */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

/** select 2개 + ＋ 버튼을 한 줄에 (시안과 같은 인라인 배치). */
const REGION_ROW: CSSProperties = { display: 'flex', gap: 8, alignItems: 'center' }
const REGION_FIELD: CSSProperties = { flex: 1 }

/** 시안의 ＋ 버튼 — 36px 정사각, blue-95 바탕. */
const ADD_BTN: CSSProperties = {
  flex: '0 0 36px',
  width: 36,
  height: 36,
  border: 'none',
  borderRadius: 10,
  background: 'var(--blue-95)',
  color: 'var(--primary)',
  fontSize: 18,
  fontWeight: 700,
  cursor: 'pointer',
}

/** 지역 칩 (시안 `badge` + blue-95 바탕 + × 버튼). */
const CHIP: CSSProperties = { background: 'var(--blue-95)', color: 'var(--primary)', gap: 4 }
const CHIP_X: CSSProperties = {
  border: 'none',
  background: 'none',
  color: 'inherit',
  cursor: 'pointer',
  padding: 0,
  fontSize: 14,
  lineHeight: 1,
}
const CHIP_SET: CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }

/** 내 정보·수정 모드의 읽기 전용 입력 (시안: 바탕 fill-normal). */
const READONLY_INP: CSSProperties = { background: 'var(--fill-normal)' }

/** 상단 안내문 + 모드 토글 버튼 한 줄. */
const HEAD_ROW: CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  marginBottom: 16,
}

/** 라벨 옆 보조 문구. */
const LABEL_HINT: CSSProperties = {
  marginLeft: 8,
  color: 'var(--label-assistive)',
  fontWeight: 'normal',
  fontSize: 'var(--caption1-size)',
}

/** 푸터 왼쪽(취소 버튼) 옆에 오류 문구를 함께 두기 위한 묶음. */
const FOOT_LEFT: CSSProperties = { display: 'flex', alignItems: 'center', gap: 'var(--space-12)' }

/** 소속 select 의 무소속 값 — `deptCd` 가 null 인 사용자 묶음(Q2). 실제 부서코드와 겹치지 않는 값. */
const NO_DEPT = '__NO_DEPT__'

interface DsSelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  /** 래퍼(`.ds-select`)에 얹을 스타일. 지역 2단 배치에서 폭을 나눌 때만 쓴다. */
  wrapperStyle?: CSSProperties
}

/** 시안의 `.ds-select` — 네이티브 select 위에 chevron 아이콘을 얹는다. */
function DsSelect({ wrapperStyle, className, children, ...rest }: DsSelectProps) {
  return (
    <div className={`ds-select${className ? ` ${className}` : ''}`} style={wrapperStyle}>
      <SrLabel text={rest['aria-label']}>
        <select {...rest}>{children}</select>
      </SrLabel>
      <span className="ds-select__ic">
        <Icon name="chevron-down" size={18} />
      </span>
    </div>
  )
}

/** 지역 칩 1개. `sggNm`·`emdCd` 둘 다 없으면 전체 지역(R3). */
interface RegionChip {
  sggNm: string | null
  emdCd: string | null
  label: string
}

const chipKey = (c: { sggNm: string | null; emdCd: string | null }): string =>
  c.emdCd ? `E:${c.emdCd}` : c.sggNm ? `S:${c.sggNm}` : 'ALL'

const toRequest = (c: { sggNm: string | null; emdCd: string | null }): NotiReceiverRegionRequest =>
  c.emdCd ? { emdCd: c.emdCd } : c.sggNm ? { sggNm: c.sggNm } : {}

/**
 * 칩 추가 규칙 (R11). 서버도 같은 규칙으로 400 을 내지만 화면이 먼저 막는다.
 *   - 전체 지역 → 다른 칩 전부 걷음(배타)
 *   - 시군구 전체 → 전체 지역 + 그 시군구 읍면동 칩 걷음
 *   - 읍면동 → 전체 지역 + 그 시군구 전체 칩 걷음
 */
function addChip(prev: RegionChip[], next: RegionChip): RegionChip[] {
  const key = chipKey(next)
  if (prev.some((c) => chipKey(c) === key)) return prev
  if (key === 'ALL') return [next]
  const kept = prev.filter((c) => {
    const k = chipKey(c)
    if (k === 'ALL') return false
    if (!next.emdCd && c.emdCd && c.sggNm === next.sggNm) return false
    if (next.emdCd && !c.emdCd && c.sggNm === next.sggNm) return false
    return true
  })
  return [...kept, next]
}

interface ReceiverAddModalProps {
  /** 지역 select 옵션 — `GET /api/regions` 결과. */
  regions: RegionItem[]
  /** 수정 모드(R10) — 넘기면 사람 영역 잠금 + 지역·단계 프리필 → `PUT`. 없으면 추가 모드. */
  editing?: NotiReceiverItem | null
  /** 닫기 요청 (overlay 클릭 · Escape · 취소 · 닫기 버튼). */
  onClose: () => void
  /** 저장 성공 — 인자는 서버가 돌려준 수신자 1건이다. 목록 반영은 호출부 책임. */
  onSaved: (saved: NotiReceiverItem, mode: 'create' | 'update') => void
}

export default function ReceiverAddModal({
  regions,
  editing,
  onClose,
  onSaved,
}: ReceiverAddModalProps) {
  const isEdit = !!editing
  const me = useAuth()

  // ── 사람 (추가 모드만) ──
  /** `me` = 내 정보로 입력(시안 기본) · `list` = 사용자 목록 불러오기. */
  const [personMode, setPersonMode] = useState<'me' | 'list'>('me')
  const [candidates, setCandidates] = useState<NotiReceiverCandidate[]>([])
  const [candLoading, setCandLoading] = useState(!isEdit)
  const [candError, setCandError] = useState<string | null>(null)
  const [deptKey, setDeptKey] = useState('')
  const [userId, setUserId] = useState('')

  // ── 지역 ──
  /** `''` = 전체 지역. */
  const [sggNm, setSggNm] = useState('')
  /** `''` = 시군구 전체. */
  const [emdCd, setEmdCd] = useState('')
  const [chips, setChips] = useState<RegionChip[]>(() =>
    (editing?.regions ?? []).map((r) => ({ sggNm: r.sggNm, emdCd: r.emdCd, label: r.regionNm })),
  )

  /** 선택된 단계 코드 집합. 추가는 시안과 같이 **심각만 켠 상태**, 수정은 기존 값으로 시작한다. */
  const [levels, setLevels] = useState<string[]>(() => editing?.riskLvlCds ?? ['CRIT'])

  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // 후보는 추가 모드에서만 받는다 — 수정은 사람을 바꾸지 않는다(R10).
  useEffect(() => {
    if (isEdit) return
    let cancelled = false
    fetchReceiverCandidates()
      .then((list) => {
        if (cancelled) return
        setCandidates(list)
      })
      .catch((e) => {
        if (cancelled) return
        setCandError(
          axios.isAxiosError(e) && e.response?.status === 403
            ? '사용자 목록을 볼 권한이 없습니다.'
            : '사용자 목록을 불러오지 못했습니다.',
        )
      })
      .finally(() => {
        if (!cancelled) setCandLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [isEdit])

  /** 소속 select 옵션 — 서버 정렬 순서대로 중복 제거, 무소속은 **맨 끝**(Q2). */
  const deptOptions = useMemo(() => {
    const seen = new Map<string, string>()
    let hasNoDept = false
    for (const c of candidates) {
      if (c.deptCd == null) hasNoDept = true
      else if (!seen.has(c.deptCd)) seen.set(c.deptCd, c.deptNm ?? c.deptCd)
    }
    const opts = [...seen].map(([value, label]) => ({ value, label }))
    if (hasNoDept) opts.push({ value: NO_DEPT, label: '무소속' })
    return opts
  }, [candidates])

  // 후보를 받으면 첫 소속을 고른다.
  useEffect(() => {
    if (deptKey === '' && deptOptions.length > 0) setDeptKey(deptOptions[0].value)
  }, [deptKey, deptOptions])

  const usersInDept = candidates.filter((c) =>
    deptKey === NO_DEPT ? c.deptCd == null : c.deptCd === deptKey,
  )

  /** 내 행 — `/me` 의 `userId` 로 후보에서 찾는다. 없으면(비활성 등) 내 정보로는 등록할 수 없다. */
  const myCandidate = me ? candidates.find((c) => c.userId === me.userId) : undefined

  /** 이번 등록 대상 userId. 비면 `추가` 비활성. */
  const targetUserId = isEdit
    ? ''
    : personMode === 'me'
      ? myCandidate && !myCandidate.registered
        ? myCandidate.userId
        : ''
      : userId

  const emds = regions.find((r) => r.sggNm === sggNm)?.emds ?? []

  /** 현재 select 값 → 칩. */
  const currentChip = (): RegionChip => {
    if (emdCd) {
      const emdNm = emds.find((e) => e.emdCd === emdCd)?.emdNm ?? emdCd
      return { sggNm, emdCd, label: `${sggNm} ${emdNm}` }
    }
    if (sggNm) return { sggNm, emdCd: null, label: `${sggNm} 전체` }
    return { sggNm: null, emdCd: null, label: '전체 지역' }
  }

  const toggleLevel = (code: string) => {
    setLevels((prev) => (prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]))
  }

  // 수정 모드는 칩이 1개 이상이어야 저장한다 — 칩을 다 지운 채 기본 select("전체")로 떨어지면
  // "지역을 줄이려던" 동작이 전 지역으로 넓어진다(R3 fail-closed 취지와 반대). 전체가 필요하면 "전체 지역" 칩을 담는다.
  const submittable =
    !saving && levels.length > 0 && (isEdit ? chips.length > 0 : targetUserId !== '')

  const handleSubmit = async () => {
    if (!submittable) return
    setSaving(true)
    setError(null)

    // 칩이 0개면 현재 select 값 하나로 저장한다(시안 — 기본 "전체" → [{}], R3).
    const regionsBody = (chips.length > 0 ? chips : [currentChip()]).map(toRequest)
    // 선택 순서가 아니라 화면 순서(심각 → 경고 → 주의)로 보낸다 — 중복은 서버가 제거한다.
    const riskLvlCds = RISK_LEVELS.filter((l) => levels.includes(l.code)).map(
      (l) => l.code as string,
    )

    try {
      if (editing) {
        onSaved(
          await updateReceiver(editing.rcvrId, { regions: regionsBody, riskLvlCds }),
          'update',
        )
      } else {
        onSaved(
          await createNotiReceiver({ userId: targetUserId, regions: regionsBody, riskLvlCds }),
          'create',
        )
      }
    } catch (e) {
      const status = axios.isAxiosError(e) ? e.response?.status : undefined
      const code = axios.isAxiosError(e)
        ? (e.response?.data as { code?: string } | undefined)?.code
        : undefined
      if (status === 409 && code === 'RCVR_DUPLICATED') {
        setError('이미 수신자로 등록된 사용자입니다. 목록에서 수정해 주세요.')
      } else if (status === 400) {
        setError('입력값을 확인해 주세요. (지역이 겹치거나 지원하지 않는 값일 수 있습니다)')
      } else if (status === 404) {
        setError('이미 삭제된 수신자입니다.')
      } else {
        setError(
          isEdit
            ? '수신자 수정에 실패했습니다. 잠시 후 다시 시도해 주세요.'
            : '수신자 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.',
        )
      }
    } finally {
      setSaving(false)
    }
  }

  /** 내 정보 모드 안내 — 등록 불가 사유. */
  const meNotice = (() => {
    if (isEdit || personMode !== 'me' || candLoading || candError) return null
    if (!myCandidate)
      return '내 정보를 사용자 목록에서 찾지 못했습니다. 사용자 목록에서 선택해 주세요.'
    if (myCandidate.registered) return '이미 수신자로 등록돼 있습니다. 목록에서 수정해 주세요.'
    return null
  })()

  return (
    <Modal
      title={isEdit ? '수신자 수정' : '수신자 추가'}
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
            style={submittable ? undefined : { opacity: 0.4 }}
            onClick={() => {
              void handleSubmit()
            }}
          >
            {isEdit ? '저장' : '추가'}
          </button>
        </>
      }
    >
      <div style={HEAD_ROW}>
        <p className="crit-card__sub" style={{ margin: 0 }}>
          시스템 알림에 대한 수신자 설정입니다.
        </p>
        {!isEdit && (
          <button
            className="btn-edit"
            type="button"
            onClick={() => {
              setPersonMode((m) => (m === 'me' ? 'list' : 'me'))
              setError(null)
            }}
          >
            {personMode === 'me' ? '사용자 목록 불러오기' : '내 정보로 입력'}
          </button>
        )}
      </div>

      {candError && (
        <p style={{ ...ERROR_TEXT, marginTop: 0 }} role="alert">
          {candError}
        </p>
      )}

      {/* ── 사람 ── */}
      {isEdit || personMode === 'me' ? (
        <>
          <div className="form-field">
            <label className="form-field__label" htmlFor="rcvr-name">
              사용자명
            </label>
            <input
              id="rcvr-name"
              type="text"
              className="form-inp"
              readOnly
              style={READONLY_INP}
              value={
                editing
                  ? editing.rcvrNm
                  : candLoading
                    ? '불러오는 중...'
                    : (myCandidate?.userNm ?? me?.userNm ?? '')
              }
            />
          </div>
          <div className="form-field">
            <label className="form-field__label" htmlFor="rcvr-dept">
              부서
            </label>
            <input
              id="rcvr-dept"
              type="text"
              className="form-inp"
              readOnly
              style={READONLY_INP}
              value={
                editing
                  ? (editing.deptNm ?? '무소속')
                  : candLoading || !myCandidate
                    ? ''
                    : (myCandidate.deptNm ?? '무소속')
              }
            />
          </div>
          {meNotice && (
            <p style={{ ...ERROR_TEXT, marginTop: 0 }} role="status">
              {meNotice}
            </p>
          )}
        </>
      ) : (
        <>
          <div className="form-field">
            <label className="form-field__label" htmlFor="rcvr-dept">
              소속
            </label>
            <DsSelect
              className="form-field__select"
              id="rcvr-dept"
              aria-label="소속"
              value={deptKey}
              disabled={candLoading || deptOptions.length === 0}
              onChange={(e) => {
                setDeptKey(e.target.value)
                setUserId('') // 소속을 바꾸면 다른 소속 사용자가 남지 않게 초기화
              }}
            >
              {deptOptions.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </DsSelect>
          </div>
          <div className="form-field">
            <label className="form-field__label" htmlFor="rcvr-user">
              사용자
            </label>
            <DsSelect
              className="form-field__select"
              id="rcvr-user"
              aria-label="사용자"
              value={userId}
              disabled={candLoading}
              onChange={(e) => setUserId(e.target.value)}
            >
              <option value="">사용자 선택</option>
              {/* registered 는 고를 수 없다(R9) — 바꾸려면 표의 `수정` 이다. */}
              {usersInDept.map((c) => (
                <option key={c.userId} value={c.userId} disabled={c.registered}>
                  {c.registered ? `${c.userNm} (등록됨)` : c.userNm}
                </option>
              ))}
            </DsSelect>
          </div>
        </>
      )}

      {/* ── 지역 ── */}
      <div className="form-field">
        <label className="form-field__label">
          지역
          <span style={LABEL_HINT}>
            {isEdit && chips.length === 0
              ? '수정할 때는 지역을 1개 이상 ＋ 로 담아 주세요'
              : '지역을 선택하고 ＋ 버튼 클릭'}
          </span>
        </label>
        <div style={REGION_ROW}>
          <DsSelect
            className="form-field__select"
            wrapperStyle={REGION_FIELD}
            aria-label="시/도"
            value={sggNm}
            onChange={(e) => {
              // 시/도를 바꾸면 읍면동을 `전체` 로 리셋한다 — 안 하면 다른 시의 읍면동이 남는다.
              setSggNm(e.target.value)
              setEmdCd('')
            }}
          >
            <option value="">제주도 전체</option>
            {regions.map((r) => (
              <option key={r.sggNm} value={r.sggNm}>
                {r.sggNm}
              </option>
            ))}
          </DsSelect>

          <DsSelect
            className="form-field__select"
            wrapperStyle={REGION_FIELD}
            aria-label="읍면동"
            value={emdCd}
            disabled={!sggNm}
            onChange={(e) => setEmdCd(e.target.value)}
          >
            <option value="">읍면동 전체</option>
            {emds.map((emd) => (
              <option key={emd.emdCd} value={emd.emdCd}>
                {emd.emdNm}
              </option>
            ))}
          </DsSelect>

          <button
            type="button"
            style={ADD_BTN}
            aria-label="지역 추가"
            onClick={() => setChips((prev) => addChip(prev, currentChip()))}
          >
            ＋
          </button>
        </div>

        {chips.length > 0 && (
          <div style={CHIP_SET}>
            {chips.map((c) => (
              <span key={chipKey(c)} className="badge" style={CHIP}>
                {c.label}
                <button
                  type="button"
                  style={CHIP_X}
                  aria-label={`${c.label} 삭제`}
                  onClick={() => setChips((prev) => prev.filter((p) => chipKey(p) !== chipKey(c)))}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="form-field">
        <label className="form-field__label">단계 그룹</label>
        <div className="chip-set">
          {/* 라벨은 `RiskBadge` 의 매핑을 그대로 쓴다 — 화면에서 다시 적지 않는다. */}
          {RISK_LEVELS.map((l) => (
            <button
              key={l.code}
              type="button"
              aria-pressed={levels.includes(l.code)}
              className={`fchip${levels.includes(l.code) ? ' is-active' : ''}`}
              onClick={() => toggleLevel(l.code)}
            >
              {l.label}
            </button>
          ))}
        </div>
      </div>
    </Modal>
  )
}
