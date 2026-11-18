package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 집중구역 보고서의 엑셀 재계산 데이터 {@code wbk}.
 *
 * <p>{@code rslt_json.wbk} 에 저장되고 생성 응답·스냅샷 응답에 그대로 실린다. 엑셀(프론트 ExcelJS)이
 * B3(민원 가중치)를 바꿨을 때 서버와 같은 식으로 다시 계산하는 데 필요한 것만 담는다 —
 * 후보 구역 전부 + 지표별 최댓값 + 가중치별 0점 제외 군집 수.
 *
 * <p>점수·등급은 싣지 않는다 — 엑셀이 계산하고, 저장 당시 결과의 정본은 기존 {@code zones} 다.
 * 등급 비율은 응답의 {@code gradeCriteria} 를 쓴다.
 */
@Schema(description = "엑셀 재계산 데이터 (후보 구역 + 상수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotWorkbookVO {

    /**
     * 적용 민원 가중치(%) — 서비스가 실제 계산에 쓴 계수 {@code HotspotWeights.cvlcptWgt() × 100}.
     * 🔴 엑셀 B3 초기값의 유일한 출처이며 {@link #scrCnts} 에 같은 값의 행이 반드시 있다. 반올림하지 않는다.
     */
    @Schema(description = "적용 민원 가중치(%) — 엑셀 B3 초기값", example = "60")
    private BigDecimal cvlcptWgvl;

    /** 전체 군집 중 최대 민원 건수 (군집이 없으면 0) */
    @Schema(description = "최대 민원 건수 (전체 군집)", example = "51")
    private int maxCvlcptCnt;

    /** 전체 군집 중 최대 단속 건수 (군집이 없으면 0) */
    @Schema(description = "최대 단속 건수 (전체 군집)", example = "120")
    private int maxCrdnCnt;

    /** 민원 가중치별 0점 제외 군집 수 — 0~100 정수 101행 + 적용 가중치가 비정수면 1행. 가중치 오름차순 */
    @Schema(description = "민원 가중치별 0점 제외 군집 수 (가중치 오름차순)")
    private List<HotspotScrCntVO> scrCnts;

    /** 후보 구역 — 0~100% 어느 정수 가중치(및 적용 가중치)에서든 주의 이상에 드는 구역. {@code cid} 오름차순 */
    @Schema(description = "후보 구역 (cid 오름차순)")
    private List<HotspotWorkbookZoneVO> zones;
}
