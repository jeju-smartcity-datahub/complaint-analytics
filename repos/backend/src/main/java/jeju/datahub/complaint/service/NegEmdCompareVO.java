package jeju.datahub.complaint.service;

/**
 * §8 응답 — 부정 민원 상위 지역 카드 1행. ({@code GET /api/complaints/gis} 응답의
 * {@code negEmdCompare[]} 원소)
 *
 * <h2>가이드 §8 결정</h2>
 * <ul>
 *   <li>{@link #rank} — 1-base. {@code negativeRatio} DESC, {@code emdCd} ASC
 *       tie-breaker (결정적, 2026-09-04 갱신). 이전엔 negCnt DESC 였으나 가이드
 *       정의("부정 비율 순") 와 정합.</li>
 *   <li>{@link #negativeCount} — 현재 기간 {@code aprs_cd='NEGATIVE'} 건수.
 *       §7 의 cvlcptCnt 와 다른 의미.</li>
 *   <li>{@link #totalCount} — 그 zone 의 감성 분석 완료된 전체 distinct 민원
 *       건수. 부정 비율 분모 (가이드 §8 envelope).</li>
 *   <li>{@link #sentimentDist} — 긍정/보통/부정 분포. 시안의 sentiment bar UI
 *       원천 (§8 envelope, 2026-09-04 갱신으로 동봉).</li>
 *   <li>{@link #negativeRatio} — 그 zone 전체 민원 중 부정 비율 (0.0~1.0,
 *       §3 KPI {@code negativityRatio} 와 같은 단위 — 그 zone 의 부정비율이지
 *       전체 평균이 아니다).</li>
 *   <li>{@link #deltaPct} — 부정 건수의 증감율(%). prevNegativeCount=0 이면 분모
 *       1 로 치환.</li>
 *   <li>{@link #deltaPctInferred} — {@code prevNegativeCount=0} 일 때 부정확.</li>
 * </ul>
 *
 * <p>§7 과 달리 {@code topTypeCd/topTypeNm/topTypeRatio} 가 없음 — 부정은 단일
 * 차원이라 최다 유형 의미가 없다 (가이드 §8 자체 결정, §7 응답에 대응되는 필드
 * 없음).
 *
 * <p>필드명 — 가이드 §8 envelope 의 응답 키와 정합. 이전 라운드
 * ({@code negCnt}/{@code prevCnt}) 는 §7 의 cvlcptCnt 와 헷갈려서 가이드 envelope
 * 의 {@code negativeCount}/{@code prevNegativeCount} 로 통일 (2026-09-04 갱신).
 *
 * <p>Jackson 기본 직렬화는 record 컴포넌트명을 JSON 키로 사용하므로 nested record
 * {@link SentimentDist} 의 필드명이 곧 JSON 키가 된다.
 */
public record NegEmdCompareVO(
        int rank,
        String emdCd,
        String emdNm,
        long negativeCount,
        long totalCount,
        SentimentDistVO sentimentDist,
        double negativeRatio,
        int deltaPct,
        boolean deltaPctInferred
) {

    // §8 감성 분포는 §3 KPI 의 공통 {@link SentimentDistVO} 를 재사용한다.
    // 동일 형태의 record 를 중복 정의하면 타입/테스트가 분산되므로 (코파일럿
    // 2026-09-04 코멘트 정합), Jackson 직렬화 결과(JSON 키)도 동일.
}
