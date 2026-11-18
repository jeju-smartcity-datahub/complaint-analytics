package jeju.datahub.analysis.service.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 행정구역명 한 벌 ({@code parking.TB_ADMDST} 의 {@code sgg_nm} + {@code emd_nm}). MyBatis 조회 전용 VO.
 *
 * <p>🔴 <b>두 칸을 한 번의 조회로 함께 읽으려고</b> 존재한다. 읍면동명만 읽고 시군구명을 요청값에
 * 맡기면, {@code emdCd} 만 온 요청에서 {@code HotspotAnalysisServiceImpl#resolveRegion} 이
 * {@code sggNm=null} 을 돌려주는 탓에 {@code TB_ANLS_EXCN.sgg_nm} 이 비고, 그러면
 * {@code ReportMapper.selectReportById} 의 {@code e.sgg_nm || ' ' || a.emd_nm} 이 NULL 연결이 되어
 * 보고서의 <b>지역명이 통째로 NULL</b> 이 된다. 그래서 저장 전에 이 조회로 시군구명까지 채운다.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 {@code map-underscore-to-camel-case=true} 로 자동 처리한다.
 * {@code impl} 패키지에 두는 이유는 {@link ZoneNameCacheVO} 와 같다 — API 응답에 그대로 나가지
 * 않는 내부 조회 결과다(밖으로 나가는 것은 조립된 {@code regionNm} 문자열 하나뿐).
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AdmdstNmVO {

    /** 시군구명 ({@code sgg_nm}). {@code TB_ADMDST} 에서 NOT NULL 이라 조회에 성공하면 항상 값이 있다 */
    private String sggNm;

    /** 읍면동명 ({@code emd_nm}). 같은 이유로 NOT NULL */
    private String emdNm;
}
