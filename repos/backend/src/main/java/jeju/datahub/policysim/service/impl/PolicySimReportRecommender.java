package jeju.datahub.policysim.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 보고서 추천 판정기 — 상태 없는 순수 함수.
 *
 * <p>범위는 <b>이 보고서에 담긴 시나리오</b>다(화면의 "지금 보이는 행" 이 아니다). 규칙은 프론트
 * {@code policySimView.ts} 의 {@code pickRecommended} 와 같다:
 * <ol>
 *   <li>{@code cvlcptRdctRt < 0}(민원 감소)인 것만 후보 — null·0·양수(민원 증가)는 제외</li>
 *   <li>감소 폭 최대(더 음수)</li>
 *   <li>동률이면 비용 낮은 쪽 — {@code LOW 0 · MID 1 · HIGH 2 · TBD 3 · null/미등록 4}</li>
 *   <li>다시 동률이면 <b>요청 순서 앞</b> — 🔴 프론트의 마지막 기준(최근 실행)과 다르다. 미저장 행에는
 *       서버가 믿을 시각이 없다</li>
 * </ol>
 *
 * <p>🔴 비교는 퍼센트 값(소수 둘째 자리) 그대로 {@link BigDecimal#compareTo} 로 한다 — 정수 % 로 바꾸면 동률이 늘고,
 * {@code equals} 는 스케일까지 봐서 {@code -5.0} 과 {@code -5.00} 을 다르게 본다.
 */
final class PolicySimReportRecommender {

    /** 추천 없음 */
    static final int NONE = -1;

    private static final Map<String, Integer> CST_RANK = Map.of("LOW", 0, "MID", 1, "HIGH", 2, "TBD", 3);
    private static final int CST_RANK_NONE = 4;

    private PolicySimReportRecommender() {
    }

    /**
     * @param results 시나리오 결과 — 요청 순서. null 원소는 후보에서 뺀다
     * @return 추천 시나리오의 인덱스(0-base), 후보가 없으면 {@link #NONE}(-1)
     */
    static int pick(List<PolicySimResultVO> results) {
        if (results == null) {
            return NONE;
        }
        int best = NONE;
        for (int i = 0; i < results.size(); i++) {
            PolicySimResultVO r = results.get(i);
            if (r == null || r.getCvlcptRdctRt() == null || r.getCvlcptRdctRt().signum() >= 0) {
                continue;
            }
            if (best == NONE || isBetter(r, results.get(best))) {
                best = i;   // 엄격히 나을 때만 바꾼다 — 완전 동률이면 앞 순서가 남는다
            }
        }
        return best;
    }

    private static boolean isBetter(PolicySimResultVO a, PolicySimResultVO b) {
        int cmp = a.getCvlcptRdctRt().compareTo(b.getCvlcptRdctRt());
        if (cmp != 0) {
            return cmp < 0;   // 더 음수 = 감소 폭이 크다
        }
        return cstRank(a.getCstLvlCd()) < cstRank(b.getCstLvlCd());
    }

    /** 비용 순위 — 미등록 코드·null 은 맨 뒤 */
    static int cstRank(String cstLvlCd) {
        if (cstLvlCd == null) {
            return CST_RANK_NONE;
        }
        return CST_RANK.getOrDefault(cstLvlCd, CST_RANK_NONE);
    }
}
