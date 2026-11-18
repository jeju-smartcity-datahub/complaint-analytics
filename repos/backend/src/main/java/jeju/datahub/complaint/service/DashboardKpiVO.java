package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * KPI 카드용 조회 전용 VO.
 *
 * <p>"총 민원" 카드는 {@link #total} 을 쓴다. 종전에는 "불법주차 발생" 카드가 <b>같은 모수</b>
 * (parking.TB_CVLCPT 전 유형 COUNT)를 재사용했지만, 카드 제목이 "불법주정차 단속 건수" 로 바뀌며
 * {@link #crdnCnt}(parking.TB_CRDN_DSCTN 실집계)로 분리했다 — <b>재사용은 끝났다</b>(TSK-4169).
 *
 * <p>"위험 단계 발생" 카드는 <b>다른 테이블</b>(parking.TB_RISK_STP_NOTI_HSTRY — 알림 이력)을
 * 집계하므로 {@link #riskAlert} 로 분리해 담는다. 한 응답에 묶는 이유는 세 카드가 같은 필터로
 * 동시에 갱신되기 때문이다(TSK-4058 D5).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class DashboardKpiVO {

    /** 현재 기간 민원수 */
    private long total;

    /** 직전 동일 길이 기간 민원수 */
    private long prevTotal;

    /** 증감률(%). round((total-prevTotal)/prevTotal*100). prevTotal=0 이면 0. */
    private int deltaPct;

    /**
     * 현재 기간 단속 건수. <b>민원과 모수가 다르다</b> — parking.TB_CRDN_DSCTN 집계다.
     * 종전에는 프론트가 {@link #total} 을 "불법주차 발생" 카드에 재사용했는데,
     * 카드 제목이 `불법주정차 단속 건수` 로 바뀌며 실집계로 분리했다(TSK-4169).
     */
    private long crdnCnt;

    /**
     * 공영주차장 현황: 선택 지역의 활성 주차장 수 (parking.TB_PBLPKLT). (TSK-4169 · 2026-10-01)
     *
     * <p>🔴 <b>{@link #total}·{@link #crdnCnt} 와 성격이 다르다</b> — 저 둘은 <b>기간 집계</b>
     * ("그 기간에 몇 건 발생했나")이지만 이 값은 <b>시점 현황</b>("지금 몇 개 있나")이다.
     * 그래서 조회단위·기간({@code unit}/{@code startDt}/{@code endDt})을 <b>무시</b>하고
     * {@code sggCd}(행정구역)만 따른다 — 조회단위를 바꿔도 값이 변하지 않는 것이 정상이다.
     *
     * <p>소멸분({@code extsh_dt IS NOT NULL})은 제외한다 — 수집 배치가 "이번 수집에 없었다" 고
     * 판정한 주차장은 현황에서 뺀다.
     *
     * <p>2026-10-01 이전에는 2번 카드가 {@link #crdnCnt}(불법주정차 단속 건수)였다.
     * 카드가 "공영주차장 현황" 으로 바뀌며 이 필드가 생겼고, {@code crdnCnt} 는
     * <b>지우지 않고 계속 내린다</b>(화면만 안 쓴다 — 되돌리기 쉽게).
     */
    private long pkltCnt;

    /** 위험단계 알림 집계. 별도 테이블·별도 모수라 중첩 객체로 분리한다(TSK-4058 D5). */
    private RiskAlertKpiVO riskAlert;
}
