package jeju.datahub.region.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * GeoJSON Feature 1건 (행정동 경계).
 *
 * <
 * * {@code geometryRaw} 는 PostGIS {@code ST_AsGeoJSON(bndry_geom)} 결과(JSON 문자열) 그대로.
 *   매퍼 XML 의 {@code AS geometryRaw} 와 직접 매핑 (snake → camel 자동) — 서비스가
 *   Jackson 으로 JsonNode 변환해 {@link #geometry} 에 옮겨 담는다.
 * * {@code propertiesRaw} 는 PostgreSQL {@code json_build_object(...)} 결과(JSON 문자열).
 *   서비스가 Jackson {@code readValue(String, Map.class)} 로 {@link #properties} 에 옮긴다.
 * * {@link #geometry} / {@link #properties} 는 응답 시 JSON 객체로 직렬화되는 필드.
 *
 * * 컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동.
 */
@Schema(description = "GeoJSON Feature — 행정동 1건의 polygon")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AdminDistrictFeatureVO {

    /** GeoJSON 고정 값 "Feature" */
    @Schema(description = "GeoJSON type (항상 \"Feature\")", example = "Feature")
    private String type = "Feature";

    /**
     * PostGIS {@code ST_AsGeoJSON(bndry_geom)} 결과를 그대로 받는 임시 필드.
     * 서비스 단계에서 {@link com.fasterxml.jackson.databind.ObjectMapper#readTree(String)} 로
     * JsonNode 로 변환해 {@link #geometry} 에 옮긴다.
     */
    @Schema(hidden = true)
    private String geometryRaw;

    /** GeoJSON geometry 객체 (Polygon 또는 MultiPolygon). */
    @Schema(description = "GeoJSON geometry 객체")
    private Object geometry;

    /**
     * PostgreSQL {@code json_build_object(...)} 결과를 그대로 받는 임시 필드.
     * 서비스가 Jackson {@code readValue(String, Map.class)} 로 {@link #properties} 에 옮긴다.
     */
    @Schema(hidden = true)
    private String propertiesRaw;

    /** GeoJSON properties — emdCd / emdNm / sggCd */
    @Schema(description = "GeoJSON properties (emdCd, emdNm, sggCd)")
    private java.util.Map<String, Object> properties;
}