package jeju.datahub.policysim.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.impl.AnalysisWeightMapper;
import jeju.datahub.analysis.service.impl.AnlsExcnRepository;
import jeju.datahub.analysis.service.impl.AnlsVrblOvrdResolver;
import jeju.datahub.policysim.service.PolicySimParamMissingException;
import jeju.datahub.policysim.service.PolicySimRequest;
import jeju.datahub.policysim.service.PolicySimResultVO;

/**
 * 정책 시뮬레이션 서비스 — 요금제 적용(FEE) 분기 단위테스트.
 *
 * <p>매퍼는 Mockito, 계산기는 실물이다 — 서비스의 조립(17계수 확인 · 대상 범위 · 면수 · 인접 · 민감도 · 출력 VO · 저장 엔티티)과
 * 입력 검증만 본다. 🔴 실데이터를 쓰지 않는다 — DB 없이 돈다.
 */
class PolicySimServiceFeeTest {

    private static final String YEON = "50110137";
    private static final String MYEON = "50110310";
    private static final String ADJ_A = "50110101";

    /** 13 + 요금제 4 기본값 (INSERT 기본값) */
    static Map<String, BigDecimal> defaults17() {
        Map<String, BigDecimal> m = new HashMap<>(PolicySimBaselineTest.defaults());
        m.put(FeeCoef.PRC_ELST_VL, new BigDecimal("-0.3"));
        m.put(FeeCoef.PAID_DMND_RT, new BigDecimal("0.3"));
        m.put(FeeCoef.TRNSF_RT, new BigDecimal("0.2"));
        m.put(FeeCoef.TRNSF_LOCAL_RT, new BigDecimal("0.5"));
        return m;
    }

    /** 민감도 범위(min_vl·max_vl) — INSERT 값 */
    private static final Map<String, BigDecimal[]> RANGES = Map.of(
            FeeCoef.PRC_ELST_VL, new BigDecimal[] {new BigDecimal("-0.6"), new BigDecimal("-0.1")},
            FeeCoef.PAID_DMND_RT, new BigDecimal[] {new BigDecimal("0.15"), new BigDecimal("0.5")},
            FeeCoef.TRNSF_RT, new BigDecimal[] {new BigDecimal("0.1"), new BigDecimal("0.3")});

    private static List<AnalysisWeightVO> weightRows(Map<String, BigDecimal> coefs) {
        List<AnalysisWeightVO> rows = PolicySimBaselineTest.weightRows(coefs);
        rows.forEach(r -> {
            BigDecimal[] mm = RANGES.get(r.getVrblCd());
            if (mm != null) {
                r.setMinVl(mm[0]);
                r.setMaxVl(mm[1]);
            }
        });
        return rows;
    }

    private static PolicySimEmdCountRow row(String emdCd, String emdNm, String sggNm, long cvlcpt, long pda, long s) {
        PolicySimEmdCountRow r = PolicySimBaselineTest.row(sggNm, cvlcpt, 0, pda, 0, s);
        r.setEmdCd(emdCd);
        r.setEmdNm(emdNm);
        return r;
    }

    private static List<PolicySimEmdCountRow> rows() {
        return List.of(
                row(YEON, "연동", "제주시", 800, 200, 1000),
                row(MYEON, "한경면", "제주시", 100, 30, 600),
                row(ADJ_A, "이웃동", "제주시", 600, 100, 500));
    }

    private static FeeTargetRow slot(String emdCd, long n) {
        FeeTargetRow r = new FeeTargetRow();
        r.setEmdCd(emdCd);
        r.setAplcnTrgtSlot(n);
        return r;
    }

    private final AnalysisWeightMapper wm = mock(AnalysisWeightMapper.class);
    private final PolicySimMapper pm = mock(PolicySimMapper.class);

    private PolicySimServiceImpl service(Map<String, BigDecimal> coefs, List<String> adjacent, List<FeeTargetRow> slots) {
        when(wm.selectWeightsByType("POLICY_SIM")).thenReturn(weightRows(coefs));
        PolicySimCvlcptPeriodRow period = new PolicySimCvlcptPeriodRow();
        LocalDate first = LocalDate.now().withDayOfMonth(1).minusMonths(12);
        period.setMinOcrnDt(first.atStartOfDay());
        period.setMaxOcrnDt(first.plusMonths(11).atStartOfDay());
        when(pm.selectCvlcptPeriod()).thenReturn(period);
        when(pm.selectEmdCounts(any())).thenReturn(rows());
        when(pm.selectAdjacentEmd(anyList())).thenReturn(adjacent);
        when(pm.selectFeeTargetSlot(anyString(), any(), any(), any())).thenReturn(slots);
        return new PolicySimServiceImpl(new PolicySimBaselineCalculator(wm, pm), new ExpandPolicyCalculator(),
                new RepairPolicyCalculator(), new PolicySimOutputCalculator(),
                mock(AnlsExcnRepository.class), mock(PlcySmltRsltRepository.class), pm, new ObjectMapper(),
                new AnlsVrblOvrdResolver(wm));
    }

    static PolicySimRequest fee(String mthd, String zone, Integer rt, String emdCd) {
        PolicySimRequest req = new PolicySimRequest();
        req.setPlcyType("FEE");
        req.setFeeAplcnMthd(mthd);
        req.setFeeAplcnZone(zone);
        req.setFeeIncrsRt(rt);
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
    @DisplayName("필수 계수 503 — FEE 17종 · EXPAND 13종")
    class ParamMissing {

        @Test
        @DisplayName("TRNSF_RT 행이 없으면 FEE → 503(POLICY_SIM_PARAM_MISSING) · 같은 상태에서 EXPAND 는 정상 계산")
        void trnsfMissing() {
            Map<String, BigDecimal> m = defaults17();
            m.remove(FeeCoef.TRNSF_RT);
            PolicySimServiceImpl svc = service(m, List.of(), List.of(slot(YEON, 100)));

            assertThatThrownBy(() -> svc.simulate(fee("CONVERT", null, null, YEON)))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .containsExactly(FeeCoef.TRNSF_RT));
            verify(pm, never()).selectFeeTargetSlot(anyString(), any(), any(), any());

            PolicySimResultVO vo = svc.simulate(expand());
            assertThat(vo.getCvlcptRdctRt()).isNotNull();
            assertThat(vo.getAplcnParam().tau()).isNull();
            assertThat(vo.getFeeAplcnMthd()).isNull();
            assertThat(vo.getCvlcptRdctRtRng()).isNull();
        }

        @Test
        @DisplayName("요금제 4종이 전부 없으면(마이그레이션 83 미적용) FEE 만 503 — 4종 각각을 누락으로 본다")
        void allFeeMissing() {
            PolicySimServiceImpl svc = service(PolicySimBaselineTest.defaults(), List.of(), List.of());
            assertThatThrownBy(() -> svc.simulate(fee("RAISE", "ALL", 10, null)))
                    .isInstanceOf(PolicySimParamMissingException.class)
                    .satisfies(e -> assertThat(((PolicySimParamMissingException) e).getMissingVrblCds())
                            .containsExactlyElementsOf(FeeCoef.FEE_VRBL_CDS));
            verify(pm, never()).selectEmdCounts(any());
        }

        @Test
        @DisplayName("필수 목록 — FEE 17 = 13 + 요금제 4 · EXPAND·REPAIR 목록은 그대로")
        void lists() {
            assertThat(PolicySimParams.FEE_REQUIRED_VRBL_CDS).hasSize(17)
                    .containsAll(PolicySimParams.REQUIRED_VRBL_CDS).containsAll(FeeCoef.FEE_VRBL_CDS);
            assertThat(PolicySimParams.REQUIRED_VRBL_CDS).hasSize(13).doesNotContainAnyElementsOf(FeeCoef.FEE_VRBL_CDS);
            assertThat(PolicySimParams.REPAIR_REQUIRED_VRBL_CDS).hasSize(17).doesNotContainAnyElementsOf(FeeCoef.FEE_VRBL_CDS);
        }
    }

    @Nested
    @DisplayName("FEE 계산 조립")
    class Compute {

        @Test
        @DisplayName("CONVERT 연동 — 응답 키 · 관계식(trnsf = τ×V · V ≤ 면수×occ×λ · 흡수 ≤ E0) · TBD · ΔS null · 민감도 lambda·tau 퍼센트")
        void convert() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(ADJ_A), List.of(slot(YEON, 400)));
            PolicySimResultVO vo = svc.simulate(fee("CONVERT", "Z2", 30, YEON));

            // emdCd 가 있으면 급지는 무시하고 null 을 넘긴다(매퍼가 emdCd 우선)
            verify(pm).selectFeeTargetSlot(eq("N"), isNull(), isNull(), eq(YEON));
            verify(pm).selectAdjacentEmd(List.of(YEON));
            assertThat(vo.getFeeAplcnMthd()).isEqualTo("CONVERT");
            assertThat(vo.getFeeAplcnZone()).as("연동(동) → Z1 — 요청 Z2 무시").isEqualTo("Z1");
            assertThat(vo.getFeeIncrsRt()).as("CONVERT 의 인상률은 무시").isNull();
            assertThat(vo.getAplcnTrgtSlot()).isEqualTo(400L);
            // V = 400 × 0.85 × 0.3 = 102
            assertThat(vo.getLvngVhclCnt()).isEqualByComparingTo("102.00");
            assertThat(vo.getTrnsfCnt()).isEqualByComparingTo("20.40");
            assertThat(vo.getAbsrpCnt()).isLessThanOrEqualTo(vo.getCrtrIlglCnt());
            assertThat(vo.getVldAddPkltSlot()).isNull();
            assertThat(vo.getCstAmt()).isNull();
            assertThat(vo.getCstLvlCd()).isEqualTo("TBD");
            assertThat(vo.getAdjcEmdCnt()).isEqualTo(1);
            assertThat(vo.getAdjcCvlcptCnt()).isPositive();
            assertThat(vo.getWrnngCn()).isNull();
            assertThat(vo.getAplcnParam().tau()).isEqualByComparingTo("0.2");
            assertThat(vo.getAplcnParam().lambda()).isEqualByComparingTo("0.3");
            assertThat(vo.getAplcnParam().eps()).isEqualByComparingTo("-0.3");
            assertThat(vo.getAplcnParam().tauLocal()).isEqualByComparingTo("0.5");
            assertThat(vo.getAplcnParam().alphaB()).isNull();

            assertThat(vo.getCvlcptRdctRtRng()).containsOnlyKeys("lambda", "tau");
            List<BigDecimal> tau = vo.getCvlcptRdctRtRng().get("tau");
            assertThat(tau).hasSize(2);
            assertThat(tau.get(0).scale()).isEqualTo(2);
            assertThat(tau.get(0)).isLessThanOrEqualTo(vo.getCvlcptRdctRt());
            assertThat(tau.get(1)).isGreaterThanOrEqualTo(vo.getCvlcptRdctRt());

            double ratio = vo.getAftrCvlcptCnt().doubleValue() / vo.getCrtrCvlcptCnt().doubleValue() - 1;
            assertThat(ratio).isCloseTo(vo.getCvlcptRdctRt().doubleValue() / 100, within(5e-4));
        }

        @Test
        @DisplayName("RAISE 제주 전체 — crgYn Y · 급지 그대로 · 인상률 10 · 민감도 eps·tau (lambda 없음)")
        void raise() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of(slot(YEON, 100), slot(MYEON, 50)));
            PolicySimResultVO vo = svc.simulate(fee("RAISE", null, 10, null));

            verify(pm).selectFeeTargetSlot(eq("Y"), eq("ALL"), isNull(), isNull());
            assertThat(vo.getAnlsRngCn()).isEqualTo("전체 지역");
            assertThat(vo.getFeeAplcnZone()).as("생략 시 ALL").isEqualTo("ALL");
            assertThat(vo.getFeeIncrsRt()).isEqualTo(10);
            assertThat(vo.getAplcnTrgtSlot()).isEqualTo(150L);
            // V = 150 × 0.85 × 0.3 × 0.1 = 3.825 (double 원값이라 3.8249… → 3.82 도 정상 — 4037 반올림 규칙)
            assertThat(vo.getLvngVhclCnt().doubleValue()).isCloseTo(3.825, within(0.006));
            assertThat(vo.getAdjcEmdCnt()).isZero();
            assertThat(vo.getAdjcCvlcptCnt()).isEqualByComparingTo("0");
            assertThat(vo.getCvlcptRdctRtRng()).containsOnlyKeys("eps", "tau");
        }

        @Test
        @DisplayName("occ < occ_th — 흡수 0 · wrnngCn 채워짐")
        void noAbsorb() {
            Map<String, BigDecimal> m = defaults17();
            m.put("PCSHR_CRTR_VL", new BigDecimal("0.70"));
            PolicySimServiceImpl svc = service(m, List.of(), List.of(slot(YEON, 100)));
            PolicySimResultVO vo = svc.simulate(fee("CONVERT", "ALL", null, YEON));
            assertThat(vo.getAbsrpCnt()).isEqualByComparingTo("0");
            assertThat(vo.getWrnngCn()).isEqualTo(FeePolicyCalculator.WRNNG_NO_ABSORB);
            assertThat(vo.getCvlcptRdctRt()).isPositive();
        }

        @Test
        @DisplayName("급지 판정 — 읍·면 → Z2 · 그 밖 Z1 (면 지정 시 응답 Z2)")
        void zoneOf() {
            assertThat(PolicySimServiceImpl.zoneOf("한경면")).isEqualTo("Z2");
            assertThat(PolicySimServiceImpl.zoneOf("애월읍")).isEqualTo("Z2");
            assertThat(PolicySimServiceImpl.zoneOf("연동")).isEqualTo("Z1");
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of(slot(MYEON, 10)));
            assertThat(svc.simulate(fee("CONVERT", "Z1", null, MYEON)).getFeeAplcnZone()).isEqualTo("Z2");
        }

        @Test
        @DisplayName("급지가 대상을 좁힌다 — 제주시 Z1 → 인접 인자 {연동, 이웃동} · Z2 → {한경면}")
        @SuppressWarnings("unchecked")
        void zoneNarrowsTarget() {
            // Z1 — slot 은 급지에 맞는 동만(한경면 slot 을 넣으면 대상 밖 면수 행으로 400)
            PolicySimServiceImpl z1 = service(defaults17(), List.of(), List.of(slot(YEON, 100), slot(ADJ_A, 50)));
            PolicySimRequest r1 = fee("CONVERT", "Z1", null, null);
            r1.setSggNm("제주시");
            PolicySimResultVO v1 = z1.simulate(r1);
            ArgumentCaptor<List<String>> c1 = ArgumentCaptor.forClass(List.class);
            verify(pm).selectAdjacentEmd(c1.capture());
            assertThat(c1.getValue()).containsExactlyInAnyOrder(YEON, ADJ_A).doesNotContain(MYEON);
            verify(pm).selectFeeTargetSlot(eq("N"), eq("Z1"), eq("제주시"), isNull());
            assertThat(v1.getFeeAplcnZone()).isEqualTo("Z1");
            assertThat(v1.getAplcnTrgtSlot()).isEqualTo(150L);
            // 기준 민원 = 대상(연동 800 + 이웃동 600)만 — 한경면 100 제외(Z1 < 범위 전체)
            assertThat(v1.getCrtrCvlcptCnt().doubleValue()).isLessThan(1500);

            // Z2 — 대상 {한경면}, 같은 시의 동(연동)이 인접이 된다
            org.mockito.Mockito.reset(pm, wm);
            PolicySimServiceImpl z2 = service(defaults17(), List.of(YEON), List.of(slot(MYEON, 30)));
            PolicySimRequest r2 = fee("CONVERT", "Z2", null, null);
            r2.setSggNm("제주시");
            PolicySimResultVO v2 = z2.simulate(r2);
            ArgumentCaptor<List<String>> c2 = ArgumentCaptor.forClass(List.class);
            verify(pm).selectAdjacentEmd(c2.capture());
            assertThat(c2.getValue()).containsExactly(MYEON);
            assertThat(v2.getFeeAplcnZone()).isEqualTo("Z2");
            assertThat(v2.getAplcnTrgtSlot()).isEqualTo(30L);
            assertThat(v2.getAdjcEmdCnt()).isEqualTo(1);
        }

        @Test
        @DisplayName("급지 필터 결과가 비면 400 (scope 와 같은 규칙) — 읍·면이 없는 범위 + Z2")
        void zoneEmptyTarget() {
            // 픽스처에서 한경면(유일한 Z2)을 뺀 기준선 — service() 의 스텁을 덮어쓴다
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of());
            when(pm.selectEmdCounts(any())).thenReturn(List.of(
                    row(YEON, "연동", "제주시", 800, 200, 1000), row(ADJ_A, "이웃동", "제주시", 600, 100, 500)));
            PolicySimRequest req = fee("CONVERT", "Z2", null, null);
            req.setSggNm("제주시");
            assertThatThrownBy(() -> svc.simulate(req))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            verify(pm, never()).selectAdjacentEmd(anyList());
        }

        @Test
        @DisplayName("없는 읍면동코드 → 400")
        void unknownEmd() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of());
            assertThatThrownBy(() -> svc.simulate(fee("CONVERT", null, null, "99999999")))
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
        @DisplayName("FEE — 방식 누락·목록 밖 · RAISE 인상률 누락·15·0·40 · 급지 목록 밖 · 예측기간 누락 → 400")
        void invalid() {
            assert400(fee(null, "ALL", null, null));
            assert400(fee("XXX", "ALL", null, null));
            assert400(fee("RAISE", "ALL", null, null));
            assert400(fee("RAISE", "ALL", 15, null));
            assert400(fee("RAISE", "ALL", 0, null));
            assert400(fee("RAISE", "ALL", 40, null));
            assert400(fee("CONVERT", "Z3", null, null));
            PolicySimRequest noPredc = fee("CONVERT", "ALL", null, null);
            noPredc.setPredcMm(null);
            assert400(noPredc);
        }

        @Test
        @DisplayName("FEE 정상 — 급지 생략 = ALL · CONVERT 의 인상률은 무시 · RAISE 10·20·30 통과 · 다른 정책 필드 무시")
        void valid() {
            PolicySimServiceImpl.ValidInput c = PolicySimServiceImpl.validate(fee("CONVERT", null, 15, null));
            assertThat(c.isFee()).isTrue();
            assertThat(c.feeAplcnMthd()).isEqualTo(FeeAplcnMthd.CONVERT);
            assertThat(c.feeAplcnZone()).isEqualTo("ALL");
            assertThat(c.feeIncrsRt()).isNull();
            for (int rt : new int[] {10, 20, 30}) {
                assertThat(PolicySimServiceImpl.validate(fee("RAISE", "Z1", rt, null)).feeIncrsRt()).isEqualTo(rt);
            }
            PolicySimRequest withOthers = fee("CONVERT", "Z2", null, null);
            withOthers.setExpandMethod("XXX");
            withOthers.setRoadMtncLen(0);
            assertThat(PolicySimServiceImpl.validate(withOthers).expandMethod()).isNull();
        }

        @Test
        @DisplayName("저장 엔티티 CONVERT — FEE · fee_* · aplcn_trgt_slot · V·흡수·전이 · adjc · TBD · 나머지 NULL · rcmdtn false")
        void entityConvert() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(ADJ_A), List.of(slot(YEON, 400)));
            PolicySimRequest req = fee("CONVERT", "Z2", 20, YEON);
            req.setAddPkltSlot(100);
            req.setRoadMtncType("RSDNC");
            PolicySimResultVO vo = svc.simulate(req);
            PlcySmltRslt r = PolicySimServiceImpl.toEntity(7L, req, vo, PolicySimServiceImpl.validate(req));

            assertThat(r.getPlcyTypeCd()).isEqualTo("FEE");
            assertThat(r.getPredcMm()).isEqualTo(12);
            assertThat(r.getFeeAplcnMthd()).isEqualTo("CONVERT");
            assertThat(r.getFeeAplcnZone()).isEqualTo("Z1");
            assertThat(r.getFeeIncrsRt()).isNull();
            assertThat(r.getAplcnTrgtSlot()).isEqualTo(400);
            assertThat(r.getLvngVhclCnt()).isEqualByComparingTo(vo.getLvngVhclCnt());
            assertThat(r.getAbsrpCnt()).isEqualByComparingTo(vo.getAbsrpCnt());
            assertThat(r.getTrnsfCnt()).isEqualByComparingTo(vo.getTrnsfCnt());
            assertThat(r.getAdjcCvlcptCnt()).isEqualByComparingTo(vo.getAdjcCvlcptCnt());
            assertThat(r.getCstLvlCd()).isEqualTo("TBD");
            assertThat(r.getAddPkltSlot()).isNull();
            assertThat(r.getRoadMtncType()).isNull();
            assertThat(r.getRoadMtncLen()).isNull();
            assertThat(r.getVldAddPkltSlot()).isNull();
            assertThat(r.getRcmdtnYn()).isFalse();
            assertThat(r.getCvlcptRdctRt()).isEqualByComparingTo(vo.getCvlcptRdctRt());
        }

        @Test
        @DisplayName("저장 엔티티 RAISE — fee_incrs_rt = 인상률")
        void entityRaise() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of(slot(YEON, 100)));
            PolicySimRequest req = fee("RAISE", "ALL", 20, null);
            PlcySmltRslt r = PolicySimServiceImpl.toEntity(7L, req, svc.simulate(req), PolicySimServiceImpl.validate(req));
            assertThat(r.getFeeIncrsRt()).isEqualByComparingTo("20");
            assertThat(r.getFeeAplcnZone()).isEqualTo("ALL");
        }

        @Test
        @DisplayName("EXPAND 저장 엔티티는 종전대로 — 요금제 칸 NULL")
        void expandEntityUnchanged() {
            PolicySimServiceImpl svc = service(defaults17(), List.of(), List.of());
            PolicySimRequest req = expand();
            PlcySmltRslt r = PolicySimServiceImpl.toEntity(7L, req, svc.simulate(req), PolicySimServiceImpl.validate(req));
            assertThat(r.getAddPkltSlot()).isEqualTo(100);
            assertThat(r.getFeeAplcnMthd()).isNull();
            assertThat(r.getFeeAplcnZone()).isNull();
            assertThat(r.getAplcnTrgtSlot()).isNull();
            assertThat(r.getLvngVhclCnt()).isNull();
            assertThat(r.getVldAddPkltSlot()).isNotNull();
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
        @DisplayName("FEE CONVERT(인상률 없음) · RAISE(인상률 있음) · EXPAND → 위반 없음")
        void ok() {
            assertThat(violatedPaths(fee("CONVERT", null, null, YEON))).isEmpty();
            assertThat(violatedPaths(fee("CONVERT", "Z1", 15, null))).as("CONVERT 의 인상률은 400 아님").isEmpty();
            assertThat(violatedPaths(fee("RAISE", "ALL", 10, null))).isEmpty();
            assertThat(violatedPaths(expand())).isEmpty();
        }

        @Test
        @DisplayName("FEE 방식 없음 · RAISE 인상률 없음 → validForType 위반 · 방식·급지 목록 밖 → 필드 위반")
        void violations() {
            assertThat(violatedPaths(fee(null, "ALL", null, null))).containsExactly("validForType");
            assertThat(violatedPaths(fee("RAISE", "ALL", null, null))).containsExactly("validForType");
            assertThat(violatedPaths(fee("XXX", "ALL", null, null))).contains("feeAplcnMthd");
            assertThat(violatedPaths(fee("CONVERT", "Z3", null, null))).contains("feeAplcnZone");
        }
    }
}
