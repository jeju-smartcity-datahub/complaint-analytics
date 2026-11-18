package jeju.datahub.complaint.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 민원현황(GIS) 핵심 API {@code GET /api/complaints/gis} 응답 객체 (flat 구조 — 가이드 §1).
 *
 * * 응답 필드 매핑 (가이드 §1 + §3 + §7):
 * * * {@link #lastSyncDt}       — {@code TB_CVLCPT.load_dt} 의 MAX값. §1 Header 원천
 * * * {@link #totalCount}       — 현재 필터 적용된 <b>민원 행 수</b> (§3 KPI 1번).
 *   산식: {@code count(*)} — 🔁 2026-10-02 TSK-4197 (사용자 결정)으로 2026-09-04·09-21 의 distinct
 *   기준({@code COUNT(DISTINCT cvlcpt_cn)} → {@code (cvlcpt_cn, cvlcpt_type_cd)})이 폐기됐다.
 *   같은 문구가 중복이 아니라 정형화된 신고 문구였기 때문 — 근거는
 *   {@code mapper/complaint/ComplaintMapper.xml} 상단 「민원 집계 기준」 주석.
 *   §4 분석레이어 `view=most-complaint` 의 `cvlcptCnt` · 대시보드 KPI 와 <b>동일 베이스</b>이고,
 *   §7 지역별 현황의 합과도 같다({@code totalCount == Σ cvlcptCnt}).
 * * * {@link #negativityRatio}  — 부정 비율 (0.0~1.0, §3 KPI 2번).
 *   산식: {@code sentimentDist.negative / (sentimentDist.positive + sentimentDist.neutral + sentimentDist.negative)}.
 *   분모 = 분석 완료된 행의 합 ({@code aprs_cd IS NOT NULL}). 현 데이터(2026-08-31) {@code aprs_cd IS NULL} 0건이라
 *   사실상 {@code sentimentDist.negative / totalCount} 와 동치. 의도(미분석 행 제외)는 유지.
 * * * {@link #sentimentDist}    — 감정 분포 (§3 KPI 2번 미니 막대)
 * * * {@link #enforcementCount} — 필터 적용 후 단속 집중 지역 중 <b>"심각" 등급 동 수</b> (§3 KPI 3번,
 *   <b>2026-09-30 TSK-4167 D16 갱신</b>).
 *   산식: {@code FLOOR(COUNT(DISTINCT tb_crdn_dsctn.emd_cd) * 0.1)} — 단속이 있는 동 전체(모집단)의 상위 10%.
 *   이전 정의("단속이 한 건이라도 등록된 모든 zone 수") 폐기 — 등급 개념이 없어 KPI 43 vs 화면 리스트
 *   "심각" 4개동으로 어긋났다(9월 실측). 지금은 4 로 리스트와 같다.
 * * * {@link #shortageCount}    — 필터 적용 후 <b>주차민원이 있는 zone 수의 상위 10%</b> (§3 KPI 4번,
 *   🔁 <b>2026-10-02 TSK-4197 갱신 — Jenks 등급 분류를 쓰지 않는다</b>).
 *   정의: PSDR = tb_pblpklt.pklt_cpcty 합산 / tb_cvlcpt 주차 관련 5종 민원 수 →
 *   모집단 = {@code fn_jenks_shortage(…, NULL::text)} 중 {@code out_cvlcpt_sum > 0} 인 zone 수 →
 *   그 <b>상위 10%</b>({@code FLOOR(… * 0.1)}, 본 매퍼의 selectShortageCount 가 직접 집계).
 *   🔴 <b>등급("심각")으로 모집단을 줄이던 종전 설명은 이제 틀렸다.</b> 2026-09-04 의 "Jenks 심각 등급
 *   zone 수" → 2026-09-30 TSK-4167 D16 의 "심각 안에서 다시 상위 10%" → 2026-10-02 TSK-4197 의
 *   현 정의로 이어진 변경이다. 등급과 백분위 두 층위가 겹쳐 숫자를 서로 설명할 수 없었던 것이 이유다
 *   (종전 KPI 전용 래퍼 {@code parking.fn_shortage_count} 는 더 이상 호출하지 않는다).
 *   실측 2026-10-02(9월 전역): 모집단 67 → KPI 6 (종전 39 → 3).
 *   🔴 두 값의 {@code 10%} 임계값이 프론트({@code legendConstants.PCT_10})와 백엔드 SQL 두 곳에 있다 —
 *   한쪽만 고치면 KPI 와 리스트가 다시 어긋난다.
 * * * {@link #emdCompare}       — §7 지역별 민원 현황 카드 목록 (count &gt; 0 인 전체 + rank/deltaPct 등)
 * * {@link #negEmdCompare}   — §8 부정 민원 상위 지역 카드 목록 (부정 카운트 + ratio + deltaPct)
 *
 * * {@code enforcementCount} / {@code shortageCount} 는 데이터 적재 producer batch 미구현 시 0 반환 —
 *   가이드 §3 KPI 표의 "단속 집중 지역" / "수요 부족 지역" 행과 시.
 *   ⚠️ {@code FLOOR(N * 0.1)} 이라 모집단이 1~9 인 구간에서도 0 이 된다 (0 = "데이터 없음" 이 아니다).
 *
 * * {@code emdCompare[]} / {@code negEmdCompare[]} 는 §3 라운드 추가 — 가이드 §0 표의
 *   컴포넌트 7 (지역별 민원현황) / 컴포넌트 8 (부정 민원 상위 지역) 이 참조.
 *
 * * {@code bgngYmd} / {@code endYmd} 는 TSK-4164 D3 추가 — <b>서버가 실제로 적용한 조회 구간</b>을
 *   응답이 되비춘다. 화면의 조회 단위는 {@code YESTERDAY}/{@code WEEK}/{@code MONTH} 라
 *   <b>프론트가 실제 날짜를 모르고</b>, {@code TB_ANLS_EXCN.anls_bgng_ymd}/{@code anls_end_ymd} 는
 *   {@code CHAR(8)} 날짜를 요구한다. 자정을 넘기면 화면이 본 구간과 저장된 구간이 달라지므로
 *   내보내기 조건은 이 두 값을 그대로 되보낸다 (집중구역 {@code exportCriteria} 와 같은 규약).
 *   형식은 {@code YYYYMMDD} — {@code DateTimeFormatter#BASIC_ISO_DATE}.
 */
public record ComplaintKpiVO(
        LocalDateTime lastSyncDt,
        long totalCount,
        double negativityRatio,
        SentimentDistVO sentimentDist,
        long enforcementCount,
        long shortageCount,
        List<EmdCompareVO> emdCompare,
        List<NegEmdCompareVO> negEmdCompare,
        /** 실제로 적용된 조회 시작일 {@code YYYYMMDD} — unit 을 해소한 결과 (TSK-4164 D3) */
        String bgngYmd,
        /** 실제로 적용된 조회 종료일 {@code YYYYMMDD} (당일 포함) */
        String endYmd
) {
}