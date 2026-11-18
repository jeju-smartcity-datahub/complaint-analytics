package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 집중구역 1건. (MyBatis 조회 전용 VO — DBSCAN 군집 1개 = 이 VO 1건)
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 mybatis.configuration.map-underscore-to-camel-case=true 로
 * 자동 처리한다. <b>JPA 엔티티가 아니다</b> — 이번 작업은 조회 전용이라 분석 결과를 저장하지 않는다(D7).
 *
 * <p>일부 필드는 매퍼가 아니라 <b>서비스가 채운다</b>:
 * <ul>
 *   <li>{@link #zoneNm}·{@link #zoneNmSrc} — 매퍼는 ①단속주소 단계까지만 채우고, 비면 서비스가
 *       VWorld POI → 역지오코딩 → {@code {읍면동명} 일대} 순으로 폴백한다(D6).</li>
 *   <li>{@link #grade}·{@link #gradeNm} — {@link HotspotGrade#of(BigDecimal)} 로 판정한다(D5).</li>
 * </ul>
 */
@Schema(description = "집중구역 1건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotZoneVO {

    /** 순위. 종합점수 내림차순 1부터 */
    @Schema(description = "순위 (종합점수 내림차순)", example = "1")
    private int rnk;

    /**
     * 구역명. 폴백 체인(D6)의 결과이며 최종적으로 비지 않는다.
     *
     * <p>매퍼 조회 직후에는 {@code null} 일 수 있다 — 그 상태가 "폴백이 필요한 구역"의 표시다.
     */
    @Schema(description = "구역명", example = "연동 신광로 제원사거리")
    private String zoneNm;

    /** 읍면동명. 구역 중심좌표가 속한 행정동 (TB_ADMDST) */
    @Schema(description = "읍면동명", example = "연동")
    private String emdNm;

    /**
     * 구역명 출처 — 폴백 체인(D6) 중 어느 단계에서 나온 이름인지.
     *
     * <p>{@code CRDN_ADDR}(단속주소) / {@code POI}(VWorld POI 최근접) /
     * {@code REVERSE_GEOCODE}(VWorld 역지오코딩) / {@code EMD}({읍면동명} 일대).
     */
    @Schema(description = "구역명 출처 (CRDN_ADDR | POI | REVERSE_GEOCODE | EMD)", example = "CRDN_ADDR")
    private String zoneNmSrc;

    /** 민원건수. 군집에 속한 민원 합계 */
    @Schema(description = "민원건수", example = "164")
    private int cvlcptCnt;

    /** 단속건수. 군집에 속한 단속 합계 (민원 기반 단속을 빼지 않는다 — D18) */
    @Schema(description = "단속건수", example = "402")
    private int crdnCnt;

    /**
     * 종합점수. {@code 민원건수 × 가중치 + 단속건수 × 가중치} — 정규화도 기간 보정도 없다(D3).
     *
     * <p>{@link BigDecimal} 인 이유: 가중치가 {@code NUMERIC(5,2)} 라 곱셈 결과에 소수부가 생긴다.
     * 부동소수로 받으면 등급 경계(80/60/40) 판정이 오차로 흔들린다.
     */
    @Schema(description = "종합점수 (상한 없음)", example = "259.20")
    private BigDecimal snthsScr;

    /** 점수 내림차순 순위(동점 동순위). 등급 판정 입력이라 응답에 싣지 않는다 (TSK-4321) */
    @JsonIgnore
    @Schema(hidden = true)
    private long scrRnk;

    /** 0점을 뺀 전체 구역 수. {@link #scrRnk} 와 함께 등급을 가른다 (TSK-4321) */
    @JsonIgnore
    @Schema(hidden = true)
    private long scrCnt;

    /** 등급코드. {@link HotspotGrade} 의 이름 (SEVERE | WARN | CAUTION | NORMAL) */
    @Schema(description = "등급코드 (SEVERE | WARN | CAUTION | NORMAL)", example = "SEVERE")
    private String grade;

    /** 등급명. {@link HotspotGrade#getGradeNm()} */
    @Schema(description = "등급명", example = "심각")
    private String gradeNm;

    /** 마커 위도 (EPSG:4326). 군집 중심좌표 */
    @Schema(description = "마커 위도 (EPSG:4326)", example = "33.4869")
    private BigDecimal mrkLat;

    /** 마커 경도 (EPSG:4326). 군집 중심좌표 */
    @Schema(description = "마커 경도 (EPSG:4326)", example = "126.4903")
    private BigDecimal mrkLot;
}
