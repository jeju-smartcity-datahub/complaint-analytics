package jeju.datahub.complaint.service;

import java.math.BigDecimal;

/**
 * 보고서 §5 — 주차 수요 부족 지역 카드 1행. ({@code POST /api/complaints/reports} 201 ·
 * {@code GET /api/reports/{rptpId}/complaint} 응답의 {@code shortageCompare[]} 원소)
 *
 * <h2>🔴 {@code HotspotRankingZoneVO} 를 재사용하지 않는다</h2>
 * 그 VO 의 {@code bndryGeoJson}(행정동 경계 polygon)은 <b>수 MB</b> 다. 보고서는 이 목록을
 * {@code tb_anls_rslt.rslt_json} <b>한 칸</b>에 굳히므로 재사용하면 보고서 1건이 수 MB 가 된다.
 * 생성 경로가 {@code rank("shortage")} 결과를 옮겨 담을 때 경계·마커를 버린다.
 *
 * <h2>🔴 {@code zoneNm} 이 아니라 {@link #emdNm} 이다</h2>
 * 같은 응답의 {@link EmdCompareVO}·{@link NegEmdCompareVO} 와 칸 이름을 맞춘다.
 *
 * <h2>🔴 {@link #shortageRatio} 와 {@link #shortageDeltaPp} 는 <b>단위가 다르다</b></h2>
 * <ul>
 *   <li>{@link #shortageRatio} — <b>0~1 소수 4자리</b>. 화면·문서가 표시할 때 <b>×100</b> 한다
 *       (예: {@code 0.7838} → {@code 78.4%}). 분모 0 이면 {@code null}.</li>
 *   <li>{@link #shortageDeltaPp} — <b>이미 %p 단위</b>(현재 부족률 − 직전 부족률, ×100 된 값,
 *       소수 1자리). 🔴 여기에 다시 ×100 하지 않는다. 직전 분모가 0 이면 {@code null}.</li>
 * </ul>
 *
 * <h2>나머지 필드</h2>
 * <ul>
 *   <li>{@link #rank} — 1-base 순위. {@code RANK()} 라 동률은 같은 순위다.</li>
 *   <li>{@link #pkltCpctySum} — 공급 면수. {@code 0} 은 정상값(공영주차장 없음).</li>
 *   <li>{@link #demandIndex} — 주차 수요 = 공급 면수 + 주차 관련 민원 수. 면(面)과 건(件)을
 *       더한 지수라 단위가 없다.</li>
 *   <li>{@link #deltaPctInferred} — "직전 분모가 0 이라 {@link #shortageDeltaPp} 를 낼 수
 *       없었다" (🔴 {@link CrdnCompareVO#deltaPctInferred()} 의 "분모 1 치환" 과 의미가 다르다).</li>
 * </ul>
 *
 * <p>⚠️ 직전 구간의 공급 면수는 현재와 같은 값이다({@code tb_pblpklt} 에 기간 개념이 없다) —
 * 따라서 {@link #shortageDeltaPp} 는 실질적으로 <b>민원 증감만</b> 반영한다.
 */
public record ShortageCompareVO(
        long rank,
        String emdCd,
        String emdNm,
        BigDecimal shortageRatio,
        BigDecimal pkltCpctySum,
        BigDecimal demandIndex,
        BigDecimal shortageDeltaPp,
        boolean deltaPctInferred,
        /**
         * 직전 동일 길이 구간 부족률 (보고서 §5 {@code 전기 부족률} 열).
         * 🔴 {@link #shortageRatio} 와 같은 <b>0~1 소수 4자리</b>다. 직전 분모 0 이거나 구 스냅샷이면 {@code null}.
         */
        BigDecimal prevShortageRatio
) {
}
