package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 생활권 격자 집계 매퍼의 <b>조회 전용</b> 행. 서비스가 이것을 가공해
 * {@code LifezoneCellVO} / {@code LifezoneRankVO} 로 옮긴다(rule_persistence-jpa-mybatis:
 * 엔티티와 매퍼 결과 타입을 겸용하지 않는다).
 *
 * <p>필드명은 SELECT 별칭과 {@code map-underscore-to-camel-case} 로 맞물린다 —
 * 별칭을 고치면 이 필드도 함께 고쳐야 한다(안 그러면 컴파일은 통과하고 값만 null 이 된다).
 */
@Getter
@Setter
@ToString
public class LifezoneCellRow {

    /** 격자코드. 경계격자 중복 제거(D7) 후의 유일 키다 */
    private String gridCd;

    /** 분석값 — 격자 안의 민원 건수 */
    private int anlsVl;

    /**
     * 격자 중심 위도 (EPSG:4326). 표시·저장용이자 구역명 POI 조회 좌표다
     * (D10 개정 — TSK-4339: 종전 민원 무게중심은 조건마다 좌표가 움직여 캐시 키가 어긋났다).
     */
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) — 구역명 POI 조회 좌표이기도 하다(D10 개정 — TSK-4339) */
    private BigDecimal ctrLot;

    /**
     * 격자 경계의 GeoJSON({@code ST_AsGeoJSON(bndry_geom)}). {@code MULTIPOLYGON} 이라
     * 서비스가 첫 폴리곤의 외곽 링에서 {@code [lat, lot]} 순서로 꼭짓점을 뽑는다(D6).
     */
    private String ringJson;

    /**
     * 민원유형 상위 2종 세부 내역 JSON 문자열 (D16).
     *
     * <p>🔴 SQL 에서 {@code ::text} 로 캐스팅해 뽑는다 — jsonb 를 그대로 매핑하면 {@code PGobject}
     * 가 와서 {@code String} 필드에 붙지 않는다. 역직렬화는 서비스가 Jackson 으로 한다.
     */
    private String dtlJson;

    /** 읍면동명. 격자 중심이 속한 행정동. 매칭이 없으면 null */
    private String emdNm;

    /**
     * 격자 중심 키로 찾은 구역명 캐시 이름 (TSK-4339 — {@code parking.tb_zone_nm_cache} LEFT JOIN).
     * 출처가 {@code POI}·{@code REVERSE_GEOCODE} 일 때만 채워지고 그 밖(캐시 없음·{@code NOT_FOUND})은 null.
     * 🔴 Row 전용 — 응답 VO 에 이 키를 새로 만들지 않는다(셀 {@code cntrPoiNm} 으로만 옮긴다).
     */
    private String cacheZoneNm;
}
