package jeju.datahub.analysis.service;

/**
 * 읍면동별 최다 단속유형 1건 (TSK-4322). {@code HotspotRankingMapper.selectEnforcementTopMthd} 결과.
 *
 * <p>서비스가 {@code crdnCnt} 와 나눠 {@link HotspotRankingZoneVO#getTopCrdnMthdRatio()} 를 낸다.
 * 🔴 생성자 매핑이라 컬럼 순서와 필드 순서가 같아야 한다.
 */
public record EnforcementTopMthdRow(
        String emdCd,
        String crdnMthd,
        long mthdCnt
) {
}
