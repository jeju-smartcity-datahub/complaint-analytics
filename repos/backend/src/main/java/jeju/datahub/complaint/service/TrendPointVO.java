package jeju.datahub.complaint.service;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 추이 차트의 x축 한 칸. (조회 전용 — MyBatis 평면 행을 서비스가 조립한다)
 *
 * <p>데이터가 없는 칸도 {@code total=0, breakdown=[]} 로 <b>반드시 포함</b>한다 — 빠뜨리면
 * 프론트가 x축 칸 수를 스스로 계산해야 하고, 시간대 분포에서 "빈 시간"이 그래프에서 사라진다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class TrendPointVO {

    /** 버킷 키. HOUR 면 {@code "00"}~{@code "23"}, DAY 면 {@code "2025-12-04"}(ISO). */
    private String bucketKey;

    /** 이 칸의 총건수(= breakdown 합) */
    private long total;

    /** 유형별 분해([{typeCd, count}]). 건수 0 인 유형은 넣지 않는다. */
    private List<TypeCountVO> breakdown;
}
