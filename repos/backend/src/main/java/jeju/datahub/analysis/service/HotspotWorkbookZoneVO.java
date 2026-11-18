package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 엑셀 재계산용 후보 구역 1건. (MyBatis 조회 전용 VO — {@code selectHotspotWorkbookZones})
 *
 * <p>후보 = 민원 가중치 0~100%(정수) 및 적용 민원 가중치 중 <b>어느 하나에서든 주의 이상에 드는 군집</b>의 합집합이다.
 * 점수·등급은 싣지 않는다 — 엑셀이 B3(민원 가중치)로 다시 계산하고, 저장 당시 결과는 기존 {@code zones} 가 정본이다.
 *
 * <p>{@link HotspotZoneVO} 를 재사용하지 않는 이유: {@code wbk.zones} 는 {@link #cid} 를 내보내야 한다
 * (엑셀 표시 순번의 마지막 정렬키 — {@code top.rnk} 의 {@code ROW_NUMBER} 와 같다).
 *
 * <p>{@link #zoneNm}·{@link #zoneNmSrc} 는 매퍼가 ①단속주소 단계까지만 채운다 — 비면 서비스가 폴백한다.
 */
@Schema(description = "엑셀 재계산용 후보 구역 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotWorkbookZoneVO {

    /** DBSCAN 군집 번호. 같은 조건이면 표시 순번의 동점 정렬키다(실행마다 다를 수 있어 식별자로 쓰지 않는다) */
    @Schema(description = "군집 번호 (동점 정렬키)", example = "17")
    private int cid;

    /** 구역명. 폴백 체인의 결과 — 매퍼 조회 직후에는 {@code null} 일 수 있다 */
    @Schema(description = "구역명", example = "연동 대로변")
    private String zoneNm;

    /** 읍면동명. 구역 중심좌표가 속한 행정동 (TB_ADMDST) */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /** 민원건수. 군집에 속한 민원 합계 */
    @Schema(description = "민원건수", example = "51")
    private int cvlcptCnt;

    /** 단속건수. 군집에 속한 단속 합계 */
    @Schema(description = "단속건수", example = "32")
    private int crdnCnt;

    /** 구역명 출처 (CRDN_ADDR | POI | REVERSE_GEOCODE | EMD). 폴백 판정용이라 응답에 싣지 않는다 */
    @JsonIgnore
    @Schema(hidden = true)
    private String zoneNmSrc;

    /** 중심 위도 (EPSG:4326). 구역명 폴백·표시 구역과의 이름 짝짓기용이라 응답에 싣지 않는다 */
    @JsonIgnore
    @Schema(hidden = true)
    private BigDecimal mrkLat;

    /** 중심 경도 (EPSG:4326). {@link #mrkLat} 와 같은 규칙 */
    @JsonIgnore
    @Schema(hidden = true)
    private BigDecimal mrkLot;
}
