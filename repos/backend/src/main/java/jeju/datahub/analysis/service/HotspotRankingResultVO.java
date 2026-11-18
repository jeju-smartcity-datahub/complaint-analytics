package jeju.datahub.analysis.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석레이어 순위 조회 응답. ({@code GET /api/hotspots/ranking})
 *
 * < 가이드 §4 결정: view 별 zone 목록. envelope 에 view/단위만 echo 하고 zones[] 를
 * 함께 내린다. {@link HotspotRankingZoneVO} 의 컬럼은 view 에 따라 의미가 달라진다
 * (가이드 §6 view별 표시 표 참고).
 */
@Schema(description = "분석레이어 순위 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotRankingResultVO {

    /** 응답에 사용된 view (default most-complaint) */
    @Schema(description = "응답에 사용된 view", example = "most-complaint")
    private String view;

    /** 조회 단위 (echo) */
    @Schema(description = "조회 단위", example = "YESTERDAY")
    private String unit;

    /** zone 목록. 0건이어도 빈 배열로 내린다(가이드 §0 의 "결과 0건이어도 정상"). */
    @Schema(description = "zone 목록")
    private List<HotspotRankingZoneVO> zones;
}