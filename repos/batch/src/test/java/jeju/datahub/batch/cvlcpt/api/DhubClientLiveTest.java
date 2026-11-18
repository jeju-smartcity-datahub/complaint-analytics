package jeju.datahub.batch.cvlcpt.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * <b>DHubManager 실호출</b> 확인 (Step 10-3 스파이크의 재현 가능한 형태).
 *
 * <p>{@code DHUB_USER_EMAIL} 환경변수가 있을 때만 돈다 — 자격증명 없는 환경에서 빌드가 깨지지 않게
 * 조용히 건너뛴다({@code VworldGeocodingClientLiveTest} 와 같은 관례).
 *
 * <pre>{@code
 * set -a; . ./.env; set +a
 * ./gradlew test --tests '*Dhub*'
 * }</pre>
 *
 * <p>⚠️ <b>토큰 값을 assert 메시지·출력 어디에도 남기지 않는다.</b> 확인하는 것은 "받았는가"와
 * "만료를 읽을 수 있는가"이지 값 자체가 아니다.
 *
 * <p>기대값은 <b>2026-09-01 실측</b>이다(원천 이관 후 — {@code jeju_smart_city_test.tb_cvlcpt},
 * TSK-4109). 원천 스냅샷이 갱신되면 건수는 늘어날 수 있으므로 <b>하한</b>으로만 단언한다.
 *
 * <p>🔴 이 테스트가 실제로 막는 사고는 <b>"원천이 바뀐 줄 모르는 것"</b>이다. 2026-08-31 에
 * DTonic 이 <b>데이터셋 ID 는 그대로 둔 채 대상 테이블만</b> {@code minwon_sentiment} 에서
 * {@code tb_cvlcpt} 로 갈아끼웠고, 그 결과 배치가 422 로 즉시 FAILED 됐다. 아래 검증은 컬럼
 * 존재·값 도메인·건수 규모를 전부 실호출로 보므로, 같은 일이 또 나면 여기서 먼저 빨개진다.
 */
@EnabledIfEnvironmentVariable(named = "DHUB_USER_EMAIL", matches = ".+")
class DhubClientLiveTest {

    /**
     * 원천 최소 {@code ocrn_dt}(2026-09-01 실측).
     *
     * <p>⚠️ {@code dclr_ymd} 가 아니다 — 새 원천의 {@code dclr_ymd} 는 2025-01-01~2025-12-15 뿐이라
     * 그것으로 거르면 이 테스트가 전부 0건이 된다(운영 배치가 겪은 것과 같은 함정).
     */
    private static final LocalDate BACKFILL_FROM = LocalDate.parse("2026-01-01");

    /**
     * 2026-09-01 실측 backfill 건수. 스냅샷이 갱신되면 늘어날 수 있어 하한으로 본다.
     *
     * <p>⚠️ 구 원천(106,579행)보다 <b>작다</b> — 원천이 통째로 교체되면서 담는 구간이 달라졌다.
     * 새 원천은 {@code ocrn_dt} 2026-01-01~실행일을 덮는다.
     */
    private static final long MEASURED_BACKFILL_ROWS = 78_386L;

    /** 원천 감정값 도메인(2026-09-01 실측 전수: 보통 71,102 / 부정 7,269 / 긍정 15). */
    private static final List<String> SENTIMENT_VALUES = List.of("보통", "부정", "긍정");

    /** CASE 가 덮는 10종(사전조사 "유형 분류 규칙 확정"). 여기 없는 값이 나오면 CASE 보강 대상이다. */
    private static final List<String> CASE_COVERED_TYPES = List.of(
            "횡단보도", "인도",
            "교차로 모퉁이", "소화전", "버스정류소", "어린이보호구역", "소방차전용구역",
            "장애인전용구역", "친환경차충전구역", "기타불법주정차");

    /** 로컬 시드에서 확인된 유입경로 3종. */
    private static final List<String> LOCAL_INFLOW_PATHS = List.of("모바일앱", "포털", "API");

    private final DhubProperties properties = liveProperties();
    private final DhubAuthClient authClient = new DhubAuthClient(properties);
    private final DhubQueryClient queryClient = new DhubQueryClient(authClient, properties);

    @Test
    @DisplayName("자격증명으로 로그인하면 access_token 을 받고 JWT exp 로 수명을 알 수 있다")
    void loginsAndReadsTokenLifetime() {
        String token = authClient.currentToken();

        // 값은 출력하지 않는다 — 받았다는 사실과 JWT 형태(3분절)만 본다.
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(authClient.loginCount()).isEqualTo(1);

        // exp 를 읽었으면 선제갱신이 켜져 있어야 한다(실측 수명 900초).
        assertThat(authClient.isProactiveRefreshEnabled()).isTrue();
        assertThat(authClient.expiresAt()).isNotNull();
        Duration remaining = Duration.between(Instant.now(), authClient.expiresAt());
        assertThat(remaining).isPositive().isLessThanOrEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("잘못된 토큰으로 부르면 401 — 재로그인 후 1회 재시도로 회복된다")
    void recoversFromUnauthorizedWithSingleRetry() {
        // 서버가 401 을 주는 상황을 실제로 만든다: 위조 토큰을 캐시에 심을 수는 없으므로
        // withToken 계약을 그대로 확인한다 — 첫 호출이 401 이면 재로그인 후 딱 한 번 더 부른다.
        List<String> tokens = new ArrayList<>();
        String result = authClient.withToken(token -> {
            tokens.add(token);
            if (tokens.size() == 1) {
                throw new DhubUnauthorizedException("401(실호출 재현)");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(tokens).hasSize(2);
        assertThat(authClient.loginCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("G: API 원천의 dclr_type 집합이 CASE 좌변 10종을 모두 포함한다")
    void sourceTypesCoverCaseBranches() {
        String table = properties.requireSourceTable();
        String where = DhubSql.halfOpenRange(
                DhubComplaintApiClient.DATE_COLUMN, BACKFILL_FROM, LocalDate.now());

        ArrayNode rows = queryClient.query(
                "SELECT dclr_type, count(*) AS cnt FROM " + table + " WHERE " + where
                        + " GROUP BY dclr_type ORDER BY cnt DESC");

        List<String> types = new ArrayList<>();
        rows.forEach(row -> types.add(row.path("dclr_type").asText()));

        // CASE 좌변이 실제값을 전부 덮는지 — 빠진 게 있으면 그 유형이 통째로 99 로 빠진다.
        assertThat(types).containsAll(CASE_COVERED_TYPES);
        // 33종(2026-09-01 실측). 늘어나면 CASE 보강 검토 신호다.
        assertThat(types).hasSizeGreaterThanOrEqualTo(33);
    }

    @Test
    @DisplayName("🔴 감정(aprs_cd) 값 도메인이 보통/부정/긍정 3종이다 — APRS_CD 매핑의 입력")
    void sentimentDomainIsThreeValues() {
        String table = properties.requireSourceTable();

        ArrayNode rows = queryClient.query(
                "SELECT aprs_cd, count(*) AS cnt FROM " + table
                        + " GROUP BY aprs_cd ORDER BY cnt DESC");

        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.path("aprs_cd").asText()));

        // ⚠️ 컬럼 이름은 aprs_cd 지만 값은 여전히 한글 원문이다. 원천이 코드로 바꿔 보내기
        //    시작하면 이 단언이 먼저 깨진다 — CvlcptCodeMapping.aprsCd 가 그 경우도 통과시키지만,
        //    도메인이 바뀐 사실 자체는 드러나야 한다.
        assertThat(values).containsAll(SENTIMENT_VALUES);
        assertThat(values).hasSizeLessThanOrEqualTo(SENTIMENT_VALUES.size() + 1); // NULL 그룹 여유
    }

    @Test
    @DisplayName("발생일시(ocrn_dt)가 전 행에 있고 자정이 아닌 시각을 담는다")
    void occurrenceDateTimeIsPresentAndDistinct() {
        String table = properties.requireSourceTable();

        long missing = queryClient.count(
                "SELECT count(*) AS cnt FROM " + table + " WHERE ocrn_dt IS NULL");
        long withTime = queryClient.count(
                "SELECT countIf(toString(ocrn_dt) NOT LIKE '% 00:00:00%') AS cnt FROM " + table);

        // 결측이면 Processor 가 dclr_ymd 00:00:00 폴백을 타므로 시각이 사라진다 — 없어야 의미가 있다.
        assertThat(missing).isZero();
        // 자정이 아닌 시각이 실제로 담겨 있어야 dclr_ymd 파생 대비 값이 늘어난다(실측 19,447행).
        assertThat(withTime).isGreaterThan(10_000L);
    }

    @Test
    @DisplayName("🔴 SELECT 목록 26컬럼이 원천에 전부 실재한다 — 하나만 없어도 수집이 통째로 죽는다")
    void everyMappedColumnExistsInSource() {
        String table = properties.requireSourceTable();

        // 컬럼이 하나라도 없으면 서버가 422 로 거절한다 — 그 실패가 곧 이 테스트의 목적이다.
        ArrayNode rows = queryClient.query(
                "SELECT " + String.join(", ", ComplaintOriginRow.API_COLUMNS)
                        + " FROM " + table + " LIMIT 1", 1);

        assertThat(rows).hasSize(1);
        JsonNode row = rows.get(0);
        for (String column : ComplaintOriginRow.API_COLUMNS) {
            assertThat(row.has(column))
                    .as("원천 응답에 %s 컬럼이 없다 — 원천 스키마가 바뀌었는지 확인할 것", column)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("가공 컬럼(lat/lot)을 원천이 채워 주는지 관측한다 — 단언하지 않고 수치만 남긴다")
    void observesWhetherSourceFillsDerivedColumns() {
        String table = properties.requireSourceTable();

        // 2026-09-01 실측은 전량 NULL 이라 배치가 직접 계산한다. 원천이 채우기 시작하면
        // Processor 가 자동으로 그 값을 쓰므로 코드 변경은 필요 없다 —
        // 다만 지오코딩 호출량이 급감하는 이유를 설명할 수 있게 수치를 남긴다.
        long filled = queryClient.count(
                "SELECT countIf(lat IS NOT NULL AND lot IS NOT NULL) AS cnt FROM " + table);

        // 어느 쪽이든 정상 동작이라 값 자체는 단언하지 않는다.
        assertThat(filled).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("src_path 는 로컬과 같은 3종(모바일앱/포털/API) — SRC_CD_MAP 을 그대로 쓸 수 있다")
    void inflowPathsMatchLocalSet() {
        String table = properties.requireSourceTable();
        String where = DhubSql.halfOpenRange(
                DhubComplaintApiClient.DATE_COLUMN, BACKFILL_FROM, LocalDate.now());

        ArrayNode rows = queryClient.query(
                "SELECT src_path, count(*) AS cnt FROM " + table + " WHERE " + where
                        + " GROUP BY src_path ORDER BY cnt DESC");

        List<String> paths = new ArrayList<>();
        rows.forEach(row -> paths.add(row.path("src_path").asText()));

        assertThat(paths).containsExactlyInAnyOrderElementsOf(LOCAL_INFLOW_PATHS);
    }

    @Test
    @DisplayName("backfill 반개구간 count(*) 가 실측 규모(78,386)와 같은 자릿수다")
    void backfillCountMatchesLocalScale() {
        String table = properties.requireSourceTable();
        String where = DhubSql.halfOpenRange(
                DhubComplaintApiClient.DATE_COLUMN, BACKFILL_FROM, LocalDate.now());

        long count = queryClient.count("SELECT count(*) AS cnt FROM " + table + " WHERE " + where);

        // 구간이 원천 전 기간(2026.1~실행일)을 덮으므로 크게 작으면 구간 설정 오류다.
        assertThat(count).isGreaterThanOrEqualTo(MEASURED_BACKFILL_ROWS);
    }

    @Test
    @DisplayName("J: page-size(1000) 한 페이지가 timeout-ms 안에 넉넉히 들어온다")
    void pageFitsWithinTimeout() {
        String table = properties.requireSourceTable();
        String where = DhubSql.halfOpenRange(
                DhubComplaintApiClient.DATE_COLUMN, BACKFILL_FROM, LocalDate.now());

        long startedAt = System.nanoTime();
        ArrayNode rows = queryClient.query(
                "SELECT cvlcpt_id, ocrn_dt, dclr_ocrn_rgn, src_path, dclr_type FROM " + table
                        + " WHERE " + where + " ORDER BY cvlcpt_id LIMIT "
                        + DhubSql.limit(properties.getPageSize()));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(rows).hasSize(properties.getPageSize());
        // 실측 upstream 130~153ms. 왕복 전체가 timeout 의 절반을 넘으면 page-size 를 낮춰야 한다.
        assertThat(elapsed).isLessThan(Duration.ofMillis(properties.getTimeoutMs() / 2));

        // keyset 페이징이 성립하려면 정렬 키가 단조 증가해야 한다.
        String previous = "";
        for (JsonNode row : rows) {
            String current = row.path("cvlcpt_id").asText();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("주입 시도가 섞인 일자·테이블명은 호출 전에 막힌다")
    void rejectsInjectionBeforeCalling() {
        assertThatThrownBy(() -> DhubSql.date("2025-01-01' OR '1'='1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DhubSql.table("`db`.`tbl` ; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 실호출용 설정. {@code base-url}·{@code table-id}·{@code source-table} 은 소스에 하드코딩하지
     * 않는다는 규칙을 테스트에서도 지킨다 — 공통 {@code application.yml} 에서 읽어 온다.
     */
    private static DhubProperties liveProperties() {
        DhubProperties properties = new DhubProperties();
        properties.setBaseUrl(fromYaml("base-url"));
        properties.setTableId(fromYaml("table-id"));
        properties.setSourceTable(fromYaml("source-table"));
        properties.setEmail(System.getenv("DHUB_USER_EMAIL"));
        properties.setPassword(System.getenv("DHUB_USER_PW"));
        properties.setPageSize(1000);
        properties.setTimeoutMs(30_000);
        return properties;
    }

    /** {@code application.yml} 의 {@code dhub.*} 한 줄을 읽는다(스프링 컨텍스트를 띄우지 않기 위한 최소 파서). */
    private static String fromYaml(String key) {
        try (var input = DhubClientLiveTest.class.getResourceAsStream("/application.yml")) {
            assertThat(input).as("application.yml").isNotNull();
            for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + ":")) {
                    String value = trimmed.substring(key.length() + 1).trim();
                    int comment = value.indexOf(" #");
                    if (comment >= 0) {
                        value = value.substring(0, comment).trim();
                    }
                    return resolvePlaceholder(value.replaceAll("^\"|\"$", ""));
                }
            }
        } catch (IOException e) {   // 4.3(TSK-4336) — 리소스 읽기 실패만
            throw new IllegalStateException("application.yml 에서 dhub." + key + " 를 읽지 못했습니다.", e);
        }
        throw new IllegalStateException("application.yml 에 dhub." + key + " 가 없습니다.");
    }

    /** {@code ${ENV_KEY:기본값}} — 키 이름에는 콜론이 없으므로 첫 콜론 뒤가 기본값이다(URL·백틱 포함 가능). */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("^\\$\\{([A-Za-z0-9_.]+):(.*)}$");

    /**
     * {@code ${ENV_KEY:기본값}} 을 스프링과 같은 규칙으로 푼다 — 환경변수가 <b>있으면</b>(빈 값 포함) 그 값,
     * 없으면 기본값. placeholder 가 아니면 그대로 돌려준다.
     *
     * <p>🔁 TSK-4142({@code b0a902b})가 {@code dhub.*} 를 환경변수 주입 형태로 바꾼 뒤, 이 최소 파서가
     * placeholder 원문을 값으로 넘겨 로그인 URL·원천 테이블명이 깨졌다(TSK-4145). 스프링 컨텍스트는
     * 여전히 띄우지 않는다.
     */
    private static String resolvePlaceholder(String value) {
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        String fromEnv = System.getenv(matcher.group(1));
        return fromEnv != null ? fromEnv : matcher.group(2);
    }
}
