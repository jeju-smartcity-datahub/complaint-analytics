package jeju.datahub.region.service.impl;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 행정동 1건의 polygon (ST_AsGeoJSON 결과) — 2026-09-08 추가 (TSK-4125).
 *
 * <p>용도: 분석레이어 shortage view ({@code selectShortage}) 의 in-memory 매핑 입력.
 * {@code fn_jenks_shortage} 함수 시그니처를 보존하면서 각 zone 의 {@code bndryGeoJson} 을
 * 채우기 위해 admdst 매퍼가 일괄 조회 결과를 이 VO 로 받는다. {@code AdminDistrictFeatureVO} 와
 * 분리 — 응답 envelope (type / geometry / properties) 이 불필요한 내부 매핑 전용.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로 자동.
 *
 * <p>tb_admdst.bndry_geom IS NULL 인 zone 은 매퍼 WHERE 에서 제외되므로, polygon 미존재는
 * "조회 결과에 해당 emdCd 엔트리가 없음"으로 표현된다. {@link #bndryGeoJson} 이 항상
 * non-null 인 이유가 이것이며 — shortage view 의 in-memory 매핑 호출자(`HotspotRankingServiceImpl`)
 * 가 결과에 없는 emdCd 를 만나면 fallback 마커 대상으로 분류한다.
 */
@Schema(description = "행정동 polygon (내부 매핑용 — 응답 envelope 제외)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AdmdstBndryGeoVO {

    /** 읍면동 코드 (8자리) */
    @Schema(description = "읍면동 코드 (8자리)", example = "50110137")
    private String emdCd;

    /**
     * PostGIS {@code ST_AsGeoJSON(bndry_geom)} 결과 (JSON 문자열).
     * {@code tb_admdst.bndry_geom IS NULL} 인 행은 매퍼 WHERE 조건에서 제외되므로
     * 본 VO 의 {@code bndryGeoJson} 은 항상 non-null 이지만, 호출자 안전을 위해 nullable 유지.
     */
    @Schema(description = "GeoJSON polygon (JSON 문자열)",
            example = "{\"type\":\"Polygon\",\"coordinates\":[[[126.5,33.4],...]]}")
    private String bndryGeoJson;
}
