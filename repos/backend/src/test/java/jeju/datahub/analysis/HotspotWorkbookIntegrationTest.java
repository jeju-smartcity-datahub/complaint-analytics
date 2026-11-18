package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.service.GradeCriterionVO;
import jeju.datahub.analysis.service.HotspotAnalysisRequest;
import jeju.datahub.analysis.service.HotspotAnalysisResultVO;
import jeju.datahub.analysis.service.HotspotAnalysisService;
import jeju.datahub.analysis.service.HotspotReportVO;
import jeju.datahub.analysis.service.HotspotScrCntVO;
import jeju.datahub.analysis.service.HotspotWorkbookVO;
import jeju.datahub.analysis.service.HotspotWorkbookZoneVO;
import jeju.datahub.analysis.service.HotspotZoneVO;
import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 집중구역 보고서의 <b>엑셀 재계산 데이터({@code wbk})</b> 통합 테스트 (TSK-4338 Step 3).
 *
 * <p>이 테스트가 엑셀 산식의 <b>정답지</b>다 — 응답 {@code wbk}(후보 구역 + 상수)를 계획서 3-2 의 엑셀 수식과
 * <b>같은 순서·같은 {@code double} 연산</b>으로 다시 계산해, 같은 조건의 {@code GET /api/analysis/hotspot}
 * 결과(표시 수·{@code snthsScr}·{@code grade}·순번·{@code zoneNm})와 맞는지 본다.
 * 🔴 {@code BigDecimal} 로 계산하지 않는다 — 엑셀의 double 오차를 검증하지 못한다(반올림 한 단계만 빌린다).
 *
 * <h2>구역 매칭</h2>
 * {@code wbk.zones} 에는 좌표가 없고 GET 응답에는 {@code cid} 가 없다. 그래서 <b>표시 순번 위치</b>로 짝짓는다 —
 * 정렬키(점수 desc · 민원 desc · cid asc) 중 cid 만 두 쿼리(DBSCAN 2회)에서 갈릴 수 있으므로,
 * 같은 (점수, 민원 건수) 묶음은 순서 무관 다중집합으로 비교하고 묶음 밖은 위치(= rnk)를 정확히 맞춘다.
 * ⚠️ 묶음 안에서는 "어느 wbk 구역이 어느 GET 구역인가" 를 1:1 로 확정하지 못한다 — 다중집합 비교가 그 한계다.
 *
 * <h2>정리</h2>
 * {@code HotspotReportIntegrationTest} 와 같다 — 응답으로 받은 {@code rptpId}·{@code excnId} 만 결과 → 보고서 → 실행
 * 순으로 지우고, 로그인 이력은 {@link IntegrationLoginSupport#cleanUp()} 이 {@code lgn_sn > startSn} 만 지운다.
 * 날짜는 박지 않는다 — 원천 보유 구간({@code selectDataRange} 와 같은 SQL)의 끝 기준으로 잡는다.
 *
 * <p>가중치 요청값(0~100)은 설정 {@code HOTSPOT COMPLAINT/ENFORCE} 의 {@code min_vl}~{@code max_vl}(0~100) 안이다 —
 * {@link #assumeWeightRangeCoversCases()} 가 먼저 확인한다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // HotspotReportIntegrationTest 와 같은 프로퍼티 — VWorld 호출 차단 · 컨텍스트 공유
        properties = "analysis.zone-name.vworld.api-key=")
class HotspotWorkbookIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HotspotWorkbookIntegrationTest.class);

    private static final String HOTSPOT_URL = "/api/analysis/hotspot";
    private static final String CREATE_URL = "/api/analysis/hotspot/reports";
    private static final String SNAPSHOT_URL = "/api/reports/{rptpId}/hotspot";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HotspotAnalysisService hotspotAnalysisService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Set<Long> createdRptpIds = new LinkedHashSet<>();
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    private String dataBgngYmd;
    private String dataEndYmd;

    /**
     * 두 원천(민원·단속)이 <b>모두</b> 있는 마지막 날 = LEAST(민원 max, 단속 max).
     * 가중치 대조 구간의 끝이다 — 보유 구간 끝(GREATEST)을 쓰면 한쪽 원천만 있는 달이 잡혀
     * 가중치 100 에서 표시 구역이 0 이 되거나 가중치가 결과를 바꾸지 못해 대조가 공허해진다(실측 2026-11-09).
     */
    private String bothEndYmd;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();
        session = auth.mockSession();

        String[] range = jdbcTemplate.queryForObject(
                "SELECT to_char(LEAST(   (SELECT min(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                        + "                     (SELECT min(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD'),"
                        + "       to_char(GREATEST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                        + "                     (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')",
                (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd).as("원천이 비어 있다 — 배치 적재분을 먼저 확인하라").matches("\\d{8}");
        assertThat(dataEndYmd).matches("\\d{8}");
        bothEndYmd = jdbcTemplate.queryForObject(
                "SELECT to_char(LEAST((SELECT max(ocrn_dt) FROM parking.tb_cvlcpt WHERE cvlcpt_type_cd <> '99'),"
                        + "                  (SELECT max(crdn_dt) FROM parking.tb_crdn_dsctn)), 'YYYYMMDD')",
                String.class);
        assertThat(bothEndYmd).matches("\\d{8}");
    }

    @AfterEach
    void tearDown() {
        List<RuntimeException> failures = new ArrayList<>();
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long rptpId : createdRptpIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_rptp WHERE rptp_id = ?", rptpId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdRptpIds.clear();
        createdExcnIds.clear();
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Object id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    // ───────────────────────────────────────────── 1. 가중치별 재계산 = 화면 조회

    @Test
    @DisplayName("0. 검증할 가중치(0~100)가 설정 min_vl~max_vl 안이다")
    void assumeWeightRangeCoversCases() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT vrbl_cd, min_vl, max_vl, use_yn FROM parking.tb_anls_vrbl_stng"
                        + " WHERE anls_type_cd = 'HOTSPOT' AND vrbl_cd IN ('COMPLAINT', 'ENFORCE')");
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat((BigDecimal) row.get("min_vl")).as("%s min_vl", row.get("vrbl_cd"))
                    .isLessThanOrEqualTo(BigDecimal.ZERO);
            assertThat((BigDecimal) row.get("max_vl")).as("%s max_vl", row.get("vrbl_cd"))
                    .isGreaterThanOrEqualTo(new BigDecimal("100"));
            assertThat(row.get("use_yn")).as("두 지표가 켜져 있어야 가중치 입력이 400 이 아니다").isEqualTo("Y");
        }
    }

    @Test
    @DisplayName("1. 정수 가중치 0·5·40·60·100 — wbk 재계산 결과 = GET 조회 결과 · N = analyze().scrCnt · scrCnts 101행")
    void integerWeightsRecomputeMatchesAnalysis() {
        for (String w : List.of("0", "5", "40", "60", "100")) {
            String crdn = new BigDecimal("100").subtract(new BigDecimal(w)).toPlainString();
            HotspotWorkbookVO wbk = verifyWeight(w, crdn);
            assertThat(wbk.getScrCnts()).as("w=%s scrCnts 행 수", w).hasSize(101);
        }
    }

    @Test
    @DisplayName("2. 비정수 가중치 62.5/37.5 — cvlcptWgvl 62.5 · scrCnts 102행(62.5 행 1개) · 재계산 = GET")
    void nonIntegerWeightRecomputeMatchesAnalysis() {
        HotspotWorkbookVO wbk = verifyWeight("62.5", "37.5");
        assertThat(wbk.getCvlcptWgvl()).isEqualByComparingTo("62.5");
        assertThat(wbk.getScrCnts()).hasSize(102);
        assertThat(wbk.getScrCnts().stream()
                .filter(r -> r.getCvlcptWgvl().compareTo(new BigDecimal("62.5")) == 0).count()).isEqualTo(1);
    }

    /**
     * 가중치 하나에 대해 GET(캐시 채우기) → XLSX 저장 → wbk 재계산 → GET 과 대조 → 서비스 analyze 로 N 대조.
     */
    private HotspotWorkbookVO verifyWeight(String cvlcptWgvl, String crdnWgvl) {
        Map<String, Object> cond = recentQuarter();
        cond.put("cvlcptWgvl", cvlcptWgvl);
        cond.put("crdnWgvl", crdnWgvl);

        // ① GET 먼저 — 구역명 캐시를 채운다(저장 쪽 세션 상한에 걸려 EMD 로 밀리지 않게)
        HotspotAnalysisResultVO shown = analyzeOk(cond);
        assertThat(shown.getZones()).as("w=%s 표시 구역이 있어야 대조가 의미 있다", cvlcptWgvl).isNotEmpty();

        // ② 저장
        Map<String, Object> body = new LinkedHashMap<>(cond);
        body.put("cvlcptWgvl", new BigDecimal(cvlcptWgvl));
        body.put("crdnWgvl", new BigDecimal(crdnWgvl));
        body.put("fileFmt", "XLSX");
        HotspotReportVO report = createOk(body);
        HotspotWorkbookVO wbk = report.getWbk();
        assertThat(wbk).as("w=%s wbk", cvlcptWgvl).isNotNull();
        assertThat(wbk.getCvlcptWgvl()).as("적용 민원 가중치").isEqualByComparingTo(cvlcptWgvl);
        assertThat(wbk.getZones().size()).as("후보 ≥ 표시").isGreaterThanOrEqualTo(shown.getZones().size());

        assertThat(wbk.getMaxCvlcptCnt()).as("대조 구간에 민원이 있어야 한다").isPositive();
        assertThat(wbk.getMaxCrdnCnt()).as("대조 구간에 단속이 있어야 한다").isPositive();

        // ③ 엑셀 식으로 재계산
        List<Recomputed> expected = recompute(wbk, shown.getGradeCriteria());

        // ④ GET 과 대조
        assertMatches(cvlcptWgvl, expected, shown.getZones());

        // ⑤ N 대조 — scrCnt 는 @JsonIgnore 라 서비스 직접 호출로만 읽힌다
        HotspotAnalysisRequest req = new HotspotAnalysisRequest();
        req.setBgngYmd((String) cond.get("bgngYmd"));
        req.setEndYmd((String) cond.get("endYmd"));
        req.setCvlcptWgvl(new BigDecimal(cvlcptWgvl));
        req.setCrdnWgvl(new BigDecimal(crdnWgvl));
        HotspotAnalysisResultVO direct = hotspotAnalysisService.analyze(req);
        assertThat(direct.getZones()).isNotEmpty();
        assertThat(direct.getZones().get(0).getScrCnt()).as("w=%s N = wbk.scrCnts 해당 행", cvlcptWgvl)
                .isEqualTo(populationOf(wbk));

        log.info("[TSK-4338] w={} {}~{} 후보 {} · 표시 {} · N {} — 재계산 일치",
                cvlcptWgvl, cond.get("bgngYmd"), cond.get("endYmd"), wbk.getZones().size(), shown.getZones().size(), populationOf(wbk));
        return wbk;
    }

    // ───────────────────────────────────────────── 2. 저장 경로 (Step 2 이관)

    @Test
    @DisplayName("3. PDF 저장도 wbk 를 싣는다 (설정 가중치 — 요청에 가중치 없음) · cvlcptWgvl = 응답 weights 의 민원 계수")
    void pdfReportAlsoCarriesWorkbook() {
        Map<String, Object> body = lastMonth();
        body.put("fileFmt", "PDF");
        HotspotReportVO report = createOk(body);
        assertThat(report.getWbk()).isNotNull();
        assertThat(report.getWbk().getZones()).isNotEmpty();
        BigDecimal stngCvlcpt = jdbcTemplate.queryForObject(
                "SELECT stng_vl FROM parking.tb_anls_vrbl_stng WHERE anls_type_cd = 'HOTSPOT' AND vrbl_cd = 'COMPLAINT'",
                BigDecimal.class);
        assertThat(report.getWbk().getCvlcptWgvl()).as("설정값 실행이면 설정 민원 가중치").isEqualByComparingTo(stngCvlcpt);
        assertThat(fileFmtOf(report.getRptpId())).isEqualTo("PDF");
    }

    @Test
    @DisplayName("4. fileFmt=XLS 는 400 · 아무것도 저장하지 않는다")
    void xlsIsRejected() {
        long before = countRptp();
        Map<String, Object> body = lastMonth();
        body.put("fileFmt", "XLS");
        ResponseEntity<String> res = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), String.class);
        if (res.getStatusCode().is2xxSuccessful()) {
            trackFromJson(res.getBody());
        }
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(countRptp()).isEqualTo(before);
    }

    @Test
    @DisplayName("5. XLSX 저장 → file_fmt='XLSX' · 스냅샷 wbk = 생성 응답 wbk · wbk 키를 지운 구 스냅샷은 wbk null")
    void xlsxSnapshotRoundTripAndLegacySnapshot() {
        Map<String, Object> body = lastMonth();
        body.put("fileFmt", "XLSX");
        HotspotReportVO created = createOk(body);
        assertThat(created.getFileFmt()).isEqualTo("XLSX");
        assertThat(fileFmtOf(created.getRptpId())).isEqualTo("XLSX");

        HotspotReportVO snapshot = snapshotOk(created.getRptpId());
        assertThat(snapshot.getWbk()).isNotNull();
        assertThat(canonical(snapshot.getWbk())).as("스냅샷 wbk = 생성 응답 wbk").isEqualTo(canonical(created.getWbk()));

        // 구 스냅샷 — 이 테스트가 만든 행의 wbk 키만 지운다
        int updated = jdbcTemplate.update(
                "UPDATE parking.tb_anls_rslt SET rslt_json = rslt_json - 'wbk' WHERE excn_id = ?",
                created.getExcnId());
        assertThat(updated).isEqualTo(1);
        HotspotReportVO legacy = snapshotOk(created.getRptpId());
        assertThat(legacy.getWbk()).as("wbk 없는 구 스냅샷").isNull();
        assertThat(legacy.getZones()).as("기존 zones 는 그대로").isNotEmpty();
    }

    @Test
    @DisplayName("6. 1년 구간 저장 — rslt_json 크기를 INFO 로 남긴다(단언 없음)")
    void oneYearSnapshotSizeIsLogged() {
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);
        LocalDate bgng = max(end.minusMonths(12).plusDays(1), LocalDate.parse(dataBgngYmd, YMD));
        Map<String, Object> body = period(bgng.format(YMD), dataEndYmd);
        body.put("fileFmt", "XLSX");
        long t0 = System.nanoTime();
        HotspotReportVO created = createOk(body);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Long len = jdbcTemplate.queryForObject(
                "SELECT length(rslt_json::text) FROM parking.tb_anls_rslt WHERE excn_id = ?",
                Long.class, created.getExcnId());
        log.info("[TSK-4338] 1년 구간 {}~{} 저장: rslt_json {} 자 · 표시 {} · 후보 {} · 소요 {} ms",
                bgng.format(YMD), dataEndYmd, len, created.getZones().size(),
                created.getWbk() == null ? null : created.getWbk().getZones().size(), ms);
    }

    // ───────────────────────────────────────────── 엑셀 재계산 (계획서 3-2)

    /** 재계산된 표시 구역 1건. */
    private record Recomputed(int cid, String zoneNm, int cvlcptCnt, int crdnCnt, double score, String grade) {
    }

    /**
     * 계획서 3-2 의 엑셀 수식을 같은 순서의 double 연산으로 옮긴다.
     * 민원비·단속비(최댓값 0 이면 0) → 가중합 → 종합점수 round2(round9(가중합/최대가중합×100))
     * → 점수순위(나보다 큰 수 + 1) → N → T_k = ceil(round6(N·p_k/100)) → 표시·등급 → 표시 순번.
     */
    private static List<Recomputed> recompute(HotspotWorkbookVO wbk, List<GradeCriterionVO> criteria) {
        double w = wbk.getCvlcptWgvl().doubleValue() / 100;   // 엑셀 B3
        int maxCv = wbk.getMaxCvlcptCnt();
        int maxCd = wbk.getMaxCrdnCnt();
        List<HotspotWorkbookZoneVO> zones = wbk.getZones();

        double[] sum = new double[zones.size()];
        double maxSum = 0;
        for (int i = 0; i < zones.size(); i++) {
            double a = maxCv == 0 ? 0 : zones.get(i).getCvlcptCnt() / (double) maxCv;
            double b = maxCd == 0 ? 0 : zones.get(i).getCrdnCnt() / (double) maxCd;
            sum[i] = w * a + (1 - w) * b;
            maxSum = Math.max(maxSum, sum[i]);
        }
        double[] score = new double[zones.size()];
        for (int i = 0; i < zones.size(); i++) {
            score[i] = round(round((maxSum == 0 ? 0 : sum[i] / maxSum) * 100, 9), 2);
        }

        long n = populationOf(wbk);
        long t1 = cutoff(n, pctOf(criteria, "SEVERE"));
        long t2 = cutoff(n, pctOf(criteria, "WARN"));
        long t3 = cutoff(n, pctOf(criteria, "CAUTION"));

        List<Recomputed> shown = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            int rank = 1;
            for (double other : score) {
                if (other > score[i]) {
                    rank++;
                }
            }
            if (!(score[i] > 0 && rank <= t3)) {
                continue;
            }
            String grade = rank <= t1 ? "SEVERE" : rank <= t2 ? "WARN" : "CAUTION";
            HotspotWorkbookZoneVO z = zones.get(i);
            shown.add(new Recomputed(z.getCid(), z.getZoneNm(), z.getCvlcptCnt(), z.getCrdnCnt(), score[i], grade));
        }
        shown.sort(Comparator.comparingDouble(Recomputed::score).reversed()
                .thenComparing(Comparator.comparingInt(Recomputed::cvlcptCnt).reversed())
                .thenComparingInt(Recomputed::cid));
        return shown;
    }

    /** 엑셀 ROUND — 0 에서 먼 쪽(HALF_UP). 반올림 한 단계만 BigDecimal 을 빌린다(연산은 double). */
    private static double round(double x, int digits) {
        return BigDecimal.valueOf(x).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    /** 엑셀 {@code ROUNDUP(ROUND(N*p/100, 6), 0)} */
    private static long cutoff(long n, int pct) {
        return (long) Math.ceil(round(n * (double) pct / 100, 6));
    }

    private static int pctOf(List<GradeCriterionVO> criteria, String grade) {
        return criteria.stream().filter(c -> grade.equals(c.getGrade())).findFirst()
                .orElseThrow(() -> new AssertionError("gradeCriteria 에 " + grade + " 가 없다: " + criteria))
                .getMaxRankPct();
    }

    /** 모집단 N = scrCnts 중 적용 가중치 행. 정확히 1행이어야 한다(엑셀 MATCH). */
    private static long populationOf(HotspotWorkbookVO wbk) {
        List<HotspotScrCntVO> rows = wbk.getScrCnts().stream()
                .filter(r -> r.getCvlcptWgvl().compareTo(wbk.getCvlcptWgvl()) == 0).toList();
        assertThat(rows).as("scrCnts 에 적용 가중치 %s 행이 1개", wbk.getCvlcptWgvl()).hasSize(1);
        return rows.get(0).getScrCnt();
    }

    /**
     * 재계산 결과와 GET 결과를 대조한다.
     * 위치마다 (점수, 민원 건수) 정렬키는 정확히 같아야 하고(= 묶음 밖 rnk 정확 일치), 같은 정렬키 묶음 안에서는
     * (단속 건수 · 등급)과 구역명을 순서 무관 다중집합으로 비교한다. 구역명은 GET 이 EMD 폴백이 아닌 구역만.
     */
    private static void assertMatches(String w, List<Recomputed> expected, List<HotspotZoneVO> actual) {
        assertThat(expected).as("w=%s 표시 수", w).hasSize(actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertThat(actual.get(i).getRnk()).as("w=%s GET rnk 는 1..N 연속", w).isEqualTo(i + 1);
        }
        int i = 0;
        while (i < actual.size()) {
            HotspotZoneVO head = actual.get(i);
            int j = i;
            while (j < actual.size()
                    && actual.get(j).getSnthsScr().compareTo(head.getSnthsScr()) == 0
                    && actual.get(j).getCvlcptCnt() == head.getCvlcptCnt()) {
                j++;
            }
            List<String> actKeys = new ArrayList<>();
            List<String> expKeys = new ArrayList<>();
            List<String> actNames = new ArrayList<>();
            List<String> expNames = new ArrayList<>();
            for (int k = i; k < j; k++) {
                HotspotZoneVO a = actual.get(k);
                Recomputed e = expected.get(k);
                String where = "w=" + w + " rnk=" + a.getRnk();
                assertThat(BigDecimal.valueOf(e.score())).as("%s 종합점수", where).isEqualByComparingTo(a.getSnthsScr());
                assertThat(e.cvlcptCnt()).as("%s 민원 건수", where).isEqualTo(a.getCvlcptCnt());
                actKeys.add(a.getCrdnCnt() + "|" + a.getGrade());
                expKeys.add(e.crdnCnt() + "|" + e.grade());
                if (!"EMD".equals(a.getZoneNmSrc())) {
                    actNames.add(a.getZoneNm());
                }
                expNames.add(e.zoneNm());
            }
            String where = "w=" + w + " rnk " + (i + 1) + ".." + j;
            if (j - i == 1) {
                assertThat(expKeys.get(0)).as("%s (단속|등급)", where).isEqualTo(actKeys.get(0));
            } else {
                assertThat(expKeys).as("%s 동점 묶음 (단속|등급)", where).containsExactlyInAnyOrderElementsOf(actKeys);
            }
            // 묶음 안 EMD 구역의 wbk 쪽 이름은 대응을 확정할 수 없어 '포함' 으로 본다
            assertThat(expNames).as("%s 구역명(EMD 제외)", where).containsAll(actNames);
            if (actNames.size() == j - i) {
                assertThat(expNames).as("%s 구역명", where).containsExactlyInAnyOrderElementsOf(actNames);
            }
            i = j;
        }
    }

    // ───────────────────────────────────────────── 헬퍼

    private Map<String, Object> lastMonth() {
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);
        LocalDate bgng = max(end.minusMonths(1).plusDays(1), LocalDate.parse(dataBgngYmd, YMD));
        return period(bgng.format(YMD), dataEndYmd);
    }

    /** 가중치 대조 구간 — 두 원천이 모두 있는 마지막 날 기준 3개월. */
    private Map<String, Object> recentQuarter() {
        LocalDate end = LocalDate.parse(bothEndYmd, YMD);
        LocalDate bgng = max(end.minusMonths(3).plusDays(1), LocalDate.parse(dataBgngYmd, YMD));
        return period(bgng.format(YMD), bothEndYmd);
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static Map<String, Object> period(String bgngYmd, String endYmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bgngYmd", bgngYmd);
        m.put("endYmd", endYmd);
        return m;
    }

    private HotspotAnalysisResultVO analyzeOk(Map<String, Object> params) {
        StringBuilder uri = new StringBuilder(HOTSPOT_URL);
        String sep = "?";
        for (String key : params.keySet()) {
            uri.append(sep).append(key).append("={").append(key).append('}');
            sep = "&";
        }
        ResponseEntity<HotspotAnalysisResultVO> res = restTemplate.exchange(uri.toString(), HttpMethod.GET,
                new HttpEntity<>(session), HotspotAnalysisResultVO.class, params);
        assertThat(res.getStatusCode().value()).as("GET %s", params).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private HotspotReportVO createOk(Map<String, Object> body) {
        ResponseEntity<HotspotReportVO> res = restTemplate.exchange(
                CREATE_URL, HttpMethod.POST, auth.jsonEntity(session, body), HotspotReportVO.class);
        HotspotReportVO vo = res.getBody();
        track(vo);
        assertThat(res.getStatusCode().value()).as("생성 201 (요청: %s)", body).isEqualTo(201);
        assertThat(vo).isNotNull();
        return vo;
    }

    private HotspotReportVO snapshotOk(Long rptpId) {
        ResponseEntity<HotspotReportVO> res = restTemplate.exchange(
                SNAPSHOT_URL, HttpMethod.GET, new HttpEntity<>(session), HotspotReportVO.class, rptpId);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();
        return res.getBody();
    }

    private void track(HotspotReportVO vo) {
        if (vo == null) {
            return;
        }
        if (vo.getRptpId() != null) {
            createdRptpIds.add(vo.getRptpId());
        }
        if (vo.getExcnId() != null) {
            createdExcnIds.add(vo.getExcnId());
        }
    }

    private void trackFromJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            HotspotReportVO vo = new HotspotReportVO();
            if (node.hasNonNull("rptpId")) {
                vo.setRptpId(node.get("rptpId").asLong());
            }
            if (node.hasNonNull("excnId")) {
                vo.setExcnId(node.get("excnId").asLong());
            }
            track(vo);
        } catch (JsonProcessingException e) {   // 4.3(TSK-4336) — readTree 가 던지는 파싱 예외만
            // 보고서 JSON 이 아니면 지울 것도 없다
            org.slf4j.LoggerFactory.getLogger(HotspotWorkbookIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
        }
    }

    private String fileFmtOf(Long rptpId) {
        return jdbcTemplate.queryForObject("SELECT file_fmt FROM parking.tb_rptp WHERE rptp_id = ?",
                String.class, rptpId);
    }

    private long countRptp() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_rptp", Long.class);
        return c == null ? 0 : c;
    }

    /** 숫자 표기(스케일) 차이를 걷은 wbk 비교용 문자열. */
    private static String canonical(HotspotWorkbookVO wbk) {
        StringBuilder sb = new StringBuilder();
        sb.append(wbk.getCvlcptWgvl().stripTrailingZeros().toPlainString())
                .append('|').append(wbk.getMaxCvlcptCnt()).append('|').append(wbk.getMaxCrdnCnt()).append('\n');
        for (HotspotScrCntVO r : wbk.getScrCnts()) {
            sb.append(r.getCvlcptWgvl().stripTrailingZeros().toPlainString()).append('=').append(r.getScrCnt()).append(';');
        }
        sb.append('\n');
        for (HotspotWorkbookZoneVO z : wbk.getZones()) {
            sb.append(z.getCid()).append('|').append(z.getZoneNm()).append('|').append(z.getEmdNm()).append('|')
                    .append(z.getCvlcptCnt()).append('|').append(z.getCrdnCnt()).append('\n');
        }
        return sb.toString();
    }
}
