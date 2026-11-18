package jeju.datahub.analysis.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import jeju.datahub.analysis.service.DataRangeVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;

/**
 * 구역추천(공유주차제 적용) 분석 MyBatis 매퍼. SQL 은 resources/mapper/analysis/ZoneRcmdtnShareMapper.xml.
 *
 * <p>복합 JOIN·집계라 JPA 가 아니라 MyBatis 다.
 *
 * <p>🔴 요금제 트랙의 {@link ZoneRcmdtnMapper} 와 <b>파일을 나눴지만 민원 조건은 같다</b> — 같은 화면의
 * 두 트랙이 "월평균 민원" 을 다르게 세면 안 된다. 조건을 {@code <sql>} 조각으로 공유하지는 않고
 * 같은 3줄을 그대로 적는다(XML 머리 주석 참고).
 */
@Mapper
public interface ZoneRcmdtnShareMapper {

    /**
     * 읍면동 전체의 판정 입력 — 민원 건수·민원 최다 좌표 1곳의 건수·공영주차장 개소·면수(공한지 포함/제외).
     *
     * <p>{@code TB_ADMDST} 를 왼쪽에 두고 {@code LEFT JOIN} 하므로 <b>원천이 0건인 동도 빠지지 않는다</b>
     * (그 동은 값이 0 이다). 정렬은 {@code emd_cd}.
     *
     * <p>🔴 <b>판정(검토 등급·주의 표시)·월평균·100면당 비율은 채우지 않는다</b> — 서비스가 이 값으로
     * 계산한다. 개월 수도 서비스가 만든다(매퍼는 건수 합계만 낸다).
     *
     * @param cvFrom   민원 구간 시작(<b>포함</b>). {@code ocrn_dt} 가 {@code timestamp} 라
     *                 {@link LocalDateTime} 이다
     * @param cvToExcl 민원 구간 종료(<b>미포함</b> — 반열림 구간의 상한 = 종료일 + 1일)
     * @param trendFrom    1차 이전 블록 시작(<b>포함</b>) = 조회 시작 − D일. 이전 블록 = {@code [trendFrom, cvFrom)}.
     * 기간 하한 미달이면 {@code null} — 추세 3칸이 0 이다
     * @param splitMid     폴백 앞 블록 종료(<b>미포함</b>) = 조회 시작 + D/2일. 앞 블록 = {@code [cvFrom, splitMid)}.
     *                     기간 하한 미달이면 {@code null}
     * @param splitMidExcl 폴백 뒤 블록 시작(<b>포함</b>) = {@code cvToExcl} − D/2일. 뒤 블록 =
     *                     {@code [splitMidExcl, cvToExcl)}. D 가 홀수면 {@code splitMid + 1일} 이라 가운데 1일을
     *                     버린다. 기간 하한 미달이면 {@code null}
     * @return 읍면동 목록. 행정동 마스터 기준이라 비지 않는다
     */
    List<ZoneRcmdtnShareBaseVO> selectShareBase(@Param("cvFrom") LocalDateTime cvFrom,
                                                @Param("cvToExcl") LocalDateTime cvToExcl,
                                                @Param("trendFrom") LocalDateTime trendFrom,
                                                @Param("splitMid") LocalDateTime splitMid,
                                                @Param("splitMidExcl") LocalDateTime splitMidExcl);

    /**
     * 이 화면이 세는 민원의 보유 구간(min/max {@code ocrn_dt}) — 응답의 자료 구간 안내용이다.
     *
     * <p>🔴 집중구역의 {@code HotspotAnalysisMapper.selectDataRange} 를 재사용하지 않는다 — 그쪽은
     * 단속 구간이 섞인다. 이 화면은 민원만 세므로 민원 구간만 낸다.
     *
     * @return 보유 구간({@code YYYYMMDD}). 대상 민원이 없으면 두 값이 모두 {@code null} 이다
     */
    DataRangeVO selectDataRange();
}
