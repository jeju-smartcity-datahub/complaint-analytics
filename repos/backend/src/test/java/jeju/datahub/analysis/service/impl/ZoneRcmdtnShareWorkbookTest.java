package jeju.datahub.analysis.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jeju.datahub.analysis.service.AnalysisWeightVO;
import jeju.datahub.analysis.service.AnlsVrblOvrd;
import jeju.datahub.analysis.service.AnlsVrblScope;
import jeju.datahub.analysis.service.AnlsWorkbookVrblVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareBaseVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareResultVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareWorkbookRowVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareWorkbookVO;
import jeju.datahub.analysis.service.ZoneRcmdtnShareZoneVO;

/**
 * 공유주차제 엑셀 재계산 데이터 {@code wbk} 단위테스트 — DB 없이
 * ① 블록 건수가 판정에 쓴 블록과 같음 ② 행 집합·순서 ③ 계수 4행 규칙을 고정한다.
 *
 * <p>🔴 {@code ZoneRcmdtnShareCalculatorTest} 는 건드리지 않는다 — 판정 회귀는 그쪽이, {@code wbk} 는 여기가 진다.
 */
class ZoneRcmdtnShareWorkbookTest {

    private static final BigDecimal MONTHS = BigDecimal.valueOf(8);
    private static final Integer D = 30;
    private static final Integer HALF = 15;
    private static final ZoneRcmdtnShareCalculator.Crtr CRTR =
            new ZoneRcmdtnShareCalculator.Crtr(new BigDecimal("50.0000"), new BigDecimal("7.0000"),
                    new BigDecimal("0.5000"), new BigDecimal("100.0000"));

    // ── ① 블록 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PRIMARY — rcntCnt = 조회 기간 건수 · bfrCnt = 직전 기간 건수, 응답 증가율과 같은 블록")
    void primaryBlock() {
        List<ZoneRcmdtnShareBaseVO> rows = List.of(
                trend(base("제주시", "50110001", 896, 800), 400, 3, 5),
                trend(base("제주시", "50110002", 480, 100), 0, 7, 9));
        ZoneRcmdtnShareResultVO r = withWbk(rows, D, HALF, null, null);

        assertThat(r.getWbk().getTrendMode()).isEqualTo("PRIMARY");
        ZoneRcmdtnShareWorkbookRowVO a = row(r, "50110001");
        assertThat(a.getRcntCnt()).isEqualTo(896L);
        assertThat(a.getBfrCnt()).isEqualTo(400L);
        assertSameBlockAsJudge(r);
    }

    @Test
    @DisplayName("FALLBACK — 직전 기간 전 읍면동 0건 → rcntCnt = 반 분할 뒤 · bfrCnt = 반 분할 앞")
    void fallbackBlock() {
        List<ZoneRcmdtnShareBaseVO> rows = List.of(
                trend(base("제주시", "50110001", 896, 800), 0, 300, 500),
                trend(base("제주시", "50110002", 480, 100), 0, 12, 30));
        ZoneRcmdtnShareResultVO r = withWbk(rows, D, HALF, null, null);

        assertThat(r.getWbk().getTrendMode()).isEqualTo("FALLBACK");
        ZoneRcmdtnShareWorkbookRowVO a = row(r, "50110001");
        assertThat(a.getRcntCnt()).isEqualTo(500L);
        assertThat(a.getBfrCnt()).isEqualTo(300L);
        assertSameBlockAsJudge(r);
    }

    @Test
    @DisplayName("NONE — 기간 하한 미달이면 rcntCnt·bfrCnt 둘 다 null")
    void noneBlock() {
        List<ZoneRcmdtnShareBaseVO> rows = List.of(
                trend(base("제주시", "50110001", 896, 800), 400, 300, 500));
        ZoneRcmdtnShareResultVO r = withWbk(rows, null, null, null, null);

        assertThat(r.getWbk().getTrendMode()).isEqualTo("NONE");
        ZoneRcmdtnShareWorkbookRowVO a = row(r, "50110001");
        assertThat(a.getRcntCnt()).isNull();
        assertThat(a.getBfrCnt()).isNull();
        assertThat(r.getZones().get(0).getBfrCvlcptCnt()).isNull();
        assertThat(r.getZones().get(0).getCvlcptIncrsRt()).isNull();
    }

    @Test
    @DisplayName("wbk 를 붙여도 판정 결과는 같다 · 기존 calculate 는 wbk 가 null")
    void judgmentUnchanged() {
        List<ZoneRcmdtnShareBaseVO> rows = mixedRows();
        ZoneRcmdtnShareResultVO plain = ZoneRcmdtnShareCalculator.calculate(rows, MONTHS, CRTR, new ArrayList<>(),
                null, null, D, HALF, null, null);
        ZoneRcmdtnShareResultVO wbk = withWbk(rows, D, HALF, null, null);

        assertThat(plain.getWbk()).isNull();
        assertThat(wbk.getZones().toString()).isEqualTo(plain.getZones().toString());
        assertThat(wbk.getSmry().toString()).isEqualTo(plain.getSmry().toString());
        assertThat(wbk.getCrtrVls().toString()).isEqualTo(plain.getCrtrVls().toString());
        assertThat(wbk.getWarnings()).isEqualTo(plain.getWarnings());
    }

    // ── ② 행 집합 · 순서 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("rows = 응답 zones 와 같은 emdCd 집합(지역 필터 반영) · 민원↓ → emdCd↑ 순 · 원자료 그대로")
    void rowsMatchZonesAndOrder() {
        ZoneRcmdtnShareResultVO r = ZoneRcmdtnShareCalculator.calculateWithWorkbook(mixedRows(), MONTHS, CRTR,
                new ArrayList<>(), "서귀포시", null, D, HALF, null, null);
        ZoneRcmdtnShareWorkbookVO wbk = r.getWbk();

        Set<String> zoneCds = r.getZones().stream().map(ZoneRcmdtnShareZoneVO::getEmdCd).collect(Collectors.toSet());
        assertThat(wbk.getRows()).extracting(ZoneRcmdtnShareWorkbookRowVO::getEmdCd)
                .containsExactlyInAnyOrderElementsOf(zoneCds)
                .doesNotContain("50110001", "50110002");
        // 950(V) → 800(R) → 500(P1·P2 동점 — emdCd↑) → 399(Q) → 100(W)
        assertThat(wbk.getRows()).extracting(ZoneRcmdtnShareWorkbookRowVO::getEmdCd)
                .containsExactly("50130004", "50130003", "50130001", "50130006", "50130002", "50130005");

        ZoneRcmdtnShareWorkbookRowVO p = row(r, "50130001");
        assertThat(p.getSggNm()).isEqualTo("서귀포시");
        assertThat(p.getEmdNm()).isEqualTo("동50130001");
        assertThat(p.getCvlcptCnt()).isEqualTo(500L);
        assertThat(p.getPkltCnt()).isEqualTo(1L);
        assertThat(p.getPkltCpcty()).isEqualTo(600L);
        assertThat(p.getTopPntCvlcptCnt()).isEqualTo(40L);
        assertThat(p.getVaclotExclPkltCpcty()).isEqualTo(550L);

        assertThat(wbk.getMonths()).isEqualByComparingTo("8");
        assertThat(wbk.getFixed().getCoreMltp()).isEqualByComparingTo("2");
        assertThat(wbk.getFixed().getBndryRt()).isEqualByComparingTo("1.05");
        assertThat(wbk.getFixed().getMinTrendBase()).isEqualTo(10);
        assertThat(wbk.getVrbls()).isNull();   // 계수는 서비스가 붙인다
    }

    // ── ③ 계수 4행 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("APLCN_VL 키 집합 = RECOMMEND_SHARE 4행")
    void aplcnVlCoversScope() {
        assertThat(ZoneRcmdtnShareWorkbooks.APLCN_VL.keySet())
                .containsExactlyInAnyOrderElementsOf(AnlsVrblScope.RECOMMEND_SHARE.getVrblCds());
    }

    @Test
    @DisplayName("vrbls — 스코프 순서 4행 · aplcnVl = crtrVls 칸 · 이름·범위 = 설정 행 · intOnlyYn N · inptYn Y")
    void vrblsAllOn() {
        List<AnlsWorkbookVrblVO> vrbls = ZoneRcmdtnShareWorkbooks.vrbls(ovrdWithout(), crtrVls());

        assertThat(vrbls).extracting(AnlsWorkbookVrblVO::getVrblCd)
                .containsExactlyElementsOf(AnlsVrblScope.RECOMMEND_SHARE.getVrblCds());
        assertThat(vrbls).extracting(v -> v.getAplcnVl().toPlainString())
                .containsExactly("60", "7.5", "0.4", "120");
        assertThat(vrbls).allSatisfy(v -> {
            assertThat(v.getVrblNm()).isEqualTo(v.getVrblCd() + "_NM");
            assertThat(v.getMinVl().toPlainString()).isEqualTo("0");
            assertThat(v.getMaxVl().toPlainString()).isEqualTo("1000");
            assertThat(v.getIntOnlyYn()).isEqualTo("N");
            assertThat(v.getInptYn()).isEqualTo("Y");
        });
    }

    @Test
    @DisplayName("vrbls — 설정 행 없음(row null)이면 이름 vrblCd · 범위 null · inptYn N, 적용값은 그대로")
    void vrblsRowMissing() {
        List<AnlsWorkbookVrblVO> vrbls = ZoneRcmdtnShareWorkbooks.vrbls(
                ovrdWithout("CVLCPT_INCRS_WGVL"), crtrVls());

        AnlsWorkbookVrblVO off = vrbls.get(2);
        assertThat(off.getVrblCd()).isEqualTo("CVLCPT_INCRS_WGVL");
        assertThat(off.getVrblNm()).isEqualTo("CVLCPT_INCRS_WGVL");
        assertThat(off.getMinVl()).isNull();
        assertThat(off.getMaxVl()).isNull();
        assertThat(off.getInptYn()).isEqualTo("N");
        assertThat(off.getAplcnVl().toPlainString()).isEqualTo("0.4");
        assertThat(vrbls).filteredOn(v -> v != off).extracting(AnlsWorkbookVrblVO::getInptYn)
                .containsOnly("Y");
    }

    // ─────────────────────────────────────────────────────────────────────

    /** 응답 zones 의 이전 블록 건수·증가율이 wbk 블록 건수로 다시 계산한 값과 같다(판정과 같은 블록). */
    private static void assertSameBlockAsJudge(ZoneRcmdtnShareResultVO r) {
        for (ZoneRcmdtnShareZoneVO z : r.getZones()) {
            ZoneRcmdtnShareWorkbookRowVO w = row(r, z.getEmdCd());
            assertThat(w.getBfrCnt()).isEqualTo(z.getBfrCvlcptCnt());
            if (w.getBfrCnt() >= ZoneRcmdtnCalculator.MIN_TREND_BASE) {
                BigDecimal g = BigDecimal.valueOf(w.getRcntCnt() - w.getBfrCnt())
                        .divide(BigDecimal.valueOf(w.getBfrCnt()), 6, RoundingMode.HALF_UP);
                assertThat(z.getCvlcptIncrsRt())
                        .isEqualByComparingTo(g.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
            } else {
                assertThat(z.getCvlcptIncrsRt()).isNull();
            }
        }
    }

    private static ZoneRcmdtnShareResultVO withWbk(List<ZoneRcmdtnShareBaseVO> rows, Integer d, Integer half,
                                                   String sggNm, String emdCd) {
        return ZoneRcmdtnShareCalculator.calculateWithWorkbook(rows, MONTHS, CRTR, new ArrayList<>(),
                sggNm, emdCd, d, half, null, null);
    }

    private static ZoneRcmdtnShareWorkbookRowVO row(ZoneRcmdtnShareResultVO r, String emdCd) {
        return r.getWbk().getRows().stream().filter(w -> emdCd.equals(w.getEmdCd())).findFirst().orElseThrow();
    }

    private static ZoneRcmdtnShareResultVO.CrtrVls crtrVls() {
        ZoneRcmdtnShareResultVO.CrtrVls v = new ZoneRcmdtnShareResultVO.CrtrVls();
        v.setCvlcptMmCrtrVl(new BigDecimal("60"));
        v.setCvlcptRtCrtrVl(new BigDecimal("7.5000"));
        v.setIncrsWgvl(new BigDecimal("0.4"));
        v.setIncrsRtUplmtVl(new BigDecimal("120"));
        return v;
    }

    private static AnlsVrblOvrd ovrdWithout(String... offCds) {
        Set<String> off = new HashSet<>(List.of(offCds));
        List<AnalysisWeightVO> rows = new ArrayList<>();
        for (String cd : AnlsVrblScope.RECOMMEND_SHARE.getVrblCds()) {
            if (off.contains(cd)) {
                continue;
            }
            AnalysisWeightVO row = new AnalysisWeightVO();
            row.setAnlsTypeCd("RECOMMEND");
            row.setVrblCd(cd);
            row.setVrblNm(cd + "_NM");
            row.setMinVl(new BigDecimal("0.0000"));
            row.setMaxVl(new BigDecimal("1000.0000"));
            row.setUseYn("Y");
            rows.add(row);
        }
        return AnlsVrblOvrd.of(AnlsVrblScope.RECOMMEND_SHARE, rows, Map.of());
    }

    /** 두 시군구 · 동점(500건 2곳) · 판정 불가(면수 0) 포함 */
    private static List<ZoneRcmdtnShareBaseVO> mixedRows() {
        List<ZoneRcmdtnShareBaseVO> rows = new ArrayList<>(List.of(
                base("서귀포시", "50130005", 100, 0),
                base("서귀포시", "50130002", 399, 100),
                base("제주시", "50110002", 480, 100),
                base("서귀포시", "50130006", 500, 300),
                base("서귀포시", "50130001", 500, 600),
                base("서귀포시", "50130004", 950, 0),
                base("제주시", "50110001", 896, 800),
                base("서귀포시", "50130003", 800, 800)));
        for (ZoneRcmdtnShareBaseVO vo : rows) {
            trend(vo, vo.getCvlcptCnt().longValue() / 2, 20, 30);
        }
        ZoneRcmdtnShareBaseVO p = rows.get(4);
        p.setTopPntCvlcptCnt(BigDecimal.valueOf(40));
        p.setVaclotExclPkltCpcty(BigDecimal.valueOf(550));
        return rows;
    }

    private static ZoneRcmdtnShareBaseVO trend(ZoneRcmdtnShareBaseVO vo, long bfr, long halfBfr, long halfAftr) {
        vo.setBfrCvlcptCnt(BigDecimal.valueOf(bfr));
        vo.setHalfBfrCvlcptCnt(BigDecimal.valueOf(halfBfr));
        vo.setHalfAftrCvlcptCnt(BigDecimal.valueOf(halfAftr));
        return vo;
    }

    private static ZoneRcmdtnShareBaseVO base(String sggNm, String emdCd, long cvlcptCnt, long pkltCpcty) {
        ZoneRcmdtnShareBaseVO vo = new ZoneRcmdtnShareBaseVO();
        vo.setEmdCd(emdCd);
        vo.setSggNm(sggNm);
        vo.setEmdNm("동" + emdCd);
        vo.setCvlcptCnt(BigDecimal.valueOf(cvlcptCnt));
        vo.setTopPntCvlcptCnt(BigDecimal.ZERO);
        vo.setPkltCnt(BigDecimal.ONE);
        vo.setPkltCpcty(BigDecimal.valueOf(pkltCpcty));
        vo.setVaclotExclPkltCpcty(BigDecimal.valueOf(pkltCpcty));
        return vo;
    }
}
