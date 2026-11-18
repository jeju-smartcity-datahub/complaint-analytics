package jeju.datahub.policysim.service;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 보고서 안의 시나리오 한 건 (TSK-4229 Step 2) — {@code rslt_json.scenarios[]} 의 한 원소와 같은 모양.
 *
 * <p>{@link #params} 는 저장 경로의 {@code anlsParam} 과 <b>같은 12키</b>다({@code plcyType} · {@code expandMethod} ·
 * {@code addPkltSlot} · {@code roadMtncType} · {@code roadMtncLen} · {@code feeAplcnMthd} · {@code feeAplcnZone} ·
 * {@code feeIncrsRt} · {@code predcMm} · {@code sggNm} · {@code emdCd} · {@code vrblAjmt}).
 * {@code vrblAjmt}(TSK-4332)는 계수 조정 적용 내역이며 구 저장분·구 보고서에는 null 이거나 키가 없다. 시나리오 이름은 프론트가 이 칸으로
 * 조립한다 — 서버가 이름을 만들지 않는다(라벨 표가 두 벌이 된다).
 *
 * <p>🔴 {@link #result} 에는 {@link PolicySimResultVO} 칸만 담는다 — {@link PolicySimSavedResultVO} 를 그대로 넣으면
 * 생성 응답과 스냅샷 조회가 갈린다(Step 3).
 */
@Schema(description = "보고서 시나리오 한 건")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PolicySimReportScenarioVO {

    /** 순번 — 요청 인덱스 + 1 (1-base). 1절 표 순번 · 2·3절 막대 순서 */
    @Schema(description = "순번 (1-base, 요청 순서)", example = "1")
    private Integer seq;

    /** 추천 여부 — 서버가 이 보고서에 담긴 시나리오 범위에서 판정해 굳힌 값(D11). 최대 1건, 없을 수도 있다 */
    @Schema(description = "추천 여부 — 이 보고서의 시나리오 범위에서 서버가 판정(최대 1건)", example = "true")
    private boolean rcmdYn;

    /** 저장된 결과 아이디 — 저장분이면 값, 미저장분(재계산)이면 null */
    @Schema(description = "저장된 결과 아이디 — 미저장분이면 null", example = "12", nullable = true)
    private Long rsltId;

    /** 요청 조건 12키 (anlsParam 과 같은 키 — vrblAjmt 포함) */
    @Schema(description = "요청 조건 12키 — plcyType · expandMethod · addPkltSlot · roadMtncType · roadMtncLen · "
            + "feeAplcnMthd · feeAplcnZone · feeIncrsRt · predcMm · sggNm · emdCd · vrblAjmt "
            + "(유형이 쓰지 않는 칸은 null · vrblAjmt 는 계수 조정 적용 내역, 구 저장분은 null)")
    private Map<String, Object> params;

    /** 시뮬레이션 결과 — 저장분은 저장값 그대로, 미저장분은 재계산 값 */
    @Schema(description = "시뮬레이션 결과 (저장분 = 저장값 그대로 · 미저장분 = 재계산)")
    private PolicySimResultVO result;
}
