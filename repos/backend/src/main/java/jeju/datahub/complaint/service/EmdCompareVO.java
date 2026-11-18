package jeju.datahub.complaint.service;

/**
 * §7 응답 — 읍면동별 민원 현황 카드 1행. ({@code GET /api/complaints/gis} 응답의
 * {@code emdCompare[]} 원소)
 *
 * < 가이드 §7 결정:
 * <ul>
 *   <li>{@link #rank} — 1-base. {@code cvlcptCnt} DESC, {@code emdCd} ASC tie-breaker (deterministic)</li>
 *   <li>{@link #cvlcptCnt} — 현재 기간 <b>민원 행 수</b> (🔁 2026-10-02 TSK-4197 — 2026-09-04·09-21 의
 *       distinct 기준 폐기. 근거는 {@code mapper/complaint/ComplaintMapper.xml} 상단 주석).
 *       산식: {@code count(*)} — §4 분석레이어 `view=most-complaint` 의 `cvlcptCnt` 와
 *       <b>동일 베이스</b>. 가이드 §3 KPI {@code totalCount} 와도 같은 베이스라
 *       <b>{@code totalCount == Σ cvlcptCnt}</b> 가 성립한다(집계 단위만 다르다).</li>
 *   <li>{@link #deltaPct} — 증감율(%). 양수=증가=나쁨, 음수=감소=좋음 (§7 자체 규칙, §5 무관)</li>
 *   <li>{@link #deltaPctInferred} — {@code prevCnt=0} 일 때 분모 1 로 치환한 결과라 부정확</li>
 *   <li>{@link #topTypeRatio} — 그 유형이 그 zone 전체에서 차지하는 비율 (0.0~1.0, §3 KPI {@code negativityRatio} 와 동일 단위)</li>
 * </ul>
 *
 * * 가이드 §7 응답에 "4개 필드 추가 (2026-08-21): rank / topTypeCd / topTypeNm / topTypeRatio" 라 적힘.
 *   그 외 필드({@code emdCd}/{@code emdNm}/{@code cvlcptCnt}/{@code deltaPct}/{@code deltaPctInferred}) 는 §3 / 응답 정의상 포함.
 *
 * <p>컴포넌트명 {@code cvlcptCnt}/{@code prevCnt} 등 — 2026-08-31 Q 라운드 갱신. SQL alias 의
 * PostgreSQL 집계 함수 이름과의 충돌 회피 + 의미 분명화. 응답 JSON 키도 같은 이름으로 노출.
 */
public record EmdCompareVO(
        int rank,
        String emdCd,
        String emdNm,
        long cvlcptCnt,
        int deltaPct,
        String topTypeCd,
        String topTypeNm,
        double topTypeRatio,
        boolean deltaPctInferred,
        /**
         * 직전 동일 길이 구간 민원 건수 (🆕 TSK-4311 — 보고서 §3 {@code 전기 민원} 열).
         * 🔴 boxed 다 — 이 키가 없는 구 스냅샷이 {@code 0} 으로 읽히면 문서에 거짓 {@code 0건} 이 찍힌다.
         * {@code null} = 값 없음, {@code 0} = 실제 0건.
         */
        Long prevCnt
) {
}