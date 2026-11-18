package jeju.datahub.analysis.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 주차장별 <b>3개월 추이 차트</b>의 x축 한 칸 (시안 {@code 추천 구역별 민원 건수 (3개월)}).
 *
 * <p>🔴 <b>배정이 0건인 달도 {@code cnt=0} 으로 반드시 포함한다</b> — 매퍼는 배정이 있는 달만
 * 돌려주므로(이도이동 {@code 1963-7} 은 2행뿐이다) 그대로 내리면 주차장마다 x축 칸 수가 달라져
 * 차트가 어긋난다({@link TrendPointVO} 와 같은 근거). 채우는 쪽은 서비스다.
 *
 * <p>🔴 그래서 {@code cnt} 의 합은 항상 {@link ZoneRcmdtnLotVO#getAsgnCvlcptCntRcnt()} 과 같다 —
 * 어긋나면 월별 집계와 구간 집계가 다른 배정을 본 것이다(매퍼가 CTE 조각을 공유하는 이유).
 */
@Schema(description = "주차장 월별 배정 민원 1칸 (3개월 추이 차트)")
@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ZoneRcmdtnTrendVO {

    /** 연월({@code YYYY-MM}) */
    @Schema(description = "연월 (YYYY-MM)", example = "2025-06")
    private String ym;

    /** 그 달의 배정 민원 건수. 🔴 0 인 달도 담는다 */
    @Schema(description = "그 달의 배정 민원 건수", example = "23")
    private long cnt;
}
