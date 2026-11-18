package jeju.datahub.complaint.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 행정구역명 한 벌 ({@code parking.TB_ADMDST} 의 {@code sgg_nm} + {@code emd_nm}).
 * 민원현황 보고서 생성 전용 MyBatis 조회 VO.
 *
 * <p>🔴 <b>두 칸을 한 번의 조회로 함께 읽으려고</b> 존재한다. 읍면동명만 읽고 시군구명을 요청값에
 * 맡기면 {@code emdCd} 만 온 요청에서 {@code TB_ANLS_EXCN.sgg_nm} 이 비고, 그러면 보고서 조회의
 * {@code e.sgg_nm || ' ' || a.emd_nm} 이 NULL 연결이 되어 지역명이 통째로 사라진다
 * ({@code ComplaintReportNaming#regionNm} 도 같은 이유로 {@code emdNm} 만 오는 조합을 거절한다).
 *
 * <p>🔴 <b>패키지가 {@code service} 바로 아래인 것이 핵심이다</b> — {@code mybatis.type-aliases-package}
 * 가 {@code jeju.datahub.**.service} 라 여기 둔 타입만 XML 에서 <b>짧은 별칭</b>으로 쓸 수 있다.
 * {@code analysis.service.impl} 의 {@code AdmdstNmVO} 를 재사용하면 XML 에 FQN 문자열을 박아야 하고,
 * 그 문자열은 컴파일러가 검사하지 않아 클래스가 옮겨지면 <b>빌드는 통과하고 런타임에 터진다</b>.
 *
 * <p>컬럼(스네이크) → 필드(카멜) 매핑은 {@code map-underscore-to-camel-case=true} 가 자동 처리한다.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ComplaintAdmdstNmVO {

    /** 시군구명 ({@code sgg_nm}). {@code TB_ADMDST} 에서 NOT NULL 이라 조회에 성공하면 항상 값이 있다 */
    private String sggNm;

    /** 읍면동명 ({@code emd_nm}). 같은 이유로 NOT NULL */
    private String emdNm;
}
