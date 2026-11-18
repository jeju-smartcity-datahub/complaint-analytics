import { useCallback, useEffect, useState, type CSSProperties } from 'react'
import axios from 'axios'
import Breadcrumb from '../../components/common/Breadcrumb'
import ConfirmDeleteModal from '../../components/common/ConfirmDeleteModal'
import Pager from '../../components/common/Pager'
import RiskBadge from '../../components/common/RiskBadge'
import Switch from '../../components/common/Switch'
import TopbarActions from '../../components/common/TopbarActions'
import Icon from '../../components/Icon'
import CriteriaEditModal from '../../components/alerts/CriteriaEditModal'
import ReceiverAddModal from '../../components/alerts/ReceiverAddModal'
import {
  deleteNotiReceiver,
  fetchNotiReceivers,
  fetchRiskCriteria,
  updateReceiverActivation,
  type NotiReceiverItem,
  type RiskCriteriaItem,
} from '../../api/alerts'
import { fetchRegions, type RegionItem } from '../../api/regions'
import type { PageResponse } from '../../api/reports'

/*
 * 위험단계 알림 관리 `/alerts/management`.
 *
 * 레이아웃·DOM 은 시안을 그대로 옮겼고,
 * 데이터는 목데이터 대신 실 API(`src/api/alerts.ts`)에서 받는다.
 *
 * 시안과 같은 부분
 *   - 수신자 표(10행 페이저) + 알림 기준 카드 2단 구성, `＋ 수신자 추가` · `설정` 모달
 *   - 삭제로 현재 페이지가 비면 **이전 페이지로 자동 이동**한다
 *
 * 시안과 의도적으로 다른 부분
 *   - 목록·등록·토글·삭제·기준 저장이 전부 서버 왕복이다. 정렬(`rcvr_id` 순)과 페이징은 서버가 한다
 *   - 표의 `지역` 칸은 **서버가 조립한 `regionNm`**(`제주시 연동, 제주시 노형동` / `전체 지역` /
 *     `지역 없음`)을 그대로 찍는다 — 프론트에서 `regions` 를 다시 조합하지 않는다
 *   - 행마다 `수정` 버튼(시안 밖). 같은 모달을 수정 모드로 열어 지역·단계를 `PUT` 하고,
 *     응답 1건으로 그 행만 갈아끼운다. 한 사용자 = 수신자 1행이라 수정이 유일한 변경 경로다
 *   - 삭제는 **소프트 삭제**(`del_yn='Y'`)라 목록에서만 사라지고 과거 이력·단계 매핑은 남는다.
 *     확인 다이얼로그는 시안에 없어 넣지 않는다
 *   - 조회 중 / 조회 실패(+ `다시 시도`) / 빈 목록 · 토글·삭제 실패 문구 (시안은 실패 경로가 없다)
 *   - 알림 기준 카드의 **최소 민원수 공통 줄** — 시안에는 최소 건수 개념 자체가 없다.
 *     단계 3행은 증가율만 두고, 목록 아래에 전 단계 공통 값 한 줄을 덧붙인다
 *
 * ⚠️ 단계 그룹(`riskLvlCds`)은 저장·표시 전용이다 — 알림 **조회** 결과를 거르지 않는다.
 * 실제로 쓰이는 곳은 메일 발송(범위 밖)이다.
 */

/** 페이지당 행 수. 서버 `size` 와 `Pager.perPage` 가 같은 값을 써야 총 페이지 수가 맞는다. */
const PER_PAGE = 10

/** 수신자 표의 열 수. 상태 행(조회 중·실패·빈 목록) `colSpan` 이 이 값을 쓴다. */
const COL_COUNT = 8

/** 수신자 표 헤더 (시안 그대로). */
const HEADERS = ['소속', '이름', '이메일', '지역', '단계 그룹', '활성화', '수정', '삭제'] as const

/** 가운데 정렬 열. */
const CENTER_COLS: readonly string[] = ['활성화', '수정', '삭제']

/** 오류 문구 스타일. 시안엔 실패 경로가 없어 전용 클래스도 없다(다른 화면과 같은 토큰을 쓴다). */
const ERROR_TEXT: CSSProperties = {
  color: 'var(--status-negative)',
  fontSize: 'var(--label1-size)',
}

export default function AlertManagementPage() {
  // ── 수신자 목록 ──
  const [receivers, setReceivers] = useState<PageResponse<NotiReceiverItem> | null>(null)
  /** 서버와 같은 **0-base**. `Pager` 가 표시용 1-base 로만 바꾼다. */
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  /** `다시 시도` · 등록/삭제 후 재조회 트리거. */
  const [reloadKey, setReloadKey] = useState(0)
  /** 토글·삭제가 도는 동안 같은 행을 다시 누르지 못하게 한다. */
  const [rowBusy, setRowBusy] = useState(false)
  const [listError, setListError] = useState<string | null>(null)
  /** 삭제 확인 대상 수신자 ID. null 이면 확인 팝업을 닫는다. */
  const [deleteTargetId, setDeleteTargetId] = useState<number | null>(null)

  // ── 알림 기준 ──
  const [criteria, setCriteria] = useState<RiskCriteriaItem[]>([])
  const [criteriaError, setCriteriaError] = useState(false)

  // ── 모달 select 옵션 (수신자 추가·수정 — 소속·사용자는 모달이 후보 API 로 직접 받는다) ──
  const [regions, setRegions] = useState<RegionItem[]>([])

  // ── 모달 열림 상태 ──
  const [addOpen, setAddOpen] = useState(false)
  /** 수정 모달 대상. null 이면 닫힘. */
  const [editTarget, setEditTarget] = useState<NotiReceiverItem | null>(null)
  const [critOpen, setCritOpen] = useState(false)

  // 지역·기준은 페이지를 넘겨도 변하지 않으므로 마운트 시 한 번만 받는다.
  useEffect(() => {
    let cancelled = false

    fetchRegions()
      .then((regionList) => {
        if (!cancelled) setRegions(regionList)
      })
      .catch(() => {
        // 옵션을 못 받아도 목록 조회까지 막지는 않는다 — 모달의 지역 select 만 "전체" 하나가 된다.
        if (!cancelled) setRegions([])
      })

    fetchRiskCriteria()
      .then((list) => {
        if (!cancelled) setCriteria(list)
      })
      .catch(() => {
        if (cancelled) return
        setCriteria([])
        setCriteriaError(true)
      })

    return () => {
      cancelled = true
    }
  }, [])

  // 수신자 목록 — 페이지가 바뀌거나 등록·삭제 후 재조회한다.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(false)

    fetchNotiReceivers(page, PER_PAGE)
      .then((res) => {
        if (cancelled) return
        // 삭제로 현재 페이지가 비면 이전 페이지로 물러선다(시안 보정 로직).
        // `page` 가 바뀌면 이 effect 가 다시 돌아 그 페이지를 받아온다.
        const lastPage = Math.max(0, Math.ceil(res.totalElements / PER_PAGE) - 1)
        if (page > lastPage) {
          setPage(lastPage)
          return
        }
        setReceivers(res)
      })
      .catch(() => {
        if (cancelled) return
        setReceivers(null)
        setLoadError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [page, reloadKey])

  /** 실패 문구. 서버에 전역 예외 핸들러가 없어 400 사유가 본문에 실려 오지 않는다(상태코드로만 갈린다). */
  const errorText = (error: unknown, fallback: string): string => {
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      return '이미 삭제된 수신자입니다. 목록을 새로 고쳤습니다.'
    }
    return fallback
  }

  /** 서버 응답 1건으로 그 행만 갈아끼운다(토글·수정 공용). */
  const replaceRow = (updated: NotiReceiverItem) => {
    setReceivers((prev) =>
      prev
        ? { ...prev, content: prev.content.map((r) => (r.rcvrId === updated.rcvrId ? updated : r)) }
        : prev,
    )
  }

  /**
   * 활성 스위치 — **낙관적 갱신**(먼저 화면을 바꾸고 실패하면 되돌린다).
   * 서버 응답이 곧 진실이라 성공 시 응답 1건으로 그 행만 갈아끼운다.
   */
  const handleToggle = async (target: NotiReceiverItem, next: boolean) => {
    if (rowBusy) return
    const useYn = next ? 'Y' : 'N'

    setRowBusy(true)
    setListError(null)
    replaceRow({ ...target, useYn })

    try {
      replaceRow(await updateReceiverActivation(target.rcvrId, useYn))
    } catch (e) {
      replaceRow(target) // 원복
      setListError(errorText(e, '활성화 상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.'))
      if (axios.isAxiosError(e) && e.response?.status === 404) setReloadKey((k) => k + 1)
    } finally {
      setRowBusy(false)
    }
  }

  const closeDelete = useCallback(() => setDeleteTargetId(null), [])

  /**
   * 삭제 — 소프트 삭제라 목록에서만 사라진다. 지운 뒤 서버 기준으로 목록을 다시 맞춘다.
   * 확인 팝업(`ConfirmDeleteModal`)을 거친 뒤에만 부른다.
   */
  const handleDelete = async (rcvrId: number) => {
    if (rowBusy) return
    setRowBusy(true)
    setListError(null)
    try {
      await deleteNotiReceiver(rcvrId)
    } catch (e) {
      if (!(axios.isAxiosError(e) && e.response?.status === 404)) {
        setListError(errorText(e, '수신자를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.'))
        setRowBusy(false)
        return
      }
      setListError(errorText(e, ''))
    }
    setRowBusy(false)
    setReloadKey((k) => k + 1)
  }

  const rows = receivers?.content ?? []

  return (
    <>
      <header className="topbar">
        <div>
          <Breadcrumb group="보고서·알림" page="위험단계 알림 관리" />
          <h1 className="page-title">위험단계 알림 관리</h1>
          <p className="page-sub">민원 급증·위험 단계별 알림 기준 및 수신자 관리</p>
        </div>
        <TopbarActions />
      </header>

      <div className="content content--manage">
        <section className="manage-row">
          {/* ── 수신자 관리 ── */}
          <div className="card recv-card">
            <div className="recv-card__head">
              <h2 className="card-head__title">수신자 관리</h2>
              <button className="btn-edit" type="button" onClick={() => setAddOpen(true)}>
                ＋ 수신자 추가
              </button>
            </div>

            {listError && (
              <p style={{ ...ERROR_TEXT, padding: '0 var(--space-28) var(--space-8)' }} role="alert">
                {listError}
              </p>
            )}

            <div className="rt-wrap">
              <table className="rt">
                <caption className="sr-only">위험단계 알림 수신자 목록</caption>
                <thead>
                  <tr>
                    {HEADERS.map((h) => (
                      <th scope="col" key={h} className={CENTER_COLS.includes(h) ? 'col-center' : undefined}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {loading && (
                    <tr>
                      <td className="col-center" colSpan={COL_COUNT}>
                        불러오는 중...
                      </td>
                    </tr>
                  )}

                  {!loading && loadError && (
                    <tr>
                      <td className="col-center" colSpan={COL_COUNT}>
                        수신자 목록을 불러오지 못했습니다.{' '}
                        <button
                          type="button"
                          className="btn-edit"
                          onClick={() => setReloadKey((k) => k + 1)}
                        >
                          다시 시도
                        </button>
                      </td>
                    </tr>
                  )}

                  {!loading && !loadError && rows.length === 0 && (
                    <tr>
                      <td className="col-center" colSpan={COL_COUNT}>
                        등록된 수신자가 없습니다.
                      </td>
                    </tr>
                  )}

                  {!loading &&
                    !loadError &&
                    rows.map((r) => (
                      <tr key={r.rcvrId}>
                        <td>{r.deptNm ?? '-'}</td>
                        <td>
                          <span className="rt-name">{r.rcvrNm}</span>
                        </td>
                        <td className="rt-email">{r.eml}</td>
                        {/* 서버가 조립한 표시명을 그대로 찍는다 — 프론트 조합 금지. */}
                        <td>{r.regionNm}</td>
                        <td>
                          <span className="grp-set">
                            {r.riskLvlCds.map((code) => (
                              <RiskBadge key={code} code={code} />
                            ))}
                          </span>
                        </td>
                        <td className="col-center">
                          <Switch
                            checked={r.useYn === 'Y'}
                            disabled={rowBusy}
                            label={`${r.rcvrNm} 알림 수신 활성화`}
                            onChange={(next) => {
                              void handleToggle(r, next)
                            }}
                          />
                        </td>
                        <td className="col-center">
                          <button
                            type="button"
                            className="mt-act"
                            disabled={rowBusy}
                            onClick={() => setEditTarget(r)}
                          >
                            수정
                          </button>
                        </td>
                        <td className="col-center">
                          <button
                            type="button"
                            className="rt-del"
                            aria-label={`${r.rcvrNm} 삭제`}
                            title="삭제"
                            disabled={rowBusy}
                            onClick={() => setDeleteTargetId(r.rcvrId)}
                          >
                            <Icon name="close" size={16} />
                          </button>
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>

              {receivers && (
                <Pager
                  total={receivers.totalElements}
                  page={page}
                  setPage={setPage}
                  perPage={PER_PAGE}
                />
              )}
            </div>
          </div>

          {/* ── 알림 기준 ── */}
          <div className="card crit-card">
            <div className="crit-card__head">
              <div className="crit-card__heading">
                <h2 className="card-head__title">알림 기준</h2>
                <p className="crit-card__sub">민원 증가율·최소 건수 기준 알림 발생 단계 설정</p>
              </div>
              <button
                className="btn-edit"
                type="button"
                disabled={criteria.length === 0}
                onClick={() => setCritOpen(true)}
              >
                설정
              </button>
            </div>

            <div className="crit-list">
              {criteriaError && (
                <p className="crit-card__sub" role="alert">
                  알림 기준을 불러오지 못했습니다.
                </p>
              )}

              {!criteriaError && criteria.length === 0 && (
                <p className="crit-card__sub">불러오는 중...</p>
              )}

              {/* 순서는 서버(sort_seq = 심각 → 경고 → 주의) 그대로 — 프론트 재정렬 금지. */}
              {criteria.map((c) => (
                <div className="crit-row" key={c.thrshId}>
                  <RiskBadge code={c.riskLvlCd} className="crit-row__tag" />
                  <span className="crit-row__view">{c.incrsRt}% 이상</span>
                </div>
              ))}

              {/* 최소 건수는 단계와 무관한 **공통 값 1개**라 3행에 반복하지 않는다.
                  `Math.min` 은 설정 모달과 **같은 식**이다 — 다른 값을 고르면 카드와 모달이 어긋난다. */}
              {criteria.length > 0 && (
                <p className="crit-card__sub">
                  공통 · 당일·전일 민원 모두{' '}
                  <b>{Math.min(...criteria.map((c) => c.minCvlcptCnt))}건</b> 이상일 때만 발생
                </p>
              )}
            </div>
          </div>
        </section>
      </div>

      {addOpen && (
        <ReceiverAddModal
          regions={regions}
          onClose={() => setAddOpen(false)}
          onSaved={() => {
            setAddOpen(false)
            setListError(null)
            // 신규 행은 `rcvr_id` 오름차순이라 **목록 맨 뒤**에 붙는다(첫 페이지가 아니다).
            setReloadKey((k) => k + 1)
          }}
        />
      )}

      {editTarget && (
        <ReceiverAddModal
          regions={regions}
          editing={editTarget}
          onClose={() => setEditTarget(null)}
          onSaved={(saved) => {
            setEditTarget(null)
            setListError(null)
            replaceRow(saved)
          }}
        />
      )}

      {critOpen && (
        <CriteriaEditModal
          criteria={criteria}
          onClose={() => setCritOpen(false)}
          onSaved={(saved) => {
            setCriteria(saved)
            setCritOpen(false)
          }}
        />
      )}

      {deleteTargetId !== null && (
        <ConfirmDeleteModal
          message="수신자를 삭제하시겠습니까?"
          onCancel={closeDelete}
          onConfirm={() => {
            const id = deleteTargetId
            setDeleteTargetId(null)
            void handleDelete(id)
          }}
        />
      )}
    </>
  )
}
