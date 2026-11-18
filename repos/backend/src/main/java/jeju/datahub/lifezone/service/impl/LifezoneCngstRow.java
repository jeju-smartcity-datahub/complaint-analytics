package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 혼잡도 격자 집계 1행 (매퍼 결과 전용 VO). (TSK-4090)
 *
 * <p>🔴 혼잡지수·등급·세 상태 판정은 <b>여기에 없다</b> — SQL 은 격자별 <b>집계 원자료</b>까지
 * 내고, 모델 계수(설정값)를 쓰는 산정은 서비스가 한다(수급과 같은 분리).
 */
@Getter
@Setter
@ToString
public class LifezoneCngstRow {

    /** 격자 X 인덱스 = {@code floor(EPSG:5179 X / 격자크기)} */
    private long gx;

    /** 격자 Y 인덱스 = {@code floor(EPSG:5179 Y / 격자크기)} */
    private long gy;

    /** 공영주차면 합계 (항상 &gt; 0 — 공급 0 격자는 매퍼가 거른다) */
    private int pkltCpctySum;

    /** 공영주차장 수 */
    private int pkltCnt;

    /** 고정형 단속 건수 — 고정형·주행형·버스장착형 CCTV */
    private int nFixed;

    /** PDA 단속 건수 */
    private int nPda;

    /** 그 밖의 단속 건수 — 민원 기원 단속(공익제보·국민신문고 …)·수기보행등록 포함 */
    private int nManual;

    /** 같은 기간 창의 민원건수 (보조값 · NO_ENF 판정용) */
    private int cvlcptCnt;

    /** 격자 내 공영주차장 최빈 읍면동코드 */
    private String emdCd;

    /** 시군구명 (tb_admdst 조인 결과 · 매칭 없으면 null) */
    private String sggNm;

    /** 읍면동명 (tb_admdst 조인 결과 · 매칭 없으면 null) */
    private String emdNm;

    /** 격자 중심 위도 (EPSG:4326) */
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) */
    private BigDecimal ctrLot;

    /** 격자 경계 GeoJSON — EPSG:5179 정방형을 4326 으로 되돌린 사각형이다 */
    private String ringJson;

    /**
     * 격자 중심 키로 찾은 구역명 캐시 이름 (TSK-4339 — {@code parking.tb_zone_nm_cache} LEFT JOIN).
     * 출처가 {@code POI}·{@code REVERSE_GEOCODE} 일 때만 채워지고 그 밖(캐시 없음·{@code NOT_FOUND})은 null.
     * 🔴 Row 전용 — 응답 VO 에 이 키를 새로 만들지 않는다(셀 {@code cntrPoiNm} 으로만 옮긴다).
     */
    private String cacheZoneNm;
}
