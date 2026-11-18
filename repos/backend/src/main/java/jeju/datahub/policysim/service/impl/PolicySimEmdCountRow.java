package jeju.datahub.policysim.service.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 읍면동 1곳의 기준선 원자료 (매퍼 결과 전용). (TSK-4037 Step 2)
 *
 * <p>🔴 산식(÷M · m · g_sgg · occ)은 여기에 없다 — 계수가 설정값이라 {@link PolicySimBaselineCalculator} 가 적용한다.
 */
@Getter
@Setter
@ToString
public class PolicySimEmdCountRow {

    private String emdCd;

    /** 시군구명 — g_sgg 선택 키 */
    private String sggNm;

    private String emdNm;

    /** 주차민원 건수 ({@code cvlcpt_type_cd <> '99'}, 민원 구간) */
    private long cvlcptCnt;

    /** N_CCTV — 고정형·주행형·버스장착형 CCTV (가중 w_fixed) */
    private long cctvCrdnCnt;

    /** N_PDA */
    private long pdaCrdnCnt;

    /** N_현장 — 그 밖 전부(민원 기원 단속·수기보행등록·crdn_mthd NULL 포함) */
    private long fldCrdnCnt;

    /** S — 공영(민영 제외)·미소멸 주차면수 합 */
    private long pkltCpctySum;
}
