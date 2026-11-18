package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * §8 매퍼 결과 평면 row — selectNegEmdCompare 의 1행. (PostgreSQL FILTER 로
 * 부정/전체/감성 분포 동시 집계)
 *
 * <p>POJO + setter 인 이유 — {@link EmdCompareRow} 와 같은 record 매핑 한계
 * 우회 (2026-08-31 Q 라운드 실측). 응답 VO({@link NegEmdCompareVO})는 record
 * 그대로.
 *
 * <p>SQL alias (camelCase): {@code emdCd}, {@code emdNm}, {@code negativeCount},
 * {@code prevNegativeCount}, {@code totalCount},
 * {@code positiveCount}/{@code neutralCount}/(부정은 {@code negativeCount} 재사용),
 * {@code negativeRatio}.
 *
 * <p>2026-09-04 갱신 — 가이드 §8 envelope 에 맞춰 {@code totalCount} 와
 * {@code sentimentDist} 분포 필드를 추가 (이전엔 부정/직전 부정의 두 카운트만
 * 응답 envelope 에 동봉 → 시안의 sentiment bar 를 그릴 수 없었다). 가이드
 * §8 정합 — 응답에 totalCount/sentimentDist 가 모두 동봉되어야 §8 NegEmdCompareCard
 * 가 긍정/보통/부정 bar 를 표시할 수 있다.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NegEmdCompareRow {

    /** 현재 기간 부정 민원 건수 (§8 negativeCount) — 가이드 §8 envelope 응답 키 */
    private long negativeCount;

    /** 직전 동일 길이 구간 부정 민원 건수 (§8 prevNegativeCount, deltaPct 분자) */
    private long prevNegativeCount;

    /**
     * 그 zone 전체 민원 (감성 분석 완료) 건수 — 가이드 §8 envelope 응답 키.
     * 분모 = positive + neutral + negative 의 distinct cvlcpt_cn 합.
     */
    private long totalCount;

    /** 감성 분포 — 긍정 (§8 sentimentDist.positive) */
    private long positiveCount;

    /** 감성 분포 — 보통 (§8 sentimentDist.neutral) */
    private long neutralCount;

    // 감성 분포 — 부정 (§8 sentimentDist.negative): 별도 필드 없음.
    // SQL alias 도 제거됐고, 서비스에서 {@link #negativeCount} 를 그대로
    // sentimentDist.negative 로 재사용한다 (코파일럿 2026-09-04 코멘트 정합 —
    // 동일 DISTINCT/FILTER 의 중복 컬럼 회피).

    /** 그 zone 전체 민원 중 부정 비율 (0.0~1.0, SQL NULLIF 계산) */
    private Double negativeRatio;

    /** 읍면동 코드 (8자리) */
    private String emdCd;

    /** 읍면동명 (TB_ADMDST LEFT JOIN) */
    private String emdNm;
}
