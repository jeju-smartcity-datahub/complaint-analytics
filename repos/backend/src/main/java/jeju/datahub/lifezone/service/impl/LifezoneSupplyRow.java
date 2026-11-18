package jeju.datahub.lifezone.service.impl;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 수급 격자 집계 1행 (매퍼 결과 전용 VO). (TSK-4089)
 *
 * <p>🔁 <b>부족률·순위·모집단 수는 SQL 이 낸다</b>(TSK-4258 — 종전 사분면 판정 폐기).
 * 서비스는 {@link #cvlcptCnt} 와 {@link #dmndIdx} 로 부족률 등간격 등급만 판정한다(D6·D19 — {@link #rnk} 는 리스트 순서용).
 * 산식·정렬 규칙은 {@code LifezoneSupplyMapper.xml} 상단 주석에 있다.
 */
@Getter
@Setter
@ToString
public class LifezoneSupplyRow {

    /** 격자 X 인덱스 = {@code floor(EPSG:5179 X / 격자크기)} */
    private long gx;

    /** 격자 Y 인덱스 = {@code floor(EPSG:5179 Y / 격자크기)} */
    private long gy;

    /** 민원건수 */
    private int cvlcptCnt;

    /** 격자 안 민원의 고유 좌표 개수 */
    private int dstncPtCnt;

    /** 격자 내 최빈 읍면동코드 */
    private String emdCd;

    /** 시군구명 (tb_admdst 조인 결과 · 매칭 없으면 null) */
    private String sggNm;

    /** 읍면동명 (tb_admdst 조인 결과 · 매칭 없으면 null) */
    private String emdNm;

    /** 공영주차면 합계 (주차장 없으면 0) */
    private int pkltCpctySum;

    /** 공영주차장 수 (없으면 0) */
    private int pkltCnt;

    /**
     * 격자 중심 위도 (EPSG:4326). POI 구역명 조회 좌표이기도 하다
     * (D10 개정 — TSK-4339: 종전 TSK-4272 의 민원 무게중심을 대체했다).
     */
    private BigDecimal ctrLat;

    /** 격자 중심 경도 (EPSG:4326) — POI 구역명 조회 좌표이기도 하다(D10 개정 — TSK-4339) */
    private BigDecimal ctrLot;

    /** 격자 경계 GeoJSON — EPSG:5179 정방형을 4326 으로 되돌린 사각형이다 */
    private String ringJson;

    /** 수요지수 = 공영주차면 합계 + 민원건수 (D5) */
    private int dmndIdx;

    /** 부족률 = 민원 ÷ (면수 + 민원), 소수 4자리 (D5 — 공간 집중 분석 B 와 같은 식·반올림) */
    private BigDecimal shrtRt;

    /**
     * 면당민원의 역수 = 면수 ÷ 민원, 소수 4자리.
     *
     * <p>⚠️ <b>정렬 근거 확인용·미노출</b> — 응답(CellVO)에 내보내지 않는다. B 도 정렬 근거로만
     * 쓰고 화면은 부족률만 보인다. 🔴 순위는 이 반올림값이 아니라 SQL 의 반올림 전 값으로 매긴다.
     */
    private BigDecimal psdr;

    /** 부족 순위 (1부터, 동점 없음 — {@code row_number()}) */
    private int rnk;

    /** 모집단 격자 수 = 월평균 최소 기준을 넘은 격자 수 (순위의 최댓값) */
    private int totGridCnt;

    /**
     * 격자 중심 키로 찾은 구역명 캐시 이름 (TSK-4339 — {@code parking.tb_zone_nm_cache} LEFT JOIN).
     * 출처가 {@code POI}·{@code REVERSE_GEOCODE} 일 때만 채워지고 그 밖(캐시 없음·{@code NOT_FOUND})은 null.
     * 🔴 Row 전용 — 응답 VO 에 이 키를 새로 만들지 않는다(셀 {@code cntrPoiNm} 으로만 옮긴다).
     */
    private String cacheZoneNm;
}
