package jeju.datahub.analysis.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 투자우선순위 — 지표별 최근 자료일(TSK-4352 D2). {@code InvestPrrtMapper.selectDataLastDt} 결과 전용.
 *
 * <p>MyBatis 조회 결과 VO 다 — 응답 VO({@link InvestPrrtResultVO})와 겸용하지 않는다
 * (rule_persistence-jpa-mybatis). 서비스가 같은 이름 칸으로 옮긴다.
 *
 * <p>🔴 창 안이 아니라 <b>종료일(종료 달) 이하 전체</b>의 최댓값이다. 자료가 없으면 {@code null}.
 * 배치 실행일이 아니라 지표 원천의 <b>자료 날짜</b>다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class InvestPrrtDataLastVO {

    /** 종료일 이하 주차 민원(좌표 있음 · 유형 01~05) 최근 발생일 (YYYYMMDD) */
    private String cvlcptLastYmd;

    /** 종료 달 이하 대상 주차장의 회전율 값 있는 최근 달 (YYYYMM) */
    private String utztnLastYm;

    /** 종료일 이하 대상 주차장의 최근 안전점검일 (YYYYMMDD) */
    private String sftyChckLastYmd;

    /** 종료일 이하 대상 주차장의 최근 사고일 (YYYYMMDD) */
    private String acdntLastYmd;
}
