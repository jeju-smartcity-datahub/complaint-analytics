package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.analysis.InvestPrrtTestData.Win;
import jeju.datahub.analysis.service.InvestPrrtLotBaseVO;
import jeju.datahub.analysis.service.InvestPrrtService;
import jeju.datahub.analysis.service.impl.InvestPrrtMapper;

/**
 * 투자우선순위 조회 API 통합 테스트.
 *
 * <p>쿼리 파라미터 바인딩 · 400 상태코드 · 응답 JSON 계약은 서블릿을 타야 보이므로 실제 포트로 부른다.
 * test 프로파일은 운영 필터체인을 끄고 permitAll 로 교대하므로 인증 없이 호출한다.
 *
 * <p>🔴 <b>날짜·건수·주차장 이름·점수를 박지 않는다.</b> 기간은 DB 의 주차 민원 최근 발생일에서 3개월을
 * 거슬러 잡고(검증용 창 — 기본 기간 12개월과 별개)({@link InvestPrrtTestData#recentWin()}), 기대값은 응답 안의 불변식·매퍼 원값·DB 실측으로
 * 만든다. 시드가 날짜만 옮겨 재적재돼도 깨지지 않는다.
 *
 * <p>이 API 는 <b>적재하지 않는다</b> — 정리할 행이 없어 {@code @AfterEach} 가 없다(그 사실 자체를
 * {@link #queryDoesNotRecordExecution()} 이 단언한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("투자우선순위 조회 API")
class InvestPrrtApiTest {

    private static final String URL = "/api/analysis/investment-priority";

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** 같은 창의 필터 없는 응답 — 전 지역 공간 조인이라 케이스마다 다시 부르지 않는다(조회는 부수효과가 없다) */
    private static final Map<String, JsonNode> FULL_MEMO = new ConcurrentHashMap<>();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InvestPrrtMapper mapper;

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private InvestPrrtTestData data;
    private Win win;
    private String bgngYmd;
    private String endYmd;

    @BeforeEach
    void resolveWindow() {
        data = new InvestPrrtTestData(jdbcTemplate);
        win = data.recentWin();
        assumeTrue(!data.targetIds().isEmpty(), "노상·공한지가 아닌 미소멸 주차장이 없다");
        assumeTrue(win != null, "좌표 있는 주차 민원(parking.tb_cvlcpt · 유형 01~05)이 없어 창을 잡을 수 없다");
        bgngYmd = win.bgng().format(InvestPrrtTestData.YMD);
        endYmd = win.end().format(InvestPrrtTestData.YMD);
    }

    // ───────────────────────── 200 · 구조 ─────────────────────────

    @Test
    @DisplayName("200 — 필터가 없으면 lots 길이 = smry.trgtCnt = 대상 주차장 수, 순위는 1..n 연속")
    void fullListCoversAllTargetsWithConsecutiveRanks() {
        JsonNode body = full();
        JsonNode lots = body.path("lots");

        assertThat(body.path("bgngYmd").asText()).isEqualTo(bgngYmd);
        assertThat(body.path("endYmd").asText()).isEqualTo(endYmd);
        assertThat(lots.size()).as("lots 길이 = smry.trgtCnt").isEqualTo(body.path("smry").path("trgtCnt").asInt());
        assertThat(lots.size()).as("대상 = 노상·공한지가 아닌 미소멸 주차장(독립 SQL)").isEqualTo(data.targetIds().size());

        Set<Long> ids = new TreeSet<>();
        for (int i = 0; i < lots.size(); i++) {
            assertThat(lots.get(i).path("rnk").asInt()).as("순위는 1부터 연속이다 (index %d)", i).isEqualTo(i + 1);
            ids.add(lots.get(i).path("pkltId").asLong());
        }
        assertThat(ids).as("주차장 한 곳 = 한 행").containsExactlyElementsOf(data.targetIds());

        long high = 0;
        for (JsonNode l : lots) {
            if ("H".equals(l.path("prrtLvlCd").asText())) {
                high++;
            }
        }
        assertThat(body.path("smry").path("highCnt").asLong()).as("smry.highCnt = 등급 H 수").isEqualTo(high);
        assertThat(body.path("scoreFormula").asText()).isNotBlank();
        assertThat(body.path("crtrVls").path("altmntRngVl").decimalValue()).isPositive();
        assertThat(body.path("crtrVls").path("indctPkltCnt").asInt()).isPositive();
    }

    @Test
    @DisplayName("정렬 — snthsScr 내림차순, 동점이면 pkltId 오름차순 · 점수는 0~100")
    void lotsAreOrderedByScoreThenPkltId() {
        JsonNode lots = full().path("lots");
        for (int i = 0; i < lots.size(); i++) {
            BigDecimal scr = lots.get(i).path("snthsScr").decimalValue();
            assertThat(scr).as("종합점수 범위 (index %d)", i).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
            if (i == 0) {
                continue;
            }
            BigDecimal prev = lots.get(i - 1).path("snthsScr").decimalValue();
            int c = prev.compareTo(scr);
            assertThat(c).as("snthsScr 내림차순 (index %d)", i).isGreaterThanOrEqualTo(0);
            if (c == 0) {
                assertThat(lots.get(i - 1).path("pkltId").asLong())
                        .as("동점이면 pkltId 오름차순 (index %d)", i).isLessThan(lots.get(i).path("pkltId").asLong());
            }
        }
    }

    @Test
    @DisplayName("가중치 — 반영한 변수만 싣고 합이 100 이다(산식 밖 변수가 켜져 있으면 100 미만 + 알림)")
    void weightsSumToHundred() {
        JsonNode body = full();
        JsonNode weights = body.path("weights");
        assertThat(weights.size()).isPositive();

        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode w : weights) {
            // INVEST 단속(ENFORCE) 설정 행이 지워지고 사고(ACCIDENT)가 산식에 들어왔다
            assertThat(w.path("vrblCd").asText()).isIn("SAFETY", "COMPLAINT", "USAGE", "ACCIDENT");
            assertThat(w.path("vrblSeCd").asText()).isEqualTo("WGVL");
            assertThat(w.path("stngVl").decimalValue()).as("가중치 0 은 싣지 않는다").isPositive();
            sum = sum.add(w.path("stngVl").decimalValue());
        }
        if (warningCodes(body).contains("WGVL_NOT_APPLIED")) {
            assertThat(sum).as("산식 밖 변수가 켜져 있으면 반영 합이 100 미만").isLessThan(BigDecimal.valueOf(100));
        } else {
            assertThat(sum).as("가중치 합").isEqualByComparingTo(BigDecimal.valueOf(100));
        }
    }

    @Test
    @DisplayName("등급 — gradeCriteria 는 H·M·L(이름은 공통코드) 이고, 각 주차장의 등급이 그 임계와 일치한다")
    void gradesMatchCriteria() {
        JsonNode body = full();
        JsonNode gc = body.path("gradeCriteria");
        assertThat(gc.size()).isEqualTo(3);
        assertThat(List.of(gc.get(0).path("prrtLvlCd").asText(), gc.get(1).path("prrtLvlCd").asText(),
                gc.get(2).path("prrtLvlCd").asText())).containsExactly("H", "M", "L");

        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT cd_vl, cd_nm FROM parking.tb_com_cd"
                + " WHERE group_cd = 'IVST_PRRT_LVL_CD' AND use_yn = 'Y'", rs -> {
            names.put(rs.getString(1), rs.getString(2));
        });
        for (JsonNode g : gc) {
            String cd = g.path("prrtLvlCd").asText();
            assertThat(g.path("prrtLvlNm").asText()).as("등급명(%s)", cd).isEqualTo(names.getOrDefault(cd, cd));
        }

        BigDecimal up = gc.get(0).path("minScr").decimalValue();
        BigDecimal md = gc.get(1).path("minScr").decimalValue();
        assertThat(md).as("중간 임계 < 높음 임계").isLessThan(up);
        assertThat(gc.get(2).path("minScr").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(gc.get(0).path("minScr").asText()).as("임계 표기는 꼬리 0·지수 없이").doesNotContain("E");

        for (JsonNode l : body.path("lots")) {
            BigDecimal scr = l.path("snthsScr").decimalValue();
            String expected = scr.compareTo(up) >= 0 ? "H" : scr.compareTo(md) >= 0 ? "M" : "L";
            assertThat(l.path("prrtLvlCd").asText())
                    .as("pkltId %s 의 등급 (점수 %s)", l.path("pkltId").asLong(), scr).isEqualTo(expected);
        }
    }

    // ───────────────────────── 필터 ─────────────────────────

    @Test
    @DisplayName("필터 — sggNm·emdCd 를 걸어도 같은 주차장의 점수·순위가 바뀌지 않고, smry·warnings 는 전체 기준 그대로다")
    void regionFilterDoesNotChangeScoreOrRank() {
        JsonNode full = full();
        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        JsonNode sample = null;
        for (JsonNode l : full.path("lots")) {
            byId.put(l.path("pkltId").asLong(), l);
            if (sample == null && l.hasNonNull("sggNm") && l.hasNonNull("emdCd")) {
                sample = l;
            }
        }
        assumeTrue(sample != null, "시군구·읍면동이 채워진 대상 주차장이 없다");
        String sggNm = sample.path("sggNm").asText();
        String emdCd = sample.path("emdCd").asText();

        JsonNode bySgg = getOk(URL + "?bgngYmd={b}&endYmd={e}&sggNm={s}", bgngYmd, endYmd, sggNm);
        JsonNode byEmd = getOk(URL + "?bgngYmd={b}&endYmd={e}&emdCd={c}", bgngYmd, endYmd, emdCd);
        JsonNode byBoth = getOk(URL + "?bgngYmd={b}&endYmd={e}&sggNm={s}&emdCd={c}", bgngYmd, endYmd, sggNm, emdCd);

        assertFiltered(full, byId, bySgg, "sggNm", sggNm);
        assertFiltered(full, byId, byEmd, "emdCd", emdCd);
        assertFiltered(full, byId, byBoth, "emdCd", emdCd);
        assertThat(bySgg.path("lots").size()).as("시군구 필터는 읍면동 필터보다 좁지 않다")
                .isGreaterThanOrEqualTo(byEmd.path("lots").size());

        // 없는 지역이면 빈 목록 + 200 — smry 는 그대로 전체 기준
        JsonNode none = getOk(URL + "?bgngYmd={b}&endYmd={e}&emdCd={c}", bgngYmd, endYmd, "00000000");
        assertThat(none.path("lots").size()).isZero();
        assertThat(none.path("smry")).isEqualTo(full.path("smry"));
    }

    private void assertFiltered(JsonNode full, Map<Long, JsonNode> byId, JsonNode filtered, String key, String value) {
        long expected = 0;
        for (JsonNode l : full.path("lots")) {
            if (value.equals(l.path(key).asText(null))) {
                expected++;
            }
        }
        JsonNode lots = filtered.path("lots");
        assertThat((long) lots.size()).as("%s=%s 필터 통과 수", key, value).isEqualTo(expected).isPositive();

        int prevRnk = 0;
        for (JsonNode l : lots) {
            assertThat(l.path(key).asText()).isEqualTo(value);
            // 🔴 행 전체가 필터 없는 응답의 같은 주차장과 같다 — 점수·순위·시군구 안 등수·단속 제외 순위 모두
            assertThat(l).as("pkltId %s (%s=%s)", l.path("pkltId").asLong(), key, value)
                    .isEqualTo(byId.get(l.path("pkltId").asLong()));
            assertThat(l.path("rnk").asInt()).as("전체 순위 오름차순 유지").isGreaterThan(prevRnk);
            prevRnk = l.path("rnk").asInt();
        }
        assertThat(filtered.path("smry")).as("smry 는 필터와 무관하게 전체 기준").isEqualTo(full.path("smry"));
        assertThat(filtered.path("warnings")).as("warnings 는 필터 전 전체 기준").isEqualTo(full.path("warnings"));
        assertThat(filtered.path("weights")).isEqualTo(full.path("weights"));
    }

    // ───────────────────────── 원값 · null · 알림 ─────────────────────────

    @Test
    @DisplayName("원값 — 같은 창·같은 반경으로 매퍼를 직접 부른 값과 응답이 주차장마다 일치하고, null 은 null 로 내린다")
    void rawValuesMatchMapper() {
        JsonNode body = full();
        BigDecimal rdsM = body.path("crtrVls").path("altmntRngVl").decimalValue();
        List<InvestPrrtLotBaseVO> bases = mapper.selectLotBase(InvestPrrtTestData.queryParam(win, rdsM));

        Map<Long, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode l : body.path("lots")) {
            byId.put(l.path("pkltId").asLong(), l);
        }
        assertThat(byId).hasSameSizeAs(bases);

        for (InvestPrrtLotBaseVO b : bases) {
            JsonNode l = byId.get(b.getPkltId());
            assertThat(l).as("pkltId %s 가 응답에 있다", b.getPkltId()).isNotNull();
            String at = "pkltId " + b.getPkltId();

            assertThat(l.path("pkltNm").asText(null)).as(at).isEqualTo(b.getPkltNm());
            assertThat(l.path("sggNm").asText(null)).as(at).isEqualTo(b.getSggNm());
            assertThat(l.path("emdCd").asText(null)).as(at).isEqualTo(b.getEmdCd());
            assertThat(l.path("emdNm").asText(null)).as(at).isEqualTo(b.getEmdNm());
            assertThat(l.path("cvlcptCnt").asLong()).as(at + " 민원").isEqualTo(b.getCvlcptCnt());
            assertThat(l.path("crdnCnt").asLong()).as(at + " 단속").isEqualTo(b.getCrdnCnt());
            assertThat(l.path("lat").decimalValue()).as(at).isEqualByComparingTo(b.getLat());
            assertThat(l.path("lot").decimalValue()).as(at).isEqualByComparingTo(b.getLot());

            // 🔴 null 유지 — 키는 있고 값이 null 이다(0·빈 문자열로 바꾸지 않는다)
            assertThat(l.has("dayAvgTrnvrRt")).as(at + " 키 유지").isTrue();
            if (b.getDayAvgTrnvrRt() == null) {
                assertThat(l.path("dayAvgTrnvrRt").isNull()).as(at + " 회전율 null 유지").isTrue();
            } else {
                assertThat(l.path("dayAvgTrnvrRt").decimalValue()).as(at + " 회전율")
                        .isEqualByComparingTo(b.getDayAvgTrnvrRt());
            }
            assertThat(l.has("sftyChckRsltCd")).as(at + " 키 유지").isTrue();
            assertThat(l.path("sftyChckRsltCd").asText(null)).as(at + " 안전 코드").isEqualTo(b.getSftyChckRsltCd());
            assertThat(l.path("sftyChckYmd").asText(null)).as(at + " 점검일").isEqualTo(b.getSftyChckYmd());
            assertThat(l.has("sggRnk")).isTrue();
            assertThat(l.path("sggRnk").isNull()).as(at + " 시군구가 없으면 sggRnk 도 null")
                    .isEqualTo(b.getSggNm() == null);
            assertThat(l.has("crdnExclRnk")).isTrue();
            assertThat(l.has("instlYm")).isTrue();
        }
    }

    @Test
    @DisplayName("알림 — 전체 대상에서 판정한다: 사고 자료 없음 ↔ acdntCnt null, 점검 없는 곳 수, 회전율 없는 곳 수, 단속의 시 치우침")
    void warningsReflectData() {
        JsonNode body = full();
        Set<String> codes = warningCodes(body);
        for (JsonNode w : body.path("warnings")) {
            assertThat(w.path("code").asText()).isNotBlank();
            assertThat(w.path("message").asText()).isNotBlank();
        }

        Long acdntRows = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_pklt_acdnt_hstry", Long.class);
        boolean noAcdnt = acdntRows == null || acdntRows == 0L;
        assertThat(codes.contains("ACDNT_NO_DATA")).as("사고이력 0행 ↔ ACDNT_NO_DATA").isEqualTo(noAcdnt);
        assertThat(body.path("smry").has("acdntCnt")).isTrue();
        assertThat(body.path("smry").path("acdntCnt").isNull()).as("자료가 없으면 acdntCnt 는 0 이 아니라 null")
                .isEqualTo(noAcdnt);

        int sftyMissing = 0;
        int utztnMissing = 0;
        Set<String> sggAll = new TreeSet<>();
        Set<String> sggWithCrdn = new TreeSet<>();
        for (JsonNode l : body.path("lots")) {
            if (l.path("sftyChckRsltCd").isNull()) {
                sftyMissing++;
            }
            if (!l.hasNonNull("dayAvgTrnvrRt")) {
                utztnMissing++;
            }
            if (l.hasNonNull("sggNm")) {
                sggAll.add(l.path("sggNm").asText());
                if (l.path("crdnCnt").asLong() > 0) {
                    sggWithCrdn.add(l.path("sggNm").asText());
                }
            }
        }
        assertThat(codes.contains("SFTY_CHCK_MISSING")).isEqualTo(sftyMissing > 0);
        if (sftyMissing > 0) {
            assertThat(warning(body, "SFTY_CHCK_MISSING").path("cnt").asInt()).isEqualTo(sftyMissing);
        }
        // 이용패턴 없는 대상도 남아 이용 0점이 된다. 경고 건수 = 회전율 null 인 lots 수
        assertThat(codes.contains("UTZTN_MISSING")).as("회전율 null 인 대상이 있을 때만 UTZTN_MISSING")
                .isEqualTo(utztnMissing > 0);
        if (utztnMissing > 0) {
            assertThat(warning(body, "UTZTN_MISSING").path("cnt").asInt())
                    .as("UTZTN_MISSING.cnt = lots 중 dayAvgTrnvrRt == null 인 수").isEqualTo(utztnMissing);
        }
        // 🔴 단속이 실제 반영됐을 때만(응답 weights 에 ENFORCE 가 있을 때만) 낸다
        boolean enforceApplied = false;
        for (JsonNode w : body.path("weights")) {
            enforceApplied |= "ENFORCE".equals(w.path("vrblCd").asText());
        }
        assertThat(codes.contains("CRDN_SGG_SKEW")).as("단속이 반영됐고, 단속 있는 대상이 한 시에만 있을 때만")
                .isEqualTo(enforceApplied && sggAll.size() > 1 && sggWithCrdn.size() == 1);
    }

    // ───────────────────────── 기본 기간 ─────────────────────────

    @Test
    @DisplayName("기본 기간 — 파라미터가 없으면 200 이고 bgngYmd·endYmd 가 '오늘에서 12개월 전 ~ 오늘' 로 채워진다 · 이용률 집계 달·안전점검 기준일")
    void defaultPeriodIsRecentOneYear() {
        LocalDate before = LocalDate.now(ZoneId.of("Asia/Seoul"));
        JsonNode body = getOk(URL);
        LocalDate after = LocalDate.now(ZoneId.of("Asia/Seoul"));

        assertThat(body.path("bgngYmd").asText()).matches("\\d{8}");
        assertThat(body.path("endYmd").asText()).matches("\\d{8}");
        LocalDate bgng = LocalDate.parse(body.path("bgngYmd").asText(), InvestPrrtTestData.YMD);
        LocalDate end = LocalDate.parse(body.path("endYmd").asText(), InvestPrrtTestData.YMD);

        assertThat(end).as("종료일 = 오늘(호출이 자정을 넘겼으면 그 전후)").isBetween(before, after);
        assertThat(bgng).as("시작일 = 종료일에서 %d개월 전", InvestPrrtService.DEFAULT_PRD_MONTHS)
                .isEqualTo(end.minusMonths(InvestPrrtService.DEFAULT_PRD_MONTHS));
        assertThat(body.path("lots").size()).isEqualTo(body.path("smry").path("trgtCnt").asInt());

        // = A — 시작일이 1일이 아니면 시작 달 제외 → 끝 달 포함 12개 달. 오늘이 1일이면 시작 달도 들어가 13개 달(허용한 예외)
        int back = end.getDayOfMonth() == 1 ? InvestPrrtService.DEFAULT_PRD_MONTHS : InvestPrrtService.DEFAULT_PRD_MONTHS - 1;
        assertThat(body.path("utztnBgngYm").asText()).as("이용률 집계 시작 달 = 종료 달 − %d", back)
                .isEqualTo(YearMonth.from(end).minusMonths(back).format(YM));
        assertThat(body.path("utztnEndYm").asText()).as("이용률 집계 끝 달 = 종료 달").isEqualTo(YearMonth.from(end).format(YM));
        assertThat(body.path("sftyCrtrYmd").asText()).as("안전점검 기준일 = 종료일").isEqualTo(body.path("endYmd").asText());
    }

    @Test
    @DisplayName("이용률 집계 달 — 명시 창은 독립 규칙(1일이면 그 달·아니면 다음 달)과 같고, 한 달 안 창이면 끝 달로 clamp 한다")
    void utztnMonthsFollowStartDayRule() {
        JsonNode body = full();
        assertThat(body.path("utztnBgngYm").asText()).as("이용률 집계 시작 달 (창 %s)", win).isEqualTo(win.bgngYm());
        assertThat(body.path("utztnEndYm").asText()).isEqualTo(win.endYm());
        assertThat(body.path("sftyCrtrYmd").asText()).isEqualTo(endYmd);

        // 같은 달 안(5일 ~ 20일) — 시작 달 제외 규칙만 쓰면 다음 달이 되어 이용률이 통째로 빈다 → 끝 달 하나로 clamp
        YearMonth ym = YearMonth.from(win.end()).minusMonths(1);
        JsonNode clamp = getOk(URL + "?bgngYmd={b}&endYmd={e}",
                ym.atDay(5).format(InvestPrrtTestData.YMD), ym.atDay(20).format(InvestPrrtTestData.YMD));
        assertThat(clamp.path("utztnBgngYm").asText()).as("clamp — 시작 달 = 끝 달").isEqualTo(ym.format(YM));
        assertThat(clamp.path("utztnEndYm").asText()).isEqualTo(ym.format(YM));
    }

    @Test
    @DisplayName("최근 자료일 4칸 — 독립 SQL(종료일 이하 전체의 최댓값)과 같고, 날짜는 endYmd 이하 · 이용률 달은 utztnEndYm 이하")
    void dataLastDatesMatchIndependentSql() {
        JsonNode body = full();
        InvestPrrtTestData.DataLast exp = data.dataLast(win);

        assertThat(textOrNull(body, "cvlcptLastYmd")).as("민원 최근 자료일").isEqualTo(exp.cvlcptLastYmd());
        assertThat(textOrNull(body, "utztnLastYm")).as("이용률 최근 자료 달").isEqualTo(exp.utztnLastYm());
        assertThat(textOrNull(body, "sftyChckLastYmd")).as("안전점검 최근 자료일").isEqualTo(exp.sftyChckLastYmd());
        assertThat(textOrNull(body, "acdntLastYmd")).as("사고 최근 자료일").isEqualTo(exp.acdntLastYmd());

        String end = body.path("endYmd").asText();
        for (String f : List.of("cvlcptLastYmd", "sftyChckLastYmd", "acdntLastYmd")) {
            String v = textOrNull(body, f);
            if (v != null) {
                assertThat(v).as("%s 는 YYYYMMDD 이고 endYmd 이하", f).matches("\\d{8}").isLessThanOrEqualTo(end);
            }
        }
        String utz = textOrNull(body, "utztnLastYm");
        if (utz != null) {
            assertThat(utz).as("utztnLastYm 은 YYYYMM 이고 utztnEndYm 이하").matches("\\d{6}")
                    .isLessThanOrEqualTo(body.path("utztnEndYm").asText());
        }
        // 창은 주차 민원 최근 발생일에서 잡았다 — 민원 칸은 비지 않고 종료일 그 자체다
        assertThat(exp.cvlcptLastYmd()).as("recentWin 의 종료일 = 주차 민원 최근 발생일").isEqualTo(endYmd);
    }

    // ───────────────────────── 400 ─────────────────────────

    @Test
    @DisplayName("400 — 날짜가 한쪽만 오면 400")
    void onlyOneDateIs400() {
        assertThat(status(URL + "?bgngYmd={b}", bgngYmd)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(URL + "?endYmd={e}", endYmd)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(URL + "?bgngYmd={b}&endYmd=", bgngYmd)).as("빈 값도 생략이다").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("400 — 형식이 틀리거나 실재하지 않는 날짜면 400")
    void malformedDateIs400() {
        String ym = bgngYmd.substring(0, 6);
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", "2026-01-01", endYmd)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, "abcdefgh")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", ym, endYmd)).as("연월만").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", ym + "99", endYmd)).as("없는 날짜")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("400 — 종료일이 시작일보다 빠르면 400")
    void reversedPeriodIs400() {
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", endYmd, bgngYmd)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("400 — 걸친 달이 36개월을 넘으면 400")
    void tooLongPeriodIs400() {
        // 종료 달을 포함해 37개월: 시작 달 = 종료 달 − 36
        String tooEarly = win.end().withDayOfMonth(1).minusMonths(InvestPrrtService.MAX_PRD_MONTHS)
                .format(InvestPrrtTestData.YMD);
        assertThat(status(URL + "?bgngYmd={b}&endYmd={e}", tooEarly, endYmd)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ───────────────────────── 적재하지 않는다 ─────────────────────────

    @Test
    @DisplayName("적재 — 조회는 tb_anls_excn · tb_anls_rslt 를 늘리지 않는다")
    void queryDoesNotRecordExecution() {
        long excnBefore = count("parking.tb_anls_excn");
        long rsltBefore = count("parking.tb_anls_rslt");

        getOk(URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd);
        assertThat(status(URL + "?bgngYmd={b}", bgngYmd)).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(count("parking.tb_anls_excn")).as("실행 이력 행 수 불변").isEqualTo(excnBefore);
        assertThat(count("parking.tb_anls_rslt")).as("분석 결과 행 수 불변").isEqualTo(rsltBefore);
    }

    // ───────────────────────── 일시 입력 ─────────────────────────

    @Test
    @DisplayName("입력 없음 — vrblAjmt 는 ajmtYn='N' 이고 설정 8행을 모두 담는다(적용값 = 설정값) · 값으로 비교한다(60.00 = 60)")
    void noInputKeepsSettingValues() {
        JsonNode body = full();
        assertAjmt(body, "N", Map.of());

        // 표기만 다른 설정값은 조정이 아니고 결과도 같다
        BigDecimal up = stng("UP_GRD_CRTR_SCR");
        JsonNode sameValue = adjusted(Map.of("UP_GRD_CRTR_SCR", up.setScale(2, RoundingMode.UNNECESSARY)));
        assertThat(sameValue.path("vrblAjmt").path("ajmtYn").asText()).isEqualTo("N");
        assertThat(sameValue.path("lots")).isEqualTo(body.path("lots"));
    }

    @Test
    @DisplayName("입력 — weights·gradeCriteria·crtrVls·점수·배정 건수가 입력값으로 계산되고 설정 표는 그대로다")
    void inputIsAppliedToCalculation() {
        Map<String, BigDecimal> input = data.adjustedVrblVls(BigDecimal.TEN);
        assumeTrue(input != null, "±10 을 줄 수 있는 INVEST 가중치 두 개가 없다");
        List<Map<String, Object>> stngBefore = data.investStngPrint();

        JsonNode body = adjusted(input);

        // 가중치 — 입력 그대로(합계 100)
        Map<String, BigDecimal> applied = new LinkedHashMap<>();
        for (JsonNode w : body.path("weights")) {
            applied.put(w.path("vrblCd").asText(), w.path("stngVl").decimalValue());
            assertThat(w.path("stngVl").decimalValue()).as(w.path("vrblCd").asText())
                    .isEqualByComparingTo(input.get(w.path("vrblCd").asText()));
            assertThat(body.path("scoreFormula").asText())
                    .contains(w.path("stngVl").decimalValue().stripTrailingZeros().toPlainString() + "%");
        }
        assertThat(applied).isNotEmpty();

        // 등급 기준·계수
        BigDecimal up = stng("UP_GRD_CRTR_SCR");
        BigDecimal md = input.get("MD_GRD_CRTR_SCR");
        JsonNode gc = body.path("gradeCriteria");
        assertThat(gc.get(0).path("minScr").decimalValue()).isEqualByComparingTo(up);
        assertThat(gc.get(1).path("minScr").decimalValue()).isEqualByComparingTo(md);
        assertThat(body.path("crtrVls").path("altmntRngVl").decimalValue())
                .isEqualByComparingTo(input.get("ALTMNT_RNG_VL"));
        // INDCT_PKLT_CNT 는 숨김이라 입력에 없다 → 설정값 그대로
        assertThat(body.path("crtrVls").path("indctPkltCnt").asInt()).isEqualTo(stng("INDCT_PKLT_CNT").intValue());

        // 배정 건수는 입력 반경으로 — 같은 반경으로 매퍼를 직접 부른 값과 같다
        Map<Long, InvestPrrtLotBaseVO> bases = new LinkedHashMap<>();
        for (InvestPrrtLotBaseVO b : mapper.selectLotBase(InvestPrrtTestData.queryParam(win, input.get("ALTMNT_RNG_VL")))) {
            bases.put(b.getPkltId(), b);
        }
        assertThat(body.path("lots").size()).isEqualTo(bases.size()).isPositive();
        for (JsonNode l : body.path("lots")) {
            String at = "pkltId " + l.path("pkltId").asLong();
            assertThat(l.path("cvlcptCnt").asLong()).as(at + " 민원").isEqualTo(bases.get(l.path("pkltId").asLong()).getCvlcptCnt());

            // 종합점수 = 입력 가중치로 지표 점수를 평균한 값 (지표 점수가 표시 자릿수로 반올림돼 근사 비교)
            BigDecimal expected = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> w : applied.entrySet()) {
                expected = expected.add(w.getValue().multiply(indicator(l, w.getKey())));
            }
            expected = expected.divide(BigDecimal.valueOf(100), 9, RoundingMode.HALF_UP);
            BigDecimal scr = l.path("snthsScr").decimalValue();
            assertThat(scr).as(at + " 종합점수").isCloseTo(expected, within(new BigDecimal("0.001")));
            String grade = scr.compareTo(up) >= 0 ? "H" : scr.compareTo(md) >= 0 ? "M" : "L";
            assertThat(l.path("prrtLvlCd").asText()).as(at + " 등급").isEqualTo(grade);
        }

        assertAjmt(body, "Y", input);
        assertThat(data.investStngPrint()).as("설정 표 불변").isEqualTo(stngBefore);
    }

    @Test
    @DisplayName("표시 주차장 수(INDCT_PKLT_CNT · 숨김)를 입력하면 400 이다 · 설정과 같은 값이어도 400")
    void indctPkltCntInputIsRejectedAsHidden() {
        BigDecimal indct = stng("INDCT_PKLT_CNT");
        BigDecimal other = indct.add(BigDecimal.ONE);

        assertThat(adjustedStatus("{\"INDCT_PKLT_CNT\":" + other.toPlainString() + "}"))
                .as("숨김 코드는 일시 조정 대상이 아니다").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{\"INDCT_PKLT_CNT\":" + indct.toPlainString() + "}"))
                .as("설정값과 같아도 입력 자체가 400").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(full().path("crtrVls").path("indctPkltCnt").asInt()).as("입력 없으면 설정값")
                .isEqualTo(indct.intValue());
    }

    @Test
    @DisplayName("0 인 가중치 — 응답 weights 에서 빠지지만 vrblAjmt 에는 적용값 0 으로 남는다")
    void zeroWeightStaysInAjmtOnly() {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (Map<String, Object> row : data.investRows()) {
            if ("WGVL".equals(row.get("vrbl_se_cd"))) {
                weights.put((String) row.get("vrbl_cd"), (BigDecimal) row.get("stng_vl"));
            }
        }
        List<String> cds = new ArrayList<>(weights.keySet());
        assumeTrue(cds.size() >= 2, "가중치 행이 두 개 미만이다");
        String zero = cds.get(0);
        String keep = cds.get(1);
        weights.put(keep, weights.get(keep).add(weights.get(zero)));
        weights.put(zero, BigDecimal.ZERO);

        JsonNode body = adjusted(weights);

        List<String> shown = new ArrayList<>();
        body.path("weights").forEach(w -> shown.add(w.path("vrblCd").asText()));
        assertThat(shown).doesNotContain(zero).contains(keep);
        assertThat(ajmtRow(body, zero).path("aplcnVl").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("입력 400 — 중간 ≥ 상위 · 합계 99 · 일부만 보내 합계가 깨짐 · 목록 밖 코드 · 범위 밖 · null · 형식 오류")
    void invalidInputIs400() {
        BigDecimal up = stng("UP_GRD_CRTR_SCR");
        assertThat(adjustedStatus("{\"MD_GRD_CRTR_SCR\":" + up.toPlainString() + "}")).as("중간 = 상위")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        StringBuilder sum99 = new StringBuilder("{");
        BigDecimal remain = BigDecimal.valueOf(99);
        List<String> cds = new ArrayList<>();
        for (Map<String, Object> row : data.investRows()) {
            if ("WGVL".equals(row.get("vrbl_se_cd"))) {
                cds.add((String) row.get("vrbl_cd"));
            }
        }
        assumeTrue(!cds.isEmpty(), "가중치 행이 없다");
        for (int i = 0; i < cds.size() - 1; i++) {
            sum99.append('"').append(cds.get(i)).append("\":0,");
        }
        sum99.append('"').append(cds.get(cds.size() - 1)).append("\":").append(remain).append('}');
        assertThat(adjustedStatus(sum99.toString())).as("합계 99").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(adjustedStatus("{\"" + cds.get(0) + "\":" + stng(cds.get(0)).add(BigDecimal.ONE).toPlainString() + "}"))
                .as("한 행만 바꿔 합계가 설정값 위에서 깨짐").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{\"GRD_BNDRY1\":3}")).as("타 화면 변수").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{\"ENFORCE\":10}")).as("목록 밖 가중치").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{\"ALTMNT_RNG_VL\":99999999}")).as("범위 밖").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{\"ALTMNT_RNG_VL\":null}")).as("null 값").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adjustedStatus("{not-json")).as("형식 오류").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** 적용 내역 단언 — 🔴 기대값은 DB 현재 설정값이다. {@code input} 에 없는 행은 적용값 = 설정값 */
    private void assertAjmt(JsonNode body, String ajmtYn, Map<String, BigDecimal> input) {
        JsonNode ajmt = body.path("vrblAjmt");
        assertThat(ajmt.path("ajmtYn").asText()).isEqualTo(ajmtYn);
        List<Map<String, Object>> rows = data.investRows();
        assertThat(ajmt.path("vrbls").size()).as("사용 중인 설정 행 전체").isEqualTo(rows.size());
        for (Map<String, Object> row : rows) {
            String cd = (String) row.get("vrbl_cd");
            BigDecimal stngVl = (BigDecimal) row.get("stng_vl");
            JsonNode actual = ajmtRow(body, cd);
            assertThat(actual.path("stngVl").decimalValue()).as(cd + " stngVl").isEqualByComparingTo(stngVl);
            assertThat(actual.path("aplcnVl").decimalValue()).as(cd + " aplcnVl")
                    .isEqualByComparingTo(input.getOrDefault(cd, stngVl));
            assertThat(actual.has("vrblNm")).isTrue();
            assertThat(actual.has("unitNm")).isTrue();
        }
    }

    private static JsonNode ajmtRow(JsonNode body, String vrblCd) {
        for (JsonNode v : body.path("vrblAjmt").path("vrbls")) {
            if (vrblCd.equals(v.path("vrblCd").asText())) {
                return v;
            }
        }
        throw new AssertionError("vrblAjmt 에 " + vrblCd + " 가 없다");
    }

    private static BigDecimal indicator(JsonNode lot, String vrblCd) {
        String field = switch (vrblCd) {
            case "SAFETY" -> "sftyRiskScr";
            case "COMPLAINT" -> "cvlcptFrqScr";
            case "USAGE" -> "utztnPttnScr";
            case "ACCIDENT" -> "acdntScr";
            case "ENFORCE" -> "crdnScr";
            default -> throw new AssertionError("모르는 지표: " + vrblCd);
        };
        JsonNode node = lot.path(field);
        return node.isNull() || node.isMissingNode() ? BigDecimal.ZERO : node.decimalValue();
    }

    private BigDecimal stng(String vrblCd) {
        return jdbcTemplate.queryForObject("SELECT stng_vl FROM parking.tb_anls_vrbl_stng"
                + " WHERE anls_type_cd = 'INVEST' AND vrbl_cd = ?", BigDecimal.class, vrblCd);
    }

    private JsonNode adjusted(Map<String, BigDecimal> input) {
        try {
            return getOk(URL + "?bgngYmd={b}&endYmd={e}&vrblVls={v}", bgngYmd, endYmd,
                    objectMapper.writeValueAsString(input));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpStatus adjustedStatus(String vrblVls) {
        return status(URL + "?bgngYmd={b}&endYmd={e}&vrblVls={v}", bgngYmd, endYmd, vrblVls);
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    /** 창을 명시한 필터 없는 응답(창마다 한 번만 부른다) */
    private JsonNode full() {
        return FULL_MEMO.computeIfAbsent(bgngYmd + "~" + endYmd,
                k -> getOk(URL + "?bgngYmd={b}&endYmd={e}", bgngYmd, endYmd));
    }

    private long count(String table) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }

    private HttpStatus status(String url, Object... vars) {
        return HttpStatus.valueOf(restTemplate.getForEntity(url, String.class, vars).getStatusCode().value());
    }

    private JsonNode getOk(String url, Object... vars) {
        ResponseEntity<String> res = restTemplate.getForEntity(url, String.class, vars);
        assertThat(res.getStatusCode()).as("GET %s %s → %s", url, List.of(vars), res.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패: " + url, e);
        }
    }

    /** 값이 없거나 JSON null 이면 {@code null} */
    private static String textOrNull(JsonNode body, String field) {
        JsonNode n = body.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static Set<String> warningCodes(JsonNode body) {
        Set<String> codes = new TreeSet<>();
        for (JsonNode w : body.path("warnings")) {
            codes.add(w.path("code").asText());
        }
        return codes;
    }

    private static JsonNode warning(JsonNode body, String code) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode w : body.path("warnings")) {
            if (code.equals(w.path("code").asText())) {
                found.add(w);
            }
        }
        assertThat(found).as("알림 %s 는 한 건", code).hasSize(1);
        return found.get(0);
    }
}
