package jeju.datahub.region.service;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 행정동 경계 GeoJSON 응답 envelope. ({@code GET /api/admin-districts})
 *
 * < 결정 (RFC 7946): 응답은 GeoJSON FeatureCollection 이다.
 * * {@code type} = {@code "FeatureCollection"} (고정)
 * * {@code features} = 행정동 1개당 1 Feature 의 배열 (없어도 빈 배열)
 *
 * < {@code Accept-Encoding: gzip} 필수, 클라이언트 캐싱 권장.
 * 74개 행정동 × MultiPolygon 의 응답 크기가 수 MB 가능해 압축이 필수.
 */
@Schema(description = "GeoJSON FeatureCollection — 행정동 경계 전체")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AdminDistrictFeatureCollection {

    /** GeoJSON 고정 값 "FeatureCollection" */
    @Schema(description = "GeoJSON type (항상 \"FeatureCollection\")", example = "FeatureCollection")
    private String type = "FeatureCollection";

    /** Feature 배열. */
    @Schema(description = "Feature 배열 (행정동 1개당 1건)")
    private List<AdminDistrictFeatureVO> features;
}