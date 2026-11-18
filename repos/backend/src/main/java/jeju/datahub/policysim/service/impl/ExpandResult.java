package jeju.datahub.policysim.service.impl;

import java.util.List;

/**
 * 주차장 확충 변화식 결과 — 조회 범위 하나 (TSK-4037 Step 3).
 *
 * <p>출력 지표(R·I·비용·등급·범위 {@code calc_stts_cd})는 Step 4 가 이 결과로 만든다.
 *
 * @param method         확충 방식
 * @param addPkltSlot    N — 요청 면수
 * @param predcMm        T_m — 예측기간(개월). 공한지 반환분 계산에만 쓴다
 * @param vldAddPkltSlot ΔS_eff — 반환분 차감 후 순증 면수 (저장 칸 {@code vld_add_pklt_slot})
 * @param alphaE         α_e = α × min(1, occ ÷ occ_th) — occ 가 설정 상수라 전 읍면동 동일
 * @param sumE0          범위 Σ E0 (배분 분모). 0 이면 범위 전체가 NO_ENF 라 배분하지 않는다
 * @param emds           범위 읍면동별 값 (입력 순서 유지)
 * @param excs           범위 안 읍면동이 하나라도 포화면 true (저장 칸 {@code excs_yn})
 */
public record ExpandResult(
        ExpandMethod method,
        int addPkltSlot,
        int predcMm,
        double vldAddPkltSlot,
        double alphaE,
        double sumE0,
        List<ExpandEmdResult> emds,
        boolean excs) {
}
