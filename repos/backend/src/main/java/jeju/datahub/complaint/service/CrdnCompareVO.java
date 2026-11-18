package jeju.datahub.complaint.service;

/**
 * 보고서 §4 — 단속 집중 지역 카드 1행. ({@code POST /api/complaints/reports} 201 ·
 * {@code GET /api/reports/{rptpId}/complaint} 응답의 {@code crdnCompare[]} 원소)
 *
 * <h2>🔴 {@code HotspotRankingZoneVO} 를 재사용하지 않는다</h2>
 * 그 VO 에는 {@code bndryGeoJson}(행정동 경계 polygon — <b>수 MB</b>)이 붙어 있다. 보고서는
 * 이 목록을 {@code tb_anls_rslt.rslt_json} <b>한 칸</b>에 통째로 굳히므로, 재사용하면 보고서
 * 1건이 수 MB 가 된다. 그래서 보고서가 그리는 칸만 담은 <b>경량 VO</b> 를 따로 둔다.
 * 생성 경로({@code ComplaintReportServiceImpl})가 {@code rank("enforcement")} 결과를 옮겨
 * 담을 때 {@code bndryGeoJson}·{@code mrkLat}·{@code mrkLot}·{@code typeCnts} 를 버린다.
 *
 * <h2>🔴 {@code zoneNm} 이 아니라 {@link #emdNm} 이다</h2>
 * 같은 응답의 {@link EmdCompareVO}·{@link NegEmdCompareVO} 가 {@code emdNm} 을 쓴다 —
 * 한 응답 안에서 같은 뜻의 칸 이름이 갈리지 않게 맞춘다
 * (원본 {@code HotspotRankingZoneVO.zoneNm} 이 여기서 이름을 바꾼다).
 *
 * <h2>필드</h2>
 * <ul>
 *   <li>{@link #rank} — 1-base 순위. 매퍼 SQL 의 {@code RANK()} 라 <b>동률은 같은 순위</b>이고
 *       그만큼 다음 순위가 건너뛴다(1,1,3).</li>
 *   <li>{@link #crdnCnt} — 현재 기간 단속 건수(정렬 기준).</li>
 *   <li>{@link #cvlcptCnt} — 같은 동·기간의 민원 건수 (행 단위 {@code COUNT(*)} —
 *       🔁 2026-10-02 DISTINCT 기준 폐기).</li>
 *   <li>{@link #deltaPct} — 단속 건수 증감률(%). 직전 동일 길이 구간 대비이며, 직전 0건이면
 *       분모 1 치환 + {@link #deltaPctInferred}=true. 산출 불가면 {@code null}.</li>
 *   <li>{@link #deltaPctInferred} — "직전 0건이라 분모를 1 로 치환했다"
 *       (🔴 {@link ShortageCompareVO#deltaPctInferred()} 와 의미가 다르다).</li>
 * </ul>
 */
public record CrdnCompareVO(
        long rank,
        String emdCd,
        String emdNm,
        long crdnCnt,
        long cvlcptCnt,
        Integer deltaPct,
        boolean deltaPctInferred,
        /**
         * 직전 동일 길이 구간 단속 건수 (보고서 §4 {@code 전기 단속} 열).
         * 🔴 boxed 다 — 이 키가 없는 구 스냅샷이 {@code 0} 으로 읽히면 문서에 거짓 {@code 0건} 이 찍힌다.
         */
        Long prevCrdnCnt,
        /**
         * 그 동·기간의 최다 단속유형 ({@code crdn_mthd} 원문, 보고서 §4 {@code 세부 내역}).
         * 🔴 이 키가 없는 구 스냅샷은 {@code null} 이고, 문서가 종전 문구({@code 민원 N건 대비})로 그린다.
         */
        String topCrdnMthd,
        /**
         * 최다 단속유형 비율 = 그 유형 건수 ÷ {@link #crdnCnt} (0~1).
         * 🔴 boxed 다 — 구 스냅샷이 {@code 0.0} 으로 읽히면 문서에 거짓 {@code 0%} 가 찍힌다.
         */
        Double topCrdnMthdRatio
) {
}
