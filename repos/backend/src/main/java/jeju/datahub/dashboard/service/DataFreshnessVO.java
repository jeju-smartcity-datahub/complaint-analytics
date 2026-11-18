package jeju.datahub.dashboard.service;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 데이터 최신 갱신 응답. {@code GET /api/dashboard/data-freshness}.
 *
 * <p>화면 상단 {@code 최신 갱신} 표기({@link #latestDt})와 (!) 툴팁 표({@link #items})를 한 번에 준다.
 *
 * <p>🔴 {@link #items} 는 <b>항상 4행</b>이다 — 한 번도 안 돈 잡도
 * {@code lastExcnDt: null} 로 채워 넣는다. 순서는 민원 → 단속 → 공영주차장 현황 → 이용률이며
 * <b>그 순서가 곧 툴팁 표의 행 순서</b>다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DataFreshnessVO {

    /**
     * {@link #items} 의 {@code lastExcnDt} 중 <b>null 이 아닌 값의 최댓값</b>.
     * 네 잡이 모두 한 번도 안 돌았으면 {@code null} 이다.
     */
    private LocalDateTime latestDt;

    /** 원천별 갱신 현황. 🔴 항상 4행(위 설명 참고). */
    private List<DataFreshnessItemVO> items;
}
