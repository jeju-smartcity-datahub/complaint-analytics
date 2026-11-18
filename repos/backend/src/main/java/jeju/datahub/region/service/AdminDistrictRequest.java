package jeju.datahub.region.service;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 행정동 경계 GeoJSON 조회 요청. ({@code GET /api/admin-districts})
 *
 * < 행정동 경계는 정적 자산 — {@code unit} / {@code cvlcptTypeCd} 같은
 * 시간·민원 필터를 받지 않는다. 행정구역 필터({@code sggCd} / {@code emdCd})만 받는다.
 *
 * < 결정 (RFC 7946): 응답은 GeoJSON FeatureCollection 이며 PostGIS
 * {@code ST_AsGeoJSON(bndry_geom)} 으로 직렬화한다. 행정구역 매핑은 다른 SELECT 와
 * 같은 {@code LEFT(emd_cd, 5) = #{sggCd}} 패턴.
 */
@Schema(description = "행정동 경계 GeoJSON 조회 요청")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AdminDistrictRequest {

    /** 시군구 코드(5자리). null 이면 전체 행정동 (74개). */
    @Schema(description = "시군구 코드(50110=제주시, 50130=서귀포시). null=전체 74개")
    private String sggCd;

    /** 읍면동코드(8자리). sggCd 와 동시 지정 시 emdCd 우선. */
    @Schema(description = "읍면동코드(8자리) — sggCd 보다 우선")
    private String emdCd;
}