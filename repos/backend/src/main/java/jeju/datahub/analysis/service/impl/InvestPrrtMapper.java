package jeju.datahub.analysis.service.impl;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import jeju.datahub.analysis.service.InvestPrrtDataLastVO;
import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;

/**
 * 투자우선순위 분석 MyBatis 매퍼(TSK-4036). SQL 은 resources/mapper/analysis/InvestPrrtMapper.xml.
 *
 * <p>복합 JOIN·집계라 JPA 가 아니라 MyBatis 다(rule_persistence-jpa-mybatis).
 */
@Mapper
public interface InvestPrrtMapper {

    /**
     * 대상 주차장마다 한 행 — 기본 정보와 4지표 <b>원값</b>(안전 · 민원 · 이용 · 단속).
     *
     * <p>대상은 소멸하지 않은({@code extsh_dt IS NULL}) 노상·공한지를 제외한 공영주차장이다
     * ({@code pklt_type_cd NOT IN ('ONSTR','VACLOT')} — TSK-4347 D1). 이용패턴 유무는 대상을 정하지 않는다
     * (없으면 이용 0점). 🔴 <b>행정구역으로 거르지 않는다</b> — 백분위가 전 지역 대상 전체에서 계산되므로
     * 필터는 서비스가 표시 단계에서 건다.
     *
     * <p>🔴 민원·단속은 <b>사건마다</b> 반경 안의 가장 가까운 대상 주차장 <b>1곳에만</b> 센다
     * (동률은 {@code pklt_id} 오름차순). 경쟁 상대가 <b>대상 주차장끼리</b>라는 점이 구역추천의
     * 배정({@code lotsAndCvAsgn} — 한 읍면동의 전환 후보끼리)과 다르다.
     *
     * <p>🔴 점수·순위·등급은 채우지 않는다 — 계산기의 몫이다. 정렬은 {@code pklt_id} 오름차순.
     *
     * @param param 창({@code bgngDt}·{@code endDtExcl} 반열림) · 이용률 집계 달({@code bgngYm}·{@code endYm} —
     *              시작일이 1일이 아니면 시작 달 제외, TSK-4352 D1)
     *              · 배정 반경({@code rdsM}, m)
     * @return 대상 주차장 목록. 노상·공한지가 아닌 미소멸 주차장이 없으면 <b>빈 목록</b>이다
     */
    List<InvestPrrtLotBaseVO> selectLotBase(InvestPrrtQueryParam param);

    /**
     * 창 안의 사고 건수(TSK-4036 Step 6 — 응답 {@code smry.acdntCnt}).
     *
     * <p>🔴 사고이력 표({@code tb_pklt_acdnt_hstry})에 행이 <b>하나도 없으면 {@code null}</b> 이다 —
     * "자료가 없다" 와 "창 안에 사고가 없었다(0)" 를 구분한다.
     *
     * @param param 창({@code bgngDt}·{@code endDtExcl} 반열림)만 쓴다
     * @return 창 안 사고 건수. 자료가 없으면 {@code null}
     */
    Long selectAcdntCnt(InvestPrrtQueryParam param);

    /**
     * 지표별 최근 자료일(TSK-4352 D2 — 화면 헤더 "지표별 최근 갱신일").
     *
     * <p>🔴 창 안이 아니라 <b>종료일(종료 달) 이하 전체</b>의 최댓값이다 — 창 시작보다 앞이면 그 날짜가 그대로 나온다.
     * 이용률·안전점검·사고는 분석 대상 주차장({@code tgtLotCond})의 자료만 보고, 민원은 반경 배정 없이 사건 조건만 본다.
     *
     * @param param {@code endDtExcl}·{@code endYm} 만 쓴다
     * @return 1행. 칸마다 자료가 없으면 {@code null}
     */
    InvestPrrtDataLastVO selectDataLastDt(InvestPrrtQueryParam param);
}
