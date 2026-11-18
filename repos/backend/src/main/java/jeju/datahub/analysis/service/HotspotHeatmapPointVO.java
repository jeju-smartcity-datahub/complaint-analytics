package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 히트맵 칸 1개. ({@code GET /api/hotspots/heatmap} 응답 원소 — TSK-4295)
 *
 * <p>원점 좌표가 아니라 <b>0.001° 칸으로 반올림한 좌표</b>({@code ROUND(lat::numeric, 3)})와 그 칸의 건수다.
 * 원점을 그대로 내리지 않는 이유는 응답 크기(1년 단속 약 8만 점)와 개별 신고 위치 노출이다.
 * 컬럼(스네이크) → 필드(카멜) 매핑은 {@code map-underscore-to-camel-case} 로 자동 처리.
 */
@Schema(description = "히트맵 칸 1개 (0.001° 반올림 좌표 + 건수)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotHeatmapPointVO {

    /** 칸 위도 — 원천 {@code NUMERIC(12,10)} 을 소수 3자리로 반올림. */
    @Schema(description = "칸 위도 (EPSG:4326, 소수 3자리 반올림)", example = "33.487")
    private BigDecimal lat;

    /** 칸 경도 — 원천 {@code NUMERIC(13,10)} 을 소수 3자리로 반올림. */
    @Schema(description = "칸 경도 (EPSG:4326, 소수 3자리 반올림)", example = "126.490")
    private BigDecimal lot;

    /** 칸 안의 건수 — 민원(view=most-complaint) 또는 단속(view=enforcement) 행 단위 {@code COUNT(*)}. */
    @Schema(description = "칸 안의 건수 (민원 또는 단속 행 단위 COUNT(*))", example = "12")
    private long cnt;
}
