package jeju.datahub.policysim.service;

import java.util.List;

/**
 * 정책 시뮬레이션 필수 계수(POLICY_SIM) 행이 없어 계산할 수 없다 (TSK-4037 Step 2).
 *
 * <p>🔴 <b>{@code AnalysisWeightService.coefficient()} 의 "행 없으면 1.0" 폴백을 쓰지 않는 이유</b> —
 * {@code CST_*} 가 1원, {@code PCSHR_CRTR_VL}·{@code GEO_CVRG_*} 가 1.0 이 되어 숫자가 <b>조용히</b> 틀린다.
 * 그래서 계산 전에 끊는다. 원인은 대개 마이그레이션 81 미적용이다.
 *
 * <p>응답은 <b>503 {@code {"code":"POLICY_SIM_PARAM_MISSING"}}</b> — 핸들러({@code PolicySimExceptionHandler})는
 * Step 5 에서 만든다. 빠진 코드 이름은 <b>본문에 싣지 않는다</b>(로그 전용).
 * ⚠️ ERROR 1줄은 던지는 쪽({@code PolicySimParams.resolve})이 이미 남긴다 — 핸들러가 또 남기면 2줄이 된다.
 */
public class PolicySimParamMissingException extends RuntimeException {

    /** 응답 본문 {@code code} 값 */
    public static final String CODE = "POLICY_SIM_PARAM_MISSING";

    private final List<String> missingVrblCds;

    public PolicySimParamMissingException(List<String> missingVrblCds) {
        super(CODE + " " + missingVrblCds);
        this.missingVrblCds = List.copyOf(missingVrblCds);
    }

    /** 빠진(또는 값이 없어 쓸 수 없는) {@code vrbl_cd} 목록 — 로그용 */
    public List<String> getMissingVrblCds() {
        return missingVrblCds;
    }
}
