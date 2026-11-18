package jeju.datahub.lifezone.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 지도에 그리는 격자 셀 1칸.
 *
 * <p>🔴 <b>값&gt;0 격자를 전부 싣는다</b> — 순위 {@code limit} 과 무관하다. 500m 전 기간
 * 실측이 597칸, 100m 는 2,015칸이다. 셀을 그리는 목록과 순위 목록은 <b>서로 다른 집합</b>이다.
 *
 * <p>🔴 <b>{@link #ring} 은 bbox 가 아니라 꼭짓점 링</b>이다. 격자는 EPSG:4326 에서 축정렬이
 * 아니라({@code TB_GRID} 가 미터 좌표계에서 만들어졌다) {@code L.rectangle} 로 그리면 셀이 서로
 * 겹친다. 꼭짓점 4점을 그대로 내려 {@code L.polygon} 으로 그리게 한다.
 *
 * <p>좌표 순서는 <b>{@code [위도, 경도]}</b>다 — GeoJSON 은 {@code [lon, lat]} 이지만 Leaflet 이
 * {@code [lat, lng]} 이라, 서비스가 {@code ST_AsGeoJSON} 결과를 뒤집어 담는다. 닫힌 링의
 * 마지막 점(= 첫 점)은 빼서 <b>4점</b>이다.
 */
@Schema(description = "격자 셀 1칸")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class LifezoneCellVO {

    /** 격자코드. 경계격자 중복 제거 후의 유일 키다 */
    @Schema(description = "격자코드", example = "다사5108")
    private String gridCd;

    /** 분석값. 현재 유일 구현 유형({@code CVLCPT_CNT})에서는 민원 건수다 */
    @Schema(description = "분석값", example = "386")
    private int anlsVl;

    /** 등급코드. {@link LifezoneGradeScale#gradeOf(int)} 판정 결과 */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL | LOW)", example = "SEVERE")
    private String grade;

    /** 격자 중심 위도 (EPSG:4326). 라벨 위치·지도 이동에 쓴다 */
    @Schema(description = "격자 중심 위도 (EPSG:4326)", example = "33.4869")
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) */
    @Schema(description = "격자 중심 경도 (EPSG:4326)", example = "126.4901")
    private BigDecimal ctrLot;

    /**
     * 격자 꼭짓점 링. {@code [[위도, 경도], …]} 4점 (닫는 점 제외).
     *
     * <p>빈 목록으로 시작한다 — {@code null} 이면 프론트가 셀 하나 때문에 렌더 전체를 잃는다.
     */
    @Schema(description = "격자 꼭짓점 링 [[위도, 경도], …] 4점")
    private List<List<BigDecimal>> ring = new ArrayList<>();

    /** 읍면동명. 격자 중심이 속한 행정동. 매칭이 없으면 null */
    @Schema(description = "읍면동명 (매칭 없으면 null)", example = "연동")
    private String emdNm;

    /**
     * 구역명. 셀 전량은 {@code {읍면동명} 격자 {격자코드}} 폴백이다.
     *
     * <p>{@code emdNm} 이 없으면 {@code 격자 {격자코드}} 다. <b>순위 행만</b>
     * {@code ranking[].cntrPoiNm} 에서 VWorld POI 이름으로 대체된다 — 셀 전량을 태우지 않기 위해서다
     * (외부 호출은 요청당 {@code analysis.zone-name.max-lookups-per-run} 으로 묶이고 넘는 순위 행은 폴백 이름이다).
     */
    @Schema(description = "구역명 (셀은 폴백 이름)", example = "연동 격자 다사5108")
    private String cntrPoiNm;
}
