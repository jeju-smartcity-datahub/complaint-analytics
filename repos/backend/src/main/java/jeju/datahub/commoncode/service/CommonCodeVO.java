package jeju.datahub.commoncode.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 공통코드 VO. parking.TB_COM_CD + TB_CD_GROUP 조회 결과.
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class CommonCodeVO {

    /** 그룹코드 (TB_CD_GROUP.group_cd) */
    private String groupCd;

    /** 그룹명 */
    private String groupNm;

    /** 코드값 */
    private String cdVl;

    /** 코드명 */
    private String cdNm;

    /** 정렬순서 */
    private Integer sortSeq;

    /** 사용여부 (Y/N) */
    private String useYn;

    /** 부가속성1 - 그룹마다 의미가 다르다. ANLS_TYPE_CD 에서는 용도 구분(ANLS/RPTP/BOTH) */
    private String addlAtrb1;
}
