package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareZoneVO;

/**
 * {@link ZoneRcmdtnShareCalculator} 단위테스트 — <b>DB 없이</b> 판정 경계를 고정한다.
 *
 * <p>🔴 스프링 컨텍스트를 띄우지 않는다 — 계산기가 순수 함수라 입력만 만들면 된다. 그래서 로컬 DB 의
 * 민원이 재적재돼 날짜·건수가 바뀌어도 이 테스트는 흔들리지 않는다.
 *
 * <p>입력은 따로 적지 않으면 <b>8개월</b> 창이고 기준값은 기본값(월 50건 · 100면당 월 7건)이다. 앞의
 * 케이스는 표 10행과 1:1 이고, 뒤쪽은 검토 지수({@code rvwScr})·새 순번 규칙
 * (2026-10-13 사용자 결정)을 고정한다.
 *
 * <p>손계산 공식(8개월 · 기준 50·7): 월평균 {@code mm = 건수 ÷ 8}, 비율 {@code rt = mm × 100 ÷ 면수},
 * 검토 지수 {@code = min(mm ÷ 50, rt ÷ 7)} 을 소수 2자리로 <b>버림</b>.
 */
class ZoneRcmdtnShareCalculatorTest {

    private static final BigDecimal MONTHS = BigDecimal.valueOf(8);

    /** 기본 기준값 — 설정 컬럼({@code NUMERIC(12,4)})이 내려주는 모양 그대로 둔다 */
    private static final ZoneRcmdtnShareCalculator.Crtr CRTR =
            new ZoneRcmdtnShareCalculator.Crtr(new BigDecimal("50.0000"), new BigDecimal("7.0000"),
                    new BigDecimal("0.5000"), new BigDecimal("100.0000"));

    @Test
    @DisplayName("민원 기준 정확히(월 50.000000) — ≥ 포함이라 CAND, 여유 5% 미만이라 CRTR_BNDRY")
    void cvlcptCrtrExactlyIsCand() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 400, 0, 500, 500));

        assertThat(z.getRvwGrdCd()).isEqualTo("CAND");
        assertThat(z.getRvwTrgtYn()).isEqualTo("Y");
        assertThat(z.getCvlcptMmAvg()).isEqualByComparingTo("50.0");
        assertThat(z.getCvlcptRt()).isEqualByComparingTo("10.00");
        assertThat(z.getCutnCds()).containsExactly("CRTR_BNDRY");   // 월 50 < 52.5
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("1.00"); // min(50/50, 10/7=1.428571)
    }

    @Test
    @DisplayName("비율 기준 정확히(rt 7.000000) — ≥ 포함이라 CAND, rt 7 < 7.35 라 CRTR_BNDRY")
    void rtCrtrExactlyIsCand() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 448, 0, 800, 800));

        assertThat(z.getRvwGrdCd()).isEqualTo("CAND");
        assertThat(z.getCvlcptMmAvg()).isEqualByComparingTo("56.0");
        assertThat(z.getCvlcptRt()).isEqualByComparingTo("7.00");
        assertThat(z.getCutnCds()).containsExactly("CRTR_BNDRY");
        // 검토 지수 (a) — min(56/50 = 1.12, 7/7 = 1.0) = 1.00
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("1.00");
    }

    @Test
    @DisplayName("기준 바로 아래(월 49.875) — 비율이 높아도 CVLCPT_ONLY 가 아니라 NONE")
    void justBelowCvlcptCrtrIsNone() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 399, 0, 100, 100));

        assertThat(z.getRvwGrdCd()).isEqualTo("NONE");
        assertThat(z.getRvwTrgtYn()).isEqualTo("N");
        assertThat(z.getCvlcptRt()).isEqualByComparingTo("49.88");   // 49.875 → HALF_UP
        assertThat(z.getCutnCds()).isEmpty();
        // 검토 지수 (a) — min(49.875/50 = 0.9975, 49.875/7 = 7.125) = 0.9975 → 버림 0.99 (반올림이면 1.00)
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("0.99");
        // 🔁 순번은 후보에만이 아니라 지수가 있는 읍면동 전부에 매긴다
        assertThat(z.getRnk()).isEqualTo(1);
    }

    @Test
    @DisplayName("핵심 경계(월 112 · rt 14.000000) — CORE, 핵심 기준 여유 5% 미만이라 CRTR_BNDRY")
    void coreBoundaryIsCore() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 896, 0, 800, 800));

        assertThat(z.getRvwGrdCd()).isEqualTo("CORE");
        assertThat(z.getCvlcptRt()).isEqualByComparingTo("14.00");
        assertThat(z.getCutnCds()).containsExactly("CRTR_BNDRY");   // rt 14 < 14.7
        // 검토 지수 (a) — min(112/50 = 2.24, 14/7 = 2.0) = 2.00
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("2.00");
    }

    @Test
    @DisplayName("면수 0 — UNABLE, cvlcptRt·rvwScr·rnk 는 null, 후보 아님")
    void zeroCpctyIsUnable() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 150, 0, 0, 0));

        assertThat(z.getRvwGrdCd()).isEqualTo("UNABLE");
        assertThat(z.getCvlcptRt()).isNull();
        assertThat(z.getRvwScr()).as("면수 0 이면 지수를 계산할 수 없다(0 이 아니다)").isNull();
        assertThat(z.getRnk()).isNull();
        assertThat(z.getRvwTrgtYn()).isEqualTo("N");
        assertThat(z.getVaclotExclCvlcptRt()).isNull();
        assertThat(z.getVaclotExclRvwTrgtYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("한 지점 쏠림(1,040 중 711) — CORE + TOP_PNT (제외 후 월 41.1 로 후보 미달)")
    void topPntConcentration() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 1040, 711, 684, 684));

        assertThat(z.getRvwGrdCd()).isEqualTo("CORE");
        assertThat(z.getCutnCds()).containsExactly("TOP_PNT");
        assertThat(z.getTopPntRt()).isEqualByComparingTo("68.4");
    }

    @Test
    @DisplayName("공한지 제외 — 기본 판정은 CVLCPT_ONLY, 공한지를 뺀 면수로는 후보")
    void vaclotExcludedBecomesCand() {
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 1051, 0, 2113, 1446));

        assertThat(z.getRvwGrdCd()).isEqualTo("CVLCPT_ONLY");
        assertThat(z.getRvwTrgtYn()).isEqualTo("N");
        assertThat(z.getVaclotExclRvwTrgtYn()).isEqualTo("Y");
        assertThat(z.getCvlcptRt()).isEqualByComparingTo("6.22");
        assertThat(z.getVaclotExclCvlcptRt()).isEqualByComparingTo("9.09");
    }

    @Test
    @DisplayName("민원 0건 — NONE, topPntRt 는 null, NO_CVLCPT_IN_RANGE 경고")
    void noCvlcptIsNone() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(
                List.of(base("50110001", 0, 0, 100, 100)), MONTHS, CRTR, new ArrayList<>());
        ZoneRcmdtnShareZoneVO z = r.getZones().get(0);

        assertThat(z.getRvwGrdCd()).isEqualTo("NONE");
        assertThat(z.getTopPntRt()).isNull();
        assertThat(r.getWarnings()).containsExactly("NO_CVLCPT_IN_RANGE");
        assertThat(r.getSmry().getRvwTrgtCvlcptRt()).isNull();
    }

    @Test
    @DisplayName("설정 행 없음 — 상수 50·7.0 + CRTR_FALLBACK (한 번만)")
    void emptySettingsFallBackToConstants() {
        List<String> warnings = new ArrayList<>();

        ZoneRcmdtnShareCalculator.Crtr crtr = ZoneRcmdtnShareCalculator.resolveCrtr(List.of(), warnings);

        assertThat(crtr.cvlcptMmCrtrVl()).isEqualByComparingTo("50");
        assertThat(crtr.cvlcptRtCrtrVl()).isEqualByComparingTo("7.0");
        assertThat(warnings).containsExactly("CRTR_FALLBACK");
    }

    @Test
    @DisplayName("순번 — 검토 지수 내림차순 (종전 순위합 규칙이면 A·B·C 순이던 입력)")
    void rnkFollowsRvwScr() {
        // 일부러 뒤섞어 넣는다 — 입력 순서에 기대는 구현을 잡는다
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                base("50110003", 400, 0, 300, 300),    // C  mm 50   rt 16.666667 → min(1.0,  2.380952) = 1.0
                base("50110002", 500, 0, 600, 600),    // B  mm 62.5 rt 10.416667 → min(1.25, 1.488095) = 1.25
                base("50110001", 500, 0, 800, 800)),   // A  mm 62.5 rt  7.8125   → min(1.25, 1.116071) = 1.116071
                MONTHS, CRTR, new ArrayList<>());

        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CAND", "CAND", "CAND");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110002", "50110001", "50110003");
        assertThat(r.getZones()).extracting(z -> z.getRvwScr().toPlainString())
                .containsExactly("1.25", "1.11", "1.00");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk).containsExactly(1, 2, 3);
        assertThat(r.getSmry().getRvwTrgtCnt()).isEqualTo(3);
        assertThat(r.getCrtrVls().getCvlcptMmCrtrVl().toPlainString()).isEqualTo("50");
        assertThat(r.getCrtrVls().getCvlcptRtCrtrVl().toPlainString()).isEqualTo("7");
        assertThat(r.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("(b) 버림 경계 — rt/c2 = 0.996428 은 0.99 이고 후보가 아니다 (반올림이면 1.00 이 되어 어긋난다)")
    void rvwScrIsTruncatedNotRounded() {
        // 558건 · 1,000면 · 8개월: mm = 69.75(÷50 = 1.395), rt = 69.75×100÷1000 = 6.975(÷7 = 0.996428…)
        // → min = 0.996428 → 버림 0.99. HALF_UP 이면 1.00 이 되어 "지수 1.00 인데 후보 아님" 이 된다.
        ZoneRcmdtnShareZoneVO z = one(base("50110001", 558, 0, 1000, 1000));

        assertThat(z.getCvlcptRt()).isEqualByComparingTo("6.98");   // 6.975 → HALF_UP (이쪽은 반올림이다)
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("0.99");
        assertThat(z.getRvwTrgtYn()).isEqualTo("N");
        assertThat(z.getRvwGrdCd()).isEqualTo("CVLCPT_ONLY");
    }

    @Test
    @DisplayName("(c) 불변식 — rvwScr ≥ 1.00 ⇔ rvwTrgtYn=Y, rvwScr ≥ 2.00 ⇔ CORE, rvwScr null ⇔ UNABLE (경계 포함 격자)")
    void rvwScrAgreesWithGrade() {
        BigDecimal one = new BigDecimal("1.00");
        BigDecimal two = new BigDecimal("2.00");
        // 기준값 — 기본, 4자리 소수(나눗셈이 딱 떨어지지 않는다), 한쪽이 0 인 경우
        List<ZoneRcmdtnShareCalculator.Crtr> crtrs = List.of(
                CRTR,
                crtr("33.3334", "6.9999"),
                crtr("0.0001", "0.0001"),
                crtr("0", "7"),
                crtr("50", "0"));
        long[] cpctys = { 0, 1, 50, 100, 357, 571, 572, 800, 1000, 1143, 2315 };
        BigDecimal[] monthsList = {
                BigDecimal.valueOf(1), BigDecimal.valueOf(3), BigDecimal.valueOf(7),
                BigDecimal.valueOf(8), BigDecimal.valueOf(12) };
        int cand = 0;
        int core = 0;
        int below = 0;
        int unable = 0;
        for (ZoneRcmdtnShareCalculator.Crtr crtr : crtrs) {
            for (BigDecimal months : monthsList) {
                List<ZoneRcmdtnShareBaseVO> rows = new ArrayList<>();
                int seq = 0;
                for (long cpcty : cpctys) {
                    // 건수 0..1,300 을 촘촘히 훑는다 — 8개월이면 400(=월 50)·800(=월 100) 경계를 지난다
                    for (long cnt = 0; cnt <= 1300; cnt += 7) {
                        rows.add(base(String.format("5011%04d", seq++), cnt, 0, cpcty, cpcty));
                    }
                    for (long cnt : new long[] { 399, 400, 401, 448, 558, 799, 800, 801, 896 }) {
                        rows.add(base(String.format("5011%04d", seq++), cnt, 0, cpcty, cpcty));
                    }
                }
                for (ZoneRcmdtnShareZoneVO z
                        : ZoneRcmdtnShareCalculator.calculate(rows, months, crtr, new ArrayList<>()).getZones()) {
                    String ctx = crtr + " months=" + months + " " + z;
                    boolean isUnable = "UNABLE".equals(z.getRvwGrdCd());
                    assertThat(z.getRvwScr() == null).as("rvwScr null ⇔ UNABLE: %s", ctx).isEqualTo(isUnable);
                    assertThat(z.getRnk() == null).as("rnk null ⇔ UNABLE: %s", ctx).isEqualTo(isUnable);
                    if (isUnable) {
                        unable++;
                        continue;
                    }
                    assertThat(z.getRvwScr().scale()).isEqualTo(2);
                    assertThat(z.getRvwScr().compareTo(one) >= 0)
                            .as("rvwScr ≥ 1.00 ⇔ 후보: %s", ctx).isEqualTo("Y".equals(z.getRvwTrgtYn()));
                    assertThat(z.getRvwScr().compareTo(two) >= 0)
                            .as("rvwScr ≥ 2.00 ⇔ CORE: %s", ctx).isEqualTo("CORE".equals(z.getRvwGrdCd()));
                    if ("CORE".equals(z.getRvwGrdCd())) {
                        core++;
                    } else if ("Y".equals(z.getRvwTrgtYn())) {
                        cand++;
                    } else {
                        below++;
                    }
                }
            }
        }
        // 헛통과 방지 — 네 갈래가 모두 실제로 나왔다
        assertThat(core).isPositive();
        assertThat(cand).isPositive();
        assertThat(below).isPositive();
        assertThat(unable).isPositive();
    }

    @Test
    @DisplayName("(d) 기준 0 — 0 인 쪽은 '항상 충족' 이라 그 항을 빼고 다른 항만으로 지수를 낸다 (0 으로 나누지 않는다)")
    void zeroCrtrDropsThatTerm() {
        // 민원 월기준 0 · 비율기준 7 — 지수 = rt ÷ 7
        ZoneRcmdtnShareCalculator.Crtr mmZero = crtr("0.0000", "7.0000");
        ZoneRcmdtnShareResultVO r1 = ZoneRcmdtnShareCalculator.calculate(List.of(
                base("50110001", 448, 0, 800, 800),    // rt 7  → 1.00  CAND
                base("50110002", 896, 0, 800, 800),    // rt 14 → 2.00  CORE (민원 쪽 2배 조건 mm ≥ 0 은 항상 참)
                base("50110003", 8, 0, 100, 100)),     // rt 1  → 1/7 = 0.142857 → 0.14  CVLCPT_ONLY
                MONTHS, mmZero, new ArrayList<>());
        assertThat(r1.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110002", "50110001", "50110003");
        assertThat(r1.getZones()).extracting(z -> z.getRvwScr().toPlainString())
                .containsExactly("2.00", "1.00", "0.14");
        assertThat(r1.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CORE", "CAND", "CVLCPT_ONLY");
        assertThat(r1.getWarnings()).isEmpty();

        // 민원 월기준 50 · 비율기준 0 — 지수 = mm ÷ 50
        ZoneRcmdtnShareCalculator.Crtr rtZero = crtr("50.0000", "0.0000");
        ZoneRcmdtnShareResultVO r2 = ZoneRcmdtnShareCalculator.calculate(List.of(
                base("50110001", 448, 0, 800, 800),    // mm 56     → 1.12  CAND
                base("50110002", 399, 0, 100, 100),    // mm 49.875 → 0.9975 → 0.99  NONE
                base("50110003", 800, 0, 9999, 9999)), // mm 100    → 2.00  CORE (면수가 커도 비율 조건은 항상 참)
                MONTHS, rtZero, new ArrayList<>());
        assertThat(r2.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110003", "50110001", "50110002");
        assertThat(r2.getZones()).extracting(z -> z.getRvwScr().toPlainString())
                .containsExactly("2.00", "1.12", "0.99");
        assertThat(r2.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CORE", "CAND", "NONE");
        assertThat(r2.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("(d) 두 기준 모두 0 — rvwScr·rnk 는 null 이고 CRTR_ALL_ZERO 경고, 등급 판정은 그대로(면수 있으면 CORE)")
    void bothCrtrZeroHasNoRvwScr() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                base("50110001", 900, 0, 0, 0),        // 면수 0 — 민원이 더 많아도 맨 뒤
                base("50110002", 448, 0, 800, 800)),
                MONTHS, crtr("0", "0"), new ArrayList<>());

        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110002", "50110001");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CORE", "UNABLE");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwScr).containsOnlyNulls();
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk).containsOnlyNulls();
        assertThat(r.getWarnings()).containsExactly("CRTR_ALL_ZERO");
    }

    @Test
    @DisplayName("(e) 정렬 — 지수 내림차순 → 민원 내림차순 → emd_cd, 판정 불가는 맨 뒤, rnk 는 1..n 연속·판정 불가는 null")
    void zonesAreOrderedByRvwScr() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                base("50110009", 900, 0, 0, 0),        // U1 면수 0
                base("50110006", 558, 0, 1000, 1000),  // O  min(1.395, 6.975/7 = 0.996428) = 0.996428  CVLCPT_ONLY
                base("50110002", 480, 0, 100, 100),    // X  mm 60 rt 60  → min(1.2, 8.571428) = 1.2
                base("50110005", 672, 0, 1000, 1000),  // Y  mm 84 rt 8.4 → min(1.68, 1.2)     = 1.2
                base("50110008", 950, 0, 0, 0),        // U2 면수 0
                base("50110004", 399, 0, 100, 100),    // N  min(0.9975, 7.125) = 0.9975              NONE
                base("50110001", 480, 0, 200, 200),    // Z  mm 60 rt 30  → min(1.2, 4.285714) = 1.2
                base("50110003", 896, 0, 800, 800)),   // K  min(2.24, 2.0) = 2.0                     CORE
                MONTHS, CRTR, new ArrayList<>());

        // K(2.0) → 지수 1.2 동률 셋: 민원 672 인 Y 먼저, 480 동률인 Z·X 는 emd_cd 순 → N → O → 판정 불가(민원 순)
        // 🔴 N·O 는 표시값이 둘 다 0.99 지만 버림 전 값(0.9975 > 0.996428)으로 갈린다 — 민원은 O 가 더 많다
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd).containsExactly(
                "50110003", "50110005", "50110001", "50110002", "50110004", "50110006", "50110008", "50110009");
        assertThat(r.getZones()).extracting(z -> z.getRvwScr() == null ? null : z.getRvwScr().toPlainString())
                .containsExactly("2.00", "1.20", "1.20", "1.20", "0.99", "0.99", null, null);
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk)
                .containsExactly(1, 2, 3, 4, 5, 6, null, null);
        // 등급별 묶음 정렬이 아니다 — 기준 미달 안에서는 NONE 이 CVLCPT_ONLY 보다 앞설 수 있다
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd).containsExactly(
                "CORE", "CAND", "CAND", "CAND", "NONE", "CVLCPT_ONLY", "UNABLE", "UNABLE");
    }

    @Test
    @DisplayName("(f) 필터 후 순번 — 시군구로 거르면 그 안에서 1..n(지수 내림차순), 판정 불가는 null·맨 뒤, "
            + "등급·rvwScr·smry 는 필터 전과 같다")
    void rnkIsAssignedWithinFilteredRows() {
        List<ZoneRcmdtnShareBaseVO> rows = mixedSggRows();

        // 필터 없음 — 두 시군구가 섞여 도 전체 번호다: A(2.0) R(1.785714) P(1.25) B(1.2) Q(0.9975) → 판정 불가(민원 순)
        ZoneRcmdtnShareResultVO all = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>());
        assertThat(all.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd).containsExactly(
                "50110001", "50130003", "50130001", "50110002", "50130002", "50130004", "50110003", "50130005");
        assertThat(all.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk)
                .containsExactly(1, 2, 3, 4, 5, null, null, null);
        // 필터 둘이 null 인 6인자 호출은 4인자 호출과 같다
        assertThat(ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>(), null, null)
                .getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk)
                .containsExactly(1, 2, 3, 4, 5, null, null, null);

        // 서귀포시 — 도 전체 번호 2·3·5 가 아니라 1·2·3 이다
        ZoneRcmdtnShareResultVO sgp = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), "서귀포시", null);
        assertThat(sgp.getZones()).extracting(ZoneRcmdtnShareZoneVO::getSggNm).containsOnly("서귀포시");
        assertThat(sgp.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd).containsExactly(
                "50130003", "50130001", "50130002", "50130004", "50130005");
        assertThat(sgp.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk)
                .containsExactly(1, 2, 3, null, null);
        assertThat(sgp.getZones()).extracting(z -> z.getRvwScr() == null ? null : z.getRvwScr().toPlainString())
                .containsExactly("1.78", "1.25", "0.99", null, null);
        assertThat(sgp.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CAND", "CAND", "NONE", "UNABLE", "UNABLE");

        // 제주시 — 도 전체 번호 1·4 가 아니라 1·2 다
        ZoneRcmdtnShareResultVO jeju = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), "제주시", null);
        assertThat(jeju.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110001", "50110002", "50110003");
        assertThat(jeju.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk).containsExactly(1, 2, null);

        // 🔴 바뀌는 것은 번호뿐이다 — 번호를 지우면 필터 전의 같은 읍면동과 한 글자도 다르지 않다
        for (ZoneRcmdtnShareResultVO filtered : List.of(sgp, jeju)) {
            for (ZoneRcmdtnShareZoneVO z : filtered.getZones()) {
                ZoneRcmdtnShareZoneVO before = byEmdCd(all, z.getEmdCd());
                assertThat(withoutRnk(z)).as("번호 밖의 값은 필터와 무관하다: %s", z.getEmdCd())
                        .isEqualTo(withoutRnk(before));
            }
            // 요약은 필터와 무관한 도 전체 값이다
            assertThat(filtered.getSmry().toString()).isEqualTo(all.getSmry().toString());
            assertThat(filtered.getSmry().getEmdCnt()).isEqualTo(8);
            assertThat(filtered.getSmry().getRvwTrgtCnt()).isEqualTo(4);
            assertThat(filtered.getSmry().getUnableCnt()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("(f) emdCd 필터 — 그 1행이 rnk 1 이다(도 전체 5번이던 행), 판정 불가면 null. 두 필터가 어긋나면 0행")
    void emdCdFilterRestartsRnkAtOne() {
        List<ZoneRcmdtnShareBaseVO> rows = mixedSggRows();

        ZoneRcmdtnShareResultVO q = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), null, "50130002");
        assertThat(q.getZones()).hasSize(1);
        assertThat(q.getZones().get(0).getRnk()).isEqualTo(1);
        assertThat(q.getZones().get(0).getRvwScr().toPlainString()).isEqualTo("0.99");
        assertThat(q.getZones().get(0).getRvwGrdCd()).isEqualTo("NONE");

        ZoneRcmdtnShareResultVO unable = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), null, "50130004");
        assertThat(unable.getZones()).hasSize(1);
        assertThat(unable.getZones().get(0).getRnk()).isNull();
        assertThat(unable.getZones().get(0).getRvwGrdCd()).isEqualTo("UNABLE");

        // 둘 다 주면 둘 다 만족해야 한다 — 맞으면 1행(rnk 1), 어긋나면 0행. 요약은 그래도 도 전체다
        ZoneRcmdtnShareResultVO both = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), "서귀포시", "50130001");
        assertThat(both.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk).containsExactly(1);
        ZoneRcmdtnShareResultVO mismatch = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), "제주시", "50130001");
        assertThat(mismatch.getZones()).isEmpty();
        assertThat(mismatch.getSmry().getEmdCnt()).isEqualTo(8);
    }


    // =================================================================================
    // 추세 반영 지수 · 순번 1차 블록 D=30 · 폴백 D/2=15 로 넘긴다
    // =================================================================================

    private static final Integer D = 30;
    private static final Integer HALF = 15;

    @Test
    @DisplayName("[추세 1] 가중치 0(설정값) — 모든 계수 1, 순번이 추세 없는 호출과 같다 · 가중치·상한 설정 행 부재면 0.5/100 폴백")
    void zeroWeightKeepsOrder() {
        // 가중치 행만 0 으로 있고 상한 행은 없다 → 상한만 폴백(100) + CRTR_FALLBACK
        List<String> warnings = new ArrayList<>();
        ZoneRcmdtnShareCalculator.Crtr crtr = ZoneRcmdtnShareCalculator.resolveCrtr(List.of(
                vrbl("CVLCPT_MM_CRTR_VL", "50.0000"), vrbl("CVLCPT_RT_CRTR_VL", "7.0000"),
                vrbl("CVLCPT_INCRS_WGVL", "0.0000")), warnings);
        assertThat(crtr.incrsWgvl()).isEqualByComparingTo("0");
        assertThat(crtr.incrsRtUplmtVl()).isEqualByComparingTo("100");
        assertThat(warnings).containsExactly("CRTR_FALLBACK");
        // 설정 행이 하나도 없으면 가중치도 0.5 로 떨어진다
        ZoneRcmdtnShareCalculator.Crtr dflt = ZoneRcmdtnShareCalculator.resolveCrtr(List.of(), new ArrayList<>());
        assertThat(dflt.incrsWgvl()).isEqualByComparingTo("0.5");
        assertThat(dflt.incrsRtUplmtVl()).isEqualByComparingTo("100");

        // 증가율이 제각각인 입력 — 가중치 0 이면 순서가 추세 없는 호출과 한 글자도 다르지 않다
        List<ZoneRcmdtnShareBaseVO> rows = new ArrayList<>();
        long[] bfrs = { 10, 2000, 50, 400, 100, 30, 900, 10 };
        List<ZoneRcmdtnShareBaseVO> mixed = mixedSggRows();
        for (int i = 0; i < mixed.size(); i++) {
            rows.add(trend(copy(mixed.get(i)), bfrs[i], 0, 0));
        }
        ZoneRcmdtnShareResultVO legacy = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, crtr, new ArrayList<>());
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, crtr, new ArrayList<>(), null, null, D, HALF);

        assertThat(r.getZones()).extracting(z -> z.getIncrsCfc().toPlainString()).containsOnly("1.000");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactlyElementsOf(legacy.getZones().stream().map(ZoneRcmdtnShareZoneVO::getEmdCd).toList());
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk)
                .containsExactly(1, 2, 3, 4, 5, null, null, null);
        // 증가율은 그대로 낸다(가중치는 계수에만 걸린다) — W: 100 vs 10 → +900.0% · A: 896 vs 900 → −0.4%
        assertThat(byEmdCd(r, "50130005").getCvlcptIncrsRt().toPlainString()).isEqualTo("900.0");
        assertThat(byEmdCd(r, "50110001").getCvlcptIncrsRt().toPlainString()).isEqualTo("-0.4");
        // 추세 반영 지수 = 검토 지수(계수 1)
        for (ZoneRcmdtnShareZoneVO z : r.getZones()) {
            if (z.getRvwScr() != null) {
                assertThat(z.getIncrsRfltRvwScr()).isEqualByComparingTo(z.getRvwScr());
            }
        }
        assertThat(r.getCrtrVls().getIncrsWgvl().toPlainString()).isEqualTo("0");
        assertThat(r.getCrtrVls().getIncrsRtUplmtVl().toPlainString()).isEqualTo("100");
    }

    @Test
    @DisplayName("[추세 2] 이전 블록 9건 — 분모 하한(10) 미달이라 증가율 null · 계수 1 · bfrCvlcptCnt 9 / 10건이면 계산된다")
    void bfrBelowMinTrendBaseIsNeutral() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 448, 0, 800, 800), 9, 0, 0),
                trend(base("50110002", 448, 0, 800, 800), 10, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF);

        ZoneRcmdtnShareZoneVO nine = byEmdCd(r, "50110001");
        assertThat(nine.getBfrCvlcptCnt()).isEqualTo(9L);
        assertThat(nine.getCvlcptIncrsRt()).isNull();
        assertThat(nine.getIncrsCfc().toPlainString()).isEqualTo("1.000");
        assertThat(nine.getIncrsRfltRvwScr().toPlainString()).isEqualTo("1.00");   // 1.00 × 1

        ZoneRcmdtnShareZoneVO ten = byEmdCd(r, "50110002");
        assertThat(ten.getCvlcptIncrsRt()).isEqualByComparingTo("4380.0");          // (448−10)/10
        assertThat(ten.getIncrsCfc().toPlainString()).isEqualTo("1.500");            // 상한 100% 로 잘림
        assertThat(r.getCrtrVls().getMinTrendBase()).isEqualTo(10);
        assertThat(r.getCrtrVls().getTrendDayCnt()).isEqualTo(D);
        assertThat(r.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("[추세 3] 증가율 +300%, 상한 100 — 계수 1.5 (clamp) · 추세 반영 지수 = 검토 지수 × 1.5")
    void incrsRtIsClampedToUplmt() {
        // 896건 · 800면 → 검토 지수 2.0(CORE) · 이전 224 → (896−224)/224 = +300%
        ZoneRcmdtnShareZoneVO z = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 896, 0, 800, 800), 224, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF).getZones().get(0);

        assertThat(z.getCvlcptIncrsRt().toPlainString()).isEqualTo("300.0");
        assertThat(z.getIncrsCfc().toPlainString()).isEqualTo("1.500");
        assertThat(z.getRvwScr().toPlainString()).isEqualTo("2.00");
        assertThat(z.getIncrsRfltRvwScr().toPlainString()).isEqualTo("3.00");
        assertThat(z.getRvwGrdCd()).as("등급은 검토 지수로 정한다").isEqualTo("CORE");

        // 상한을 50 으로 내리면 계수 1.25 — 상한이 clamp 폭을 정한다
        ZoneRcmdtnShareZoneVO capped = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 896, 0, 800, 800), 224, 0, 0)), MONTHS,
                new ZoneRcmdtnShareCalculator.Crtr(new BigDecimal("50"), new BigDecimal("7"),
                        new BigDecimal("0.5"), new BigDecimal("50")),
                new ArrayList<>(), null, null, D, HALF).getZones().get(0);
        assertThat(capped.getIncrsCfc().toPlainString()).isEqualTo("1.250");
    }

    @Test
    @DisplayName("[추세 4] 증가율 −100% — 계수 0.5 (정밀값 — 연동 사례 −12.3% 는 0.938)")
    void minusHundredPercentIsHalf() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 0, 0, 800, 800), 100, 0, 0),
                // 정밀 g 로 계수를 낸다 — 1,000 vs 1,141: g = −0.123576 → 계수 1 − 0.061788 = 0.938212 → 표시 0.938
                trend(base("50110002", 1000, 0, 800, 800), 1141, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF);

        ZoneRcmdtnShareZoneVO zero = byEmdCd(r, "50110001");
        assertThat(zero.getCvlcptIncrsRt().toPlainString()).isEqualTo("-100.0");
        assertThat(zero.getIncrsCfc().toPlainString()).isEqualTo("0.500");
        assertThat(zero.getIncrsRfltRvwScr().toPlainString()).isEqualTo("0.00");

        ZoneRcmdtnShareZoneVO precise = byEmdCd(r, "50110002");
        assertThat(precise.getCvlcptIncrsRt().toPlainString()).isEqualTo("-12.4");
        assertThat(precise.getIncrsCfc().toPlainString()).isEqualTo("0.938");
    }

    @Test
    @DisplayName("[추세 5] rvwScr 0.99·계수 1.2(기준 미달) vs rvwScr 1.00·계수 0.6(후보): 우선도가 큰 "
            + "기준 미달이 앞, 등급 그대로 · 핵심이 후보 뒤에 설 수 있다")
    void orderFollowsPriorityRegardlessOfCandidacy() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                // A 399건·100면 → 0.9975(NONE) · 이전 285 → +40% → 계수 1.2 → 1.197
                trend(base("50110001", 399, 0, 100, 100), 285, 0, 0),
                // B 400건·500면 → 1.0(CAND) · 이전 2,000 → −80% → 계수 0.6 → 0.6
                trend(base("50110002", 400, 0, 500, 500), 2000, 0, 0),
                // C 896건·800면 → 2.0(CORE) · 이전 1,792 → −50% → 계수 0.75 → 1.5
                trend(base("50110003", 896, 0, 800, 800), 1792, 0, 0),
                // E 760건·800면 → mm 95 rt 11.875 → min(1.9, 1.696428) = 1.696428(CAND) · 이전 380 → +100% → 계수 1.5 → 2.544642
                trend(base("50110004", 760, 0, 800, 800), 380, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF);

        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110004", "50110003", "50110001", "50110002");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRvwGrdCd)
                .containsExactly("CAND", "CORE", "NONE", "CAND");
        assertThat(r.getZones()).extracting(z -> z.getRvwScr().toPlainString())
                .containsExactly("1.69", "2.00", "0.99", "1.00");
        assertThat(r.getZones()).extracting(z -> z.getIncrsCfc().toPlainString())
                .containsExactly("1.500", "0.750", "1.200", "0.600");
        assertThat(r.getZones()).extracting(z -> z.getIncrsRfltRvwScr().toPlainString())
                .containsExactly("2.54", "1.50", "1.19", "0.60");
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getRnk).containsExactly(1, 2, 3, 4);
        assertThat(r.getSmry().getRvwTrgtCnt()).isEqualTo(3);
        assertThat(r.getSmry().getCoreCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("[추세 6] 전 읍면동 이전 블록 0 — 반 분할 값(앞 D/2 ↔ 뒤 D/2)으로 계산 + TREND_BFR_FALLBACK · trendDayCnt = D/2")
    void allBfrZeroFallsBackToHalves() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 448, 0, 800, 800), 0, 100, 150),   // +50% → 1.25
                trend(base("50110002", 896, 0, 800, 800), 0, 400, 200),   // −50% → 0.75
                trend(base("50110003", 100, 0, 0, 0), 0, 5, 50)),         // 분모 5 < 10 → null · 1
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF);

        assertThat(r.getWarnings()).containsExactly("TREND_BFR_FALLBACK");
        assertThat(r.getCrtrVls().getTrendDayCnt()).isEqualTo(HALF);
        ZoneRcmdtnShareZoneVO a = byEmdCd(r, "50110001");
        assertThat(a.getBfrCvlcptCnt()).as("폴백이면 분모는 앞 D/2").isEqualTo(100L);
        assertThat(a.getCvlcptIncrsRt().toPlainString()).isEqualTo("50.0");
        assertThat(a.getIncrsCfc().toPlainString()).isEqualTo("1.250");
        ZoneRcmdtnShareZoneVO b = byEmdCd(r, "50110002");
        assertThat(b.getCvlcptIncrsRt().toPlainString()).isEqualTo("-50.0");
        assertThat(b.getIncrsCfc().toPlainString()).isEqualTo("0.750");
        ZoneRcmdtnShareZoneVO c = byEmdCd(r, "50110003");
        assertThat(c.getBfrCvlcptCnt()).isEqualTo(5L);
        assertThat(c.getCvlcptIncrsRt()).isNull();
        // 순서: 후보 둘 — B 2.0×0.75 = 1.5 > A 1.0×1.25 = 1.25
        assertThat(r.getZones()).extracting(ZoneRcmdtnShareZoneVO::getEmdCd)
                .containsExactly("50110002", "50110001", "50110003");

        // 한 읍면동이라도 1차 이전 블록이 있으면 폴백하지 않는다(도 전체 1회 판정) — 0 인 동은 0 건 분모라 null
        ZoneRcmdtnShareResultVO primary = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 448, 0, 800, 800), 0, 100, 150),
                trend(base("50110002", 896, 0, 800, 800), 1, 400, 200)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF);
        assertThat(primary.getWarnings()).isEmpty();
        assertThat(primary.getCrtrVls().getTrendDayCnt()).isEqualTo(D);
        assertThat(byEmdCd(primary, "50110001").getBfrCvlcptCnt()).isEqualTo(0L);
        assertThat(byEmdCd(primary, "50110001").getCvlcptIncrsRt()).isNull();

        // 🔴 기간 하한 미달(D null)이면 세 칸이 0 이어도 폴백 판정을 하지 않는다 — 경고 없음 · bfrCvlcptCnt null
        ZoneRcmdtnShareResultVO shortWin = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 448, 0, 800, 800), 0, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, null, null);
        assertThat(shortWin.getWarnings()).isEmpty();
        assertThat(shortWin.getCrtrVls().getTrendDayCnt()).isNull();
        assertThat(shortWin.getCrtrVls().getMinTrendBase()).isEqualTo(10);
        ZoneRcmdtnShareZoneVO s = shortWin.getZones().get(0);
        assertThat(s.getBfrCvlcptCnt()).isNull();
        assertThat(s.getCvlcptIncrsRt()).isNull();
        assertThat(s.getIncrsCfc().toPlainString()).isEqualTo("1.000");
    }

    @Test
    @DisplayName("[추세 6-1] 1차 이전 블록 시작 < 자료 시작이면 이전 블록에 민원이 있어도 폴백 · 같거나 뒤면 1차 · 자료 시작 null 이면 종전")
    void bfrBeforeDataStartFallsBack() {
        List<ZoneRcmdtnShareBaseVO> rows = List.of(
                trend(base("50110001", 448, 0, 800, 800), 300, 100, 150),
                trend(base("50110002", 896, 0, 800, 800), 50, 400, 200));
        java.time.LocalDateTime dataBgng = java.time.LocalDateTime.of(2026, 1, 10, 0, 0);

        // (a) 앞 → 폴백: 경고 · D/2 · 분모는 앞 D/2
        ZoneRcmdtnShareResultVO before = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>(),
                null, null, D, HALF, dataBgng.minusDays(1), dataBgng);
        assertThat(before.getWarnings()).containsExactly("TREND_BFR_FALLBACK");
        assertThat(before.getCrtrVls().getTrendDayCnt()).isEqualTo(HALF);
        assertThat(byEmdCd(before, "50110001").getBfrCvlcptCnt()).as("폴백이면 분모는 앞 D/2").isEqualTo(100L);

        // (b) 같은 날 · 뒤 → 1차(이전 블록 그대로)
        for (java.time.LocalDateTime from : List.of(dataBgng, dataBgng.plusDays(5))) {
            ZoneRcmdtnShareResultVO primary = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR,
                    new ArrayList<>(), null, null, D, HALF, from, dataBgng);
            assertThat(primary.getWarnings()).isEmpty();
            assertThat(primary.getCrtrVls().getTrendDayCnt()).isEqualTo(D);
            assertThat(byEmdCd(primary, "50110001").getBfrCvlcptCnt()).isEqualTo(300L);
        }

        // 자료 시작 null → 종전(합 0 이 아니므로 1차) · 기간 하한 미달(D null)이면 가드도 타지 않는다
        ZoneRcmdtnShareResultVO noData = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>(),
                null, null, D, HALF, dataBgng.minusDays(1), null);
        assertThat(noData.getWarnings()).isEmpty();
        assertThat(noData.getCrtrVls().getTrendDayCnt()).isEqualTo(D);
        ZoneRcmdtnShareResultVO shortWin = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>(),
                null, null, null, null, dataBgng.minusDays(1), dataBgng);
        assertThat(shortWin.getWarnings()).isEmpty();
        assertThat(shortWin.getCrtrVls().getTrendDayCnt()).isNull();
    }

    @Test
    @DisplayName("[추세 7] 판정 불가(면수 0) — 증가율·증가계수는 건수로 계산, 추세 반영 지수 null · 순번 없음")
    void unableStillHasTrend() {
        ZoneRcmdtnShareZoneVO z = ZoneRcmdtnShareCalculator.calculate(List.of(
                trend(base("50110001", 200, 0, 0, 0), 100, 0, 0)),
                MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF).getZones().get(0);

        assertThat(z.getRvwGrdCd()).isEqualTo("UNABLE");
        assertThat(z.getBfrCvlcptCnt()).isEqualTo(100L);
        assertThat(z.getCvlcptIncrsRt().toPlainString()).isEqualTo("100.0");
        assertThat(z.getIncrsCfc().toPlainString()).isEqualTo("1.500");
        assertThat(z.getRvwScr()).isNull();
        assertThat(z.getIncrsRfltRvwScr()).isNull();
        assertThat(z.getRnk()).isNull();
    }

    @Test
    @DisplayName("[추세 8] 추세를 켜도 '지수 ≥ 1 ⇔ 후보 · ≥ 2 ⇔ 핵심 · null ⇔ UNABLE' 은 그대로이고, 후보의 순번이 항상 앞이다")
    void trendKeepsGradeInvariantsAndCandFirst() {
        BigDecimal one = new BigDecimal("1.00");
        BigDecimal two = new BigDecimal("2.00");
        long[] cpctys = { 0, 50, 100, 571, 800, 1143 };
        long[] bfrMltp = { 0, 1, 3, 9 };   // 이전 = 건수 × {0, 1/3, 1, 3} — 증가율 −100% · +200% · 0% · −66.7% 등
        List<ZoneRcmdtnShareBaseVO> rows = new ArrayList<>();
        int seq = 0;
        for (long cpcty : cpctys) {
            for (long cnt = 0; cnt <= 1300; cnt += 13) {
                long bfr = cnt * bfrMltp[seq % bfrMltp.length] / 3;
                rows.add(trend(base(String.format("5011%04d", seq++), cnt, 0, cpcty, cpcty), bfr, 0, 0));
            }
        }
        List<ZoneRcmdtnShareZoneVO> zones = ZoneRcmdtnShareCalculator.calculate(
                rows, MONTHS, CRTR, new ArrayList<>(), null, null, D, HALF).getZones();

        boolean cfcMoved = false;
        for (ZoneRcmdtnShareZoneVO z : zones) {
            boolean isUnable = "UNABLE".equals(z.getRvwGrdCd());
            assertThat(z.getRvwScr() == null).as("rvwScr null ⇔ UNABLE: %s", z).isEqualTo(isUnable);
            assertThat(z.getIncrsRfltRvwScr() == null).as("추세 반영 지수 null ⇔ UNABLE: %s", z).isEqualTo(isUnable);
            assertThat(z.getRnk() == null).as("rnk null ⇔ UNABLE: %s", z).isEqualTo(isUnable);
            cfcMoved |= z.getIncrsCfc().compareTo(BigDecimal.ONE) != 0;
            if (isUnable) {
                continue;
            }
            boolean cand = "Y".equals(z.getRvwTrgtYn());
            assertThat(z.getRvwScr().compareTo(one) >= 0).as("rvwScr ≥ 1.00 ⇔ 후보: %s", z).isEqualTo(cand);
            assertThat(z.getRvwScr().compareTo(two) >= 0).as("rvwScr ≥ 2.00 ⇔ CORE: %s", z)
                    .isEqualTo("CORE".equals(z.getRvwGrdCd()));
        }
        assertThat(cfcMoved).as("헛통과 방지 — 계수가 1 이 아닌 행이 있다").isTrue();
        // 후보 여부와 무관하게 순번 순으로 우선도(표시값) 비증가
        List<ZoneRcmdtnShareZoneVO> ranked = zones.stream().filter(z -> z.getRnk() != null)
                .sorted(Comparator.comparing(ZoneRcmdtnShareZoneVO::getRnk)).toList();
        for (int i = 1; i < ranked.size(); i++) {
            assertThat(ranked.get(i).getIncrsRfltRvwScr()).as("우선도 비증가: %s", ranked.get(i))
                    .isLessThanOrEqualTo(ranked.get(i - 1).getIncrsRfltRvwScr());
        }
    }

    // ---------------------------------------------------------------------------------

    /** 추세 블록 3칸을 채운다 — 1차 이전 D일 · 폴백 앞 D/2 · 폴백 뒤 D/2 */
    private static ZoneRcmdtnShareBaseVO trend(ZoneRcmdtnShareBaseVO vo, long bfr, long halfBfr, long halfAftr) {
        vo.setBfrCvlcptCnt(BigDecimal.valueOf(bfr));
        vo.setHalfBfrCvlcptCnt(BigDecimal.valueOf(halfBfr));
        vo.setHalfAftrCvlcptCnt(BigDecimal.valueOf(halfAftr));
        return vo;
    }

    /** {@link #mixedSggRows()} 는 불변 목록의 공유 객체라 추세 칸을 채우기 전에 복사한다 */
    private static ZoneRcmdtnShareBaseVO copy(ZoneRcmdtnShareBaseVO src) {
        ZoneRcmdtnShareBaseVO vo = base(src.getEmdCd(), src.getCvlcptCnt().longValue(),
                src.getTopPntCvlcptCnt().longValue(), src.getPkltCpcty().longValue(),
                src.getVaclotExclPkltCpcty().longValue());
        vo.setSggNm(src.getSggNm());
        return vo;
    }

    private static AnalysisWeightVO vrbl(String vrblCd, String stngVl) {
        AnalysisWeightVO vo = new AnalysisWeightVO();
        vo.setVrblCd(vrblCd);
        vo.setUseYn("Y");
        vo.setStngVl(new BigDecimal(stngVl));
        return vo;
    }

    // ---------------------------------------------------------------------------------

    /**
     * 두 시군구가 섞인 합성 입력(8개월 · 기준 50·7). 일부러 뒤섞어 넣는다.
     * <pre>
     * 제주시   A 50110001  896건 ·  800면  mm 112    rt 14        → min(2.24, 2.0)        = 2.0       CORE
     *          B 50110002  480건 ·  100면  mm 60     rt 60        → min(1.2, 8.571428)    = 1.2       CAND
     *          U 50110003  900건 ·    0면                                                  판정 불가
     * 서귀포시 P 50130001  500건 ·  600면  mm 62.5   rt 10.416667 → min(1.25, 1.488095)   = 1.25      CAND
     *          Q 50130002  399건 ·  100면  mm 49.875 rt 49.875    → min(0.9975, 7.125)    = 0.9975    NONE
     *          R 50130003  800건 ·  800면  mm 100    rt 12.5      → min(2.0, 1.785714)    = 1.785714  CAND
     *          V 50130004  950건 ·    0면                                                  판정 불가
     *          W 50130005  100건 ·    0면                                                  판정 불가
     * </pre>
     */
    private static List<ZoneRcmdtnShareBaseVO> mixedSggRows() {
        return List.of(
                base("서귀포시", "50130005", 100, 0),
                base("서귀포시", "50130002", 399, 100),
                base("제주시", "50110003", 900, 0),
                base("서귀포시", "50130001", 500, 600),
                base("제주시", "50110002", 480, 100),
                base("서귀포시", "50130004", 950, 0),
                base("제주시", "50110001", 896, 800),
                base("서귀포시", "50130003", 800, 800));
    }

    private static ZoneRcmdtnShareBaseVO base(String sggNm, String emdCd, long cvlcptCnt, long pkltCpcty) {
        ZoneRcmdtnShareBaseVO vo = base(emdCd, cvlcptCnt, 0, pkltCpcty, pkltCpcty);
        vo.setSggNm(sggNm);
        return vo;
    }

    private static ZoneRcmdtnShareZoneVO byEmdCd(ZoneRcmdtnShareResultVO r, String emdCd) {
        return r.getZones().stream().filter(z -> emdCd.equals(z.getEmdCd())).findFirst().orElseThrow();
    }

    /** {@code rnk} 만 뺀 전 필드의 문자열 — VO 의 {@code @ToString} 이 전 필드를 싣는다 */
    private static String withoutRnk(ZoneRcmdtnShareZoneVO z) {
        return z.toString().replaceFirst("rnk=[^,]*,", "rnk=,");
    }

    private static ZoneRcmdtnShareCalculator.Crtr crtr(String mm, String rt) {
        return new ZoneRcmdtnShareCalculator.Crtr(new BigDecimal(mm), new BigDecimal(rt),
                new BigDecimal("0.5000"), new BigDecimal("100.0000"));
    }

    private static ZoneRcmdtnShareZoneVO one(ZoneRcmdtnShareBaseVO base) {
        return ZoneRcmdtnShareCalculator.calculate(List.of(base), MONTHS, CRTR, new ArrayList<>())
                .getZones().get(0);
    }

    private static ZoneRcmdtnShareBaseVO base(String emdCd, long cvlcptCnt, long topPntCvlcptCnt,
                                              long pkltCpcty, long vaclotExclPkltCpcty) {
        ZoneRcmdtnShareBaseVO vo = new ZoneRcmdtnShareBaseVO();
        vo.setEmdCd(emdCd);
        vo.setSggNm("제주시");
        vo.setEmdNm("동" + emdCd);
        vo.setCvlcptCnt(BigDecimal.valueOf(cvlcptCnt));
        vo.setTopPntCvlcptCnt(BigDecimal.valueOf(topPntCvlcptCnt));
        vo.setPkltCnt(BigDecimal.ONE);
        vo.setPkltCpcty(BigDecimal.valueOf(pkltCpcty));
        vo.setVaclotExclPkltCpcty(BigDecimal.valueOf(vaclotExclPkltCpcty));
        return vo;
    }
}
