package jeju.datahub.lifezone.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 혼잡도 보고서의 엑셀 재계산 데이터 {@code wbk}.
 *
 * <p>엑셀이 계수 10개를 바꿨을 때 서버와 같은 식(월 단속·월 민원 → 추정량 → H → {@code round(H×10000)} → 등급)으로
 * 다시 계산하는 데 필요한 원자료다. SQL·산식 변경은 없다.
 */
@Schema(description = "혼잡도 엑셀 재계산 데이터 (원자료 + 개월수 + 계수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneCngstWorkbookVO {

    /**
     * 개월수 — {@code LifezoneCngstServiceImpl.monthsOf} 결과 double 그대로.
     * 🔴 반올림하지 않는다 — 엑셀의 H 가 마지막 자리에서 갈려 동점 정렬·반올림이 달라진다.
     */
    @Schema(description = "개월수 (부분월 일수 비례 · 반올림 없음)", example = "1.0")
    private double mnthCnt;

    /** 조회 시작일 (YYYYMMDD) — 엑셀이 개월수를 다시 계산하는 대안의 재료 */
    @Schema(description = "조회 시작일", example = "20260601")
    private String bgngYmd;

    /** 조회 종료일 (YYYYMMDD, 포함) */
    @Schema(description = "조회 종료일", example = "20260630")
    private String endYmd;

    /**
     * 분석 계수 10행 — {@code AnlsVrblScope.LIFEZONE_CNGST} 순서. {@code intOnlyYn} 전부 N.
     * 🔴 {@code CNGST_GRD_BNDRY*} 는 <b>점 단위 원값</b>(예 88) — ×100 반올림은 엑셀이 한다.
     */
    @Schema(description = "분석 계수 (적용값·범위)")
    private List<AnlsWorkbookVrblVO> vrbls;

    /** 원자료 — 공급&gt;0 격자 전부. 🔴 SQL 순서({@code gx, gy}) 그대로(동점 깨기 키) */
    @Schema(description = "원자료 (공급>0 격자 전부 · gx,gy 순)")
    private List<LifezoneCngstWorkbookCellVO> cells;
}
