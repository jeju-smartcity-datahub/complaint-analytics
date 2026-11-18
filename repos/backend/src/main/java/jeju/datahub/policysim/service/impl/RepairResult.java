package jeju.datahub.policysim.service.impl;

import java.util.List;

/**
 * 이면도로 정비 변화식 결과 — 조회 범위 하나.
 *
 * <p>출력 지표(R·I·calcStts)는 {@link PolicySimOutputCalculator#indicators(RepairResult)} 가 이 결과로 만든다 —
 * 산식은 확충과 같은 한 곳이고, 차이는 {@code R} 분자에 {@link #adjcDltCvlcpt} 가 더해지는 것뿐이다.
 *
 * @param type           정비 방식
 * @param roadMtncLen    L — 정비 구간 연장(m)
 * @param n100           방식별 100m당 확보 구획
 * @param dltSlot        ΔS = L ÷ 100 × n_100 (저장 칸 {@code vld_add_pklt_slot} 재사용)
 * @param alphaB         α_B
 * @param balloonRt      s
 * @param sumE0          대상 Σ E0 (배분 분모). 0 이면 범위 NO_ENF 라 배분하지 않는다
 * @param sumDltIlgl     대상 Σ ΔE
 * @param balloon        풍선효과분 s × Σ ΔE (대)
 * @param balloonToTarget 인접이 없거나(섬·제주 전체) 전부 E0 = 0 이라 풍선효과분을 대상 E1 에 더했으면 true
 * @param emds           대상 읍면동별 값 (입력 순서 유지). {@code e1} 은 풍선 가산 후 값. 행 형식은 확충과 같다({@link ExpandEmdResult})
 * @param adjcEmds       인접 읍면동별 값 (입력 순서 유지 · E0 = 0 포함)
 * @param adjcEmdCnt     배분에 쓴 인접 수(E0 &gt; 0) — 대상에 가산했으면 0 (응답 {@code adjcEmdCnt})
 * @param adjcDltCvlcpt  인접 민원 증가분 Σ_n k_n × 배분_n (응답·저장 {@code adjc_cvlcpt_cnt}) — 대상에 가산했으면 0
 * @param excs           대상 읍면동(E0 &gt; 0) 중 하나라도 포화면 true (저장 칸 {@code excs_yn})
 */
public record RepairResult(
        RoadMtncType type,
        int roadMtncLen,
        double n100,
        double dltSlot,
        double alphaB,
        double balloonRt,
        double sumE0,
        double sumDltIlgl,
        double balloon,
        boolean balloonToTarget,
        List<ExpandEmdResult> emds,
        List<RepairAdjcEmdResult> adjcEmds,
        int adjcEmdCnt,
        double adjcDltCvlcpt,
        boolean excs) {
}
