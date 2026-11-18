package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.util.List;

/**
 * 요금제 변화식 결과 — 조회 범위 하나 (TSK-4039 Step 3).
 *
 * <p>출력 지표(R·I·calcStts)는 {@link PolicySimOutputCalculator#indicators(FeeResult)} 가 만든다 — 이면도로와 같이
 * {@code R} 분자에 {@link #adjcDltCvlcpt} 가 더해진다(인접 포함 순효과). 혼잡 지수 Σ 는 대상만.
 *
 * @param mthd           적용 방식
 * @param feeIncrsRt     인상률 p (%) — RAISE 만, CONVERT 는 null
 * @param coef           실제 적용 계수
 * @param dmndRdctRt     수요 감소 비율 — CONVERT λ · RAISE −ε × p/100 (V_e = 면수 × occ × 이 값)
 * @param aplcnTrgtSlot  적용 대상 면수 합 (Step 2 · NO_ENF 읍면동 포함)
 * @param sumE0          대상 Σ E0 (인접분 대상 가산의 분모)
 * @param lvngVhclCnt    Σ_{E0_e&gt;0} V_e (응답 {@code lvngVhclCnt})
 * @param absrpCnt       Σ ΔE_abs_e (응답 {@code absrpCnt}) — occ &lt; occ_th 면 0
 * @param trnsfCnt       전이 총량 τ × ΣV = 대상 내 + 인접분 (응답 {@code trnsfCnt})
 * @param trnsfLocalCnt  대상 내 전이 τ × τ_local × ΣV
 * @param trnsfAdjcCnt   인접분 τ × (1 − τ_local) × ΣV — 인접이 없으면 이 양이 대상에 가산됐다
 * @param trnsfToTarget  인접이 없거나 전부 E0 = 0 이라 인접분을 대상에 E0 비율로 더했으면 true
 * @param absorbs        occ ≥ occ_th (흡수가 켜졌는가 — Step 4 {@code wrnngCn})
 * @param emds           대상 읍면동별 E1·U1·S1·C1·H1 (입력 순서 유지 · {@code dltIlgl} = 흡수 · {@code dltSlot} = 0 · {@code weight} = E0 비율)
 * @param feeEmds        대상 읍면동별 V·전이 ({@code emds} 와 같은 순서)
 * @param adjcEmds       인접 읍면동별 값 (입력 순서 유지 · E0 = 0 포함) — 4038 형식 재사용
 * @param adjcEmdCnt     배분에 쓴 인접 수(E0 &gt; 0) — 대상에 가산했으면 0
 * @param adjcDltCvlcpt  인접 민원 증가분 Σ_n k_n × 배분_n — 대상에 가산했으면 0
 * @param excs           흡수가 E0_e 로 잘린 대상(E0_e &gt; 0)이 있으면 true (저장 칸 {@code excs_yn})
 */
public record FeeResult(
        FeeAplcnMthd mthd,
        BigDecimal feeIncrsRt,
        FeeCoef coef,
        double dmndRdctRt,
        long aplcnTrgtSlot,
        double sumE0,
        double lvngVhclCnt,
        double absrpCnt,
        double trnsfCnt,
        double trnsfLocalCnt,
        double trnsfAdjcCnt,
        boolean trnsfToTarget,
        boolean absorbs,
        List<ExpandEmdResult> emds,
        List<FeeEmdResult> feeEmds,
        List<RepairAdjcEmdResult> adjcEmds,
        int adjcEmdCnt,
        double adjcDltCvlcpt,
        boolean excs) {
}
