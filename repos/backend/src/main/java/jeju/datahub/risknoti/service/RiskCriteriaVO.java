package jeju.datahub.risknoti.service;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 위험단계 알림 기준 행 VO. (parking.TB_RISK_STP_NOTI_CRTR — MyBatis 조회 전용)
 *
 * <p>단계당 정확히 1행이라 응답은 <b>항상 3건</b>이고, {@code GET} 과 {@code PUT} 이 같은 배열을
 * 내린다(래핑 없는 최상위 배열 — 요청만 {@code {criteria:[...]}} 로 감싼다).
 *
 * <p>🔴 <b>순서는 {@code TB_COM_CD.sort_seq}</b>(CRIT → WARN → CAUT)다.
 * {@code ORDER BY risk_lvl_cd} 는 문자 정렬이라 {@code CAUT→CRIT→WARN}(주의·심각·경고)이 돼
 * 시안(심각·경고·주의)과 반대가 된다. 프론트는 받은 순서를 그대로 렌더한다.
 *
 * <p><b>단계 라벨({@code riskLvlNm})은 내리지 않는다</b> — 배지가 코드값에서 만든다.
 * {@code thrshCnt}(구 건수 기준)도 응답에 넣지 않는다(보류 — 범위 밖 #7).
 * {@code minCvlcptCnt} 는 그것과 <b>무관한 신설 컬럼</b>이다(TSK-4092 D7).
 * {@code bscMinCvlcptCnt} 는 <b>응답 전용</b>이라 저장 요청({@link RiskCriteriaRequest})에는 없다(D11 ③).
 */
@Schema(description = "위험단계 알림 기준 행 (단계당 1건)")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class RiskCriteriaVO {

    /** 기준아이디 (thrsh_id) — {@code 'THRSH_' + risk_lvl_cd} 고정 (D7) */
    @Schema(description = "기준아이디", example = "THRSH_CRIT")
    private String thrshId;

    /** 위험단계 코드 (risk_lvl_cd — CRIT | WARN | CAUT) */
    @Schema(description = "위험단계 코드", example = "CRIT")
    private String riskLvlCd;

    /** 증가율기준 (incrs_rt, %) — 전일 대비 민원 증가율이 이 값 이상이면 해당 단계 알림 (D18) */
    @Schema(description = "증가율 기준 (%)", example = "30.00")
    private BigDecimal incrsRt;

    /**
     * 최소민원수 (min_cvlcpt_cnt) — 판정일 당일 민원이 이 값 미만이면 해당 단계 알림에서 제외 (TSK-4092).
     * 0 이면 하한 없음. 증가율의 대안이 아니라 <b>전제 조건</b>이다.
     */
    @Schema(description = "최소 민원수 하한 (0 = 하한 없음)", example = "10")
    private Integer minCvlcptCnt;

    /**
     * 기본최소민원수 (bsc_min_cvlcpt_cnt) — <b>최초 등재값, 불변</b> (TSK-4092 D11).
     * 화면 {@code 최소 건수 기본값으로} 의 원천이며 저장 API 가 갱신하지 않는다.
     * 선례: {@code AnalysisWeightVO.bscStngVl}.
     */
    @Schema(description = "기본 최소 민원수 (초기화 기준값, 불변)", example = "10")
    private Integer bscMinCvlcptCnt;
}
