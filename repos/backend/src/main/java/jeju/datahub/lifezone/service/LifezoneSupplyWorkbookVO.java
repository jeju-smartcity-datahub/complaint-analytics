package jeju.datahub.lifezone.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 보고서의 엑셀 재계산 데이터 {@code wbk} (TSK-4340 Step 1 · D4·D5).
 *
 * <p>엑셀이 최소 월평균 민원 N·경계 4개를 바꿨을 때 서버와 같은 식(포함 {@code 민원×30 ≥ N×prdDays} ·
 * 등급 {@code 민원×100 ≥ 경계×수요지수})으로 다시 계산하는 데 필요한 것만 담는다.
 */
@Schema(description = "수급 엑셀 재계산 데이터 (후보 격자 + 계수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneSupplyWorkbookVO {

    /** 조회 일수 — SQL {@code #{prdDays}} 와 같은 값({@code LifezoneSupply.prdDaysOf}) */
    @Schema(description = "조회 일수", example = "31")
    private int prdDays;

    /** 분석 계수 5행 — {@code AnlsVrblScope.LIFEZONE_SPLY} 순서 */
    @Schema(description = "분석 계수 (적용값·범위)")
    private List<AnlsWorkbookVrblVO> vrbls;

    /** 후보 격자 — N = {@code min_vl} 기준 전부. 🔴 서버 순위 키 순(SQL {@code ORDER BY rnk} 그대로) */
    @Schema(description = "후보 격자 (서버 순위 순)")
    private List<LifezoneSupplyWorkbookCellVO> cells;
}
