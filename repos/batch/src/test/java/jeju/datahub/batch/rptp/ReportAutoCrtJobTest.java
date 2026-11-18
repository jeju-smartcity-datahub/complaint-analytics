package jeju.datahub.batch.rptp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ④{@code reportAutoCrtJob} 통합 검증 — <b>Reader 필터 → 도래 판정 → 응답 5종 분기</b>
 * (TSK-4027 Step 10 · 케이스 1~9).
 *
 * <p>계획서 케이스 표 11행 중 <b>1~9</b>를 여기서 본다. 케이스 10(실소켓 chunked)은
 * {@link BackendReportTransportTest}, 케이스 11(스케줄 플래그 off)은
 * {@link ReportAutoCrtSchedulerTest} 가 맡는다.
 *
 * <p>🔴 <b>케이스 7(도래 판정 표 7행)은 여기서 다시 쓰지 않는다.</b> 산식·경계값은
 * {@link ReportDueDateResolverTest} 가 이미 19케이스로 고정한다 — 여기서는 Job 레벨의 계약,
 * 즉 <b>"도래하지 않는 날은 아무것도 보내지 않는다(written=0)"</b> 만 확인한다. 같은 표를 두 곳에
 * 적으면 규칙이 바뀔 때 한쪽만 고쳐져 <b>어느 쪽이 정본인지 알 수 없어진다.</b>
 *
 * <h2>🔴 테스트 위생 — 테스트가 만든 것만 테스트가 치운다</h2>
 * <ul>
 *   <li>착수 시점의 살아 있는 설정(예: {@code stng_id=100})을 <b>지우지도, {@code last_crt_dt} 를
 *       바꾸지도 않는다.</b> 테스트는 {@code max(stng_id)} 를 기준선({@link #baseStngId})으로 잡고
 *       <b>그보다 큰 ID 만</b> 만들고 지운다. 기준선을 못 읽으면({@link #BASE_UNSET}) 정리 자체를
 *       건너뛴다 — 🔴 조건 없는 {@code DELETE} 가 실데이터를 쓸어가는 것을 막는 가드다.</li>
 *   <li>기준일 {@link #BASE_YMD} 는 <b>일자가 15일</b>이다. 실설정의 배포기준일이 1일이라
 *       <b>도래 판정 자체가 성립하지 않아</b> 실설정은 읽히기만 하고 백엔드로 나가지 않는다.
 *       그래도 믿지 않고 {@code @BeforeEach}/{@code @AfterEach} 가 기준선 이하 행들의
 *       {@code last_crt_dt} 스냅샷을 떠서 <b>한 칸이라도 바뀌면 실패</b>시킨다.</li>
 *   <li>{@code batch.tb_job_skip} 은 <b>잡 공통 표</b>라 {@code job_se='RPTP'} 와
 *       <b>테스트가 만든 {@code trgt_id} 목록</b>으로만 좁혀 지운다.</li>
 *   <li>{@code batch.tb_btch_excn_hstry} 는 지우지 않는다(선례 {@code RiskAlertJudgeJobTest} 와 동일 —
 *       실행 이력은 append-only 다).</li>
 * </ul>
 *
 * <h2>🔴 백엔드를 실제로 부르지 않는다</h2>
 * {@link BackendReportClient} 를 {@code @MockitoBean} 으로 덮는다. HTTP 계약은
 * {@link BackendReportClientTest}·{@link BackendReportTransportTest} 가 이미 지므로, 여기서는
 * <b>상태코드가 Job 정책(갱신/스킵/중단)으로 어떻게 번역되는가</b>만 본다.
 * 소켓을 열지 않으므로 <b>사용자가 띄운 8180 백엔드를 건드리지 않는다.</b>
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다</b> — Job 은 자기 트랜잭션에서 커밋하므로 테스트
 * 트랜잭션으로 감싸도 롤백되지 않고, 오히려 {@code REQUIRES_NEW} 로 커밋되는 스킵 기록
 * ({@link ReportAutoCrtSkipListener})을 관찰할 수 없게 된다(선례 {@code ComplaintFetchJobTest}).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 청크 1 — 스킵이 "그 행만" 걷히는 경로(청크 롤백 + 스캔)를 반드시 지나가게 한다.
        //          Reader 페이지 크기도 같은 키라 페이징까지 함께 돈다.
        "batch.rptp.chunk-size=1",
        // 테스트 컨텍스트에 크론이 등록될 필요는 없다.
        "complaint.sync.scheduled=false",
        "pblpklt.sync.scheduled=false",
        "rptp.sync.scheduled=false",
        "utztn.sync.scheduled=false",
        "sftychck.sync.scheduled=false",
})
class ReportAutoCrtJobTest {

    /** 기준선을 읽지 못했음을 뜻하는 값. 🔴 이 상태에서는 어떤 DELETE 도 돌리지 않는다. */
    private static final long BASE_UNSET = -1L;

    /**
     * 기준일. 🔴 <b>일자가 15일</b>이라 배포기준일이 1일인 실설정은 도래하지 않는다.
     * 과거 고정일이라 "오늘"에 따라 결과가 흔들리지도 않는다.
     */
    private static final LocalDate BASE_YMD = LocalDate.of(2026, 5, 15);

    /** 도래하지 않는 날(케이스 7) — 테스트 설정의 배포기준일 15일과 어긋난다. */
    private static final LocalDate NOT_DUE_YMD = LocalDate.of(2026, 5, 16);

    /** 테스트 설정의 배포기준일. {@code ck_rptp_stng_crltn_dd} 가 1~28 만 허용한다. */
    private static final int TEST_DD = 15;

    private static final String INSERT_SQL = """
            INSERT INTO parking.tb_rptp_auto_crt_stng
              (stng_id, rptp_type_cd, crltn_cycl_cd, crltn_mm, crltn_dd, crltn_dept_cd,
               sgg_nm, actvtn_yn, creatr_id, creatr_nm, crt_dt, del_dt)
            VALUES (?, ?, ?, ?, ?, 'ALL', '제주시', ?, 'RPTPTEST', '자동테스트', now(), ?)""";

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job reportAutoCrtJob;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 🔴 목이다 — 실제 백엔드로 소켓을 열지 않는다. 스텁하지 않으면 {@code null} 을 돌려줘
     * Writer 가 NPE 로 죽으므로 <b>모든 케이스가 반드시 스텁한다.</b>
     */
    @MockitoBean
    private BackendReportClient client;

    /** 착수 시점의 {@code max(stng_id)}. 테스트 설정은 전부 이 값보다 크다. */
    private long baseStngId = BASE_UNSET;

    /** 이 테스트가 만든 설정 ID — 스킵 표 정리에도 쓴다. */
    private final List<Long> createdStngIds = new ArrayList<>();

    /** 기준선 이하(= 실설정) 행들의 {@code last_crt_dt} 스냅샷. 한 칸이라도 바뀌면 실패시킨다. */
    private List<Map<String, Object>> baselineSnapshot = List.of();

    @BeforeEach
    void captureBaseline() {
        Long max = jdbc.queryForObject(
                "SELECT coalesce(max(stng_id), 0) FROM parking.tb_rptp_auto_crt_stng", Long.class);
        baseStngId = max == null ? BASE_UNSET : max;
        assertThat(baseStngId)
                .as("기준선(max(stng_id))을 읽지 못하면 정리 범위를 정할 수 없어 아무것도 심지 않는다")
                .isNotEqualTo(BASE_UNSET);
        baselineSnapshot = snapshotBaseline();
        createdStngIds.clear();
    }

    @AfterEach
    void cleanUp() {
        if (baseStngId == BASE_UNSET) {
            return;   // 🔴 기준선이 없으면 어떤 DELETE 도 돌리지 않는다.
        }
        for (Long stngId : createdStngIds) {
            jdbc.update("DELETE FROM batch.tb_job_skip WHERE job_se = ? AND trgt_id = ?",
                    ReportAutoCrtSkipListener.JOB_SE, String.valueOf(stngId));
        }
        jdbc.update("DELETE FROM parking.tb_rptp_auto_crt_stng WHERE stng_id > ?", baseStngId);

        // 🔴 실설정의 last_crt_dt 가 한 칸이라도 바뀌었으면 이 테스트는 실패해야 한다.
        assertThat(snapshotBaseline())
                .as("테스트가 착수 시점 설정의 last_crt_dt 를 건드렸다 — 기준일·도래 판정을 다시 볼 것")
                .isEqualTo(baselineSnapshot);
    }

    // ------------------------------------------------------------------ 케이스 1

    @Test
    @DisplayName("1. 201 이면 last_crt_dt 가 갱신되고 written=1 이다")
    void updatesLastCrtDtOn201() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(201, true);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(writeCount(execution)).isEqualTo(1);
        // 🔴 여기가 케이스 1의 핵심 — 다음 실행이 같은 보고서를 또 만들지 않게 하는 표식이다.
        assertThat(lastCrtDt(stngId)).isNotNull();
        assertThat(skipRows(stngId)).isEmpty();
        // 보낸 내용도 확인한다 — 기준일 2026-05-15 의 월간 직전 구간은 2026-04 다.
        ReportAutoCrtCommand sent = captureSent();
        assertThat(sent.stngId()).isEqualTo(stngId);
        assertThat(sent.trgtPrdCd()).isEqualTo("2026-04");
        assertThat(sent.bgngYmd()).isEqualTo("20260401");
        assertThat(sent.endYmd()).isEqualTo("20260430");
    }

    // ------------------------------------------------------------------ 케이스 2

    @Test
    @DisplayName("2. 🔴 200 created=false 는 통과하되 last_crt_dt 를 갱신하지 않는다 — 만든 적이 없기 때문이다")
    void doesNotTouchLastCrtDtOn200() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(200, false);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 스킵이 아니다 — Writer 가 정상 반환하므로 write 로 집계된다.
        assertThat(writeCount(execution)).isEqualTo(1);
        // 🔴 갱신하면 "이번에 만들었다"는 거짓 표식이 남아 운영 조회가 어긋난다.
        assertThat(lastCrtDt(stngId)).isNull();
        assertThat(skipRows(stngId)).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 3

    @Test
    @DisplayName("3. 400 은 그 설정만 스킵하고 batch.tb_job_skip 에 1행을 남긴다 — Job 은 COMPLETED")
    void skipsAndRecordsOn400() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(400, null);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(stngId)).isNull();

        List<Map<String, Object>> skips = skipRows(stngId);
        assertThat(skips).hasSize(1);
        Map<String, Object> skip = skips.get(0);
        assertThat(skip.get("fail_rsn")).isEqualTo(ReportAutoCrtSkippableException.BAD_REQUEST);
        // 🔴 trgt_de 는 DATE, trgt_id 는 VARCHAR 다 — 형이 어긋나면 같은 날 재실행이 중복 행을 만든다.
        assertThat(skip.get("trgt_de")).isEqualTo(java.sql.Date.valueOf(BASE_YMD));
        assertThat(skip.get("trgt_id")).isEqualTo(String.valueOf(stngId));
        // 🔴 청크 롤백에 휩쓸리지 않아야 남는다 — REQUIRES_NEW 가 그 장치다.
        assertThat((String) skip.get("note")).contains("httpStatus=400").contains("2026-04");
    }

    // ------------------------------------------------------------------ 케이스 4

    @Test
    @DisplayName("4. 🔴 500 은 스킵하되 기록하지 않는다 — 일시 실패를 굳히면 다음 실행이 영원히 건너뛴다")
    void skipsWithoutRecordingOn500() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(500, null);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(stngId)).isNull();
        // 🔴 여기가 케이스 4의 핵심 — 기록이 남으면 그 설정이 영구 제외로 굳는다.
        assertThat(skipRows(stngId)).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 5

    @Test
    @DisplayName("5. 🔴 401 은 스킵이 아니라 Job FAILED 다 — 전 설정이 같은 이유로 실패할 상황이다")
    void failsJobOn401() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(401, null);

        JobExecution execution = run(BASE_YMD);

        // skip() 목록에 넣으면 여기가 COMPLETED 가 된다 — 그러면 "성공했는데 한 건도 안 만들었다"가 된다.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(t -> assertThat(rootMessage(t)).contains("401"));
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(stngId)).isNull();
        // 행 단위 확정 실패가 아니므로 스킵 기록도 남지 않는다.
        assertThat(skipRows(stngId)).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 6

    @Test
    @DisplayName("6. 🔴 503 도 Job FAILED 다 — 백엔드가 못 뜬 상태로 전량을 조용히 건너뛰지 않는다")
    void failsJobOn503() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(503, null);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(t -> assertThat(rootMessage(t)).contains("503"));
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(stngId)).isNull();
        assertThat(skipRows(stngId)).isEmpty();
    }

    // ------------------------------------------------------------------ 케이스 7

    @Test
    @DisplayName("7. 도래하지 않는 날은 읽되 한 건도 보내지 않는다 — written=0 (판정 표 자체는 ReportDueDateResolverTest)")
    void sendsNothingWhenNotDue() throws Exception {
        long stngId = insertStng("MONTH", null, TEST_DD, true, false);
        stub(201, true);

        // 배포기준일 15일 설정을 16일에 돌린다.
        JobExecution execution = run(NOT_DUE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 🔴 Reader 는 읽는다(살아 있는 설정이므로) — 걸러 내는 것은 Processor 다.
        assertThat(readCount(execution)).isEqualTo(aliveCount());
        assertThat(writeCount(execution)).isZero();
        verify(client, never()).autoCreate(any());
        assertThat(lastCrtDt(stngId)).isNull();
    }

    @Test
    @DisplayName("7-1. 분기 설정은 기준월이 어긋나면 같은 일자여도 보내지 않는다 — 판정이 Job 에 실제로 물려 있다")
    void quarterlySettingRespectsAnchorMonth() throws Exception {
        // 기준월 1월(=1·4·7·10월 배포) 설정을 5월에 돌린다 → 도래하지 않는다.
        long offMonth = insertStng("QUARTER", 1, TEST_DD, true, false);
        // 기준월 2월(=2·5·8·11월 배포) 설정은 5월에 도래한다.
        long onMonth = insertStng("QUARTER", 2, TEST_DD, true, false);
        stub(201, true);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(writeCount(execution)).isEqualTo(1);
        assertThat(sentStngIds()).containsExactly(onMonth);
        assertThat(lastCrtDt(offMonth)).isNull();
        assertThat(lastCrtDt(onMonth)).isNotNull();
    }

    // ------------------------------------------------------------------ 케이스 8

    @Test
    @DisplayName("8. 🔴 del_dt 가 세팅된 설정은 Reader 가 읽지 않는다 — 삭제한 설정이 계속 보고서를 만들면 안 된다")
    void readerSkipsSoftDeletedSetting() throws Exception {
        // 도래 조건(주기·일자)은 완전히 맞지만 삭제된 설정이다.
        long deleted = insertStng("MONTH", null, TEST_DD, true, true);
        stub(201, true);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 🔴 읽힌 행 수가 '살아 있는 설정 수' 와 같다 — 심은 행 하나가 통째로 빠졌다는 뜻이다.
        assertThat(totalCount() - aliveCount()).isEqualTo(1);
        assertThat(readCount(execution)).isEqualTo(aliveCount());
        verify(client, never()).autoCreate(any());
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(deleted)).isNull();
    }

    // ------------------------------------------------------------------ 케이스 9

    @Test
    @DisplayName("9. 🔴 actvtn_yn=false 인 설정은 Reader 가 읽지 않는다 — 화면에서 끈 설정이 계속 돌면 안 된다")
    void readerSkipsInactiveSetting() throws Exception {
        long inactive = insertStng("MONTH", null, TEST_DD, false, false);
        stub(201, true);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(totalCount() - aliveCount()).isEqualTo(1);
        assertThat(readCount(execution)).isEqualTo(aliveCount());
        verify(client, never()).autoCreate(any());
        assertThat(writeCount(execution)).isZero();
        assertThat(lastCrtDt(inactive)).isNull();
    }

    @Test
    @DisplayName("9-1. 꺼진 설정·지운 설정이 섞여 있어도 살아 있는 설정만 정확히 나간다")
    void mixesAliveAndExcludedSettings() throws Exception {
        long alive = insertStng("MONTH", null, TEST_DD, true, false);
        insertStng("MONTH", null, TEST_DD, false, false);   // 꺼짐
        insertStng("MONTH", null, TEST_DD, true, true);     // 지워짐
        stub(201, true);

        JobExecution execution = run(BASE_YMD);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(writeCount(execution)).isEqualTo(1);
        assertThat(sentStngIds()).containsExactly(alive);
    }

    // ------------------------------------------------------------------ 헬퍼

    private JobExecution run(LocalDate baseYmd) throws Exception {
        return jobLauncher.run(reportAutoCrtJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())        // 매 실행 새 JobInstance
                .addString("baseYmd", baseYmd.toString())
                .toJobParameters());
    }

    private void stub(int status, Boolean created) {
        when(client.autoCreate(any())).thenReturn(new BackendReportClient.Result(
                status,
                status == 201 || status == 200 ? 901L : null,
                status == 201 ? 401L : null,
                status == 201 || status == 200 ? "테스트 보고서" : null,
                status == 201 || status == 200 ? "2026-04" : null,
                created));
    }

    /**
     * 테스트 설정 1건을 심는다. 🔴 {@code stng_id} 를 <b>명시로</b> 준다 —
     * IDENTITY 채번에 맡기면 기준선보다 작은 번호가 나올 수 있어({@code GENERATED BY DEFAULT})
     * {@code @AfterEach} 의 {@code > baseStngId} 정리가 그 행을 놓친다.
     */
    private long insertStng(String cycl, Integer mm, int dd, boolean active, boolean deleted) {
        long stngId = baseStngId + createdStngIds.size() + 1;
        jdbc.update(INSERT_SQL, stngId, "CVLCPT", cycl, mm, dd, active,
                deleted ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
        createdStngIds.add(stngId);
        return stngId;
    }

    private Object lastCrtDt(long stngId) {
        return jdbc.queryForObject(
                "SELECT last_crt_dt FROM parking.tb_rptp_auto_crt_stng WHERE stng_id = ?",
                Object.class, stngId);
    }

    /** 🔴 {@code job_se='RPTP'} 와 이 테스트가 만든 {@code trgt_id} 로만 좁힌다 — 잡 공통 표다. */
    private List<Map<String, Object>> skipRows(long stngId) {
        return jdbc.queryForList("""
                SELECT trgt_id, trgt_de, fail_rsn, note FROM batch.tb_job_skip
                 WHERE job_se = ? AND trgt_id = ?""",
                ReportAutoCrtSkipListener.JOB_SE, String.valueOf(stngId));
    }

    private long aliveCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM parking.tb_rptp_auto_crt_stng"
                + " WHERE actvtn_yn = TRUE AND del_dt IS NULL", Long.class);
        return count == null ? 0L : count;
    }

    private long totalCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM parking.tb_rptp_auto_crt_stng", Long.class);
        return count == null ? 0L : count;
    }

    private List<Map<String, Object>> snapshotBaseline() {
        return jdbc.queryForList("""
                SELECT stng_id, last_crt_dt FROM parking.tb_rptp_auto_crt_stng
                 WHERE stng_id <= ? ORDER BY stng_id""", baseStngId);
    }

    private ReportAutoCrtCommand captureSent() {
        List<ReportAutoCrtCommand> all = capturedCommands();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private List<Long> sentStngIds() {
        return capturedCommands().stream().map(ReportAutoCrtCommand::stngId).toList();
    }

    private List<ReportAutoCrtCommand> capturedCommands() {
        ArgumentCaptor<ReportAutoCrtCommand> captor =
                ArgumentCaptor.forClass(ReportAutoCrtCommand.class);
        verify(client, org.mockito.Mockito.atLeast(0)).autoCreate(captor.capture());
        return captor.getAllValues();
    }

    private static long readCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum();
    }

    private static long writeCount(JobExecution execution) {
        return execution.getStepExecutions().stream().mapToLong(StepExecution::getWriteCount).sum();
    }

    /** 프레임워크가 몇 겹으로 감싸도 "왜 죽었는지"는 사슬에 남아야 한다. */
    private static String rootMessage(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            sb.append(cause.getMessage()).append('\n');
            if (cause == cause.getCause()) {
                break;
            }
        }
        return sb.toString();
    }
}
