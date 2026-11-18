package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 보고서 추천 판정 단위테스트 (TSK-4229 Step 6 케이스 12 · D11). DB·Spring 컨텍스트 없이 돈다.
 */
class PolicySimReportRecommenderTest {

    static PolicySimResultVO r(String rdctRt, String cstLvlCd) {
        PolicySimResultVO vo = new PolicySimResultVO();
        vo.setCvlcptRdctRt(rdctRt == null ? null : new BigDecimal(rdctRt));
        vo.setCstLvlCd(cstLvlCd);
        return vo;
    }

    @Test
    @DisplayName("감소 폭 최대(더 음수)가 추천된다 — 비용이 더 높아도")
    void largestReduction() {
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-10.00", "LOW"), r("-27.70", "HIGH"), r("-27.69", "LOW")))).isEqualTo(1);
    }

    @Test
    @DisplayName("소수 둘째 자리까지 비교한다 — 정수 %로 반올림하면 동률이 될 값도 가른다")
    void comparesTwoDecimals() {
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-25.51", "LOW"), r("-25.78", "HIGH")))).isEqualTo(1);
    }

    @Test
    @DisplayName("감소율 동률이면 비용 낮은 쪽 — LOW < MID < HIGH < TBD < null/미등록")
    void tieBreaksByCost() {
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-20.00", "HIGH"), r("-20.00", "MID")))).isEqualTo(1);
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-20.00", null), r("-20.00", "TBD")))).isEqualTo(1);
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-20.00", "UNKNOWN"), r("-20.00", "TBD")))).isEqualTo(1);
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-20.00", "TBD"), r("-20.00", "LOW")))).isEqualTo(1);
    }

    @Test
    @DisplayName("감소율·비용이 다시 동률이면 요청 순서 앞 — 스케일만 다른 값(-5.0/-5.00)도 동률이다")
    void tieBreaksByOrder() {
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("-5.00", "MID"), r("-5.0", "MID"), r("-5", "MID")))).isEqualTo(0);
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("3.00", "LOW"), r("-5.00", "MID"), r("-5.00", "MID")))).isEqualTo(1);
    }

    @Test
    @DisplayName("전부 양수(민원 증가)·0·null 이면 추천 없음(-1)")
    void noneWhenNoReduction() {
        assertThat(PolicySimReportRecommender.pick(List.of(
                r("137.00", "TBD"), r("0.00", "LOW"), r(null, "LOW")))).isEqualTo(-1);
        assertThat(PolicySimReportRecommender.pick(List.of())).isEqualTo(-1);
        assertThat(PolicySimReportRecommender.pick(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("양수·null 은 후보에서 빠지고 감소 행만 비교된다 · null 원소는 건너뛴다")
    void skipsNonCandidates() {
        List<PolicySimResultVO> list = new ArrayList<>(Arrays.asList(
                r("137.00", "LOW"), null, r(null, "LOW"), r("-1.00", "TBD")));
        assertThat(PolicySimReportRecommender.pick(list)).isEqualTo(3);
    }
}
