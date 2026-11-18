package jeju.datahub.complaint.service.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 추이 집계 매퍼의 평면 결과 행 {@code (bucketKey, typeCd, count)}.
 * 서비스가 bucketKey 로 그룹핑하고 빈 칸을 0 으로 채운 뒤 {@code TrendPointVO} 로 재구성한다.
 *
 * <p>응답에 직접 노출되지 않는 내부 타입이라 {@code service.impl} 에 둔다
 * ({@code DashboardQueryParam} 과 같은 자리).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TrendCountVO {

    /** 버킷 키. HOUR 면 {@code "00"}~{@code "23"}, DAY 면 {@code "2025-12-04"}. */
    private String bucketKey;

    /** 민원유형코드(01~05) */
    private String typeCd;

    /** 해당 (버킷, 유형) 건수 */
    private long count;
}
