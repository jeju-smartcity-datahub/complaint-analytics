package jeju.datahub.analysis.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 하위 유형 묶음 1건의 가중치 합계. {@link AnalysisTypeWeightVO#getTrgtSums()} 의 원소.
 *
 * <p>묶음 = {@code (anls_type_cd, anls_trgt_cd)}. 가중치(WGVL) 합계 100 검사와 재정규화가 이 단위로
 * 이뤄지므로, 화면은 대메뉴 전체 합({@code stngVlSum})이 아니라 이 값을 묶음마다 보여 준다.
 * 매퍼 SELECT 에 없는 파생값이라 <b>서비스가</b> 채운다.
 */
@Schema(description = "하위 유형 묶음 1건의 가중치 합계")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class AnalysisTrgtSumVO {

    /** 분석내용코드 (anls_trgt_cd — null 이면 대메뉴 공통 묶음) */
    @Schema(description = "분석내용코드 (null 이면 대메뉴 공통 묶음)", example = "CNGST", nullable = true)
    private String anlsTrgtCd;

    /** 분석내용명 (tb_com_cd.cd_nm — 대메뉴 공통이거나 이름 미등재면 null) */
    @Schema(description = "분석내용명 (null 이면 대메뉴 공통 또는 이름 미등재)", example = "혼잡도", nullable = true)
    private String anlsTrgtNm;

    /**
     * 묶음의 가중치 합계(%) — {@code vrbl_se_cd='WGVL'} 이고 {@code use_yn='Y'} 인 행의 {@code stngVl} 합.
     *
     * <p>{@code wgvlYn='N'} 인 묶음(계수만 있다)은 0 이다. 100 판정은 문자열이 아니라 값으로 비교한다.
     */
    @Schema(description = "묶음의 가중치 합계(%) — WGVL·use_yn=Y 행 합", example = "100")
    private BigDecimal stngVlSum;

    /** 가중치여부 — 묶음에 WGVL·use_yn=Y 행이 하나라도 있으면 'Y'(합계 100 검사 대상), 없으면 'N' */
    @Schema(description = "가중치여부 — 합계 100 검사 대상 묶음이면 Y", example = "Y")
    private String wgvlYn;
}
