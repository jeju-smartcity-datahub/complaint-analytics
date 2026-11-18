package jeju.datahub.analysis.service;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 분석레이어 히트맵 조회 응답. ({@code GET /api/hotspots/heatmap})
 *
 * <p>순위 응답({@link HotspotRankingResultVO})과 같은 envelope 관례 — view/unit 을 echo 하고
 * {@code points[]} 를 함께 내린다. 🔴 {@code totalCnt} 는 같은 조건의 순위 응답 합계
 * ({@code most-complaint} 면 {@code zones[].cvlcptCnt}, {@code enforcement} 면 {@code zones[].crdnCnt})와
 * 같아야 한다 — 매퍼의 WHERE 가 순위 쿼리와 같은 식인 이유다.
 */
@Schema(description = "분석레이어 히트맵 결과")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotHeatmapResultVO {

    /** 응답에 사용된 view (most-complaint · enforcement) */
    @Schema(description = "응답에 사용된 view", example = "most-complaint")
    private String view;

    /** 조회 단위 (echo). 기간 직접 지정이면 null — 순위 응답과 같은 규칙. */
    @Schema(description = "조회 단위 (기간 직접 지정이면 null)", example = "MONTH", nullable = true)
    private String unit;

    /** 칸 크기(도). 0.001 고정 — 위도 약 111m · 경도 약 93m. */
    @Schema(description = "칸 크기(도) — 0.001 고정", example = "0.001")
    private BigDecimal cellDeg;

    /** 전 칸의 건수 합 = sum(points[].cnt). 서비스가 계산한다. */
    @Schema(description = "전 칸의 건수 합 (= sum(points[].cnt))", example = "4118")
    private long totalCnt;

    /** 칸 목록. 0건이어도 빈 배열로 내린다. 순서는 의미 없다. */
    @Schema(description = "칸 목록 (순서 무의미, 0건이면 빈 배열)")
    private List<HotspotHeatmapPointVO> points;
}
