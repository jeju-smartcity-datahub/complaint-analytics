package jeju.datahub.analysis.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 엑셀 재계산 상수 (TSK-4338 D2·D5). (MyBatis 조회 전용 VO — {@code selectHotspotWorkbookConst})
 *
 * <p>지표별 최댓값은 <b>전체 군집</b> 기준이다(후보 밖 군집 포함 — {@code maxed} CTE 와 같다).
 * {@link #scrCnts} 는 가중치 집합(0~100 정수 101개 ∪ 적용 민원 가중치)마다 1행이며 군집이 없어도 행 수는 같다.
 */
@Schema(description = "엑셀 재계산 상수")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotWorkbookConstVO {

    /** 전체 군집 중 최대 민원 건수 (군집이 없으면 0) */
    @Schema(description = "최대 민원 건수", example = "51")
    private int maxCvlcptCnt;

    /** 전체 군집 중 최대 단속 건수 (군집이 없으면 0) */
    @Schema(description = "최대 단속 건수", example = "120")
    private int maxCrdnCnt;

    /** 민원 가중치별 0점 제외 군집 수. 가중치 오름차순 */
    @Schema(description = "민원 가중치별 0점 제외 군집 수 (가중치 오름차순)")
    private List<HotspotScrCntVO> scrCnts;
}
