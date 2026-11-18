package jeju.datahub.batchops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jeju.datahub.support.IntegrationLoginSupport;

/**
 * 배치 운영 조회 API 통합테스트 (TSK-4337 Step 6) — {@code /api/ops/batch/**} · 인가 {@code M0604}.
 *
 * <p>🔴 <b>운영 필터체인을 켠다</b>({@code app.auth.security-enabled=true}) — 미인증 401 을 함께 보려면 test 체인
 * (permitAll)으로는 안 된다. 인가 판정은 {@code MenuAccessGuard} 가 매 요청 DB 를 보므로 {@code promoteTo} 로
 * 롤만 바꾸면 재로그인 없이 다음 요청이 통과한다.
 *
 * <p>🔴 <b>합성 행</b> — {@code batch} 스키마에 음수 ID(시퀀스와 절대 겹치지 않는다)와 접두 {@value #JOB_PREFIX}
 * 잡 이름으로 넣고 앞뒤로 지운다. 실데이터 기대값(잡 수·마지막 실행·스케줄)은 날짜·건수를 박지 않고 <b>DB 현재값</b>으로 계산한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.security-enabled=true")
class BatchOpsIntegrationTest {

    private static final String JOB_PREFIX = "tsk4337Test";
    private static final String HEAD_JOB = JOB_PREFIX + "HeadJob";
    private static final String CHAIN_JOB = JOB_PREFIX + "ChainJob";

    private static final long HEAD_INST = -4337001L;
    private static final long CHAIN_INST = -4337002L;

    /** 머리 잡 — 오래된 순: 성공(연쇄 1건) → 실패 → 실행 중(이력 행 없음) */
    private static final long E_SUCCESS = -4337020L;
    private static final long E_FAILED = -4337010L;
    private static final long E_RUNNING = -4337005L;
    /** 연쇄 잡 — up_excn_id 있음 / 없음(과거 연쇄 — 묶지 않는다) */
    private static final long E_CHAIN = -4337015L;
    private static final long E_CHAIN_ORPHAN = -4337016L;
    private static final long E_MISSING = -4337999L;

    private static final String BASE = "/api/ops/batch";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper om = new ObjectMapper();
    private IntegrationLoginSupport auth;

    @BeforeEach
    void setUp() {
        auth = IntegrationLoginSupport.forProductionChain(rest, jdbcTemplate);
        auth.markStart();
        deleteSynthetic();
        insertSynthetic();
    }

    @AfterEach
    void tearDown() {
        deleteSynthetic();
        auth.cleanUp();
    }

    // ---------------------------------------------------------------- 인가

    @Test
    @DisplayName("1. 미인증 — 세 GET 모두 401")
    void unauthenticatedIs401() {
        for (String url : List.of(BASE + "/jobs", BASE + "/executions", BASE + "/executions/" + E_FAILED)) {
            assertThat(rest.getForEntity(url, String.class).getStatusCode()).as(url).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("2. 일반 사용자(ROLE_USER — M0604 매핑 없음) — 세 GET 모두 403")
    void plainUserIs403() {
        HttpHeaders session = auth.mockSession();
        for (String url : List.of(BASE + "/jobs", BASE + "/executions", BASE + "/executions/" + E_FAILED)) {
            assertThat(get(session, url).getStatusCode()).as(url).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("3. 서비스관리자·시스템관리자 — 200")
    void adminsAre200() {
        HttpHeaders session = auth.mockSession();
        for (String role : List.of("ROLE_SERVICE_ADMIN", "ROLE_SYSTEM_ADMIN")) {
            auth.promoteTo(role);
            for (String url : List.of(BASE + "/jobs", BASE + "/executions", BASE + "/executions/" + E_FAILED)) {
                assertThat(get(session, url).getStatusCode()).as(role + " " + url).isEqualTo(HttpStatus.OK);
            }
        }
    }

    // ---------------------------------------------------------------- 잡 목록

    @Test
    @DisplayName("4. /jobs — 객체 {catalogAvailable=false, jobs}, 잡 집합 = DB 잡 이름, 마지막 실행·성공·스케줄이 DB 와 같다")
    void jobs() throws Exception {
        JsonNode body = adminGet(BASE + "/jobs");
        assertThat(body.isObject()).isTrue();
        assertThat(body.get("catalogAvailable").asBoolean()).isFalse();

        Set<String> dbNames = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT job_name FROM batch.batch_job_instance", String.class));
        List<String> names = new ArrayList<>();
        body.get("jobs").forEach(j -> names.add(j.get("jobNm").asText()));
        assertThat(names).hasSize(dbNames.size()).containsExactlyInAnyOrderElementsOf(dbNames);

        // 합성 머리 잡: 마지막 = 실행 중(이력 행 없음), 마지막 성공 = E_SUCCESS
        JsonNode head = job(body, HEAD_JOB);
        assertThat(head.get("lastExcn").get("jobExecutionId").asLong()).isEqualTo(E_RUNNING);
        assertThat(head.get("lastExcn").get("status").asText()).isEqualTo("STARTED");
        assertThat(head.get("lastExcn").get("hstryYn").asText()).isEqualTo("N");
        assertThat(head.get("lastSuccessExcnId").asLong()).isEqualTo(E_SUCCESS);
        assertThat(LocalDateTime.parse(head.get("lastSuccessDt").asText())).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT end_time FROM batch.batch_job_execution WHERE job_execution_id = ?", LocalDateTime.class,
                E_SUCCESS));
        assertThat(head.get("schedule").isNull()).isTrue();
        assertThat(head.get("jobKorNm").isNull()).as("카탈로그 칸은 병합 전 null").isTrue();

        // 실데이터: 실행 기록이 있는 아무 잡 1개 — 마지막 실행 ID 가 DB max 와 같다
        List<String> realWithExcn = jdbcTemplate.queryForList("""
                SELECT i.job_name FROM batch.batch_job_instance i
                  JOIN batch.batch_job_execution e ON e.job_instance_id = i.job_instance_id
                 WHERE i.job_name NOT LIKE 'tsk4337Test%' GROUP BY i.job_name ORDER BY 1 LIMIT 1""", String.class);
        for (String nm : realWithExcn) {
            Long maxId = jdbcTemplate.queryForObject("""
                    SELECT max(e.job_execution_id) FROM batch.batch_job_execution e
                      JOIN batch.batch_job_instance i ON i.job_instance_id = e.job_instance_id
                     WHERE i.job_name = ?""", Long.class, nm);
            assertThat(job(body, nm).get("lastExcn").get("jobExecutionId").asLong()).isEqualTo(maxId);
        }

        // 스케줄: 스케줄 표 행이 있는 잡은 cron·사용여부가 표와 같다
        jdbcTemplate.query("""
                SELECT s.job_nm, s.cron_expr, s.use_yn FROM batch.tb_btch_schdl s
                 WHERE EXISTS (SELECT 1 FROM batch.batch_job_instance i WHERE i.job_name = s.job_nm)""", rs -> {
            JsonNode sch = job(body, rs.getString(1)).get("schedule");
            assertThat(sch.get("cronExpr").asText()).isEqualTo(rs.getString(2));
            assertThat(sch.get("useYn").asText()).isEqualTo(rs.getString(3));
        });
    }

    // ---------------------------------------------------------------- 실행 이력

    @Test
    @DisplayName("5. /executions — 최신순·필터·하위 결과(up_excn_id 만)·메시지 마스킹+500자 절단")
    void executions() throws Exception {
        JsonNode page = adminGet(BASE + "/executions?jobNm=" + HEAD_JOB + "&size=10");
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isZero();
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(r -> ids.add(r.get("jobExecutionId").asLong()));
        assertThat(ids).containsExactly(E_RUNNING, E_FAILED, E_SUCCESS);

        JsonNode failed = page.get("content").get(1);
        String fm = failed.get("failureMessage").asText();
        assertThat(fm).contains("token=***").doesNotContain("SECRET123").endsWith("…");
        assertThat(fm).hasSize(501);
        assertThat(failed.get("exitMessage").asText()).contains("apikey=***").doesNotContain("abcdef");
        assertThat(failed.get("excnSeCd").asText()).isEqualTo("MANUAL");
        assertThat(failed.get("excutrId").asText()).isEqualTo("tester");
        assertThat(failed.get("writeCnt").asLong()).isEqualTo(3);
        assertThat(failed.get("subRslts")).isEmpty();

        JsonNode success = page.get("content").get(2);
        assertThat(success.get("subRslts")).hasSize(1);
        JsonNode sub = success.get("subRslts").get(0);
        assertThat(sub.get("subSeCd").asText()).isEqualTo("CHAIN");
        assertThat(sub.get("subNm").asText()).isEqualTo(CHAIN_JOB);
        assertThat(sub.get("jobExecutionId").asLong()).isEqualTo(E_CHAIN);
        assertThat(sub.get("writeCnt").asLong()).isEqualTo(5);
        assertThat(sub.has("upExcnId")).as("묶음 키는 응답에 싣지 않는다").isFalse();

        // 🔴 up_excn_id NULL 인 연쇄 실행은 어디에도 묶이지 않는다 — 자기 잡 목록에 단독 행
        JsonNode chain = adminGet(BASE + "/executions?jobNm=" + CHAIN_JOB);
        List<Long> chainIds = new ArrayList<>();
        chain.get("content").forEach(r -> chainIds.add(r.get("jobExecutionId").asLong()));
        assertThat(chainIds).containsExactly(E_CHAIN, E_CHAIN_ORPHAN);
        assertThat(chain.get("content").get(0).get("upExcnId").asLong()).isEqualTo(E_SUCCESS);
        assertThat(chain.get("content").get(1).get("upExcnId").isNull()).isTrue();

        // 상태 필터
        JsonNode onlyFailed = adminGet(BASE + "/executions?jobNm=" + HEAD_JOB + "&status=FAILED");
        assertThat(onlyFailed.get("totalElements").asLong()).isEqualTo(1);
        assertThat(onlyFailed.get("content").get(0).get("jobExecutionId").asLong()).isEqualTo(E_FAILED);

        // 필터 없음 — 전체 건수 = DB 현재값
        Long all = jdbcTemplate.queryForObject("SELECT count(*) FROM batch.batch_job_execution", Long.class);
        assertThat(adminGet(BASE + "/executions?size=1").get("totalElements").asLong()).isEqualTo(all);
    }

    @Test
    @DisplayName("6. /executions — page·size·status 검증 400")
    void executionsValidation() {
        HttpHeaders session = adminSession();
        for (String q : List.of("size=101", "size=0", "page=-1", "status=BOGUS")) {
            assertThat(get(session, BASE + "/executions?" + q).getStatusCode()).as(q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ---------------------------------------------------------------- 상세

    @Test
    @DisplayName("7. /executions/{id} — 지표·파라미터(마스킹)·Step 카운트·전체 메시지 · 진행 단계 · 404")
    void detail() throws Exception {
        HttpHeaders session = adminSession();

        JsonNode failed = json(get(session, BASE + "/executions/" + E_FAILED));
        String fm = failed.get("failureMessage").asText();
        assertThat(fm).contains("token=***").doesNotContain("SECRET123").doesNotEndWith("…");
        assertThat(fm.length()).isGreaterThan(600);
        assertThat(failed.get("geocodeApiLookups").asLong()).isEqualTo(11);
        assertThat(failed.get("unknownTypes").get("ETC").asInt()).isEqualTo(2);
        assertThat(failed.get("progress").isNull()).isTrue();
        assertThat(failed.get("steps")).hasSize(1);
        assertThat(failed.get("steps").get(0).get("exitMessage").asText()).contains("password=***");

        JsonNode success = json(get(session, BASE + "/executions/" + E_SUCCESS));
        assertThat(success.get("params")).hasSize(2);
        assertThat(success.get("params").get(0).get("parameterName").asText()).isEqualTo("apiKeyParam");
        assertThat(success.get("params").get(0).get("parameterValue").asText()).isEqualTo("key=***");
        assertThat(success.get("params").get(1).get("parameterValue").asText()).isEqualTo("2026-11-10");
        assertThat(success.get("subRslts")).hasSize(1);
        assertThat(success.get("steps").get(0).get("readCount").asLong()).isEqualTo(20);
        assertThat(success.get("steps").get(0).get("rollbackCount").asLong()).isEqualTo(1);

        JsonNode running = json(get(session, BASE + "/executions/" + E_RUNNING));
        JsonNode progress = running.get("progress");
        assertThat(progress.get("currentStep").asText()).isEqualTo("geocodeStep");
        assertThat(progress.get("cmptnStepCnt").asInt()).isEqualTo(1);
        assertThat(progress.get("readCount").asLong()).isEqualTo(1200);
        assertThat(progress.get("writeCount").asLong()).isEqualTo(1100);
        assertThat(progress.get("totalStepCnt").isNull()).as("전체 Step 수는 카탈로그(Step 7)").isTrue();
        assertThat(running.get("hstryYn").asText()).isEqualTo("N");

        assertThat(get(session, BASE + "/executions/" + E_MISSING).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 미설정 (Step 7)

    @Test
    @DisplayName("8. 배치 운영 미설정(base-url 빈 값) — 명령·로그 503 BATCH_OPS_NOT_CONFIGURED · 조회는 200")
    void notConfiguredIs503() throws Exception {
        HttpHeaders session = adminSession();
        HttpHeaders json = auth.json(session);
        for (Object[] c : List.<Object[]>of(
                new Object[] { HttpMethod.POST, BASE + "/jobs/complaintFetchJob/executions", java.util.Map.of() },
                new Object[] { HttpMethod.PUT, BASE + "/schedules/complaintFetchJob",
                        java.util.Map.of("cronExpr", "0 0 2 * * *", "useYn", "Y") },
                new Object[] { HttpMethod.POST, BASE + "/executions/" + E_RUNNING + "/stop", java.util.Map.of() },
                new Object[] { HttpMethod.POST, BASE + "/executions/" + E_RUNNING + "/abandon", java.util.Map.of() },
                new Object[] { HttpMethod.GET, BASE + "/executions/" + E_RUNNING + "/log?tailKb=200", null })) {
            ResponseEntity<String> res = rest.exchange((String) c[1], (HttpMethod) c[0], new HttpEntity<>(c[2], json),
                    String.class);
            assertThat(res.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(om.readTree(res.getBody()).get("code").asText()).isEqualTo("BATCH_OPS_NOT_CONFIGURED");
        }
        assertThat(json(get(session, BASE + "/jobs")).get("catalogAvailable").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- 헬퍼

    private HttpHeaders adminSession() {
        HttpHeaders session = auth.mockSession();
        auth.promoteTo("ROLE_SERVICE_ADMIN");
        return session;
    }

    private JsonNode adminGet(String url) throws Exception {
        return json(get(adminSession(), url));
    }

    private ResponseEntity<String> get(HttpHeaders session, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(session), String.class);
    }

    private JsonNode json(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode()).as(res.getBody()).isEqualTo(HttpStatus.OK);
        return om.readTree(res.getBody());
    }

    private static JsonNode job(JsonNode body, String jobNm) {
        for (JsonNode j : body.get("jobs")) {
            if (jobNm.equals(j.get("jobNm").asText())) {
                return j;
            }
        }
        throw new AssertionError("jobs 에 없음: " + jobNm);
    }

    private void insertSynthetic() {
        jdbcTemplate.update("INSERT INTO batch.batch_job_instance (job_instance_id, version, job_name, job_key) VALUES (?, 0, ?, 'k1'), (?, 0, ?, 'k2')",
                HEAD_INST, HEAD_JOB, CHAIN_INST, CHAIN_JOB);

        String exec = """
                INSERT INTO batch.batch_job_execution
                  (job_execution_id, version, job_instance_id, create_time, start_time, end_time, status, exit_code, exit_message, last_updated)
                VALUES (?, 1, ?, now() - (? * interval '1 minute'), now() - (? * interval '1 minute'),
                        CASE WHEN ? THEN NULL ELSE now() - (? * interval '1 minute') + interval '10 minute' END, ?, ?, ?, now())""";
        jdbcTemplate.update(exec, E_SUCCESS, HEAD_INST, 300, 300, false, 300, "COMPLETED", "COMPLETED", "");
        jdbcTemplate.update(exec, E_CHAIN, CHAIN_INST, 289, 289, false, 289, "COMPLETED", "COMPLETED", "");
        jdbcTemplate.update(exec, E_CHAIN_ORPHAN, CHAIN_INST, 250, 250, false, 250, "COMPLETED", "COMPLETED", "");
        jdbcTemplate.update(exec, E_FAILED, HEAD_INST, 200, 200, false, 200, "FAILED", "FAILED",
                "java.lang.IllegalStateException: upstream apikey=abcdef rejected\n\tat x.y.Z(Z.java:1)");
        jdbcTemplate.update(exec, E_RUNNING, HEAD_INST, 5, 5, true, 5, "STARTED", "UNKNOWN", "");

        jdbcTemplate.update("""
                INSERT INTO batch.batch_job_execution_params (job_execution_id, parameter_name, parameter_type, parameter_value, identifying)
                VALUES (?, 'targetDate', 'java.lang.String', '2026-11-10', 'Y'), (?, 'apiKeyParam', 'java.lang.String', 'key=zzz', 'N')""",
                E_SUCCESS, E_SUCCESS);

        String step = """
                INSERT INTO batch.batch_step_execution
                  (step_execution_id, version, step_name, job_execution_id, create_time, start_time, end_time, status,
                   commit_count, read_count, filter_count, write_count, read_skip_count, write_skip_count, process_skip_count,
                   rollback_count, exit_code, exit_message, last_updated)
                VALUES (?, 1, ?, ?, now(), now(), ?, ?, 2, ?, 0, ?, 0, 0, 1, ?, ?, ?, now())""";
        jdbcTemplate.update(step, -4337101L, "fetchStep", E_SUCCESS, LocalDateTime.now(), "COMPLETED", 20, 19, 1, "COMPLETED", "");
        jdbcTemplate.update(step, -4337102L, "fetchStep", E_FAILED, LocalDateTime.now(), "FAILED", 4, 3, 0, "FAILED",
                "org.x.AuthException: password=hunter2 denied");
        jdbcTemplate.update(step, -4337103L, "fetchStep", E_RUNNING, LocalDateTime.now(), "COMPLETED", 50, 50, 0, "COMPLETED", "");
        jdbcTemplate.update(step, -4337104L, "geocodeStep", E_RUNNING, null, "STARTED", 1200, 1100, 0, "EXECUTING", "");

        String hstry = """
                INSERT INTO batch.tb_btch_excn_hstry
                  (job_execution_id, job_nm, status, started_dt, ended_dt, duration_ms, read_cnt, write_cnt,
                   geocode_api_lookups, unknown_types, failure_message, excn_se_cd, excutr_id, up_excn_id)
                VALUES (?, ?, ?, now(), now(), 1000, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""";
        jdbcTemplate.update(hstry, E_SUCCESS, HEAD_JOB, "COMPLETED", 20, 19, null, null, null, "SCHDL", null, null);
        jdbcTemplate.update(hstry, E_CHAIN, CHAIN_JOB, "COMPLETED", 5, 5, null, null, null, "CHAIN", null, E_SUCCESS);
        jdbcTemplate.update(hstry, E_CHAIN_ORPHAN, CHAIN_JOB, "COMPLETED", 1, 1, null, null, null, null, null, null);
        jdbcTemplate.update(hstry, E_FAILED, HEAD_JOB, "FAILED", 4, 3, 11L, "{\"ETC\": 2}",
                "upstream failed token=SECRET123 " + "x".repeat(700), "MANUAL", "tester", null);
        // E_RUNNING 은 이력 행 없음 — 이력 기록이 실패한 실행도 보여야 한다(LEFT JOIN)
    }

    private void deleteSynthetic() {
        String ids = "(SELECT e.job_execution_id FROM batch.batch_job_execution e JOIN batch.batch_job_instance i"
                + " ON i.job_instance_id = e.job_instance_id WHERE i.job_name LIKE 'tsk4337Test%')";
        jdbcTemplate.update("DELETE FROM batch.tb_btch_excn_hstry WHERE job_nm LIKE 'tsk4337Test%' OR job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_step_execution WHERE job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_job_execution_params WHERE job_execution_id IN " + ids);
        jdbcTemplate.update("DELETE FROM batch.batch_job_execution WHERE job_instance_id IN"
                + " (SELECT job_instance_id FROM batch.batch_job_instance WHERE job_name LIKE 'tsk4337Test%')");
        jdbcTemplate.update("DELETE FROM batch.batch_job_instance WHERE job_name LIKE 'tsk4337Test%'");
    }
}
