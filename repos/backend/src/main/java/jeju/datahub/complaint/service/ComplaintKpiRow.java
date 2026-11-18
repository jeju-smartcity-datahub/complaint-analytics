package jeju.datahub.complaint.service;

/**
 * §3 KPI 매퍼 결과 평면 row. MyBatis 가 컬럼명(snake_case → camelCase) 으로 자동 매핑.
 *
 * <p>응답 VO ({@link ComplaintKpiVO}) 가 {@code sentimentDist} 를 nested record 로 가져가는
 * 구조라서 매퍼/서비스 단계에서는 평면 필드로 받고 서비스에서 합성한다.
 *
 * * 가이드 §3 결정: totalCount = 현재 필터 적용된 민원 전체 (분류 99·emd NULL 제외).
 * * sentimentDist 분모 = 감성 분석 완료된 전체 (aprs_cd IS NOT NULL). totalCount 와 다름.
 *   ⚠️ 실제 데이터(2026-08-31) aprs_cd NULL 0건 → 분모 = totalCount. 의도(미분석 제외)는 유지.
 * * negativityRatio = negativeCount / totalSntmntCount (분모 0이면 0.0).
 *
 * <p>⚠️ {@code lastSyncDt} 는 이 row 에서 빠졌다 — 가용 데이터가 구간 밖일 때 {@code MAX(load_dt)}
 *    가 {@code null} 로 떨어져 헤더 부제가 비는 회귀를 차단하려고 {@code selectLastSyncDt} 로
 *    분리했다. 서비스는 `ComplaintServiceImpl#getKpi` 안에서 직접 매퍼 결과를 VO 에 주입.
 */
public record ComplaintKpiRow(
        long totalCount,
        long positiveCount,
        long neutralCount,
        long negativeCount,
        long totalSntmntCount
) {
}