package jeju.datahub.dashboard.service;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 대시보드 "최근 리포트" 카드용 조회 전용 VO.
 * (parking.TB_RPTP + parking.TB_COM_CD JOIN — MyBatis 전용)
 *
 * <p>상태명(sttsNm)은 SQL JOIN(TB_COM_CD, group_cd=RPTP_STTS_CD)에서 변환한다.
 * 컬럼(스네이크) → 필드(카멜) 매핑은 map-underscore-to-camel-case=true 로 자동 처리.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ReportVO {

    /** 리포트 ID (rptp_id, BIGINT) */
    private Long rptpId;

    /** 리포트명 (rptp_nm) */
    private String rptpNm;

    /** 상태코드 (stts_cd) */
    private String sttsCd;

    /** 상태명 (TB_COM_CD.cd_nm, group_cd=RPTP_STTS_CD) */
    private String sttsNm;

    /** 파일형식 (file_fmt) */
    private String fileFmt;

    /** 생성 일시 (crt_ymd, TIMESTAMP) */
    private LocalDateTime crtYmd;
}
