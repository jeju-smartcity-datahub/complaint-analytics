package jeju.datahub.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 구역추천(공유주차제 적용) API 통합 테스트
 * ({@code GET /api/analysis/zone-recommendation/share}).
 *
 * <h2>왜 {@code RANDOM_PORT} + {@link TestRestTemplate} 인가</h2>
 * 🔴 이 레포엔 <b>MockMvc 가 0건</b>이다 — 쿼리 파라미터 바인딩·400 상태코드·응답 JSON 계약은 실제 포트로
 * 부른다({@code ZoneRcmdtnApiTest} 선례). test 프로파일은 {@code app.auth.security-enabled=false} 라
 * permitAll 체인이 걸려 세션 쿠키 없이도 {@code GET} 이 통과한다.
 *
 * <h2>🔴 정리는 "내가 만든 id" 만 지운다 — 범위로 지우지 않는다</h2>
 * ⚠️ {@code RANDOM_PORT} 에서는 {@code @Transactional} 롤백이 먹지 않는다(요청이 별 스레드다). 이 API 는
 * 호출 1회마다 {@code TB_ANLS_EXCN}·{@code TB_ANLS_RSLT} 에 1행씩 남기므로, 200 응답의 {@code excnId} 를
 * {@link #createdExcnIds} 에 모아 {@link #deleteOnlyWhatThisTestCreated()} 가 <b>그 id 만</b> 결과 → 실행
 * 순(FK 방향)으로 지운다({@code HotspotReportIntegrationTest} 선례).
 * 🔴 {@code excn_id > startId} 같은 범위 DELETE 는 같은 시각의 수동 실행·다른 테스트가 만든 행까지 지운다.
 * 400 으로 끊긴 호출은 행을 만들지 않으므로 지울 것이 없다.
 *
 * <h2>🔴 날짜·건수·기준값을 상수로 박지 않는다 (데이터 독립성)</h2>
 * 민원 적재분은 배치·시더가 소유한다 — 재적재 한 번이면 구간이 통째로 옮겨간다. 그래서
 * <ul>
 *   <li><b>조회 기간</b>은 {@link #resolveDataRange()} 가 매퍼 {@code selectDataRange} 와 <b>같은 조건</b>
 *       (유형 99 제외 · {@code emd_cd} 있음)으로 DB 에서 읽는다.</li>
 *   <li><b>기준값</b>은 같은 응답의 {@code crtrVls} 에서 읽는다(50·7 을 적지 않는다).</li>
 *   <li><b>읍면동 수</b>는 {@code tb_admdst} 행 수와 비교한다(74 를 적지 않는다).</li>
 *   <li><b>필터 값</b>(시군구명·읍면동코드)은 필터 없는 응답에서 뽑는다.</li>
 * </ul>
 * 고정 구간의 실측값(후보 18 · 핵심 6 …)은 별도 curl 검증이 맡는다 — 여기서는 불변식만 본다.
 *
 * <p>응답 바디의 {@code message} 문자열은 단언하지 않는다(devtools 유무로 갈린다) — 상태코드만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("구역추천 공유주차제 API · 실행이력·결과 적재")
class ZoneRcmdtnShareIntegrationTest {

    private static final String URL = "/api/analysis/zone-recommendation/share";

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 실재하지 않는 읍면동코드 — {@code tb_admdst} 에 없음을 테스트가 SQL 로 먼저 확인한다 */
    private static final String EMD_CD_ABSENT = "99999999";

    /** 실재하지 않는 시군구명(40자 이내) — {@code tb_admdst} 에 없음을 테스트가 SQL 로 먼저 확인한다 */
    private static final String SGG_NM_ABSENT = "없는시군구";

    /**
     * 불변식 허용 오차 — 응답 반올림 폭의 절반이다({@code cvlcptMmAvg} 소수 1자리 · {@code cvlcptRt} 소수
     * 2자리, HALF_UP). 판정은 반올림 전 값으로 하므로, 설정이 응답 자릿수보다 잘게 바뀌어 있으면
     * 반올림된 응답값이 기준보다 작게 보일 수 있다.
     */
    private static final BigDecimal MM_AVG_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal RT_TOLERANCE = new BigDecimal("0.005");

    /** 검토 지수({@code rvwScr})의 후보·핵심 후보 문턱 — 🔴 설정값이 아니라 지수의 정의다(기준의 1배·2배) */
    private static final BigDecimal SCR_CAND = BigDecimal.ONE;
    private static final BigDecimal SCR_CORE = new BigDecimal("2");

    /** 설정 변수 코드 · 경고 코드 — 값이 아니라 이름이다 */
    private static final String VRBL_INCRS_WGVL = "CVLCPT_INCRS_WGVL";
    private static final String VRBL_INCRS_RT_UPLMT = "CVLCPT_INCRS_RT_UPLMT_VL";
    private static final String WARN_TREND_BFR_FALLBACK = "TREND_BFR_FALLBACK";
    private static final String WARN_TREND_WINDOW_SHORT = "TREND_WINDOW_SHORT";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private static final Set<String> GRADES = Set.of("CORE", "CAND", "CVLCPT_ONLY", "NONE", "UNABLE");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 자료 구간 — DB 에서 읽는다. 이 구간을 그대로 조회 기간으로 쓴다 */
    private String dataBgngYmd;
    private String dataEndYmd;

    /**
     * 🔴 이 테스트가 만든 실행 아이디. {@link #deleteOnlyWhatThisTestCreated()} 가 <b>이것만</b> 지운다.
     * JUnit 은 테스트 메서드마다 인스턴스를 새로 만들므로 케이스 간에 섞이지 않는다.
     */
    private final Set<Long> createdExcnIds = new LinkedHashSet<>();

    /** 🔴 가중치를 바꾼 테스트가 되돌릴 원래 값(테스트 시작 때 읽은 값). 바꾸지 않았으면 null */
    private BigDecimal wgvlToRestore;

    /**
     * 🔴 조회 실행자({@code excutr_id})를 세션에서 정하므로 익명 GET 은 401 이다.
     * mock-login 세션({@link IntegrationLoginSupport#MOCK_ID})으로 부른다.
     */
    private IntegrationLoginSupport auth;
    private HttpHeaders session;

    @BeforeEach
    void resolveDataRange() {
        auth = IntegrationLoginSupport.forTestChain(restTemplate, jdbcTemplate);
        auth.markStart();   // 🔴 빠지면 cleanUp() 이 로그인 이력 전량을 지운다
        session = auth.mockSession();

        // 🔴 매퍼 selectDataRange 와 같은 조건이다 — 다르면 "자료가 있다는 구간인데 조회하면 0건" 이 된다
        String[] range = jdbcTemplate.queryForObject("""
                SELECT to_char(min(ocrn_dt), 'YYYYMMDD'), to_char(max(ocrn_dt), 'YYYYMMDD')
                  FROM parking.tb_cvlcpt
                 WHERE cvlcpt_type_cd <> '99' AND emd_cd IS NOT NULL
                """, (rs, rowNum) -> new String[] { rs.getString(1), rs.getString(2) });
        assertThat(range).isNotNull();
        dataBgngYmd = range[0];
        dataEndYmd = range[1];
        assertThat(dataBgngYmd)
                .as("주차 민원(parking.tb_cvlcpt · 유형 99 제외 · emd_cd 있음)이 비어 있다 — "
                        + "시드·배치 적재분을 먼저 확인하라")
                .isNotNull()
                .matches("\\d{8}");
        assertThat(dataEndYmd).isNotNull().matches("\\d{8}");
    }

    @AfterEach
    void deleteOnlyWhatThisTestCreated() {
        // 한 문장이 터져도 나머지를 마저 지운다 — 범위 DELETE 로 쓸어 담을 수 없으므로 남기면 손으로 지워야 한다.
        List<RuntimeException> failures = new ArrayList<>();
        // FK(tb_anls_rslt.excn_id → tb_anls_excn.excn_id) 때문에 결과를 먼저 지운다.
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_rslt WHERE excn_id = ?", excnId);
        }
        for (Long excnId : createdExcnIds) {
            deleteQuietly(failures, "DELETE FROM parking.tb_anls_excn WHERE excn_id = ?", excnId);
        }
        createdExcnIds.clear();
        if (wgvlToRestore != null) {
            try {
                writeIncrsWgvl(wgvlToRestore);
            } catch (RuntimeException e) {
                failures.add(e);
            }
            wgvlToRestore = null;
        }
        // 🔴 failures 단언 앞 — 단언이 실패해도 mock-user·로그인 이력은 되돌린다
        auth.cleanUp();
        assertThat(failures).as("테스트가 만든 행 정리에 실패했다 — 남은 행을 손으로 지워야 한다").isEmpty();
    }

    // ───────────────────────── 등급 합 ─────────────────────────

    @Test
    @DisplayName("등급 합 — zones 는 tb_admdst 전 행이고 등급별 개수의 합이 전체다 (smry 와도 맞는다)")
    void zonesCoverEveryEmdAndGradesAddUp() {
        JsonNode body = getOk(null, null);

        Integer admdstCnt = jdbcTemplate.queryForObject("SELECT count(*) FROM parking.tb_admdst", Integer.class);
        JsonNode zones = body.path("zones");
        assertThat(zones.size()).as("🔴 후보만이 아니라 읍면동 전부다").isEqualTo(admdstCnt);

        Map<String, Integer> byGrade = new LinkedHashMap<>();
        int rvwTrgt = 0;
        for (JsonNode z : zones) {
            String grade = z.path("rvwGrdCd").asText();
            assertThat(GRADES).as("알 수 없는 등급: %s", z).contains(grade);
            byGrade.merge(grade, 1, Integer::sum);
            boolean cand = "CORE".equals(grade) || "CAND".equals(grade);
            assertThat(z.path("rvwTrgtYn").asText()).as("rvwTrgtYn ↔ 등급: %s", z).isEqualTo(cand ? "Y" : "N");
            if (cand) {
                rvwTrgt++;
            }
        }
        assertThat(byGrade.values().stream().mapToInt(Integer::intValue).sum())
                .as("등급별 개수 합 = 전체: %s", byGrade).isEqualTo(admdstCnt);

        JsonNode smry = body.path("smry");
        assertThat(smry.path("emdCnt").asInt()).isEqualTo(admdstCnt);
        assertThat(smry.path("rvwTrgtCnt").asInt()).isEqualTo(rvwTrgt);
        assertThat(smry.path("coreCnt").asInt()).isEqualTo(byGrade.getOrDefault("CORE", 0));
        assertThat(smry.path("cvlcptOnlyCnt").asInt()).isEqualTo(byGrade.getOrDefault("CVLCPT_ONLY", 0));
        assertThat(smry.path("unableCnt").asInt()).isEqualTo(byGrade.getOrDefault("UNABLE", 0));

        // 자료 구간 안내값 — DB 에서 읽은 구간과 같다
        assertThat(body.path("dataBgngYmd").asText()).isEqualTo(dataBgngYmd);
        assertThat(body.path("dataEndYmd").asText()).isEqualTo(dataEndYmd);
    }

    // ───────────────────────── 불변식 ─────────────────────────

    @Test
    @DisplayName("불변식 — 후보(rvwTrgtYn=Y)는 모두 월평균·비율이 같은 응답의 crtrVls 이상이다 (반올림 폭 절반 허용)")
    void everyCandidateMeetsBothCriteriaOfSameResponse() {
        JsonNode body = getOk(null, null);

        // 🔴 기준은 상수가 아니라 같은 응답에서 읽는다 — 설정이 바뀌어도 성립한다
        JsonNode crtr = body.path("crtrVls");
        assertThat(crtr.hasNonNull("cvlcptMmCrtrVl")).as("crtrVls: %s", crtr).isTrue();
        assertThat(crtr.hasNonNull("cvlcptRtCrtrVl")).isTrue();
        BigDecimal mmMin = crtr.path("cvlcptMmCrtrVl").decimalValue().subtract(MM_AVG_TOLERANCE);
        BigDecimal rtMin = crtr.path("cvlcptRtCrtrVl").decimalValue().subtract(RT_TOLERANCE);

        int candCnt = 0;
        for (JsonNode z : body.path("zones")) {
            if (!"Y".equals(z.path("rvwTrgtYn").asText())) {
                continue;
            }
            candCnt++;
            assertThat(z.hasNonNull("cvlcptRt")).as("후보는 면수가 있어 비율이 null 이 아니다: %s", z).isTrue();
            assertThat(z.path("cvlcptMmAvg").decimalValue())
                    .as("월평균 민원 ≥ 기준 − 0.05: %s", z).isGreaterThanOrEqualTo(mmMin);
            assertThat(z.path("cvlcptRt").decimalValue())
                    .as("100면당 월평균 민원 ≥ 기준 − 0.005: %s", z).isGreaterThanOrEqualTo(rtMin);
        }
        assertHasCandidate(candCnt, body);
    }

    // ───────────────────────── 순번 ─────────────────────────

    @Test
    @DisplayName("순번 — rnk 는 판정 불가를 뺀 전 읍면동에 1..n 연속이고 후보 여부와 무관하게 "
            + "incrsRfltRvwScr 이 비증가다 (rvwScr null ⇔ UNABLE ⇔ rnk null · incrsRfltRvwScr null ⇔ rvwScr null · "
            + "판정 불가는 맨 뒤 · 후보 먼저 삭제)")
    void rnkIsContiguousOverEveryScoredEmdAndFollowsTrendScoreWithinCandGroup() {
        JsonNode body = getOk(null, null);
        assertRvwScrComputable(body);
        JsonNode zones = body.path("zones");

        int expected = 1;
        int candCnt = 0;
        boolean unableSeen = false;
        for (JsonNode z : zones) {
            String grade = z.path("rvwGrdCd").asText();
            boolean unable = "UNABLE".equals(grade);
            // 🔴 세 표현이 같은 사실이다 — 면수 0 이면 지수도 순번도 없다
            assertThat(z.path("rvwScr").isNull()).as("rvwScr null ⇔ UNABLE: %s", z).isEqualTo(unable);
            assertThat(z.path("incrsRfltRvwScr").isNull()).as("incrsRfltRvwScr null ⇔ rvwScr null: %s", z)
                    .isEqualTo(z.path("rvwScr").isNull());
            assertThat(z.path("rnk").isNull()).as("rnk null ⇔ UNABLE: %s", z).isEqualTo(unable);
            if (unable) {
                unableSeen = true;
                continue;
            }
            assertThat(unableSeen).as("판정 불가는 목록의 맨 뒤다 — 그 뒤에 순번 있는 행이 나왔다: %s", z).isFalse();
            assertThat(z.path("rnk").asInt()).as("rnk 가 1 부터 빠짐없이 이어진다: %s", z).isEqualTo(expected++);

            boolean cand = "CORE".equals(grade) || "CAND".equals(grade);
            // "후보가 기준 미달보다 앞" 은 더 이상 성립하지 않는다(순서는 우선도 하나다)
            if (cand) {
                candCnt++;
            }
            // "CORE 가 CAND 보다 앞" 은 더 이상 성립하지 않는다(핵심이 추세 때문에 후보 뒤에 설 수 있다)
        }
        assertThat(expected - 1).as("순번이 매겨진 행 수 = 전체 − 판정 불가")
                .isEqualTo(zones.size() - body.path("smry").path("unableCnt").asInt());
        // 후보 여부와 무관하게 전 구간 추세 반영 지수(우선도) 비증가
        assertTrendOrder(zones);
        assertHasCandidate(candCnt, body);
    }

    // ───────────────────────── 검토 지수 ─────────────────────────

    @Test
    @DisplayName("검토 지수 — 전 행에서 rvwScr ≥ 1 ⇔ rvwTrgtYn=Y · rvwScr ≥ 2 ⇔ CORE 이고, "
            + "응답의 월평균·비율·crtrVls 로 다시 계산한 값과 허용 오차 안이다")
    void rvwScrAgreesWithGradeAndRecomputes() {
        JsonNode body = getOk(null, null);
        assertRvwScrComputable(body);

        // 🔴 기준은 상수가 아니라 같은 응답에서 읽는다
        BigDecimal mmCrtr = body.path("crtrVls").path("cvlcptMmCrtrVl").decimalValue();
        BigDecimal rtCrtr = body.path("crtrVls").path("cvlcptRtCrtrVl").decimalValue();

        // 허용 오차의 산술 근거 — 응답값으로 다시 계산하면 두 군데서 어긋난다.
        //   ① rvwScr 자체가 소수 2자리 "버림" 이다                         → 참값보다 0 이상 0.01 미만 작다
        //   ② 입력이 반올림돼 있다: cvlcptMmAvg ±0.05, cvlcptRt ±0.005     → 몫이 ±0.05/c1, ±0.005/c2 흔들린다
        // 그래서 |응답 − 재계산| < 0.01 + max(0.05/c1, 0.005/c2) 다(기본 기준 50·7 이면 0.011).
        // 기준이 0 인 쪽은 지수의 항에서 빠지므로 오차에도 넣지 않는다.
        BigDecimal inputErr = BigDecimal.ZERO;
        if (mmCrtr.signum() > 0) {
            inputErr = inputErr.max(MM_AVG_TOLERANCE.divide(mmCrtr, 6, RoundingMode.UP));
        }
        if (rtCrtr.signum() > 0) {
            inputErr = inputErr.max(RT_TOLERANCE.divide(rtCrtr, 6, RoundingMode.UP));
        }
        BigDecimal tolerance = new BigDecimal("0.01").add(inputErr);

        int scored = 0;
        int candCnt = 0;
        for (JsonNode z : body.path("zones")) {
            if (z.path("rvwScr").isNull()) {
                continue;
            }
            scored++;
            BigDecimal scr = z.path("rvwScr").decimalValue();
            String grade = z.path("rvwGrdCd").asText();

            // 🔴 지수와 등급은 같은 사실의 두 표현이다 — 버림이라 허용 오차 없이 정확히 맞는다
            assertThat(scr.compareTo(SCR_CAND) >= 0)
                    .as("rvwScr ≥ 1 ⇔ rvwTrgtYn=Y: %s", z).isEqualTo("Y".equals(z.path("rvwTrgtYn").asText()));
            assertThat(scr.compareTo(SCR_CORE) >= 0)
                    .as("rvwScr ≥ 2 ⇔ CORE: %s", z).isEqualTo("CORE".equals(grade));
            if (scr.compareTo(SCR_CAND) >= 0) {
                candCnt++;
            }

            BigDecimal recomputed = null;
            if (mmCrtr.signum() > 0) {
                recomputed = z.path("cvlcptMmAvg").decimalValue().divide(mmCrtr, 6, RoundingMode.HALF_UP);
            }
            if (rtCrtr.signum() > 0) {
                BigDecimal byRt = z.path("cvlcptRt").decimalValue().divide(rtCrtr, 6, RoundingMode.HALF_UP);
                recomputed = recomputed == null ? byRt : recomputed.min(byRt);
            }
            assertThat(scr.subtract(recomputed).abs())
                    .as("rvwScr(%s) ≈ min(월평균 ÷ %s, 비율 ÷ %s) = %s (허용 %s): %s",
                            scr, mmCrtr, rtCrtr, recomputed, tolerance, z)
                    .isLessThan(tolerance);
        }
        assertThat(scored).as("전제: 지수가 있는 읍면동이 1곳 이상이어야 단언이 돈다").isPositive();
        assertThat(candCnt).as("rvwScr ≥ 1 인 행 수 = smry.rvwTrgtCnt")
                .isEqualTo(body.path("smry").path("rvwTrgtCnt").asInt());
        assertHasCandidate(candCnt, body);
    }

    // ───────────────────────── 필터 ─────────────────────────

    @Test
    @DisplayName("필터 — 시군구마다 rnk 는 그 안에서 1..n 연속(같은 후보 여부 묶음 안에서 incrsRfltRvwScr 비증가 · "
            + "판정 불가는 null·맨 뒤)이고, "
            + "rvwGrdCd·rvwTrgtYn·rvwScr·cutnCds·상대 순서·smry 는 필터 없을 때와 같다")
    void sggNmFilterRenumbersButKeepsJudgement() {
        JsonNode all = getOk(null, null);
        assertRvwScrComputable(all);
        // 필터 값은 응답에서 뽑는다(시군구명을 적지 않는다). 한글이라 URI 변수로 인코딩돼 나간다.
        Set<String> sggNms = new LinkedHashSet<>();
        for (JsonNode z : all.path("zones")) {
            sggNms.add(z.path("sggNm").asText());
        }
        assertThat(sggNms).as("전제: 시군구가 2곳 이상이어야 필터가 실제로 줄인다").hasSizeGreaterThan(1);

        boolean renumbered = false;
        for (String sggNm : sggNms) {
            // 필터 없는 응답에서 그 시군구만 — 순서를 지킨 채 뽑는다
            List<JsonNode> expected = new ArrayList<>();
            for (JsonNode z : all.path("zones")) {
                if (sggNm.equals(z.path("sggNm").asText())) {
                    expected.add(z);
                }
            }
            JsonNode filtered = getOk(sggNm, null);
            JsonNode zones = filtered.path("zones");
            assertThat(zones.size()).as("'%s' 의 읍면동 전부", sggNm).isEqualTo(expected.size());

            int next = 1;
            boolean nullSeen = false;
            for (int i = 0; i < zones.size(); i++) {
                JsonNode z = zones.get(i);
                JsonNode before = expected.get(i);
                assertThat(z.path("sggNm").asText()).isEqualTo(sggNm);
                // 🔴 상대 순서는 필터 없는 응답에서의 상대 순서와 같다
                assertThat(z.path("emdCd").asText()).as("'%s' %d번째 행의 읍면동", sggNm, i)
                        .isEqualTo(before.path("emdCd").asText());
                // 🔴 바뀌는 것은 번호뿐이다 — 판정은 필터와 무관하다
                assertThat(z.path("rvwScr")).as("rvwScr: %s", z).isEqualTo(before.path("rvwScr"));
                assertThat(z.path("rvwGrdCd")).as("rvwGrdCd: %s", z).isEqualTo(before.path("rvwGrdCd"));
                assertThat(z.path("rvwTrgtYn")).as("rvwTrgtYn: %s", z).isEqualTo(before.path("rvwTrgtYn"));
                assertThat(z.path("cutnCds")).as("cutnCds: %s", z).isEqualTo(before.path("cutnCds"));

                // 🔴 순번은 필터 결과 안에서 1 부터 빠짐없이 이어진다
                assertThat(z.path("rnk").isNull()).as("rnk null ⇔ rvwScr null: %s", z)
                        .isEqualTo(z.path("rvwScr").isNull());
                if (z.path("rnk").isNull()) {
                    nullSeen = true;
                    continue;
                }
                assertThat(nullSeen).as("순번 없는 행은 맨 뒤다 — 그 뒤에 순번 있는 행이 나왔다: %s", z).isFalse();
                assertThat(z.path("rnk").asInt()).as("'%s' 안에서 1 부터 이어진다: %s", sggNm, z).isEqualTo(next++);
                if (z.path("rnk").asInt() != before.path("rnk").asInt()) {
                    renumbered = true;
                }
            }
            // 후보 여부와 무관하게 전 구간 추세 반영 지수(우선도) 비증가
            assertTrendOrder(zones);
            assertThat(filtered.path("smry")).as("🔴 smry 는 필터와 무관한 도 전체 값이다").isEqualTo(all.path("smry"));
        }
        // 헛통과 방지 — 도 전체 번호와 실제로 달라진 행이 있어야 "다시 매겼다" 를 본 것이다
        assertThat(renumbered)
                .as("전제: 지수가 있는 읍면동이 두 시군구 이상에 있어야 필터 후 번호가 도 전체 번호와 갈린다")
                .isTrue();
    }

    @Test
    @DisplayName("필터 — emdCd 를 걸면 그 1행의 rnk 가 1 이다(판정 불가면 null) · 등급·rvwScr 는 필터 없을 때와 같다")
    void emdCdFilterRestartsRnkAtOne() {
        JsonNode all = getOk(null, null);
        assertRvwScrComputable(all);
        JsonNode zones = all.path("zones");
        assertThat(zones.size()).as("전제: 읍면동이 2곳 이상").isGreaterThan(1);

        // 도 전체에서 순번이 있는 마지막 행(= 번호가 1 이 아닌 행)과, 있으면 판정 불가 1행
        JsonNode lastScored = null;
        JsonNode unable = null;
        for (JsonNode z : zones) {
            if (z.path("rnk").isNull()) {
                unable = unable == null ? z : unable;
            } else {
                lastScored = z;
            }
        }
        assertThat(lastScored).as("전제: 지수가 있는 읍면동이 1곳 이상").isNotNull();
        assertThat(lastScored.path("rnk").asInt()).as("전제: 도 전체 번호가 1 이 아닌 행이다").isGreaterThan(1);

        JsonNode one = getOk(null, lastScored.path("emdCd").asText());
        assertThat(one.path("zones").size()).isEqualTo(1);
        JsonNode z = one.path("zones").path(0);
        assertThat(z.path("emdCd")).isEqualTo(lastScored.path("emdCd"));
        assertThat(z.path("rnk").asInt()).as("🔴 필터 결과 안에서 다시 매긴다").isEqualTo(1);
        assertThat(z.path("rvwScr")).isEqualTo(lastScored.path("rvwScr"));
        assertThat(z.path("rvwGrdCd")).isEqualTo(lastScored.path("rvwGrdCd"));
        assertThat(one.path("smry")).isEqualTo(all.path("smry"));

        if (unable != null) {
            JsonNode u = getOk(null, unable.path("emdCd").asText()).path("zones");
            assertThat(u.size()).isEqualTo(1);
            assertThat(u.path(0).path("rnk").isNull()).as("판정 불가는 필터를 걸어도 순번이 없다").isTrue();
            assertThat(u.path(0).path("rvwGrdCd").asText()).isEqualTo("UNABLE");
        }
    }

    // ───────────────────────── 400 ─────────────────────────

    @Test
    @DisplayName("400 — 날짜 형식·기간 역전·36개월 초과·41자 sggNm·없는 sggNm·없는 emdCd 는 400 이고"
            + "(🔴 500 이 아니다) RECOMMEND 실행 행 수가 그대로다")
    void rejectsBadInputBeforeRecording() {
        Integer absent = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_admdst WHERE emd_cd = ?", Integer.class, EMD_CD_ABSENT);
        assertThat(absent).as("전제: %s 는 tb_admdst 에 없는 코드다", EMD_CD_ABSENT).isZero();

        Integer absentSgg = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_admdst WHERE sgg_nm = ?", Integer.class, SGG_NM_ABSENT);
        assertThat(absentSgg).as("전제: '%s' 는 tb_admdst 에 없는 시군구명이다", SGG_NM_ABSENT).isZero();

        long before = countRecommendExcn();

        // 형식 — 대시가 섞인 날짜(YYYY-MM-DD)
        String dashed = LocalDate.parse(dataBgngYmd, YMD).toString();
        assertThat(get(dashed, dataEndYmd, null, null).getStatusCode())
                .as("bgngYmd=%s", dashed).isEqualTo(HttpStatus.BAD_REQUEST);
        // 역전 — 시작일이 종료일보다 늦다
        String afterEnd = LocalDate.parse(dataEndYmd, YMD).plusDays(1).format(YMD);
        assertThat(get(afterEnd, dataEndYmd, null, null).getStatusCode())
                .as("bgngYmd > endYmd").isEqualTo(HttpStatus.BAD_REQUEST);
        // 🔴 없는 emdCd — 검증이 적재보다 뒤면 tb_anls_excn.emd_cd FK 위반으로 500 이 난다
        assertThat(get(dataBgngYmd, dataEndYmd, null, EMD_CD_ABSENT).getStatusCode())
                .as("없는 emdCd 는 500 이 아니라 400 이다").isEqualTo(HttpStatus.BAD_REQUEST);
        // 기간 상한 — 🔴 날짜를 박지 않는다. 자료 종료일에서 37개월 전을 시작일로 잡으면 월초끼리 세어 38개월이다
        String tooEarly = LocalDate.parse(dataEndYmd, YMD).minusMonths(37).format(YMD);
        assertThat(get(tooEarly, dataEndYmd, null, null).getStatusCode())
                .as("36개월을 넘는 조회 기간: %s ~ %s", tooEarly, dataEndYmd).isEqualTo(HttpStatus.BAD_REQUEST);
        // 🔴 41자 sggNm — 검증이 없으면 tb_anls_excn.sgg_nm VARCHAR(40) 초과로 500 이 난다
        assertThat(get(dataBgngYmd, dataEndYmd, "가".repeat(41), null).getStatusCode())
                .as("41자 sggNm 은 500 이 아니라 400 이다").isEqualTo(HttpStatus.BAD_REQUEST);
        // 없는 sggNm(오타) — 빈 zones 로 200 이 나가며 실행 행이 남으면 안 된다
        assertThat(get(dataBgngYmd, dataEndYmd, SGG_NM_ABSENT, null).getStatusCode())
                .as("없는 sggNm 은 빈 목록 200 이 아니라 400 이다").isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(countRecommendExcn())
                .as("🔴 400 으로 끊긴 요청은 실행 이력을 남기지 않는다(검증이 적재보다 앞이다)").isEqualTo(before);
    }

    // ───────────────────────── 적재 ─────────────────────────

    @Test
    @DisplayName("🔴 적재 — 실행 1회 = TB_ANLS_EXCN 1행(rcmdtnTypeCd=SHARE · 적용 기준값) + TB_ANLS_RSLT 1행(smry+zones)")
    void recordsOneExcnAndOneRslt() throws Exception {
        JsonNode body = getOk(null, null);
        assertThat(body.hasNonNull("excnId")).as("응답에 excnId 가 채워진다").isTrue();
        long excnId = body.path("excnId").asLong();

        Map<String, Object> excn = jdbcTemplate.queryForMap("""
                SELECT anls_type_cd, excutr_id, excn_stts_cd, excn_dt, emd_cd, sgg_nm,
                       anls_bgng_ymd, anls_end_ymd,
                       anls_param_json->>'rcmdtnTypeCd' AS rcmdtn_type_cd,
                       anls_param_json::text AS param_json
                  FROM parking.tb_anls_excn WHERE excn_id = ?
                """, excnId);
        assertThat(excn.get("anls_type_cd")).isEqualTo("RECOMMEND");
        assertThat(excn.get("rcmdtn_type_cd")).as("🔴 요금제 실행과 가르는 유일한 표지다").isEqualTo("SHARE");
        assertThat(excn.get("excutr_id")).as("🔴 실행자 = 로그인 사용자").isEqualTo(IntegrationLoginSupport.MOCK_ID);
        assertThat(excn.get("excn_stts_cd")).isEqualTo("DONE");
        assertThat(excn.get("excn_dt")).isNotNull();
        assertThat(excn.get("emd_cd")).as("필터가 없으면 NULL").isNull();
        assertThat(excn.get("sgg_nm")).as("필터가 없으면 NULL").isNull();
        assertThat(excn.get("anls_bgng_ymd").toString().trim()).as("YYYYMMDD 그대로").isEqualTo(dataBgngYmd);
        assertThat(excn.get("anls_end_ymd").toString().trim()).isEqualTo(dataEndYmd);

        // 적용 기준값 — 🔴 응답 crtrVls 와 같은 값(꼬리 0 을 걷은 값)이 굳어 있다
        JsonNode param = objectMapper.readTree((String) excn.get("param_json"));
        JsonNode crtr = body.path("crtrVls");
        for (String key : List.of("cvlcptMmCrtrVl", "cvlcptRtCrtrVl", "coreMltp", "months")) {
            assertThat(param.hasNonNull(key)).as("anls_param_json.%s: %s", key, param).isTrue();
            assertThat(param.path(key).asText()).as("anls_param_json.%s = 응답 crtrVls.%s", key, key)
                    .isEqualTo(crtr.path(key).asText());
        }
        assertThat(param.has("sggNm")).as("필터 키는 값이 없어도 자리를 남긴다").isTrue();
        assertThat(param.path("sggNm").isNull()).isTrue();
        assertThat(param.path("emdCd").isNull()).isTrue();

        // 결과 — 실행 1건 = 결과 1행(excn_id UNIQUE), rslt_json = 응답의 smry + zones
        Integer rsltCnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_rslt WHERE excn_id = ?", Integer.class, excnId);
        assertThat(rsltCnt).isEqualTo(1);
        JsonNode rslt = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class, excnId));
        assertThat(rslt.path("smry").path("emdCnt").asInt()).isEqualTo(body.path("smry").path("emdCnt").asInt());
        assertThat(rslt.path("smry").path("rvwTrgtCnt").asInt())
                .isEqualTo(body.path("smry").path("rvwTrgtCnt").asInt());
        assertThat(rslt.path("zones").size()).isEqualTo(body.path("zones").size());
        assertThat(rslt.path("zones").path(0).path("emdCd").asText())
                .isEqualTo(body.path("zones").path(0).path("emdCd").asText());

        // 🔴 emdCd 만 온 요청 — sgg_nm 은 그 읍면동이 속한 시군구로 채운다(비우면 지역명 연결이 NULL 이 된다)
        JsonNode first = body.path("zones").path(0);
        String emdCd = first.path("emdCd").asText();
        JsonNode one = getOk(null, emdCd);
        assertThat(one.path("zones").size()).as("emdCd 필터는 1행이다").isEqualTo(1);
        Map<String, Object> oneExcn = jdbcTemplate.queryForMap("""
                SELECT emd_cd, sgg_nm, anls_param_json->>'emdCd' AS p_emd_cd,
                       anls_param_json->>'sggNm' AS p_sgg_nm
                  FROM parking.tb_anls_excn WHERE excn_id = ?
                """, one.path("excnId").asLong());
        assertThat(oneExcn.get("emd_cd")).isEqualTo(emdCd);
        assertThat(oneExcn.get("sgg_nm")).isEqualTo(first.path("sggNm").asText());
        assertThat(oneExcn.get("p_emd_cd")).isEqualTo(emdCd);
        assertThat(oneExcn.get("p_sgg_nm")).as("anls_param_json 의 필터는 요청 그대로다(주지 않았다)").isNull();
        // 🔴 적재된 zones 의 순번도 응답과 같은 값(필터 결과 안에서 매긴 번호)이다
        JsonNode oneRslt = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT rslt_json::text FROM parking.tb_anls_rslt WHERE excn_id = ?", String.class,
                one.path("excnId").asLong()));
        assertThat(oneRslt.path("zones").size()).isEqualTo(1);
        assertThat(oneRslt.path("zones").path(0).path("rnk"))
                .as("rslt_json.zones[0].rnk = 응답 zones[0].rnk").isEqualTo(one.path("zones").path(0).path("rnk"));
        assertThat(oneRslt.path("smry").path("emdCnt").asInt())
                .as("적재된 smry 는 필터와 무관한 도 전체 값이다").isEqualTo(body.path("smry").path("emdCnt").asInt());

        // sggNm + emdCd 를 함께 준 요청 — 그 읍면동 1행이고, sgg_nm 은 그 읍면동의 시군구다
        String sggNm = first.path("sggNm").asText();
        JsonNode both = getOk(sggNm, emdCd);
        assertThat(both.path("zones").size()).as("sggNm + emdCd 필터는 1행이다").isEqualTo(1);
        assertThat(both.path("zones").path(0).path("emdCd").asText()).isEqualTo(emdCd);
        Map<String, Object> bothExcn = jdbcTemplate.queryForMap("""
                SELECT emd_cd, sgg_nm, anls_param_json->>'emdCd' AS p_emd_cd,
                       anls_param_json->>'sggNm' AS p_sgg_nm
                  FROM parking.tb_anls_excn WHERE excn_id = ?
                """, both.path("excnId").asLong());
        assertThat(bothExcn.get("emd_cd")).isEqualTo(emdCd);
        assertThat(bothExcn.get("sgg_nm")).isEqualTo(sggNm);
        assertThat(bothExcn.get("p_emd_cd")).isEqualTo(emdCd);
        assertThat(bothExcn.get("p_sgg_nm")).as("anls_param_json 의 필터는 요청 그대로다").isEqualTo(sggNm);
    }

    // ───────────────────────── 부분 달 창 ─────────────────────────

    @Test
    @DisplayName("부분 달 창 — crtrVls.months = PrdMonths.of(소수) · 각 읍면동 월평균 = 건수 ÷ months · 건수 = 직접 count")
    void partialMonthWindowUsesFractionalMonths() {
        ZoneRcmdtnTestData.Win w = partialMonthWindow(LocalDate.parse(dataBgngYmd, YMD),
                LocalDate.parse(dataEndYmd, YMD));
        Map<String, Long> direct = new ZoneRcmdtnTestData(jdbcTemplate).cvCountByEmd(w);
        long directTot = ZoneRcmdtnTestData.sum(direct);
        // 🔴 먼저 — 창이 자료 밖이면 0 = 0 ÷ months 로 헛통과한다
        assertThat(directTot).as("전제: 부분 달 창 %s ~ %s 에 민원이 있어야 한다", w.bgng(), w.end()).isPositive();

        ResponseEntity<String> res = get(w.bgngYmd(), w.endYmd(), null, null);
        assertThat(res.getStatusCode()).as("GET %s → %s", URL, res.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body;
        try {
            body = objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패", e);
        }
        assertThat(body.path("smry").path("cvlcptTot").asLong()).as("창 민원 합 = 직접 count").isEqualTo(directTot);

        BigDecimal months = body.path("crtrVls").path("months").decimalValue();
        assertThat(months).as("crtrVls.months = PrdMonths.of(%s, %s)", w.bgng(), w.end())
                .usingComparator(BigDecimal::compareTo).isEqualTo(w.months());
        assertThat(months.stripTrailingZeros().scale()).as("🔴 부분 달이라 정수가 아니다: %s", months).isPositive();

        int checked = 0;
        for (JsonNode z : body.path("zones")) {
            String e = z.path("emdCd").asText();
            long cnt = z.path("cvlcptCnt").asLong();
            assertThat(cnt).as("%s 건수 = 직접 count", e).isEqualTo(direct.getOrDefault(e, 0L));
            // 계산기와 같은 경로 — 소수 6자리 HALF_UP 몫을 응답 1자리 HALF_UP 으로 반올림
            BigDecimal expected = BigDecimal.valueOf(cnt).divide(months, 6, RoundingMode.HALF_UP)
                    .setScale(1, RoundingMode.HALF_UP);
            assertThat(z.path("cvlcptMmAvg").decimalValue()).as("%s 월평균 = %d ÷ %s", e, cnt, months)
                    .usingComparator(BigDecimal::compareTo).isEqualTo(expected);
            if (cnt > 0) {
                checked++;
            }
        }
        assertThat(checked).as("민원이 있는 읍면동이 1곳 이상 대조됐다").isPositive();
    }

    /**
     * 자료 끝일 기준 30일 창(시작 = 끝 − 29일). 🔴 시작이 끝과 같은 달이면(시작일이 월초인 경우 포함) 시작을
     * 전달 말일로 당겨 <b>반드시 두 달에 걸치게</b> 한다 — 한 달 안이거나 온전한 달 경계면 months 가 정수가 될 수 있다.
     */
    static ZoneRcmdtnTestData.Win partialMonthWindow(LocalDate dataBgng, LocalDate dataEnd) {
        LocalDate bgng = dataEnd.minusDays(29);
        if (bgng.getMonth() == dataEnd.getMonth() && bgng.getYear() == dataEnd.getYear()) {
            bgng = dataEnd.withDayOfMonth(1).minusDays(1);
        }
        assertThat(bgng).as("전제: 자료 구간(%s ~ %s)이 부분 달 창을 담을 만큼 길어야 한다", dataBgng, dataEnd)
                .isAfterOrEqualTo(dataBgng);
        return new ZoneRcmdtnTestData.Win(bgng, dataEnd);
    }

    // ───────────────────────── 추세 반영 ─────────────────────────

    @Test
    @DisplayName("① 같은 창에서 가중치 0 실행과 현재(>0) 가중치 실행의 읍면동별 rvwGrdCd·rvwScr·"
            + "rvwTrgtYn·증가율이 같다 (가중치는 순서만 바꾼다)")
    void weightDoesNotChangeGradeOrScore() {
        ZoneRcmdtnTestData.Win w = trendWindow();
        // 🔴 기준 쪽 가중치는 DB 현재값이다. 현재값이 0 이면 비교가 헛돌므로 그때만 기본값(bsc_stng_vl)으로 잠시 바꾼다
        BigDecimal current = readStngVl(VRBL_INCRS_WGVL, "stng_vl");
        BigDecimal positive = current.signum() > 0 ? current : readStngVl(VRBL_INCRS_WGVL, "bsc_stng_vl");
        assertThat(positive).as("전제: 민원증가가중치 현재값 또는 기본값이 0 보다 커야 비교가 의미를 갖는다").isPositive();

        JsonNode weighted = current.signum() > 0 ? getOkAt(w) : withIncrsWgvl(positive, () -> getOkAt(w));
        JsonNode zero = withIncrsWgvl(BigDecimal.ZERO, () -> getOkAt(w));

        assertThat(weighted.path("crtrVls").path("incrsWgvl").decimalValue())
                .as("crtrVls.incrsWgvl = 적용한 설정값").usingComparator(BigDecimal::compareTo).isEqualTo(positive);
        assertThat(zero.path("crtrVls").path("incrsWgvl").decimalValue().signum()).as("가중치 0 이 적용됐다").isZero();

        Map<String, JsonNode> zeroByEmd = byEmd(zero.path("zones"));
        assertThat(zeroByEmd).hasSize(weighted.path("zones").size());
        boolean cfcMoved = false;
        for (JsonNode z : weighted.path("zones")) {
            JsonNode o = zeroByEmd.get(z.path("emdCd").asText());
            assertThat(o).as("같은 읍면동이 두 응답에 다 있다: %s", z).isNotNull();
            assertThat(z.path("rvwGrdCd")).as("🔴 등급은 가중치와 무관하다: %s", z).isEqualTo(o.path("rvwGrdCd"));
            assertThat(z.path("rvwScr")).as("🔴 검토 지수는 가중치와 무관하다: %s", z).isEqualTo(o.path("rvwScr"));
            assertThat(z.path("rvwTrgtYn")).as("후보 여부: %s", z).isEqualTo(o.path("rvwTrgtYn"));
            assertThat(z.path("cvlcptIncrsRt")).as("증가율은 건수만 쓴다: %s", z).isEqualTo(o.path("cvlcptIncrsRt"));
            if (z.path("incrsCfc").decimalValue().compareTo(BigDecimal.ONE) != 0) {
                cfcMoved = true;
            }
        }
        assertThat(weighted.path("smry")).as("요약(후보·핵심 수)도 같다").isEqualTo(zero.path("smry"));
        // 헛통과 방지 — 계수가 전부 1 이면 두 실행이 같은 것은 당연하다
        assertThat(cfcMoved).as("전제: 가중치 %s 실행에서 증가계수 ≠ 1 인 읍면동이 1곳 이상 있어야 한다", positive).isTrue();
    }

    /**
     * ② 순번이 추세 반영 지수 내림차순 → 민원 건수 → {@code emd_cd}(후보 먼저 키 삭제)와 같다.
     *
     * <p>🔴 <b>순번 검증 방식 — 표시값 비증가 + 동률 허용.</b> 응답의 {@code incrsRfltRvwScr} 는 2자리 버림(DOWN)
     * 표시값이고 정밀값은 응답에 없다. 버림은 단조라 "정밀값 내림차순 ⇒ 표시값 비증가" 는 항상 참이지만,
     * 표시값이 같은 두 행은 정밀값이 달라 민원 건수와 무관하게 갈릴 수 있다 — 그래서 표시값 동률에서
     * {@code cvlcptCnt}·{@code emdCd} 순을 단언하면 깨진다. {@code rvwScr × incrsCfc} 로 다시 곱해 정렬하는
     * 방식도 두 입력이 각각 버림·반올림된 표시값이라 근접한 행의 순서를 뒤집을 수 있어 쓰지 않았다.
     * 대신 각 행의 값 자체({@code incrsCfc} ← 증가율·설정값, {@code incrsRfltRvwScr} ← 지수 × 계수,
     * {@code bfrCvlcptCnt} ← DB 직접 count)를 허용 오차 안에서 재계산해 순서의 재료가 맞는지를 따로 본다.
     */
    @Test
    @DisplayName("② 순서 — 추세 창(직전 동일 기간이 자료 안)에서 전 구간 incrsRfltRvwScr "
            + "비증가(후보 먼저 삭제) · 이전 블록 건수 = 직접 count · 증가계수·추세 반영 지수 = DB 설정값으로 재계산한 값")
    void rnkFollowsD2WithRecomputedTrendInputs() {
        ZoneRcmdtnTestData.Win w = trendWindow();
        JsonNode body = getOkAt(w);
        assertRvwScrComputable(body);
        JsonNode crtr = body.path("crtrVls");
        long days = ChronoUnit.DAYS.between(w.bgng(), w.end()) + 1;

        // 1차 블록이다 — 폴백·기간 하한 경고가 없고 trendDayCnt = D
        assertThat(textValues(body.path("warnings"))).as("warnings: %s", body.path("warnings"))
                .doesNotContain(WARN_TREND_BFR_FALLBACK, WARN_TREND_WINDOW_SHORT);
        assertThat(crtr.path("trendDayCnt").asLong()).as("trendDayCnt = 조회 일수 D").isEqualTo(days);

        // 🔴 설정값은 상수가 아니라 DB 현재값 — 응답 crtrVls 와 같아야 한다
        BigDecimal wgvl = readStngVl(VRBL_INCRS_WGVL, "stng_vl");
        BigDecimal cap = readStngVl(VRBL_INCRS_RT_UPLMT, "stng_vl");
        assertThat(crtr.path("incrsWgvl").decimalValue()).usingComparator(BigDecimal::compareTo).isEqualTo(wgvl);
        assertThat(crtr.path("incrsRtUplmtVl").decimalValue()).usingComparator(BigDecimal::compareTo).isEqualTo(cap);
        long minBase = crtr.path("minTrendBase").asLong();
        assertThat(minBase).as("crtrVls.minTrendBase").isPositive();

        // 이전 블록 = [bgng − D, bgng) — 읍면동별 직접 count 와 같다
        Map<String, Long> bfr = new ZoneRcmdtnTestData(jdbcTemplate).cvCountByEmd(
                new ZoneRcmdtnTestData.Win(w.bgng().minusDays(days), w.bgng().minusDays(1)));
        assertThat(ZoneRcmdtnTestData.sum(bfr)).as("전제: 직전 동일 기간에 민원이 있어야 1차 블록이다").isPositive();

        BigDecimal capRatio = cap.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        // 증가율 표시값(1자리 HALF_UP, ±0.05%p)이 계수로 번지는 폭 + 계수 표시 반올림 폭(±0.0005)
        BigDecimal cfcTol = wgvl.multiply(new BigDecimal("0.0005")).add(new BigDecimal("0.0005"))
                .add(new BigDecimal("0.000001"));
        int moved = 0;
        for (JsonNode z : body.path("zones")) {
            String e = z.path("emdCd").asText();
            long b = z.path("bfrCvlcptCnt").asLong();
            assertThat(z.hasNonNull("bfrCvlcptCnt")).as("1차 블록이면 이전 건수가 있다: %s", z).isTrue();
            assertThat(b).as("%s 이전 블록 건수 = 직접 count", e).isEqualTo(bfr.getOrDefault(e, 0L));
            // 분모 하한 — 미만이면 증가율 null · 계수 1
            assertThat(z.path("cvlcptIncrsRt").isNull()).as("증가율 null ⇔ 이전 블록 < %d: %s", minBase, z)
                    .isEqualTo(b < minBase);
            BigDecimal cfc = z.path("incrsCfc").decimalValue();
            BigDecimal expectedCfc = BigDecimal.ONE;
            if (!z.path("cvlcptIncrsRt").isNull()) {
                BigDecimal g = z.path("cvlcptIncrsRt").decimalValue().divide(HUNDRED, 6, RoundingMode.HALF_UP);
                g = g.max(capRatio.negate()).min(capRatio);
                expectedCfc = BigDecimal.ONE.add(wgvl.multiply(g));
            }
            assertThat(cfc.subtract(expectedCfc).abs())
                    .as("%s 증가계수(%s) ≈ 1 + %s × clamp(증가율, ±%s%%) = %s", e, cfc, wgvl, cap, expectedCfc)
                    .isLessThanOrEqualTo(cfcTol);
            if (cfc.compareTo(BigDecimal.ONE) != 0) {
                moved++;
            }
            if (z.path("rvwScr").isNull()) {
                continue;
            }
            // 추세 반영 지수 ≈ 검토 지수 × 계수. 오차: 지수 버림(< 0.01) × 계수 + 계수 반올림(0.0005) × 지수 + 결과 버림(< 0.01)
            BigDecimal scr = z.path("rvwScr").decimalValue();
            BigDecimal rflt = z.path("incrsRfltRvwScr").decimalValue();
            BigDecimal tol = new BigDecimal("0.01").multiply(cfc.add(new BigDecimal("0.0005")))
                    .add(new BigDecimal("0.0005").multiply(scr.add(new BigDecimal("0.01"))))
                    .add(new BigDecimal("0.01"));
            assertThat(rflt.subtract(scr.multiply(cfc)).abs())
                    .as("%s 추세 반영 지수(%s) ≈ %s × %s (허용 %s)", e, rflt, scr, cfc, tol).isLessThan(tol);
        }
        if (wgvl.signum() > 0) {
            assertThat(moved).as("전제: 가중치 %s 에서 증가계수 ≠ 1 인 읍면동이 1곳 이상", wgvl).isPositive();
        }

        // 순서 — 후보 여부와 무관하게 표시값 비증가(판정 불가 맨 뒤)
        int candCnt = 0;
        for (JsonNode z : body.path("zones")) {
            if (!z.path("rnk").isNull() && "Y".equals(z.path("rvwTrgtYn").asText())) {
                candCnt++;
            }
        }
        assertTrendOrder(body.path("zones"));
        assertHasCandidate(candCnt, body);
    }

    @Test
    @DisplayName("③ 자료 전 구간 창 — 직전 동일 기간이 자료 밖(합 0)이라 TREND_BFR_FALLBACK · "
            + "trendDayCnt = D/2 · 이전 블록 = 앞 D/2일 직접 count")
    void fullDataRangeFallsBackToHalfSplit() {
        JsonNode body = getOk(null, null);
        LocalDate bgng = LocalDate.parse(dataBgngYmd, YMD);
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);
        long half = (ChronoUnit.DAYS.between(bgng, end) + 1) / 2;
        assertThat(half).as("전제: 자료 구간이 기간 하한(D/2 ≥ 7)을 넘어야 폴백 판정을 한다").isGreaterThanOrEqualTo(7);

        List<String> warnings = textValues(body.path("warnings"));
        assertThat(warnings).as("warnings").contains(WARN_TREND_BFR_FALLBACK).doesNotContain(WARN_TREND_WINDOW_SHORT);
        assertThat(body.path("crtrVls").path("trendDayCnt").asLong()).as("폴백이면 trendDayCnt = D/2").isEqualTo(half);

        // 폴백 이전 블록 = [bgng, bgng + half)
        Map<String, Long> bfr = new ZoneRcmdtnTestData(jdbcTemplate).cvCountByEmd(
                new ZoneRcmdtnTestData.Win(bgng, bgng.plusDays(half - 1)));
        for (JsonNode z : body.path("zones")) {
            assertThat(z.path("bfrCvlcptCnt").asLong()).as("%s 폴백 이전 블록 건수 = 앞 %d일 직접 count", z.path("emdCd"), half)
                    .isEqualTo(bfr.getOrDefault(z.path("emdCd").asText(), 0L));
        }
    }

    @Test
    @DisplayName("④ 가중치 0 — 전 계수 1 · 추세 반영 지수 = rvwScr · 순번 = 종전 지수 순"
            + "(rvwScr 비증가 · CORE 가 CAND 보다 앞) · 테스트 후 설정 원복")
    void zeroWeightRestoresPlainIndexOrder() {
        ZoneRcmdtnTestData.Win w = trendWindow();
        BigDecimal before = readStngVl(VRBL_INCRS_WGVL, "stng_vl");
        JsonNode body = withIncrsWgvl(BigDecimal.ZERO, () -> getOkAt(w));
        assertThat(readStngVl(VRBL_INCRS_WGVL, "stng_vl")).as("🔴 설정이 테스트 시작 값으로 돌아왔다")
                .usingComparator(BigDecimal::compareTo).isEqualTo(before);
        assertRvwScrComputable(body);

        int candCnt = 0;
        boolean candSeen = false;
        BigDecimal prevScr = null;
        for (JsonNode z : body.path("zones")) {
            assertThat(z.path("incrsCfc").decimalValue()).as("가중치 0 이면 계수 1: %s", z)
                    .usingComparator(BigDecimal::compareTo).isEqualTo(BigDecimal.ONE);
            if (z.path("rnk").isNull()) {
                continue;
            }
            BigDecimal scr = z.path("rvwScr").decimalValue();
            assertThat(z.path("incrsRfltRvwScr").decimalValue()).as("계수 1 이면 추세 반영 지수 = rvwScr: %s", z)
                    .usingComparator(BigDecimal::compareTo).isEqualTo(scr);
            // 계수 1 이면 우선도 = rvwScr 라 우선도 내림차순은 곧 전 구간 rvwScr 비증가다(종전 불변식)
            if (prevScr != null) {
                assertThat(scr).as("가중치 0 이면 rvwScr 비증가(종전 순서): %s", z).isLessThanOrEqualTo(prevScr);
            }
            prevScr = scr;
            String grade = z.path("rvwGrdCd").asText();
            if ("CAND".equals(grade)) {
                candSeen = true;
                candCnt++;
            } else if ("CORE".equals(grade)) {
                candCnt++;
                assertThat(candSeen).as("가중치 0 이면 CORE 가 CAND 보다 앞이다(종전 순서): %s", z).isFalse();
            }
        }
        assertHasCandidate(candCnt, body);
    }

    /**
     * 추세 창 — 자료 끝일 기준 31일. 🔴 직전 동일 기간(31일)도 자료 안이어야 1차 블록이 된다(날짜를 박지 않는다).
     */
    private ZoneRcmdtnTestData.Win trendWindow() {
        LocalDate dataBgng = LocalDate.parse(dataBgngYmd, YMD);
        LocalDate end = LocalDate.parse(dataEndYmd, YMD);
        LocalDate bgng = end.minusDays(30);
        assertThat(bgng.minusDays(31)).as("전제: 자료 구간(%s ~ %s)이 31일 창 + 직전 31일을 담아야 한다", dataBgng, end)
                .isAfterOrEqualTo(dataBgng);
        return new ZoneRcmdtnTestData.Win(bgng, end);
    }

    private JsonNode getOkAt(ZoneRcmdtnTestData.Win w) {
        ResponseEntity<String> res = get(w.bgngYmd(), w.endYmd(), null, null);
        assertThat(res.getStatusCode()).as("GET %s %s~%s → %s", URL, w.bgngYmd(), w.endYmd(), res.getBody())
                .isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패", e);
        }
    }

    private BigDecimal readStngVl(String vrblCd, String column) {
        // column 은 이 클래스가 넘기는 상수(stng_vl | bsc_stng_vl)뿐이다
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM parking.tb_anls_vrbl_stng "
                + "WHERE anls_type_cd = 'RECOMMEND' AND vrbl_cd = ?", BigDecimal.class, vrblCd);
    }

    private void writeIncrsWgvl(BigDecimal v) {
        int n = jdbcTemplate.update("UPDATE parking.tb_anls_vrbl_stng SET stng_vl = ? "
                + "WHERE anls_type_cd = 'RECOMMEND' AND vrbl_cd = ?", v, VRBL_INCRS_WGVL);
        assertThat(n).as("RECOMMEND/%s 설정 행이 1행 있어야 한다", VRBL_INCRS_WGVL).isEqualTo(1);
    }

    /**
     * 🔴 민원증가가중치를 잠시 바꿔 {@code body} 를 실행하고 <b>테스트 시작 때 읽은 값</b>으로 되돌린다(기본값이
     * 아니다 — 관리 화면에서 바꾼 값을 지우지 않는다). finally 가 못 돌아도 {@link #deleteOnlyWhatThisTestCreated()}
     * 가 {@link #wgvlToRestore} 로 한 번 더 되돌린다. 서비스는 요청마다 설정을 읽는다(캐시 없음).
     */
    private <T> T withIncrsWgvl(BigDecimal v, Supplier<T> body) {
        BigDecimal original = readStngVl(VRBL_INCRS_WGVL, "stng_vl");
        if (wgvlToRestore == null) {
            wgvlToRestore = original;
        }
        try {
            writeIncrsWgvl(v);
            return body.get();
        } finally {
            writeIncrsWgvl(original);
        }
    }

    /**
     * 순번이 있는 행 전 구간에서 {@code incrsRfltRvwScr} 표시값이 비증가다(후보 여부 묶음 없이).
     * 정밀값 내림차순이고 표시는 단조인 버림이라 늘어나지 않는다 — 같을 수는 있다(동률 허용, ② Javadoc).
     */
    private void assertTrendOrder(JsonNode zones) {
        BigDecimal prev = null;
        for (JsonNode z : zones) {
            if (z.path("rnk").isNull()) {
                continue;
            }
            BigDecimal rflt = z.path("incrsRfltRvwScr").decimalValue();
            if (prev != null) {
                assertThat(rflt).as("incrsRfltRvwScr 비증가: %s", z).isLessThanOrEqualTo(prev);
            }
            prev = rflt;
        }
    }

    private static Map<String, JsonNode> byEmd(JsonNode zones) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode z : zones) {
            out.put(z.path("emdCd").asText(), z);
        }
        return out;
    }

    private static List<String> textValues(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            out.add(n.asText());
        }
        return out;
    }

    // ───────────────────────── 헬퍼 ─────────────────────────

    /**
     * 🔴 전제 단언 — 후보가 0곳이면 후보를 도는 루프가 한 번도 돌지 않아 불변식·순번 단언이 헛통과한다.
     * 자료가 바뀌어 후보가 사라지면 조용히 초록불이 되지 않고 여기서 읽을 수 있는 메시지로 실패한다.
     */
    private void assertHasCandidate(int candCnt, JsonNode body) {
        assertThat(candCnt)
                .as("전제: 자료 구간 %s~%s 에 공유주차 검토 후보가 1곳 이상 있어야 이 테스트가 의미를 갖는다 — "
                        + "후보가 0곳이라 단언이 한 번도 돌지 않았다. 민원 적재분과 RECOMMEND 기준값(crtrVls=%s)을 "
                        + "확인하라", dataBgngYmd, dataEndYmd, body.path("crtrVls"))
                .isPositive();
    }

    /**
     * 🔴 전제 단언 — 두 기준값이 모두 0 이면 검토 지수를 낼 수 없어(warnings {@code CRTR_ALL_ZERO}) 전 행의
     * {@code rvwScr}·{@code rnk} 가 null 이다. 그 설정에서는 "null ⇔ UNABLE" 이 성립하지 않으므로, 조용히
     * 엉뚱한 단언으로 실패하지 않고 여기서 원인을 말한다.
     */
    private void assertRvwScrComputable(JsonNode body) {
        JsonNode crtr = body.path("crtrVls");
        boolean bothZero = crtr.path("cvlcptMmCrtrVl").decimalValue().signum() <= 0
                && crtr.path("cvlcptRtCrtrVl").decimalValue().signum() <= 0;
        assertThat(bothZero)
                .as("전제: RECOMMEND 기준값 둘 중 하나는 0 보다 커야 검토 지수가 계산된다 (crtrVls=%s, warnings=%s)",
                        crtr, body.path("warnings"))
                .isFalse();
    }

    private long countRecommendExcn() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM parking.tb_anls_excn WHERE anls_type_cd = 'RECOMMEND'", Long.class);
    }

    /** 자료 구간 전체를 조회 기간으로 한 200 호출. 응답의 {@code excnId} 를 정리 목록에 담는다. */
    private JsonNode getOk(String sggNm, String emdCd) {
        ResponseEntity<String> res = get(dataBgngYmd, dataEndYmd, sggNm, emdCd);
        assertThat(res.getStatusCode()).as("GET %s → %s", URL, res.getBody()).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readTree(res.getBody());
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            throw new IllegalStateException("응답 JSON 파싱 실패", e);
        }
    }

    /**
     * 🔴 쿼리 값은 URI 변수로 넘긴다 — 한글 {@code sggNm} 을 URL 에 그대로 적으면 Tomcat 이 request target 의
     * 비ASCII 바이트를 400 으로 거절한다. 템플릿 확장이 값을 퍼센트 인코딩한다.
     *
     * <p>실패를 기대한 호출이 200 으로 돌아와도 그 실행 행이 남지 않게, 응답에 {@code excnId} 가 있으면
     * 언제나 정리 목록에 담는다.
     */
    private ResponseEntity<String> get(String bgngYmd, String endYmd, String sggNm, String emdCd) {
        StringBuilder template = new StringBuilder(URL).append("?bgngYmd={bgngYmd}&endYmd={endYmd}");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("bgngYmd", bgngYmd);
        vars.put("endYmd", endYmd);
        if (sggNm != null) {
            template.append("&sggNm={sggNm}");
            vars.put("sggNm", sggNm);
        }
        if (emdCd != null) {
            template.append("&emdCd={emdCd}");
            vars.put("emdCd", emdCd);
        }
        ResponseEntity<String> res = restTemplate.exchange(
                template.toString(), HttpMethod.GET, new HttpEntity<>(session), String.class, vars);
        rememberExcnId(res);
        return res;
    }

    private void rememberExcnId(ResponseEntity<String> res) {
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            return;
        }
        try {
            JsonNode excnId = objectMapper.readTree(res.getBody()).path("excnId");
            if (excnId.isIntegralNumber()) {
                createdExcnIds.add(excnId.asLong());
            }
        } catch (JsonProcessingException e) {   // 4.3 — readTree 가 던지는 파싱 예외만
            // 정리 목록을 채우려는 시도일 뿐이다 — 파싱 실패는 호출한 쪽의 단언이 드러낸다
            org.slf4j.LoggerFactory.getLogger(ZoneRcmdtnShareIntegrationTest.class).debug("응답 JSON 파싱 생략 — {}", e.getClass().getSimpleName());   // 4.2
        }
    }

    private void deleteQuietly(List<RuntimeException> failures, String sql, Long id) {
        try {
            jdbcTemplate.update(sql, id);
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
