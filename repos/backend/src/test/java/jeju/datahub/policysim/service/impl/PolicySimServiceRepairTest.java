package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.policysim.service.PolicySimParamMissingException;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 정책 시뮬레이션 서비스 — 이면도로 정비(REPAIR) 분기 단위테스트.
 *
 * <p>매퍼는 Mockito, 계산기는 실물이다 — 서비스의 조립(17계수 확인 · 대상 범위 · 인접 코드 → 인접 기준선 거름 ·
 * 출력 VO · 저장 엔티티)만 본다. 🔴 실데이터를 쓰지 않는다 — DB 없이 돈다.
 */
class PolicySimServiceRepairTest {

    private static final String YEON = "50110137";
    private static final String ADJ_A = "50110101";
    private static final String ADJ_B = "50110102";
    private static final String FAR = "50130101";

    /** 13 + 이면도로 4 기본값 */
    private static Map<String, BigDecimal> defaults17() {
        Map<String, BigDecimal> m = new HashMap<>(PolicySimBaselineTest.defaults());
        m.put("N100_RSDNC", new BigDecimal("14.4"));
        m.put("N100_CMRC", new BigDecimal("12"));
        m.put("ALPHA_B_VL", new BigDecimal("0.8"));
        m.put("BALLOON_RT", new BigDecimal("0.2"));
        return m;
    }

    private static PolicySimEmdCountRow row(String emdCd, String sggNm, long cvlcpt, long pda, long s) {
        PolicySimEmdCountRow r = PolicySimBaselineTest.row(sggNm, cvlcpt, 0, pda, 0, s);
        r.setEmdCd(emdCd);
        r.setEmdNm("동" + emdCd);
        return r;
    }

    /** 연동(대상) · 인접 2곳 · 인접 아닌 1곳 — 인접 아닌 곳이 배분에 끼면 adjcEmdCnt 가 3 이 된다 */
    private static List<PolicySimEmdCountRow> rows() {
        return List.of(
                row(YEON, "제주시", 800, 200, 1000),
                row(ADJ_A, "제주시", 600, 100, 500),
                row(ADJ_B, "제주시", 100, 50, 300),
                row(FAR, "서귀포시", 50, 40, 200));
    }

    private final AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
    private final PolicySimMapper pm = mock(PolicySimMapper.class);

    private PolicySimServiceImpl service(Map<String, BigDecimal> coefs, List<String> adjacent) {
        when(wm.selectWeightsByType("POLICY_SIM")).thenReturn(PolicySimBaselineTest.weightRows(coefs));
        PolicySimCvlcptPeriodRow period = new PolicySimCvlcptPeriodRow();
        // 날짜 값은 구간 규칙과 무관한 주입값 — 보유 8개월 이상이면 나눗수 8
        LocalDate first = LocalDate.now().withDayOfMonth(1).minusMonths(12);
        period.setMinOcrnDt(first.atStartOfDay());
        period.setMaxOcrnDt(first.plusMonths(11).atStartOfDay());
        when(pm.selectCvlcptPeriod()).thenReturn(period);
        when(pm.selectEmdCounts(any())).thenReturn(rows());
        when(pm.selectAdjacentEmd(anyList())).thenReturn(adjacent);
        return new PolicySimServiceImpl(new PolicySimBaselineCalculator(wm, pm), new ExpandPolicyCalculator(),
                new RepairPolicyCalculator(), new PolicySimOutputCalculator(),
                mock(AnlsExcnRepository.class), mock(PlcySmltRsltRepository.class), pm, new ObjectMapper(),
                new AnlsVrblOvrdResolver(wm));
    }

    private static PolicySimRequest repair(String emdCd, int len) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("REPAIR");
        req.setRoadMtncType("RSDNC");
        req.setRoadMtncLen(len);
        req.setPredcMm(12);
        req.setEmdCd(emdCd);
        return req;
    }

    private static PolicySimRequest expand() {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("EXPAND");
        req.setExpandMethod("VACLOT");
        req.setAddPkltSlot(100);
        req.setPredcMm(12);
        req.setEmdCd(YEON);
        return req;
    }

    @Nested
    @DisplayName("필수 계수 503 — REPAIR 17종 · EXPAND 13종")
    class ParamMissing {

        @Test
        @DisplayName("ALPHA_B_VL 행이 없으면 REPAIR → 503(POLICY_SIM_PARAM_MISSING) · 같은 상태에서 EXPAND 는 정상 계산")
        void alphaBMissing() {
            Map<String, BigDecimal> m = defaults17();
            m.remove("ALPHA_B_VL");
            PolicySimServiceImpl svc = service(m, List.of(ADJ_A, ADJ_B));

            assertThatThrownBy(() -> svc.simulate(repair(YEON, 600)))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .containsExactly("ALPHA_B_VL"));

            PolicySimResultVO vo = svc.simulate(expand());
            assertThat(vo.getCvlcptRdctRt()).isNotNull();
            assertThat(vo.getCstLvlCd()).isIn("LOW", "MID", "HIGH");
            // 확충의 aplcnParam 에는 이면도로 계수가 없다(키는 있고 값 null)
            assertThat(vo.getAplcnParam().alphaB()).isNull();
            assertThat(vo.getAplcnParam().n100Rsdnc()).isNull();
            assertThat(vo.getRoadMtncType()).isNull();
            assertThat(vo.getAdjcCvlcptCnt()).isNull();
            assertThat(vo.getAdjcEmdCnt()).isNull();
        }

        @Test
        @DisplayName("이면도로 4종이 전부 없으면(마이그레이션 82 미적용) REPAIR 만 503 — 4종 각각을 누락으로 보고 집계 쿼리를 돌리지 않는다")
        void allRepairMissing() {
            PolicySimServiceImpl svc = service(PolicySimBaselineTest.defaults(), List.of());
            assertThatThrownBy(() -> svc.simulate(repair(YEON, 600)))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .containsExactly("N100_RSDNC", "N100_CMRC", "ALPHA_B_VL", "BALLOON_RT"));
            verify(pm, never()).selectEmdCounts(any());
            verify(pm, never()).selectAdjacentEmd(anyList());
        }
    }

    @Nested
    @DisplayName("REPAIR 계산 조립")
    class Compute {

        @Test
        @DisplayName("연동 600m 주거 — ΔS 86.40 · 인접 2곳(목록 밖 읍면동 제외) · adjcCvlcptCnt>0 · TBD · cstAmt null · aplcnParam 17계수")
        void yeondong() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(ADJ_A, ADJ_B));
            PolicySimResultVO vo = svc.simulate(repair(YEON, 600));

            verify(pm).selectAdjacentEmd(List.of(YEON));
            assertThat(vo.getAnlsRngCn()).isEqualTo("제주시 동" + YEON);
            assertThat(vo.getVldAddPkltSlot()).isEqualByComparingTo("86.40");
            assertThat(vo.getAdjcEmdCnt()).isEqualTo(2);
            assertThat(vo.getAdjcCvlcptCnt()).isPositive();
            assertThat(vo.getCstAmt()).isNull();
            assertThat(vo.getCstLvlCd()).isEqualTo("TBD");
            assertThat(vo.getRoadMtncType()).isEqualTo("RSDNC");
            assertThat(vo.getRoadMtncLen()).isEqualTo(600);
            assertThat(vo.getCalcSttsCd()).isNull();
            assertThat(vo.getAplcnParam().alphaB()).isEqualByComparingTo("0.8");
            assertThat(vo.getAplcnParam().n100Rsdnc()).isEqualByComparingTo("14.4");
            assertThat(vo.getAplcnParam().n100Cmrc()).isEqualByComparingTo("12");
            assertThat(vo.getAplcnParam().balloonRt()).isEqualByComparingTo("0.2");
            assertThat(vo.getAplcnParam().alpha()).isNotNull();   // 4037 13계수도 그대로

            // 인접 포함 정의 — aftr ÷ crtr − 1 ≈ R
            double ratio = vo.getAftrCvlcptCnt().doubleValue() / vo.getCrtrCvlcptCnt().doubleValue() - 1;
            assertThat(ratio).isCloseTo(vo.getCvlcptRdctRt().doubleValue() / 100, within(5e-4));
        }

        @Test
        @DisplayName("인접 공집합(제주 전체·섬) — adjcEmdCnt 0 · adjcCvlcptCnt 0 (풍선효과분은 대상에 가산)")
        void noAdjacent() {
            PolicySimServiceImpl svc = service(defaults17(), List.of());
            PolicySimRequest req = repair(null, 600);
            PolicySimResultVO vo = svc.simulate(req);

            assertThat(vo.getAnlsRngCn()).isEqualTo("전체 지역");
            assertThat(vo.getAdjcEmdCnt()).isZero();
            assertThat(vo.getAdjcCvlcptCnt()).isEqualByComparingTo("0");
            assertThat(vo.getCvlcptRdctRt()).isNegative();
        }

        @Test
        @DisplayName("상업지역형 — ΔS = 6 × 12 = 72.00")
        void commercial() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(ADJ_A));
            PolicySimRequest req = repair(YEON, 600);
            req.setRoadMtncType("CMRC");
            assertThat(svc.simulate(req).getVldAddPkltSlot()).isEqualByComparingTo("72.00");
        }

        @Test
        @DisplayName("없는 읍면동코드 → 400 (500 으로 새지 않는다)")
        void unknownEmd() {
            PolicySimServiceImpl svc = service(defaults17(), List.of());
            assertThatThrownBy(() -> svc.simulate(repair("99999999", 600)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Nested
    @DisplayName("입력 검증 · 저장 엔티티")
    class ValidateAndEntity {

        private void assert400(PolicySimRequest req) {
            assertThatThrownBy(() -> PolicySimServiceImpl.validate(req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("REPAIR — 방식·연장 누락·범위 밖(0 · 10001)·예측기간 누락 → 400 · FEE 필드 없는 FEE 도 400")
        void invalid() {
            PolicySimRequest noLen = repair(YEON, 600);
            noLen.setRoadMtncLen(null);
            assert400(noLen);
            assert400(repair(YEON, 0));
            assert400(repair(YEON, 10_001));
            PolicySimRequest noType = repair(YEON, 600);
            noType.setRoadMtncType(null);
            assert400(noType);
            PolicySimRequest noPredc = repair(YEON, 600);
            noPredc.setPredcMm(null);
            assert400(noPredc);
            PolicySimRequest fee = repair(YEON, 600);
            fee.setPlcyType("FEE");
            assert400(fee);
        }

        @Test
        @DisplayName("REPAIR 는 확충 필드 없이 통과하고, 확충 필드가 와도 무시한다(경계 1 · 10000 포함)")
        void validRepair() {
            assertThat(PolicySimServiceImpl.validate(repair(YEON, 1)).roadMtncType()).isEqualTo(RoadMtncType.RSDNC);
            PolicySimRequest withExpand = repair(YEON, 10_000);
            withExpand.setExpandMethod("XXX");
            withExpand.setAddPkltSlot(7);
            PolicySimServiceImpl.ValidInput in = PolicySimServiceImpl.validate(withExpand);
            assertThat(in.isRepair()).isTrue();
            assertThat(in.expandMethod()).isNull();
        }

        @Test
        @DisplayName("저장 엔티티 — plcy_type_cd REPAIR · add_pklt_slot NULL · road_mtnc_* · vld_add_pklt_slot = ΔS · adjc · TBD · rcmdtn false")
        void entity() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(ADJ_A, ADJ_B));
            PolicySimRequest req = repair(YEON, 600);
            req.setAddPkltSlot(100);    // 와도 저장하지 않는다
            PolicySimResultVO vo = svc.simulate(req);
            PlcySmltRslt r = PolicySimServiceImpl.toEntity(7L, req, vo, PolicySimServiceImpl.validate(req));

            assertThat(r.getPlcyTypeCd()).isEqualTo("REPAIR");
            assertThat(r.getPredcMm()).isEqualTo(12);
            assertThat(r.getAddPkltSlot()).isNull();
            assertThat(r.getRoadMtncType()).isEqualTo("RSDNC");
            assertThat(r.getRoadMtncLen()).isEqualTo(600);
            assertThat(r.getVldAddPkltSlot()).isEqualByComparingTo("86.40");
            assertThat(r.getAdjcCvlcptCnt()).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
            assertThat(r.getCstLvlCd()).isEqualTo("TBD");
            assertThat(r.getRcmdtnYn()).isFalse();
            assertThat(r.getExcsYn()).isEqualTo(vo.isExcsYn());
        }

        @Test
        @DisplayName("EXPAND 저장 엔티티는 종전대로 — 이면도로 칸 NULL")
        void expandEntityUnchanged() {
            PolicySimServiceImpl svc = service(defaults17(), List.of());
            PolicySimRequest req = expand();
            PlcySmltRslt r = PolicySimServiceImpl.toEntity(7L, req, svc.simulate(req), PolicySimServiceImpl.validate(req));
            assertThat(r.getAddPkltSlot()).isEqualTo(100);
            assertThat(r.getRoadMtncType()).isNull();
            assertThat(r.getRoadMtncLen()).isNull();
            assertThat(r.getAdjcCvlcptCnt()).isNull();
        }
    }

    @Nested
    @DisplayName("요청 DTO 조건부 검증 (@AssertTrue)")
    class BeanValidation {

        private final Validator validator;

        BeanValidation() {
            try (ValidatorFactory f = Validation.buildDefaultValidatorFactory()) {
                validator = f.getValidator();
            }
        }

        private Set<String> violatedPaths(PolicySimRequest req) {
            Set<ConstraintViolation<PolicySimRequest>> v = validator.validate(req);
            List<String> paths = new ArrayList<>();
            v.forEach(c -> paths.add(c.getPropertyPath().toString()));
            return Set.copyOf(paths);
        }

        @Test
        @DisplayName("REPAIR 정상 · EXPAND 정상 → 위반 없음 (서로의 필드를 요구하지 않는다)")
        void ok() {
            assertThat(violatedPaths(repair(YEON, 600))).isEmpty();
            assertThat(violatedPaths(expand())).isEmpty();
        }

        @Test
        @DisplayName("REPAIR 에 연장 없음 · EXPAND 에 면수 없음 → validForType 위반")
        void missingByType() {
            PolicySimRequest r = repair(YEON, 600);
            r.setRoadMtncLen(null);
            assertThat(violatedPaths(r)).containsExactly("validForType");
            PolicySimRequest e = expand();
            e.setAddPkltSlot(null);
            assertThat(violatedPaths(e)).containsExactly("validForType");
        }

        @Test
        @DisplayName("연장 0 · 10001 · 방식 XXX · predcMm 없음 → 필드 위반")
        void fieldViolations() {
            assertThat(violatedPaths(repair(YEON, 0))).contains("roadMtncLen");
            assertThat(violatedPaths(repair(YEON, 10_001))).contains("roadMtncLen");
            PolicySimRequest bad = repair(YEON, 600);
            bad.setRoadMtncType("XXX");
            assertThat(violatedPaths(bad)).contains("roadMtncType");
            PolicySimRequest noPredc = repair(YEON, 600);
            noPredc.setPredcMm(null);
            assertThat(violatedPaths(noPredc)).contains("predcMm");
        }
    }
}
