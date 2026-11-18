package jeju.datahub.batch.sftychck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jeju.datahub.batch.cvlcpt.api.DhubDatasetCount;
import jeju.datahub.batch.cvlcpt.api.DhubKeysetPage;
import jeju.datahub.batch.sftychck.api.DhubPkltSftyChckApiClient;
import jeju.datahub.batch.sftychck.api.PkltSftyChckOriginRow;

/**
 * {@code pkltSftyChckFetchJob} 통합 검증 — <b>수집 → 시드 정리</b> 한 Job (TSK-4254 Step 8).
 *
 * <h2>🔴 정리 Tasklet 하나만 픽스처 범위로 교체한다</h2>
 * Job 은 자기 트랜잭션으로 커밋하므로 테스트 롤백이 안 먹는다. 운영 Tasklet 그대로 돌리면 시드 전환 케이스에서
 * {@code rgtr_id='admin'} <b>전체</b>(로컬 시드 4,514행)를 지운다. 그래서 중첩 {@link FixtureConfig}
 * ({@code @TestConfiguration})가 삭제 대상을 픽스처 주차장 {@code pklt_id} 범위로 좁힌 Tasklet 을 {@code @Primary} 로
 * 덮는다(선례 {@code CrackdownFetchJobTest}). 원천은 {@link DhubPkltSftyChckApiClient} 를 목으로 바꿔 흘린다 —
 * Reader·Processor·리졸버·Writer·스킵 리스너·이력 리스너는 운영 빈이다(리졸버는 실 {@code tb_pblpklt} 를 읽기만 한다).
 * <ul>
 *   <li>교체가 먹었는지 매 케이스 시작에 단언한다 — 운영 빈이 주입되면 Job 을 돌리기 전에 멈춘다.</li>
 *   <li>마지막 방어선으로 <b>픽스처 밖 안전점검 행(등록자별 건수)·SFTYCHCK 스킵 건수가 클래스 시작 시점과 같음</b>을
 *       매 케이스 끝에 단언한다.</li>
 * </ul>
 *
 * <h2>픽스처</h2>
 * 주차장은 {@code origin_pk} 접두 {@value #ORIGIN_PK_PREFIX}, 원천 행 키({@code sfty_chck_id} = 스킵 {@code trgt_id})는
 * {@link #SRC_IDS} 범위다. 정리는 자식(안전점검) → 스킵 → 부모(주차장) 순이며 전부 그 범위로만 좁힌다.
 * 실행 이력 표({@code batch.tb_btch_excn_hstry})는 지우지 않는다(선례 {@code CrackdownFetchJobTest}).
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — {@code REQUIRES_NEW} 로 커밋되는 스킵 기록을 관찰해야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // Reader.open() 이 API 호출 전에 requireCredentials() 를 부른다(gradle test 는 루트 .env 를 읽지 않는다).
        "dhub.email=test@example.com",
        "dhub.password=test",
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class PkltSftyChckFetchJobTest {

    private static final String ORIGIN_PK_PREFIX = "TEST-SFTY-JOB-";

    /** 🔴 정리·삭제 범위의 단일 출처 — 픽스처 주차장의 {@code pklt_id}. */
    static final String FIXTURE_SCOPE =
            "SELECT pklt_id FROM parking.tb_pblpklt WHERE origin_pk LIKE '" + ORIGIN_PK_PREFIX + "%'";

    /** 원천 행 키 — 실 원천({@code 1..4514} 대)과 겹치지 않는 범위. */
    private static final long SRC_SAME_KEY = 990_000_001L;
    private static final long SRC_NEW_KEY = 990_000_002L;
    private static final long SRC_NOT_FOUND = 990_000_003L;
    private static final long SRC_YMD_INVALID = 990_000_004L;
    private static final long[] SRC_IDS = {SRC_SAME_KEY, SRC_NEW_KEY, SRC_NOT_FOUND, SRC_YMD_INVALID};
    private static final String SRC_IDS_IN = Arrays.stream(SRC_IDS)
            .mapToObj(id -> "'" + id + "'").collect(Collectors.joining(","));

    /** 정규화(읍면 접두 제거·괄호/공백 제거) 뒤에도 실 주차장과 겹치지 않는 이름·주소. */
    private static final String NM_A = "테스트안전점검잡주차장가";
    private static final String ADDR_A = "테스트안전점검잡로 1";
    private static final String NM_B = "테스트안전점검잡주차장나";
    private static final String ADDR_B = "테스트안전점검잡로 2";

    /** 시드 행 — A 는 원천과 같은 키(인수 대상), B 는 원천에 없는 키(삭제 대상). */
    private static final String SEED_SAME_YMD = "20260301";
    private static final String SEED_OTHER_YMD = "20250101";

    private static final String REAL_ROWS_SQL = "SELECT rgtr_id, count(*) AS cnt FROM parking.tb_pklt_sfty_chck"
            + " WHERE pklt_id NOT IN (" + FIXTURE_SCOPE + ") GROUP BY rgtr_id ORDER BY rgtr_id";

    private static final String REAL_SKIP_SQL = "SELECT count(*) FROM batch.tb_job_skip"
            + " WHERE job_se = 'SFTYCHCK' AND trgt_id NOT IN (" + SRC_IDS_IN + ")";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 클래스 시작 시점의 실 DB 수치 — 첫 {@code @BeforeEach} 에서 한 번만 잡는다(하드코딩 금지). */
    private static List<Map<String, Object>> realRowsAtStart;
    private static Long realSkipsAtStart;

    @TestConfiguration
    static class FixtureConfig {

        /** 🔴 시드 정리 DELETE 를 픽스처 주차장 범위로 좁힌다 — 운영 빈은 필터가 없다. */
        @Bean
        @Primary
        PkltSftyChckSeedCleanupTasklet fixtureSeedCleanupTasklet(JdbcTemplate jdbc) {
            return new PkltSftyChckSeedCleanupTasklet(jdbc, FIXTURE_SCOPE);
        }
    }

    @MockitoBean
    private DhubPkltSftyChckApiClient apiClient;

    @Autowired
    private PkltSftyChckSeedCleanupTasklet seedCleanupTasklet;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job pkltSftyChckFetchJob;

    @Autowired
    private JdbcTemplate jdbc;

    private long lotA;
    private long lotB;

    @BeforeEach
    void setUp() {
        deleteFixtures();
        if (realRowsAtStart == null) {
            realRowsAtStart = jdbc.queryForList(REAL_ROWS_SQL);
            realSkipsAtStart = jdbc.queryForObject(REAL_SKIP_SQL, Long.class);
        }

        // 🔴 교체가 먹었는지 — 운영 Tasklet 이 주입되면 시드 4,514행이 지워진다. Job 을 돌리기 전에 멈춘다.
        assertThat(seedCleanupTasklet.pkltIdScopeSql()).isEqualTo(FIXTURE_SCOPE);

        String emdCd = jdbc.queryForObject("SELECT min(emd_cd) FROM parking.tb_admdst", String.class);
        lotA = insertLot(NM_A, ADDR_A, emdCd, "A");
        lotB = insertLot(NM_B, ADDR_B, emdCd, "B");
        insertSeed(lotA, SEED_SAME_YMD);
        insertSeed(lotB, SEED_OTHER_YMD);

        when(apiClient.detectPkltIdColumn()).thenReturn(false);
        when(apiClient.count(any())).thenReturn(DhubDatasetCount.UNAVAILABLE);
    }

    @AfterEach
    void tearDown() {
        deleteFixtures();
        // 🔴 실 DB 불변 — 픽스처 밖 안전점검 행(등록자별)·스킵이 클래스 시작 시점과 같다.
        assertThat(jdbc.queryForList(REAL_ROWS_SQL)).isEqualTo(realRowsAtStart);
        assertThat(jdbc.queryForObject(REAL_SKIP_SQL, Long.class)).isEqualTo(realSkipsAtStart);
    }

    /** 🔴 자식 → 스킵 → 부모. 전부 픽스처 범위로만 좁힌다. */
    private void deleteFixtures() {
        jdbc.update("DELETE FROM parking.tb_pklt_sfty_chck WHERE pklt_id IN (" + FIXTURE_SCOPE + ")");
        jdbc.update("DELETE FROM batch.tb_job_skip WHERE job_se = 'SFTYCHCK' AND trgt_id IN (" + SRC_IDS_IN + ")");
        jdbc.update("DELETE FROM parking.tb_pblpklt WHERE origin_pk LIKE ?", ORIGIN_PK_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("① 적재 2 · 스킵 2 · 🔴 시드 2행 유지 · 관측 2행 추가 · 이력 1행")
    void loadsSkipsAndRetiresSeed() throws Exception {
        givenSource(
                row(SRC_SAME_KEY, NM_A, ADDR_A, 20260301L, "REGULAR", "A"),     // 시드 A 와 같은 자연키
                row(SRC_NEW_KEY, NM_B, ADDR_B, 20260701L, "REGULAR", "B"),      // 새 키
                row(SRC_NOT_FOUND, "테스트안전점검잡없는주차장", "테스트안전점검잡로 999", 20260301L, "REGULAR", "A"),
                row(SRC_YMD_INVALID, NM_A, ADDR_A, 20261332L, "REGULAR", "A")); // 13월 32일

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames(execution)).containsExactly(
                PkltSftyChckFetchJobConfig.STEP_NAME);
        StepExecution fetch = step(execution, PkltSftyChckFetchJobConfig.STEP_NAME);
        assertThat(fetch.getReadCount()).isEqualTo(4);
        assertThat(fetch.getWriteCount()).isEqualTo(2);
        assertThat(fetch.getProcessSkipCount()).isEqualTo(2);
        assertThat(fetch.getFilterCount()).isZero();   // Processor 는 null 을 반환하지 않는다

        List<Map<String, Object>> rows = fixtureRows();
        assertThat(rows).hasSize(4);
        assertThat(rows.stream().filter(r -> "admin".equals(r.get("rgtr_id"))).count()).isEqualTo(2);
        assertThat(rows.stream().filter(r -> "BATCH".equals(r.get("rgtr_id"))).count()).isEqualTo(2);
        assertThat(stepNames(execution)).doesNotContain(PkltSftyChckFetchJobConfig.SEED_CLEANUP_STEP_NAME);

        assertThat(fixtureSkips()).containsExactly(
                Map.of("trgt_id", String.valueOf(SRC_NOT_FOUND), "fail_rsn", PkltSftyChckSkippableException.PKLT_NOT_FOUND),
                Map.of("trgt_id", String.valueOf(SRC_YMD_INVALID), "fail_rsn", PkltSftyChckSkippableException.YMD_INVALID));

        assertThat(historyCount(execution)).isEqualTo(1L);
    }

    @Test
    @DisplayName("② 재실행 — 관측 2행 추가 · 스킵 기록 유지, 실행마다 이력 1행")
    void rerunIsIdempotent() throws Exception {
        givenSource(
                row(SRC_SAME_KEY, NM_A, ADDR_A, 20260301L, "REGULAR", "A"),
                row(SRC_NEW_KEY, NM_B, ADDR_B, 20260701L, "REGULAR", "B"),
                row(SRC_NOT_FOUND, "테스트안전점검잡없는주차장", "테스트안전점검잡로 999", 20260301L, "REGULAR", "A"));

        JobExecution first = runJob();
        List<Map<String, Object>> afterFirst = fixtureRows();
        int skipsAfterFirst = fixtureSkips().size();

        JobExecution second = runJob();

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 🔴 재실행 때 전량 DUP_KEY 가 되면(리졸버 키 집합이 안 비워짐) 적재가 0 이다.
        assertThat(step(second, PkltSftyChckFetchJobConfig.STEP_NAME).getWriteCount()).isEqualTo(2);
        assertThat(fixtureRows()).hasSize(afterFirst.size() + 2);
        assertThat(fixtureSkips()).hasSize(skipsAfterFirst).hasSize(1);
        assertThat(historyCount(first)).isEqualTo(1L);
        assertThat(historyCount(second)).isEqualTo(1L);
    }

    @Test
    @DisplayName("③ 🔴 원천 0건 → 수집 Step 만 돌고 admin 시드 2행 유지")
    void keepsSeedWhenSourceEmpty() throws Exception {
        givenSource();

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames(execution)).containsExactly(
                PkltSftyChckFetchJobConfig.STEP_NAME);
        assertSeedIntact();
    }

    @Test
    @DisplayName("④ 🔴 전량 스킵(PKLT_NOT_FOUND) → 읽음은 있어도 적재 0 이라 admin 시드가 남는다(readCount 로 판정하지 않는다)")
    void keepsSeedWhenAllSkipped() throws Exception {
        givenSource(row(SRC_NOT_FOUND, "테스트안전점검잡없는주차장", "테스트안전점검잡로 999", 20260301L, "REGULAR", "A"));

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        StepExecution fetch = step(execution, PkltSftyChckFetchJobConfig.STEP_NAME);
        assertThat(fetch.getReadCount()).isEqualTo(1);
        assertThat(fetch.getWriteCount()).isZero();
        assertSeedIntact();
        assertThat(fixtureSkips()).hasSize(1);
    }

    @Test
    @DisplayName("⑤ 수집 Step FAILED(원천 필수 컬럼 누락) → 정리 Step 미실행 · admin 시드 유지")
    void doesNotCleanupWhenFetchFails() throws Exception {
        ObjectNode broken = row(SRC_SAME_KEY, NM_A, ADDR_A, 20260301L, "REGULAR", "A");
        broken.remove("sfty_chck_rslt_cd");
        givenSource(broken);

        JobExecution execution = runJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepNames(execution)).containsExactly(PkltSftyChckFetchJobConfig.STEP_NAME);
        assertSeedIntact();
        assertThat(historyCount(execution)).isEqualTo(1L);   // FAILED 실행도 이력이 남는다
    }

    // ---------------------------------------------------------------------------------------------

    private long insertLot(String nm, String addr, String emdCd, String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO parking.tb_pblpklt (pklt_nm, pklt_addr, emd_cd, lat, lot, origin_pk)
                VALUES (?, ?, ?, 33.5, 126.5, ?)
                RETURNING pklt_id
                """, Long.class, nm, addr, emdCd, ORIGIN_PK_PREFIX + suffix);
    }

    private void insertSeed(long pkltId, String ymd) {
        jdbc.update("""
                INSERT INTO parking.tb_pklt_sfty_chck
                       (pklt_id, chck_ymd, chck_prd_cd, sfty_chck_type_cd, sfty_chck_rslt_cd, rmrk_cn, rgtr_id, reg_dt)
                VALUES (?, ?, 'H1', 'REGULAR', 'C', '시드', ?, TIMESTAMP '2020-01-01 00:00:00')
                """, pkltId, ymd, PkltSftyChckItemWriter.SEED_RGTR_ID);
    }

    private void assertSeedIntact() {
        List<Map<String, Object>> rows = fixtureRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("rgtr_id")).isEqualTo(PkltSftyChckItemWriter.SEED_RGTR_ID));
    }

    private JobExecution runJob() throws Exception {
        return jobLauncher.run(pkltSftyChckFetchJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())   // 매 실행 새 JobInstance
                .toJobParameters());
    }

    /** keyset 페이지 1장(마지막 페이지)으로 준다. 컬럼 감지가 {@code false} 라 {@code pklt_id} 는 싣지 않는다. */
    private void givenSource(ObjectNode... rows) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode r : rows) {
            array.add(r);
        }
        when(apiClient.keysetQuery(anyBoolean())).thenReturn(null);
        when(apiClient.fetchPage(any(), isNull(), anyInt()))
                .thenReturn(new DhubKeysetPage(array, false, array.isEmpty() ? null : "1"));
    }

    /** {@link PkltSftyChckOriginRow#REQUIRED_COLUMNS} 를 모두 가진 원천 1행({@code chck_prd_cd} 는 null → 점검월로 유도). */
    private static ObjectNode row(long id, String nm, String addr, long ymd, String type, String rslt) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("sfty_chck_id", id);
        node.put("chck_ymd", ymd);
        node.putNull("chck_prd_cd");
        node.put("sfty_chck_type_cd", type);
        node.put("sfty_chck_rslt_cd", rslt);
        node.put("rmrk_cn", "테스트");
        node.put("pklt_nm", nm);
        node.put("pklt_addr", addr);
        assertThat(PkltSftyChckOriginRow.REQUIRED_COLUMNS).allMatch(node::has);
        return node;
    }

    private List<Map<String, Object>> fixtureRows() {
        return jdbc.queryForList("""
                SELECT pklt_id, chck_ymd, chck_prd_cd, sfty_chck_type_cd, sfty_chck_rslt_cd, rgtr_id
                  FROM parking.tb_pklt_sfty_chck
                 WHERE pklt_id IN (""" + FIXTURE_SCOPE + ") ORDER BY pklt_id, chck_ymd");
    }

    private List<Map<String, Object>> fixtureSkips() {
        return jdbc.queryForList("SELECT trgt_id, fail_rsn FROM batch.tb_job_skip"
                + " WHERE job_se = 'SFTYCHCK' AND trgt_id IN (" + SRC_IDS_IN + ") ORDER BY trgt_id");
    }

    private long historyCount(JobExecution execution) {
        return jdbc.queryForObject("SELECT count(*) FROM batch.tb_btch_excn_hstry WHERE job_execution_id = ?",
                Long.class, execution.getId());
    }

    private static List<String> stepNames(JobExecution execution) {
        return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
    }

    private static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream()
                .filter(s -> name.equals(s.getStepName())).findFirst().orElseThrow();
    }
}
