package jeju.datahub.dashboard.service;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 "민원 집중구역 순위" 카드용 조회 전용 VO. (TSK-4032 D8)
 *
 * <p>⚠️ <b>저장 테이블의 행이 아니다.</b> 예전에는 저장된 분석 결과 테이블을 그대로 읽는
 * MyBatis 결과 타입이었지만, 지금은 {@code jeju.datahub.analysis} 의 <b>실시간 군집 분석</b> 결과
 * ({@link jeju.datahub.analysis.service.HotspotZoneVO})를 카드용으로 추린 것이다
 * — {@code DashboardPanelServiceImpl.getHotspots} 가 채운다.
 *
 * <p>필드 구성은 <b>기존 응답 계약을 그대로 유지</b>하고 {@link #grade}·{@link #gradeNm} 만 더했다.
 * 프론트 변경 폭을 줄이려는 의도이며, 등급 임계값의 단일 출처는
 * {@link jeju.datahub.analysis.service.HotspotGrade} 다(D5).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class HotspotVO {

    /** 순위 (종합점수 내림차순 1부터) */
    private Integer rnk;

    /** 구역명 (구역명 폴백 체인의 결과 — D6) */
    private String zoneNm;

    /** 민원 건수 */
    private Integer cvlcptCnt;

    /** 단속 건수 */
    private Integer crdnCnt;

    /** 종합 점수 (민원건수 × 가중치 + 단속건수 × 가중치 — 정규화 없음, D3) */
    private BigDecimal snthsScr;

    /** 등급코드 (SEVERE | WARN | CAUTION | NORMAL — {@link jeju.datahub.analysis.service.HotspotGrade}) */
    private String grade;

    /** 등급명 (심각 | 경고 | 주의 | 양호) */
    private String gradeNm;

    /** 대표 위도 (EPSG:4326, 군집 중심좌표) */
    private BigDecimal mrkLat;

    /** 대표 경도 (EPSG:4326, 군집 중심좌표) */
    private BigDecimal mrkLot;
}
